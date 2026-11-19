package com.cineglass.tv

// ───────────────────────────── Playback router and progress rules (pure logic) ─────────────────────────────

/** What a Watch Now press can do, in the order the router offers them. */
sealed class WatchAction {
    data class PlayNative(val stream: PlayableStream) : WatchAction()
    /** An official service the title is on (provider id + name), opened in its own app. */
    data class OpenService(val providerId: Int, val providerName: String, val region: String) : WatchAction()
    data class OpenStremio(val deepLink: String) : WatchAction()
    data class Unavailable(val reason: String) : WatchAction()
}

enum class PlaybackDestination { ASK, CINESAYS, SERVICE }

object PlaybackRouter {
    /**
     * Orders every way to watch [ref]: (1) native CineSays streams, best first; (2) official services,
     * the viewer's own first; (3) Stremio, if installed and the title has an IMDb id; (4) if none of
     * those exist, one Unavailable entry saying why.
     */
    fun options(
        ref: MediaRef, search: StreamSearch, services: List<Triple<Int, String, String>>, myServiceIds: Set<Int>,
        stremioInstalled: Boolean, preferredHeight: Int? = null
    ): List<WatchAction> {
        val native = rankNative(search.playable, preferredHeight).map { WatchAction.PlayNative(it) }
        val svc = services.distinctBy { it.first }.sortedBy { if (it.first in myServiceIds) 0 else 1 }
            .map { (id, name, region) -> WatchAction.OpenService(id, name, region) }
        val stremio = if (stremioInstalled) stremioLink(ref)?.let { listOf(WatchAction.OpenStremio(it)) }.orEmpty() else emptyList()
        val all = native + svc + stremio
        if (all.isNotEmpty()) return all
        val why = when {
            search.unsupported.isNotEmpty() -> "Sources were found, but none can play in CineSays."
            search.failures.isNotEmpty() && search.streams.isEmpty() -> "Couldn't reach the sources. Check your connection and try again."
            else -> "No legal source or streaming service has this ${if (ref.type == MediaType.EPISODE) "episode" else "title"} in your regions."
        }
        return listOf(WatchAction.Unavailable(why))
    }

    /**
     * What a single Watch Now press should do without asking, given the saved preference.
     * Third-party apps only open automatically when the viewer chose that destination in Settings.
     */
    fun automatic(options: List<WatchAction>, destination: PlaybackDestination): WatchAction? = when (destination) {
        PlaybackDestination.ASK -> null
        PlaybackDestination.CINESAYS -> options.firstOrNull { it is WatchAction.PlayNative }
        PlaybackDestination.SERVICE -> options.firstOrNull { it is WatchAction.OpenService }
    }

    /** Best native streams first: closest to the preferred height (when the source states one), then HLS/DASH, then MP4. */
    fun rankNative(streams: List<PlayableStream>, preferredHeight: Int?): List<PlayableStream> =
        streams.sortedWith(compareBy(
            { s -> val h = heightOf(s.resolution); if (preferredHeight == null || h == null) 0 else kotlin.math.abs(h - preferredHeight) },
            { s -> when (s.kind) { StreamKind.HLS, StreamKind.DASH -> 0; StreamKind.PROGRESSIVE -> 1; else -> 2 } }
        ))

    /** "1080p" → 1080, "4K"/"2160p" → 2160; null when the source didn't state it. */
    fun heightOf(resolution: String?): Int? {
        val r = resolution?.lowercase() ?: return null
        if (r == "4k" || r == "uhd") return 2160
        return Regex("(\\d{3,4})p").find(r)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Documented Stremio deep link to a title's page: stremio:///detail/{type}/{metaId}[/{videoId}]. */
    fun stremioLink(ref: MediaRef): String? {
        val imdb = ref.imdbId?.takeIf { MediaRef.IMDB_ID.matches(it) } ?: return null
        return if (ref.type == MediaType.MOVIE) "stremio:///detail/movie/$imdb/$imdb"
        else ref.stremioId?.let { "stremio:///detail/series/$imdb/$it" } ?: "stremio:///detail/series/$imdb"
    }
}

object ProgressRules {
    const val DEFAULT_THRESHOLD = 0.90
    /** Shorter than this into a title isn't worth offering to resume. */
    const val MIN_RESUME_MS = 30_000L

    fun fraction(positionMs: Long, durationMs: Long): Double =
        if (durationMs <= 0) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

    fun isComplete(positionMs: Long, durationMs: Long, threshold: Double = DEFAULT_THRESHOLD): Boolean =
        durationMs > 0 && fraction(positionMs, durationMs) >= threshold

    fun canResume(positionMs: Long, durationMs: Long, threshold: Double = DEFAULT_THRESHOLD): Boolean =
        positionMs >= MIN_RESUME_MS && !isComplete(positionMs, durationMs, threshold)

    /** "1:02:05" or "12:07". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /**
     * Should this save mark the title watched in the Tracker? Only once (when it first crosses the
     * threshold), and only for playback inside CineSays: opening another app never reaches here.
     */
    fun shouldMarkWatched(alreadyCompleted: Boolean, positionMs: Long, durationMs: Long, threshold: Double = DEFAULT_THRESHOLD) =
        !alreadyCompleted && isComplete(positionMs, durationMs, threshold)
}
