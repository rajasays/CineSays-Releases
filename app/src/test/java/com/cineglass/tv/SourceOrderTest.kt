package com.cineglass.tv

import org.junit.Assert.*
import org.junit.Test

class SourceOrderTest {
    private fun source(label: String?, bytes: Long? = null) = PlayableStream(
        "test", "Torrentio", "Torrentio 1080p", null, StreamKind.TORRENT, label = label, sizeBytes = bytes)

    @Test fun headingsAndDescriptionsDoNotRepeatProviderBrand() {
        val s = source("Torrentio 1080p · Movie.mkv 1 GB")
        assertEquals("1080p source", SourceDetails.heading(s))
        assertEquals("Movie.mkv 1 GB", SourceDetails.description(s))
        assertEquals("Media source", SourceDetails.heading(s.copy(sourceName = "Torrentio", label = null)))
    }
    @Test fun sizesSortNumericallyWithUnknownLast() {
        val sources = listOf(source(null), source("2 GiB"), source("700 MB"), source("1,5 GB"))
        val sorted = sources.sortedBy { SourceDetails.sizeForSorting(it) ?: Long.MAX_VALUE }
        assertEquals(listOf("700 MB", "1,5 GB", "2 GiB", null), sorted.map { it.label })
        assertEquals(2147483648L, SourceDetails.sizeForSorting(source("2 GiB")))
    }
    @Test fun structuredBytesWinAndInvalidSizesAreUnknown() {
        assertEquals(100L, SourceDetails.sizeForSorting(source("2 GB", 100)))
        assertNull(SourceDetails.sizeForSorting(source("0 MB")))
        assertNull(SourceDetails.sizeForSorting(source("999999999999999999999999999999 GB")))
    }
}
