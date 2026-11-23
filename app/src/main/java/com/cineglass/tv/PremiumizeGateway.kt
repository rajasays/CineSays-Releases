package com.cineglass.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Premiumize's documented direct-download API, for sources the account holder
 * is authorized to access. Requires a valid Premiumize access token/API key;
 * no credentials are embedded in the application or stored by this class.
 *
 * Call configure(PremiumizeGateway { secureCredentialLookup() }) from approved
 * account-linking code. The gateway is not registered until credentials exist.
 * Selected torrents may be submitted only after an explicit playback action.
 */
internal class PremiumizeGateway(
    private val credential: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) : AuthorizedTorrentHttp.Gateway {
    override suspend fun resolve(source: PlayableStream, media: MediaRef): PlayableStream? =
        withContext(Dispatchers.IO) {
            val hash = source.debridTorrent?.hash
                ?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return@withContext null
            val token = credential()?.takeIf { it.isNotBlank() } ?: return@withContext null
            val request = Request.Builder()
                .url("https://www.premiumize.me/api/transfer/directdl")
                .header("Authorization", "Bearer $token")
                .post(FormBody.Builder().add("src", "magnet:?xt=urn:btih:$hash").build())
                .build()
            val json = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string()?.takeIf { it.length <= 1_000_000 }
                        ?: return@withContext null
                    JSONObject(body)
                }
            } catch (_: IOException) {
                return@withContext null
            }
            if (json.optString("status") != "success") return@withContext null
            val list = json.optJSONArray("content") ?: return@withContext null
            val videos = (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let { obj ->
                    val path = obj.optString("path")
                    val extension = path.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("mp4", "m4v", "mkv", "webm", "mov", "ts"))
                        null else obj
                }
            }
            val torrent = source.debridTorrent
            val chosen = if (!torrent.filename.isNullOrBlank()) {
                val expected = torrent.filename.replace('\\', '/').substringAfterLast('/')
                videos.singleOrNull {
                    it.optString("path").replace('\\', '/').substringAfterLast('/')
                        .equals(expected, ignoreCase = true)
                }
            } else videos.singleOrNull()
            val url = chosen?.optString("link")?.takeIf(StreamUrls::isHttps)
                ?: return@withContext null
            PlayableStream(
                providerId = "premiumize",
                providerName = "Premiumize",
                sourceName = source.sourceName + " · Cloud HTTP",
                url = url,
                kind = StreamUrls.kindOf(url),
                label = "Authorized HTTP playback",
                resolution = source.resolution,
                licence = source.licence,
                trusted = false,
                playable = true
            )
        }
}
