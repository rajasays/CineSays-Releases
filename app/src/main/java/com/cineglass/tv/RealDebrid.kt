package com.cineglass.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class RealDebridException(message: String) : Exception(message)

internal object RealDebrid {
    private const val API = "https://api.real-debrid.com/rest/1.0"
    private val ID = Regex("[A-Za-z0-9_-]{1,80}")
    private val TOKEN = Regex("[A-Za-z0-9_-]{16,256}")

    fun validToken(token: String): Boolean = TOKEN.matches(token)

    suspend fun resolve(context: Context, torrent: DebridTorrent): String = withContext(Dispatchers.IO) {
        val token = DebridCredentials.read(context)
            ?: throw RealDebridException("Add your Real-Debrid API token in Playback & Streaming settings.")
        fun request(path: String, field: String? = null, value: String = ""): JsonObject {
            ensureActive()
            val builder = Request.Builder().url(API + path).header("Authorization", "Bearer $token")
            if (field != null) builder.post(FormBody.Builder().add(field, value).build())
            return StreamRepository.http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw RealDebridException(when (response.code) {
                    401 -> "Real-Debrid rejected the API token. Update it in Settings."
                    403 -> "Real-Debrid denied access. Check your account and subscription."
                    429 -> "Real-Debrid is receiving too many requests. Try again shortly."
                    else -> "Real-Debrid couldn't open this source (HTTP ${response.code})."
                })
                if (response.code == 204 || path.startsWith("/torrents/selectFiles/")) return@use JsonObject()
                val source = response.body?.source() ?: throw RealDebridException("Real-Debrid returned an empty response.")
                if (source.request(2_000_001L)) throw RealDebridException("Real-Debrid returned an oversized response.")
                JsonParser.parseString(source.readUtf8()).asJsonObject
            }
        }

        require(torrent.hash.matches(Regex("[0-9a-f]{40}")))
        val added = request("/torrents/addMagnet", "magnet", "magnet:?xt=urn:btih:${torrent.hash}")
        val id = added.text("id")?.takeIf(ID::matches)
            ?: throw RealDebridException("Real-Debrid didn't return a torrent ID.")
        var selected: Int? = null
        repeat(10) { attempt ->
            if (attempt > 0) delay(1_500)
            val info = request("/torrents/info/$id")
            val files = info.getAsJsonArray("files").orEmpty().mapNotNull { entry ->
                val file = entry.asJsonObject
                val path = file.text("path") ?: return@mapNotNull null
                val fileId = file.get("id")?.asString?.toIntOrNull() ?: return@mapNotNull null
                TorrentFile(fileId, path, file.get("selected")?.asString == "1")
            }
            when (info.text("status")) {
                "waiting_files_selection" -> if (selected == null) {
                    selected = chooseFile(files, torrent)
                        ?: throw RealDebridException("Couldn't identify the exact video in this torrent.")
                    request("/torrents/selectFiles/$id", "files", selected.toString())
                }
                "downloaded" -> {
                    val expected = selected ?: chooseFile(files, torrent)
                    val links = info.getAsJsonArray("links")
                    if (expected == null || files.filter { it.selected }.singleOrNull()?.id != expected || links == null || links.size() != 1) {
                        throw RealDebridException("Real-Debrid didn't return a link for the selected video.")
                    }
                    val link = links[0].asString
                    if (!StreamUrls.allowed(link, listOf("real-debrid.com", "rdeb.io"))) {
                        throw RealDebridException("Real-Debrid returned an unsupported torrent link.")
                    }
                    return@withContext request("/unrestrict/link", "link", link).text("download")
                        ?.takeIf { StreamUrls.allowed(it, listOf("real-debrid.com", "rdeb.io")) }
                        ?: throw RealDebridException("Real-Debrid didn't return a supported HTTPS playback link.")
                }
                "magnet_error", "error", "virus", "dead" ->
                    throw RealDebridException("Real-Debrid couldn't prepare this torrent.")
            }
        }
        throw RealDebridException("Real-Debrid is still preparing this source. Check its status in your Real-Debrid account.")
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun com.google.gson.JsonArray?.orEmpty(): Iterable<com.google.gson.JsonElement> = this ?: emptyList()
}

internal data class TorrentFile(val id: Int, val path: String, val selected: Boolean = false)

/** Stremio's zero-based file index is distinct from Real-Debrid's API file ID. */
internal fun chooseFile(files: List<TorrentFile>, torrent: DebridTorrent): Int? {
    val filename = torrent.filename
    if (filename != null) {
        return files.singleOrNull { it.path.trimStart('/').let { path ->
            path == filename.trimStart('/') || path.substringAfterLast('/') == filename
        } }?.id
    }
    return files.singleOrNull()?.id?.takeIf { torrent.fileIndex == null || torrent.fileIndex == 0 }
}

internal object DebridCredentials {
    private const val ALIAS = "cinesays_real_debrid"
    private const val TOKEN = "api_token"
    private const val CIPHER = "AES/GCM/NoPadding"

    private fun prefs(context: Context) = context.getSharedPreferences(ALIAS, Context.MODE_PRIVATE)

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun configured(context: Context): Boolean = prefs(context).contains(TOKEN)

    fun read(context: Context): String? = runCatching {
        val encoded = prefs(context).getString(TOKEN, null) ?: return null
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        val key = keyStore().getKey(ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.doFinal(encrypted, 12, encrypted.size - 12).toString(Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    fun save(context: Context, token: String): Boolean = runCatching {
        require(RealDebrid.validToken(token))
        val key = keyStore().getKey(ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == 12)
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    @Synchronized
    fun clear(context: Context): Boolean = prefs(context).edit().remove(TOKEN).commit()
}
