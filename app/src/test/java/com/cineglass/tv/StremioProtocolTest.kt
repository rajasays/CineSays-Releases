package com.cineglass.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioProtocolTest {
    private val hosts = listOf("archive.org", "media.example.org")

    @Test fun manifestWithStringResourcesIsAccepted() {
        val m = StremioProtocol.parseManifest("""{"id":"org.example","name":"Example","version":"1.0.0","types":["movie","series"],"resources":["catalog","stream"],"idPrefixes":["tt"]}""")
        assertNotNull(m)
        assertEquals(listOf("movie", "series"), m!!.streamTypes)
        assertTrue(m.servesStreams("movie", "tt0111161"))
        assertFalse(m.servesStreams("movie", "kitsu:1"))
    }

    @Test fun manifestWithObjectResourceUsesItsOwnTypesAndPrefixes() {
        val m = StremioProtocol.parseManifest("""{"id":"a","name":"A","version":"2","types":["movie","series","channel"],
            "resources":[{"name":"stream","types":["series"],"idPrefixes":["tt"]}]}""")!!
        assertEquals(listOf("series"), m.streamTypes)
        assertTrue(m.servesStreams("series", "tt0903747:1:2"))
        assertFalse(m.servesStreams("movie", "tt0111161"))
    }

    @Test fun manifestWithoutStreamResourceIsRejected() {
        assertNull(StremioProtocol.parseManifest("""{"id":"a","name":"A","version":"1","types":["movie"],"resources":["catalog","meta"]}"""))
        assertNull(StremioProtocol.parseManifest("""not json"""))
        assertNull(StremioProtocol.parseManifest("""{"name":"no id","resources":["stream"]}"""))
    }

    @Test fun streamUrlFollowsTheProtocol() {
        val base = StremioProtocol.baseOf("https://addon.example.org/abc/manifest.json")
        assertEquals("https://addon.example.org/abc", base)
        assertEquals("https://addon.example.org/abc/stream/series/tt0903747:1:2.json", StremioProtocol.streamUrl(base, "series", "tt0903747:1:2"))
    }

    @Test fun approvedHttpsStreamIsPlayableWithKindAndSize() {
        val json = """{"streams":[{"name":"Archive","title":"Night of the Living Dead","url":"https://archive.org/download/x/night.mp4",
            "behaviorHints":{"videoSize":734003200},"subtitles":[{"id":"en1","lang":"en","url":"https://archive.org/download/x/night.srt"}]},
            {"name":"HLS","url":"https://media.example.org/live/master.m3u8"}]}"""
        val s = StremioProtocol.parseStreams(json, "ex", "Example", hosts)
        assertEquals(2, s.size)
        assertTrue(s[0].playable)
        assertEquals(StreamKind.PROGRESSIVE, s[0].kind)
        assertEquals(734003200L, s[0].sizeBytes)
        assertEquals("application/x-subrip", s[0].subtitles.single().mimeType)
        assertEquals(StreamKind.HLS, s[1].kind)
    }

    @Test fun technicalDetailsAreNeverGuessedFromNames() {
        val s = StremioProtocol.parseStreams("""{"streams":[{"name":"4K HDR DV Atmos","url":"https://archive.org/a.mkv"}]}""", "ex", "Example", hosts).single()
        assertNull(s.resolution); assertNull(s.hdr); assertNull(s.audio); assertNull(s.videoCodec)
        assertEquals("4K HDR DV Atmos", s.label) // shown only as the source's own description
    }

    @Test fun torrentResultsAreRecognisedButNeverPlayable() {
        val json = """{"streams":[{"name":"T","infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":0},
            {"name":"M","url":"magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"}]}"""
        val s = StremioProtocol.parseStreams(json, "ex", "Example", hosts)
        assertEquals(2, s.size)
        s.forEach { assertFalse(it.playable); assertEquals(StreamKind.TORRENT, it.kind); assertNull(it.url) }
    }

    @Test fun unapprovedInsecureOrHeaderStreamsAreNotPlayable() {
        val json = """{"streams":[{"url":"https://evil.example.com/x.mp4"},{"url":"http://archive.org/x.mp4"},
            {"url":"https://archive.org.evil.com/x.mp4"},{"url":"https://archive.org/x.mp4","behaviorHints":{"proxyHeaders":{"request":{"Referer":"x"}}}},
            {"ytId":"dQw4w9WgXcQ"},{"externalUrl":"https://example.com"}]}"""
        val s = StremioProtocol.parseStreams(json, "ex", "Example", hosts)
        assertEquals(6, s.size)
        assertTrue(s.none { it.playable })
        assertTrue(s.all { it.url == null })
        assertNotNull(s.first().unsupportedReason)
    }

    @Test fun malformedStreamResponsesYieldNothing() {
        assertTrue(StremioProtocol.parseStreams("{}", "ex", "Example", hosts).isEmpty())
        assertTrue(StremioProtocol.parseStreams("""{"streams":"nope"}""", "ex", "Example", hosts).isEmpty())
        assertTrue(StremioProtocol.parseStreams("""{"streams":[1,"x",null,{}]}""", "ex", "Example", hosts).isEmpty())
        assertTrue(StremioProtocol.parseStreams("<html>", "ex", "Example", hosts).isEmpty())
    }

    @Test fun subtitlesFromUnapprovedHostsAreDropped() {
        val s = StremioProtocol.parseStreams("""{"streams":[{"url":"https://archive.org/a.mp4","subtitles":[{"lang":"en","url":"https://other.example.com/a.vtt"}]}]}""",
            "ex", "Example", hosts).single()
        assertTrue(s.subtitles.isEmpty())
    }
}
