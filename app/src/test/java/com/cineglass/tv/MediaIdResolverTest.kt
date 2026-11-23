package com.cineglass.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaIdResolverTest {
    private val episode = MediaRef(MediaType.EPISODE, 123, "Test show", season = 2, episode = 4)

    @Test fun trackerEpisodeGetsShowImdbAndKeepsEpisodeNumbers() = runBlocking {
        val resolver = MediaIdResolver { kind, id ->
            assertEquals("tv", kind); assertEquals(123, id); "tt0123456"
        }
        val resolved = resolver.resolve(episode)
        assertEquals("tt0123456:2:4", resolved.stremioId)
        assertEquals(episode.key, resolved.key)
        assertEquals("https://example.org/stream/series/tt0123456:2:4.json",
            StremioProtocol.streamUrl("https://example.org", resolved.stremioType, resolved.stremioId!!))
    }
    @Test fun repeatedEpisodesReuseShowLookup() = runBlocking {
        var calls = 0
        val resolver = MediaIdResolver { _, _ -> calls++; "tt0123456" }
        resolver.resolve(episode)
        assertEquals("tt0123456:2:5", resolver.resolve(episode.copy(episode = 5)).stremioId)
        assertEquals(1, calls)
    }
    @Test fun suppliedImdbAvoidsLookupAndSeedsCache() = runBlocking {
        val resolver = MediaIdResolver { _, _ -> error("Unexpected lookup") }
        resolver.resolve(episode.copy(imdbId = "tt0123456"))
        assertEquals("tt0123456", resolver.resolve(episode).imdbId)
    }
    @Test fun missingIdsAreNotCachedAndCanRetry() = runBlocking {
        var calls = 0
        val resolver = MediaIdResolver { _, _ -> if (++calls == 1) null else "tt0123456" }
        assertTrue(runCatching { resolver.resolve(episode) }.isFailure)
        assertEquals("tt0123456", resolver.resolve(episode).imdbId)
        assertEquals(2, calls)
    }
    @Test fun movieAndSeriesIdsHaveSeparateCacheEntries() = runBlocking {
        val resolver = MediaIdResolver { kind, _ -> if (kind == "movie") "tt7654321" else "tt0123456" }
        resolver.resolve(episode)
        assertEquals("tt7654321", resolver.resolve(episode.copy(type = MediaType.MOVIE)).imdbId)
    }
}
