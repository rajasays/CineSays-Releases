package com.cineglass.tv

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TorrentSourcesTest {
    private val hash = "11ea02584fa6351956f35671962ab46354d99060"
    private val ref = MediaRef(MediaType.MOVIE, 1, "Test", "tt0063350")
    private fun parse(json: String, enabled: Boolean = true) = StremioProtocol.parseStreams(
        json, "test", "Test", emptyList(), allowTorrentStreams = enabled, allowDebrid = false)

    @Test fun suppliedResponseOffersFreePlaybackWithoutDebrid() {
        val stream = parse("""{"streams":[{"infoHash":"$hash","title":"1.51 GB","fileIdx":0,"name":"1080p"}]}""").single()
        assertEquals(0, stream.debridTorrent!!.fileIndex)
        val options = PlaybackRouter.options(ref, StreamSearch(listOf(stream)), emptyList(), emptySet(), false)
        assertEquals(listOf(WatchAction.PlayTorrent(stream)), options)
        assertNull(PlaybackRouter.automatic(options, PlaybackDestination.CINESAYS))
    }
    @Test fun sourceCapabilityIsExplicitAndDefaultsOff() {
        val addon = Addon("x", "X", engine = Addons.STREMIO, manifestUrl = "https://example.org/manifest.json", torrentStreams = true)
        val sanitized = Addons.sanitize(addon)!!
        assertFalse(sanitized.defaultOn)
        assertTrue(Addons.permitsTorrent(sanitized, hash))
        assertFalse(Addons.permitsTorrent(sanitized.copy(torrentStreams = false), hash))
        assertNull(parse("""{"streams":[{"infoHash":"$hash"}]}""", false).single().debridTorrent)
        assertNull(Addons.sanitize(addon.copy(manifestUrl = "http://example.org/manifest.json")))
    }
    @Test fun torrentCapabilityDoesNotAllowArbitraryDirectUrls() {
        val stream = parse("""{"streams":[{"url":"https://unapproved.example/video.mp4"}]}""").single()
        assertFalse(stream.playable)
    }
    @Test fun invalidHashesAndIndexesNeverReachEngine() {
        val streams = parse("""{"streams":[{"infoHash":"bad"},{"infoHash":"$hash","fileIdx":-1},{"infoHash":"$hash","fileIdx":0.5},{"infoHash":"$hash","fileIdx":"0"},{"infoHash":"$hash","fileIdx":2147483648}]}""")
        assertEquals(5, streams.size)
        assertTrue(streams.all { it.debridTorrent == null })
    }
    @Test fun absentOrNullIndexAllowsMetadataSelection() {
        assertNull(parse("""{"streams":[{"infoHash":"$hash","fileIdx":null}]}""").single().debridTorrent!!.fileIndex)
        assertNotNull(parse("""{"streams":[{"infoHash":"${hash.uppercase()}"}]}""").single().debridTorrent)
    }
    @Test fun fileIndexSelectsFromAllFilesAndNeverFallsBack() {
        val paths = listOf("readme.txt", "Season/ep1.mkv", "Season/ep2.mkv")
        assertEquals(2, TorrentRules.selectVideo(paths, 2, "ep1.mkv"))
        assertNull(TorrentRules.selectVideo(paths, 0, null))
        assertNull(TorrentRules.selectVideo(paths, 3, "ep1.mkv"))
        assertNull(TorrentRules.selectVideo(paths, -1, null))
        assertNull(TorrentRules.selectVideo(paths, null, null))
        assertEquals(1, TorrentRules.selectVideo(paths, null, "ep1.mkv"))
    }
    @Test fun filenamesMustMatchUniquelyAndSingleVideoIsFallback() {
        val paths = listOf("a/movie.mp4", "b/movie.mp4")
        assertNull(TorrentRules.selectVideo(paths, null, "movie.mp4"))
        assertEquals(1, TorrentRules.selectVideo(paths, null, "b/movie.mp4"))
        assertNull(TorrentRules.selectVideo(paths.take(1), null, "wrong.mp4"))
        assertEquals(0, TorrentRules.selectVideo(paths.take(1), null, null))
    }
    @Test fun largerVideosRequireSpaceWithoutOverflow() {
        assertTrue(TorrentRules.hasSpace(1_600_000_000L, 2_000_000_000L, 262144))
        assertFalse(TorrentRules.hasSpace(1_600_000_000L, 1_600_000_000L, 262144))
        assertFalse(TorrentRules.hasSpace(Long.MAX_VALUE, Long.MAX_VALUE, 262144))
        assertFalse(TorrentRules.hasSpace(0, 2_000_000_000L, 262144))
    }
    @Test fun unsafeMetadataPathsAreRejected() {
        val root = File(System.getProperty("java.io.tmpdir"), "cinesays-path-test")
        assertTrue(TorrentRules.safePath(root, "movie/video.mp4"))
        listOf("../video.mp4", "/video.mp4", "C:/video.mp4", "..\\video.mp4").forEach {
            assertFalse(TorrentRules.safePath(root, it))
        }
    }
    @Test fun episodeIdIsPreservedInRequest() {
        val episode = ref.copy(type = MediaType.EPISODE, season = 2, episode = 3)
        assertEquals("https://example.org/stream/series/tt0063350:2:3.json",
            StremioProtocol.streamUrl("https://example.org", episode.stremioType, episode.stremioId!!))
    }
}
