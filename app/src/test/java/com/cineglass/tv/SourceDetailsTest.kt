package com.cineglass.tv

import org.junit.Assert.*
import org.junit.Test

class SourceDetailsTest {
    private fun parse(description: String, hints: String = "") = StremioProtocol.parseStreams(
        """{"streams":[{"name":"Torrentio 1080p","description":"$description","infoHash":"11ea02584fa6351956f35671962ab46354d99060"$hints}]}""",
        "torrentio", "Torrentio", emptyList(), allowTorrentStreams = true, allowDebrid = false).single()

    @Test fun reportsSizeContainerCodecAndLanguages() {
        val summary = SourceDetails.summary(parse("Movie.1080p.HEVC.DDP5.1.mkv 💾 1.51 GB 🇬🇧 Hindi"))
        listOf("Size: 1.51 GB", "Format: MKV", "Codec: HEVC", "Hindi", "English", "Quality: 1080p").forEach { assertTrue(summary, it in summary) }
    }
    @Test fun preservesDetailsBeyondOldLimitAndKeepsHeadingShort() {
        val stream = parse("x".repeat(240) + "\\n💾 2 GB Japanese")
        assertEquals("Torrentio 1080p", stream.sourceName)
        assertTrue(stream.label!!.contains("Japanese"))
        assertTrue(SourceDetails.summary(stream).contains("Size: 2 GB"))
    }
    @Test fun doesNotInventMissingLanguagesOrFormats() {
        val summary = SourceDetails.summary(parse("🇮🇳"))
        assertTrue(summary.contains("Language: Not reported"))
        assertTrue(summary.contains("Format: Not reported"))
        assertTrue(summary.contains("Size: Not reported"))
    }
    @Test fun usesStructuredSizeAndFilenameWithoutChangingMediaFacts() {
        val stream = parse("💾 9 GB", """, "behaviorHints":{"videoSize":1073741824,"filename":"movie.mp4"}""")
        val summary = SourceDetails.summary(stream)
        assertTrue(summary.contains("Size: 1.00 GiB"))
        assertTrue(summary.contains("Format: MP4"))
        assertNull(stream.resolution) // Display hints are not verified player metadata.
    }
    @Test fun capsUntrustedDescription() {
        assertTrue(parse("x".repeat(3000)).label!!.length < 2100)
    }
}
