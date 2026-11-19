package com.cineglass.tv

// ───────────────────────────── Streaming: shared models ─────────────────────────────
// Pure Kotlin (no Android classes) so parsing, routing and progress rules are unit-testable.
// Layers: metadata (TMDB) → availability (TMDB watch providers) → source discovery (StreamProvider)
// → source selection (Watch Now dialog) → playback (CineSays player) or fallback (PlaybackRouter)
// → progress (PlaybackStore → Tracker).

enum class MediaType { MOVIE, EPISODE }

/** What the viewer wants to watch: a movie, or one episode of a series. */
data class MediaRef(
    val type: MediaType,
    val tmdbId: Int,
    val title: String,
    val imdbId: String? = null,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val poster: String? = null
) {
    /** Stable key for progress: "movie:550" or "tv:1396:1:2". */
    val key: String get() = if (type == MediaType.MOVIE) "movie:$tmdbId" else "tv:$tmdbId:$season:$episode"

    /** Stremio's video id: "tt0111161" for movies, "tt0903747:1:2" for episodes; null without an IMDb id. */
    val stremioId: String? get() {
        val imdb = imdbId?.takeIf { IMDB_ID.matches(it) } ?: return null
        return if (type == MediaType.MOVIE) imdb else if (season != null && episode != null) "$imdb:$season:$episode" else null
    }
    /** Stremio's content type for the stream resource. */
    val stremioType: String get() = if (type == MediaType.MOVIE) "movie" else "series"

    /** "Breaking Bad · S1 E2" or "Inception". */
    val display: String get() = if (type == MediaType.EPISODE) "$title · S$season E$episode" else title

    fun next(): MediaRef? = if (type == MediaType.EPISODE && episode != null) copy(episode = episode + 1, episodeName = null) else null

    companion object { val IMDB_ID = Regex("^tt\\d{5,10}$") }
}

enum class StreamKind { PROGRESSIVE, HLS, DASH, YOUTUBE, TORRENT, EXTERNAL, UNKNOWN }

data class SubtitleTrack(val url: String, val language: String?, val label: String?, val mimeType: String?)

/**
 * One source a provider found. Only facts the provider stated are filled in: technical details
 * (resolution, codec, HDR, audio, size) stay null when unknown and are shown as unknown, never guessed
 * from a file name. [label] is the source's own description, shown as "as described by the source".
 */
data class PlayableStream(
    val providerId: String,
    val providerName: String,
    val sourceName: String,
    val url: String?,
    val kind: StreamKind,
    val label: String? = null,
    val resolution: String? = null,
    val videoCodec: String? = null,
    val audio: String? = null,
    val hdr: String? = null,
    val sizeBytes: Long? = null,
    val licence: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** Comes from a CineSays-curated source. */
    val trusted: Boolean = false,
    /** CineSays can play it in its own player. */
    val playable: Boolean = false,
    /** Why it can't play here (shown to the viewer), when [playable] is false. */
    val unsupportedReason: String? = null
)

interface StreamProvider {
    val id: String
    val name: String
    suspend fun getStreams(mediaId: String, mediaType: MediaType, season: Int? = null, episode: Int? = null): List<PlayableStream>
    /** Providers that need more than an id (e.g. title + year) override this; the default uses the Stremio-style id. */
    suspend fun streamsFor(ref: MediaRef): List<PlayableStream> =
        ref.stremioId?.let { getStreams(it, ref.type, ref.season, ref.episode) }.orEmpty()
}

/** Outcome of asking every provider, kept separate so one failing source never hides the others. */
data class StreamSearch(
    val streams: List<PlayableStream> = emptyList(),
    val failures: Map<String, String> = emptyMap()
) {
    val playable get() = streams.filter { it.playable }
    val unsupported get() = streams.filterNot { it.playable }
}

object StreamUrls {
    /** Container/protocol from the URL's path, for ExoPlayer's MIME hint. Unknown means "let ExoPlayer sniff". */
    fun kindOf(url: String): StreamKind {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") -> StreamKind.DASH
            else -> StreamKind.PROGRESSIVE
        }
    }

    fun host(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
    fun isHttps(url: String): Boolean = runCatching { java.net.URI(url).scheme.equals("https", true) }.getOrDefault(false)

    /** [url] is https and on one of [allowed] (exact host or a subdomain of it). */
    fun allowed(url: String, allowed: Collection<String>): Boolean {
        if (!isHttps(url)) return false
        val h = host(url) ?: return false
        return allowed.any { a -> val d = a.lowercase().trim(); d.isNotEmpty() && (h == d || h.endsWith(".$d")) }
    }

    /** Never log full playback URLs (they can carry tokens): scheme + host only. */
    fun redact(url: String?): String = url?.let { u -> host(u)?.let { "https://$it/…" } } ?: "(none)"
}
