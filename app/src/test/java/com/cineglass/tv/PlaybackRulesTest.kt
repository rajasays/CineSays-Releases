package com.cineglass.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {
    private val movie = MediaRef(MediaType.MOVIE, 10331, "Night of the Living Dead", "tt0063350", "1968")
    private val episode = MediaRef(MediaType.EPISODE, 1396, "Breaking Bad", "tt0903747", season = 1, episode = 2)
    private fun native(name: String, kind: StreamKind = StreamKind.PROGRESSIVE, res: String? = null) =
        PlayableStream("p", "P", name, "https://archive.org/$name.mp4", kind, resolution = res, trusted = true, playable = true)

    // ── identifiers ──
    @Test fun keysAndStremioIds() {
        assertEquals("movie:10331", movie.key)
        assertEquals("tv:1396:1:2", episode.key)
        assertEquals("tt0063350", movie.stremioId)
        assertEquals("tt0903747:1:2", episode.stremioId)
        assertEquals("series", episode.stremioType)
        assertNull(movie.copy(imdbId = null).stremioId)
        assertNull(movie.copy(imdbId = "nm0000123").stremioId)
        assertNull(episode.copy(season = null).stremioId)
        assertEquals(3, episode.next()!!.episode)
        assertNull(movie.next())
    }

    @Test fun stremioDeepLinks() {
        assertEquals("stremio:///detail/movie/tt0063350/tt0063350", PlaybackRouter.stremioLink(movie))
        assertEquals("stremio:///detail/series/tt0903747/tt0903747:1:2", PlaybackRouter.stremioLink(episode))
        assertNull(PlaybackRouter.stremioLink(movie.copy(imdbId = null)))
    }

    @Test fun urlKindsAndHostChecks() {
        assertEquals(StreamKind.HLS, StreamUrls.kindOf("https://a.org/x/master.m3u8?token=1"))
        assertEquals(StreamKind.DASH, StreamUrls.kindOf("https://a.org/x/manifest.mpd"))
        assertEquals(StreamKind.PROGRESSIVE, StreamUrls.kindOf("https://a.org/x/film.mp4"))
        assertTrue(StreamUrls.allowed("https://ia800.us.archive.org/1/a.mp4", listOf("archive.org")))
        assertFalse(StreamUrls.allowed("https://archive.org.evil.com/a.mp4", listOf("archive.org")))
        assertFalse(StreamUrls.allowed("http://archive.org/a.mp4", listOf("archive.org")))
        assertEquals("https://archive.org/…", StreamUrls.redact("https://archive.org/download/x?token=secret"))
    }

    // ── router ──
    @Test fun nativeComesFirstThenYourServicesThenStremio() {
        val search = StreamSearch(listOf(native("a")))
        val services = listOf(Triple(8, "Netflix", "US"), Triple(337, "Disney Plus", "US"))
        val opts = PlaybackRouter.options(movie, search, services, myServiceIds = setOf(337), stremioInstalled = true)
        assertTrue(opts[0] is WatchAction.PlayNative)
        assertEquals(337, (opts[1] as WatchAction.OpenService).providerId) // the viewer's own service first
        assertEquals(8, (opts[2] as WatchAction.OpenService).providerId)
        assertTrue(opts.last() is WatchAction.OpenStremio)
    }

    @Test fun directFirstWhenTorrentAvailable() {
        val torrent = PlayableStream("approved", "Approved", "torrent", null, StreamKind.TORRENT,
            debridTorrent = DebridTorrent("0123456789abcdef0123456789abcdef01234567", null, "video.mp4", directPlayback = true))
        val search = StreamSearch(listOf(torrent, native("mp4")))
        val direct = PlaybackRouter.options(movie, search, emptyList(), emptySet(), false,
            preferredSource = "direct")
        assertTrue(direct.first() is WatchAction.PlayNative)
        assertTrue(direct[1] is WatchAction.PlayTorrent)
        val torrentPreferred = PlaybackRouter.options(movie, search, emptyList(), emptySet(), false,
            preferredSource = "torrent")
        assertTrue(torrentPreferred.first() is WatchAction.PlayTorrent)
        assertTrue(torrentPreferred[1] is WatchAction.PlayNative)
    }

    @Test fun stremioOnlyWhenInstalledAndIdKnown() {
        val none = StreamSearch()
        assertTrue(PlaybackRouter.options(movie, none, emptyList(), emptySet(), stremioInstalled = false).single() is WatchAction.Unavailable)
        assertTrue(PlaybackRouter.options(movie.copy(imdbId = null), none, emptyList(), emptySet(), stremioInstalled = true).single() is WatchAction.Unavailable)
        assertTrue(PlaybackRouter.options(movie, none, emptyList(), emptySet(), stremioInstalled = true).single() is WatchAction.OpenStremio)
    }

    @Test fun unavailableExplainsWhy() {
        val torrentOnly = StreamSearch(listOf(PlayableStream("p", "P", "t", null, StreamKind.TORRENT, playable = false, unsupportedReason = "BitTorrent")))
        val r1 = PlaybackRouter.options(movie, torrentOnly, emptyList(), emptySet(), false).single() as WatchAction.Unavailable
        assertTrue(r1.reason.contains("none can play"))
        val offline = StreamSearch(failures = mapOf("Internet Archive" to "No internet connection"))
        val r2 = PlaybackRouter.options(movie, offline, emptyList(), emptySet(), false).single() as WatchAction.Unavailable
        assertTrue(r2.reason.contains("connection"))
        val r3 = PlaybackRouter.options(episode, StreamSearch(), emptyList(), emptySet(), false).single() as WatchAction.Unavailable
        assertTrue(r3.reason.contains("episode"))
    }

    @Test fun savedDestinationActsOnlyWhenChosen() {
        val opts = listOf(WatchAction.OpenService(8, "Netflix", "US"), WatchAction.PlayNative(native("a")))
        assertNull(PlaybackRouter.automatic(opts, PlaybackDestination.ASK))
        assertTrue(PlaybackRouter.automatic(opts, PlaybackDestination.CINESAYS) is WatchAction.PlayNative)
        assertTrue(PlaybackRouter.automatic(opts, PlaybackDestination.SERVICE) is WatchAction.OpenService)
        assertNull(PlaybackRouter.automatic(listOf(WatchAction.Unavailable("x")), PlaybackDestination.CINESAYS))
    }

    @Test fun rankingPrefersStatedHeightThenAdaptive() {
        val ranked = PlaybackRouter.rankNative(listOf(native("mp4", res = "720p"), native("hls", StreamKind.HLS, "2160p"), native("dash", StreamKind.DASH, "1080p")), 1080)
        assertEquals("dash", ranked.first().sourceName)
        val noPref = PlaybackRouter.rankNative(listOf(native("mp4"), native("hls", StreamKind.HLS)), null)
        assertEquals("hls", noPref.first().sourceName)
        assertEquals(2160, PlaybackRouter.heightOf("4K"))
        assertNull(PlaybackRouter.heightOf(null))
    }

    // ── progress ──
    @Test fun resumeAndCompletionThresholds() {
        val hour = 3_600_000L
        assertFalse(ProgressRules.canResume(10_000, hour))           // too early to bother
        assertTrue(ProgressRules.canResume(20 * 60_000L, hour))
        assertFalse(ProgressRules.canResume((hour * 0.95).toLong(), hour)) // past 90%: counts as finished
        assertTrue(ProgressRules.isComplete((hour * 0.9).toLong(), hour))
        assertFalse(ProgressRules.isComplete((hour * 0.9).toLong(), hour, 0.95))
        assertFalse(ProgressRules.isComplete(hour, 0))                 // unknown duration never completes
    }

    @Test fun watchedIsRecordedOnlyOnce() {
        val hour = 3_600_000L
        assertTrue(ProgressRules.shouldMarkWatched(false, hour, hour))
        assertFalse(ProgressRules.shouldMarkWatched(true, hour, hour))  // no duplicate history
        assertFalse(ProgressRules.shouldMarkWatched(false, hour / 2, hour))
    }

    @Test fun clockFormat() {
        assertEquals("0:05", ProgressRules.clock(5_000))
        assertEquals("12:07", ProgressRules.clock(727_000))
        assertEquals("1:02:05", ProgressRules.clock(3_725_000))
    }
}
