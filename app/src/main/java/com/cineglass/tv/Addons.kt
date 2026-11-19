package com.cineglass.tv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ───────────────────────────── Add-ons (legal sources only) ─────────────────────────────
// A Stremio-style catalogue system with one hard rule: CineSays only plays from engines it ships.
//   • "archive": films from the Internet Archive (archive.org), played with the in-app player.
//   • "youtube_live": broadcasters' official 24/7 live streams, played with YouTube's own embed.
// An add-on is just data that points one of those engines at a catalogue (an archive search, or a list
// of channels). New add-ons arrive from CineSays' own registry file on GitHub, never from a user-typed
// URL, and the app re-checks every one: an unknown engine is dropped, an archive search outside the
// curated public-domain collections is limited to items marked public domain or Creative Commons, and a
// channel must be a well-formed YouTube channel ID. So a registry entry can add or tune catalogues, but
// can never point the player at anything other than those sources.

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
    val allowedHosts: List<String>? = null
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
                if (hosts.isEmpty()) null else a.copy(manifestUrl = m, allowedHosts = hosts, query = null, channels = null)
            }
            else -> null
        }
    }

    /** Loads the cached registry, then refreshes it from GitHub. Failures keep what we had. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun parse(json: String?): List<Addon> = runCatching {
            gson.fromJson<List<Addon>>(json, object : TypeToken<List<Addon>>() {}.type).orEmpty().mapNotNull(::sanitize)
        }.getOrDefault(emptyList())
        registry = parse(prefs.getString("registry", null))
        runCatching {
            val c = java.net.URL(REGISTRY_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode == 200) {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val list = parse(body)
                if (list.isNotEmpty()) { registry = list; prefs.edit().putString("registry", body).apply() }
            }
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
