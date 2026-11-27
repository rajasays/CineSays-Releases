package com.cineglass.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Self-hosted TorrServer via private Tailscale HTTPS. No paid gateways. */
internal class TorrServerGateway(private val baseUrl: String) : AuthorizedTorrentHttp.Gateway {
    companion object {
        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun normalizedBase(input: String): String? {
            val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ||
                uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.path.orEmpty().isNotEmpty() && uri.path != "/")) return null
            val host = uri.host.lowercase()
            // Access via the user's authenticated Tailnet, not a public torrent API.
            if (!host.endsWith(".ts.net") || host.length <= ".ts.net".length) return null
            val port = if (uri.port == -1) "" else ":" + uri.port
            return "https://" + host + port
        }
    }

    private val server = normalizedBase(baseUrl)
        ?: throw IllegalArgumentException("Use a private Tailscale HTTPS hostname ending in .ts.net")

    private suspend fun post(payload: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder()
            .url(server + "/torrents")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            if (body.contentLength() > 256_000L) return@withContext null
            val text = body.string()
            if (text.length > 256_000) return@withContext null
            runCatching { JSONObject(text) }.getOrNull()
        }
    }

    override suspend fun resolve(source: PlayableStream, media: MediaRef): PlayableStream? {
        val torrent = source.debridTorrent ?: return null
        if (!supportsDirectTorrent(torrent)) return null
        val hash = torrent.hash.lowercase()
        val magnet = "magnet:?xt=urn:btih:" + hash
        post(JSONObject().put("action", "add").put("link", magnet)
            .put("save_to_db", false)) ?: return null

        repeat(12) {
            currentCoroutineContext().ensureActive()
            val status = post(JSONObject().put("action", "get").put("hash", hash))
            if (status != null) {
                val fileId = pickFile(status, torrent)
                if (fileId != null) {
                    val playUrl = server + "/play/" + hash + "/" + fileId
                    return source.copy(
                        providerName = "TorrServer (self-hosted)",
                        sourceName = source.sourceName + " · Home gateway",
                        url = playUrl,
                        kind = StreamKind.PROGRESSIVE,
                        playable = true,
                        debridTorrent = null)
                }
            }
            delay(650L)
        }
        return null
    }

    /** TorrServer file ids are never assumed to equal Stremio zero-based fileIdx. */
    internal fun pickFile(status: JSONObject, torrent: DebridTorrent): Int? {
        val files = status.optJSONArray("file_stats") ?: return null
        val extensions = setOf("mp4", "mkv", "webm", "m4v", "mov", "avi", "ts", "m2ts")
        val choices = (0 until files.length()).mapNotNull { index ->
            val file = files.optJSONObject(index) ?: return@mapNotNull null
            val id = file.optInt("id", -1)
            val path = file.optString("path")
            if (id < 0 || path.substringAfterLast('.', "").lowercase() !in extensions)
                null else id to path.replace('\\', '/')
        }
        if (choices.isEmpty()) return null
        val wanted = torrent.filename?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotBlank() }
        if (wanted != null) {
            val exact = choices.filter { it.second.equals(wanted, ignoreCase = true) }
            if (exact.size == 1) return exact.single().first
            val baseName = wanted.substringAfterLast('/')
            val byName = choices.filter { it.second.substringAfterLast('/').equals(baseName, ignoreCase = true) }
            return byName.singleOrNull()?.first
        }
        return choices.singleOrNull()?.first
    }
}
