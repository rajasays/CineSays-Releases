package com.cineglass.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ───────────────────────────── Source discovery ─────────────────────────────
// Providers are isolated adapters behind StreamProvider; the repository asks the enabled ones in
// parallel, each with its own timeout, so a slow or broken source never blocks or hides the others.

private const val TAG = "CineSaysStreams"

/** Public-domain / openly licensed movies from the Internet Archive add-ons the viewer has on. */
class ArchiveStreamProvider(private val context: Context) : StreamProvider {
    override val id = "archive"
    override val name = "Internet Archive"

    override suspend fun getStreams(mediaId: String, mediaType: MediaType, season: Int?, episode: Int?): List<PlayableStream> = emptyList()

    override suspend fun streamsFor(ref: MediaRef): List<PlayableStream> {
        if (ref.type != MediaType.MOVIE) return emptyList() // archive items aren't mapped to TMDB episodes
        val film = FreeFilms.match(ref.title, ref.year, Addons.enabled(context)) ?: return emptyList()
        val url = FreeFilms.streamUrl(film.id) ?: return emptyList()
        if (!StreamUrls.allowed(url, listOf("archive.org"))) return emptyList()
        return listOf(PlayableStream(
            providerId = id, providerName = name, sourceName = "Internet Archive · ${film.title}${film.year?.let { " ($it)" } ?: ""}",
            url = url, kind = StreamUrls.kindOf(url), label = "MP4 from archive.org", licence = film.licence,
            trusted = true, playable = true))
    }
}

/** A Stremio-protocol add-on from CineSays' own list. Streams only play from the hosts that list allows. */
class StremioAddonProvider(private val addon: Addon, private val http: OkHttpClient) : StreamProvider {
    override val id = addon.id
    override val name = addon.name
    private val base = StremioProtocol.baseOf(addon.manifestUrl.orEmpty())
    @Volatile private var manifest: Pair<Long, StremioManifest?>? = null

    private suspend fun get(url: String): String? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val call = http.newCall(req)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val result = runCatching {
                    response.use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        val body = r.body ?: return@use null
                        if (body.contentLength() > 2_000_000) throw java.io.IOException("Response too large")
                        val source = body.source()
                        if (source.request(2_000_001L)) throw java.io.IOException("Response too large")
                        source.readUtf8()
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private suspend fun manifest(): StremioManifest? {
        manifest?.takeIf { System.currentTimeMillis() - it.first < 24 * 3600_000L }?.let { return it.second }
        val m = get(addon.manifestUrl!!)?.let(StremioProtocol::parseManifest)
        if (m != null) manifest = System.currentTimeMillis() to m
        return m
    }

    override suspend fun getStreams(mediaId: String, mediaType: MediaType, season: Int?, episode: Int?): List<PlayableStream> = withContext(Dispatchers.IO) {
        val type = if (mediaType == MediaType.MOVIE) "movie" else "series"
        val m = manifest() ?: throw java.io.IOException("Add-on manifest is invalid or has no stream resource")
        if (!m.servesStreams(type, mediaId)) return@withContext emptyList()
        val json = get(StremioProtocol.streamUrl(base, type, mediaId)) ?: return@withContext emptyList()
        StremioProtocol.parseStreams(json, addon.id, addon.name, addon.allowedHosts.orEmpty(),
            if (addon.realDebrid) addon.authorizedTorrentHashes.orEmpty().toSet() else emptySet(),
            allowTorrentStreams = addon.torrentStreams, allowDebrid = addon.realDebrid)
    }
}

object StreamRepository {
    private const val PER_PROVIDER_TIMEOUT_MS = 15_000L
    private const val CACHE_MS = 10 * 60_000L
    private val cache = HashMap<String, Pair<Long, StreamSearch>>()

    /** No redirects (a 3xx is treated as an error), short timeouts, no cookies, no credentials. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }

    private val addonProviders = HashMap<Addon, StremioAddonProvider>()

    fun providers(context: Context): List<StreamProvider> {
        val enabled = Addons.enabled(context).filter { it.engine == Addons.STREMIO }
        val addons = synchronized(addonProviders) {
            addonProviders.keys.retainAll(enabled.toSet())
            enabled.map { addonProviders.getOrPut(it) { StremioAddonProvider(it, http) } }
        }
        return listOf<StreamProvider>(ArchiveStreamProvider(context.applicationContext)) + addons
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
        synchronized(addonProviders) { addonProviders.clear() }
    }

    suspend fun search(context: Context, ref: MediaRef, force: Boolean = false,
                       onPartial: (StreamSearch) -> Unit = {}): StreamSearch {
        Addons.loadSaved(context)
        val cacheKey = "${ref.key}:${ref.stremioId.orEmpty()}:${Addons.enabled(context)}"
        if (!force) synchronized(cache) { cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second } }
        if (force) clearCache()
        if (force) Addons.refresh(context, force = true) else Addons.refreshInBackground(context)
        val results = collectProgressively(providers(context).map { p ->
            suspend {
                val r = runCatching { withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) { p.streamsFor(ref) } }
                p.name to r.fold(onSuccess = { it?.let { s -> Result.success(s) } ?: Result.failure(java.io.IOException("Timed out")) },
                    onFailure = { e -> if (e is kotlinx.coroutines.CancellationException) throw e; Result.failure(e) })
            }
        }) { completed ->
            onPartial(StreamSearch(completed.flatMap { it.second.getOrNull().orEmpty() },
                completed.mapNotNull { (n, r) -> r.exceptionOrNull()?.let { n to friendly(it) } }.toMap()))
        }
        val streams = results.flatMap { it.second.getOrNull().orEmpty() }
        val failures = results.mapNotNull { (n, r) -> r.exceptionOrNull()?.let { n to friendly(it) } }.toMap()
        failures.forEach { (n, why) -> Log.w(TAG, "source '$n' failed for ${ref.key}: $why") } // no URLs or tokens in logs
        val search = StreamSearch(streams, failures)
        if (failures.isEmpty()) synchronized(cache) {
            if (cache.size >= 64) cache.entries.minByOrNull { it.value.first }?.let { cache.remove(it.key) }
            cache[cacheKey] = System.currentTimeMillis() to search
        }
        return search
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "Timed out"
        else -> e.message?.take(80) ?: e.javaClass.simpleName
    }
}
