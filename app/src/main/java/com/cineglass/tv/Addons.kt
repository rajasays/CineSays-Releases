package com.cineglass.tv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

// Add-on registry: existing Archive/YouTube engines plus opt-in Stremio stream discovery.
// The registry grants capabilities, not copyright permission. HTTPS stream hosts remain restricted;
// torrent results require a listed hash or explicit torrentStreams capability and user opt-in.

data class Addon(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /** "archive" or "youtube_live". */
    val engine: String = "",
    /** archive: Internet Archive search, e.g. `collection:(silent_films)`. */
    val query: String? = null,
    /** archive: only items whose licence is public domain or Creative Commons. */
    val requireLicence: Boolean = false,
    /** youtube_live: the channels. */
    val channels: List<LiveChannel>? = null,
    val defaultOn: Boolean = true,
    /** stremio: the add-on's https manifest URL. */
    val manifestUrl: String? = null,
    /** stremio: the only hosts its streams (and subtitles) may come from. */
    val allowedHosts: List<String>? = null,
    /** Opt-in for a curated add-on whose torrent results are authorized for playback. */
    val authorizedTorrentHashes: List<String>? = null,
    /** Explicit registry opt-in to discover torrent results without a per-title hash list.
     * This is a capability setting, not a statement about a title's licence. */
    val torrentStreams: Boolean = false
)

/** One add-on's films, as shown in a Free tab row. */
data class AddonCatalog(val addon: Addon, val films: List<FreeFilm>)

object Addons {
    const val ARCHIVE = "archive"
    const val YOUTUBE_LIVE = "youtube_live"
    /** A Stremio-protocol add-on from CineSays' list, restricted to the stream hosts the list names. */
    const val STREMIO = "stremio"
    private val ENGINES = setOf(ARCHIVE, YOUTUBE_LIVE, STREMIO)
    // A real domain: dot-separated labels ending in an alphabetic top-level label (so no IP addresses).
    private val HOST = Regex("""^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]([a-z0-9-]*[a-z0-9])?$""")

    /** CineSays' own registry: lets new legal catalogues appear without an app update. */
    private const val REGISTRY_URL = "https://raw.githubusercontent.com/rajasays/CineSays-Releases/main/addons.json"

    /** Internet Archive collections curated as public domain; anything else must carry a PD/CC licence. */
    private val CURATED = setOf("feature_films", "silent_films", "film_noir", "scifi_horror", "classic_cartoons", "prelinger")

    val BUILTIN: List<Addon> = listOf(
        Addon("cinesays.torrentio", "Torrentio",
            "Torrent source discovery. Enable only for content you are authorized to access. Downloads use device storage and upload to peers.",
            STREMIO, defaultOn = false, manifestUrl = "https://torrentio.strem.fun/manifest.json",
            torrentStreams = true),
        Addon("live.news", "Live news channels", "Official 24/7 live streams that broadcasters publish on YouTube.",
            YOUTUBE_LIVE, channels = LIVE_CHANNELS),
        Addon("ia.feature_films", "Classic feature films", "The Internet Archive's public-domain feature films.",
            ARCHIVE, "collection:(feature_films)"),
        Addon("ia.silent_films", "Silent era", "Silent films from the 1890s to the 1930s.",
            ARCHIVE, "collection:(silent_films)"),
        Addon("ia.film_noir", "Film noir", "Public-domain crime and noir from the 1940s and 50s.",
            ARCHIVE, "collection:(Film_Noir)"),
        Addon("ia.scifi_horror", "Sci-fi & horror classics", "B-movies and classics in the public domain.",
            ARCHIVE, "collection:(SciFi_Horror)"),
        Addon("ia.cartoons", "Cartoons & open movies", "Classic cartoons, plus Creative Commons films such as Sintel and Elephants Dream.",
            ARCHIVE, "collection:(classic_cartoons OR animationandcartoons)", requireLicence = true),
        Addon("ia.classic_tv", "Classic TV", "Public-domain TV episodes: westerns, comedies and dramas.",
            ARCHIVE, "collection:(classic_tv)", requireLicence = true),
        Addon("ia.prelinger", "Vintage shorts", "Educational, industrial and home films from the Prelinger Archives.",
            ARCHIVE, "collection:(prelinger)", defaultOn = false)
    )

    private val gson = Gson()
    private const val PREFS = "addons"

    /** Built-ins merged with the last registry we fetched (registry entries replace built-ins with the same id). */
    @Volatile private var registry: List<Addon> = emptyList()
    val all: List<Addon> get() = (BUILTIN.associateBy { it.id } + registry.associateBy { it.id }).values.toList()

    private val SAFE_QUERY = Regex("""^[A-Za-z0-9_ :()"*.\-]{1,300}$""")
    private val CHANNEL_ID = Regex("""^UC[\w-]{22}$""")

    /** Returns [a] made safe, or null if it can't be. */
    fun sanitize(a: Addon): Addon? {
        if (a.id.isBlank() || a.name.isBlank() || a.engine !in ENGINES) return null
        return when (a.engine) {
            ARCHIVE -> {
                val q = a.query?.trim() ?: return null
                if (!SAFE_QUERY.matches(q)) return null
                // Curated only when the query is exactly collection:(A OR B …) and every collection is curated.
                val whole = Regex("""^collection:\(([A-Za-z0-9_ ]+)\)$""").find(q)
                val collections = whole?.groupValues?.get(1)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() && it != "OR" }?.map { it.lowercase() }
                val curatedOnly = !collections.isNullOrEmpty() && collections.all { it in CURATED }
                a.copy(query = q, requireLicence = a.requireLicence || !curatedOnly, channels = null)
            }
            YOUTUBE_LIVE -> {
                val chans = a.channels.orEmpty().filter { CHANNEL_ID.matches(it.youtubeChannelId) && it.name.isNotBlank() }
                if (chans.isEmpty()) null else a.copy(channels = chans, query = null)
            }
            STREMIO -> {
                val m = a.manifestUrl?.trim()?.takeIf { it.endsWith("/manifest.json") && StreamUrls.isHttps(it) } ?: return null
                // Bare domains only (no wildcards, IPs or paths); without any, nothing from it could play.
                val hosts = a.allowedHosts.orEmpty().map { it.trim().lowercase() }.filter { HOST.matches(it) }
                // A hash-curated add-on can offer approved torrents without any HTTP stream hosts.
                if (hosts.isEmpty() && !a.torrentStreams && a.authorizedTorrentHashes.orEmpty().isEmpty()) return null
                val hashes = a.authorizedTorrentHashes.orEmpty().map { it.trim().lowercase() }
                    .filter { it.matches(Regex("[0-9a-f]{40}")) }.distinct()
                a.copy(manifestUrl = m, allowedHosts = hosts, query = null, channels = null,
                    defaultOn = a.defaultOn && hashes.isEmpty() && !a.torrentStreams, authorizedTorrentHashes = hashes)
            }
            else -> null
        }
    }

    fun permitsTorrent(addon: Addon, hash: String): Boolean =
        addon.engine == STREMIO && Regex("[0-9a-f]{40}").matches(hash) &&
            (addon.torrentStreams || hash in addon.authorizedTorrentHashes.orEmpty())

    private val refreshScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val refreshLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var savedRegistryLoaded = false

    /** Read local configuration once; network refresh is not on the discovery critical path. */
    @Synchronized fun loadSaved(context: Context) {
        if (savedRegistryLoaded) return
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("registry", null)
        registry = runCatching {
            gson.fromJson<List<Addon>>(json, object : TypeToken<List<Addon>>() {}.type).orEmpty().mapNotNull(::sanitize)
        }.getOrDefault(emptyList())
        savedRegistryLoaded = true
    }

    fun refreshInBackground(context: Context) {
        loadSaved(context)
        refreshScope.launch { refresh(context.applicationContext) }
    }

    @Volatile private var lastRefreshMs = 0L

    /** Loads the cached registry, then refreshes it from GitHub. Failures keep what we had. */
    suspend fun refresh(context: Context, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            loadSaved(context)
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && lastRefreshMs != 0L && now - lastRefreshMs < 60_000L) return@withContext
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            fun parse(json: String?): List<Addon> = runCatching {
                gson.fromJson<List<Addon>>(json, object : TypeToken<List<Addon>>() {}.type).orEmpty().mapNotNull(::sanitize)
            }.getOrDefault(emptyList())
            registry = parse(prefs.getString("registry", null))
            runCatching {
                val url = if (force) "$REGISTRY_URL?refresh=${System.currentTimeMillis()}" else REGISTRY_URL
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    if (force) {
                        c.useCaches = false
                        c.setRequestProperty("Cache-Control", "no-cache")
                    }
                    check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val list = parse(body)
                    check(list.isNotEmpty()) { "No valid add-on entries" }
                    registry = list
                    prefs.edit().putString("registry", body).apply()
                    if (force) {
                        val test = list.firstOrNull { it.id == "cinesays.big-buck-bunny-test" }
                        android.util.Log.i("CineSaysTorrent", "Refreshed registry. Big Buck Bunny approved hashes: ${test?.authorizedTorrentHashes.orEmpty()}")
                    }
                } finally {
                    c.disconnect()
                }
            }.onFailure {
                if (force) android.util.Log.w("CineSaysTorrent", "Registry refresh failed; using saved registry.", it)
            }
            lastRefreshMs = android.os.SystemClock.elapsedRealtime()
            Unit
        }
    }

    /** User's on/off choices; add-ons they never touched follow [Addon.defaultOn]. */
    fun isOn(context: Context, a: Addon): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when {
            a.id in p.getStringSet("on", emptySet()).orEmpty() -> true
            a.id in p.getStringSet("off", emptySet()).orEmpty() -> false
            else -> a.defaultOn
        }
    }

    fun setOn(context: Context, id: String, on: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val onSet = p.getStringSet("on", emptySet()).orEmpty().toMutableSet()
        val offSet = p.getStringSet("off", emptySet()).orEmpty().toMutableSet()
        if (on) { onSet += id; offSet -= id } else { offSet += id; onSet -= id }
        p.edit().putStringSet("on", onSet).putStringSet("off", offSet).apply()
    }

    fun enabled(context: Context): List<Addon> = all.filter { isOn(context, it) }
}
