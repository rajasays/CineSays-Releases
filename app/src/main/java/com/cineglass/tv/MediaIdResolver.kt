package com.cineglass.tv

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resolve a show ID once, not once for every episode. Failed lookups remain retryable. */
internal class MediaIdResolver(private val lookup: suspend (String, Int) -> String?) {
    private val lock = Mutex()
    private val ids = LinkedHashMap<String, String>()

    suspend fun resolve(ref: MediaRef): MediaRef = lock.withLock {
        val kind = if (ref.type == MediaType.MOVIE) "movie" else "tv"
        val key = "$kind:${ref.tmdbId}"
        val supplied = ref.imdbId?.takeIf { MediaRef.IMDB_ID.matches(it) }
        val id = supplied ?: ids[key] ?: lookup(kind, ref.tmdbId)
            ?.takeIf { MediaRef.IMDB_ID.matches(it) }
            ?: throw IllegalStateException("No IMDb ID is available for this title. Check its TMDB match and try again.")
        if (ids.size >= 128 && key !in ids) ids.remove(ids.keys.first())
        ids[key] = id
        ref.copy(imdbId = id)
    }
}
