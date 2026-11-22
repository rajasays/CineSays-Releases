package com.cineglass.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDebridSourcesTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val ref = MediaRef(MediaType.EPISODE, 1, "Example", "tt0123456", season = 1, episode = 2)

    @Test fun curatedTorrentRequiresExplicitPlaybackChoice() {
        val json = """{"streams":[{"infoHash":"$hash","fileIdx":2,"behaviorHints":{"filename":"episode.mkv"}}]}"""
        val ordinary = StremioProtocol.parseStreams(json, "example", "Example", emptyList()).single()
        assertNull(ordinary.debridTorrent)
        val debrid = StremioProtocol.parseStreams(json, "example", "Example", emptyList(), authorizedHashes = setOf(hash)).single()
        assertEquals(DebridTorrent(hash, 2, "episode.mkv"), debrid.debridTorrent)
        assertFalse(debrid.playable)
        assertNull(debrid.url)
        val actions = PlaybackRouter.options(ref, StreamSearch(listOf(debrid)), emptyList(), emptySet(), false)
        assertTrue(actions.single() is WatchAction.Unavailable)
        assertNull(PlaybackRouter.automatic(actions, PlaybackDestination.CINESAYS))
    }

    @Test fun invalidHashAndMalformedIndexStayUnsupported() {
        val json = """{"streams":[{"infoHash":"invalid"},{"infoHash":"$hash","fileIdx":-1},{"infoHash":"$hash","fileIdx":1.2}]}"""
        val streams = StremioProtocol.parseStreams(json, "example", "Example", emptyList(), authorizedHashes = setOf(hash))
        assertEquals(3, streams.size)
        assertTrue(streams.all { it.debridTorrent == null && !it.playable })
    }

    @Test fun curatedDebridAddonDefaultsOff() {
        val addon = Addon("example", "Example", engine = Addons.STREMIO, manifestUrl = "https://addon.example.com/manifest.json",
            allowedHosts = listOf("media.example.com"), realDebrid = true, authorizedTorrentHashes = listOf(hash))
        assertFalse(Addons.sanitize(addon)!!.defaultOn)
        assertNull(Addons.sanitize(addon.copy(authorizedTorrentHashes = null)))
        assertTrue(Addons.sanitize(addon.copy(realDebrid = false))!!.defaultOn)
    }

    @Test fun fileSelectionUsesServiceIdAndRejectsAmbiguousPacks() {
        val files = listOf(TorrentFile(47, "/Season 1/episode.mkv"), TorrentFile(3, "/Extras/episode.mkv"))
        assertEquals(47, chooseFile(files, DebridTorrent(hash, 0, "Season 1/episode.mkv")))
        assertNull(chooseFile(files, DebridTorrent(hash, 0, "episode.mkv")))
        assertNull(chooseFile(files, DebridTorrent(hash, 0, null)))
        assertEquals(47, chooseFile(files.take(1), DebridTorrent(hash, 0, null)))
        assertNull(chooseFile(files.take(1), DebridTorrent(hash, 2, null)))
    }
    @Test fun bigBuckBunnyHasAnExplicitFreeTorrentChoice() {
        val testHash = "0e876ce2a1a504f849ca72a5e2bc07347b3bc957"
        val json = """{"streams":[{"infoHash":"$testHash","behaviorHints":{"filename":"big_buck_bunny_720p_surround.mp4"}}]}"""
        val streams = StremioProtocol.parseStreams(json, "example", "Example", emptyList(), authorizedHashes = setOf(testHash))
        val actions = PlaybackRouter.options(ref, StreamSearch(streams), emptyList(), emptySet(), false)
        assertEquals(listOf(WatchAction.PlayTorrent(streams.single())), actions)
        assertNull(PlaybackRouter.automatic(actions, PlaybackDestination.CINESAYS))
    }
}
