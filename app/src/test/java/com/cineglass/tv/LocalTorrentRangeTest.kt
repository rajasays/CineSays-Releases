package com.cineglass.tv

import org.junit.Assert.*
import org.junit.Test

class LocalTorrentRangeTest {
    @Test fun fullRequestAndSimpleByteRanges() {
        assertEquals(0L to 999L, LocalTorrentRange.parse(null, 1000))
        assertEquals(100L to 199L, LocalTorrentRange.parse("bytes=100-199", 1000))
        assertEquals(100L to 999L, LocalTorrentRange.parse("bytes=100-", 1000))
        assertEquals(990L to 999L, LocalTorrentRange.parse("bytes=-10", 1000))
        assertEquals(0L to 999L, LocalTorrentRange.parse("bytes=-2000", 1000))
        assertEquals(950L to 999L, LocalTorrentRange.parse("bytes=950-99999", 1000))
    }

    @Test fun rejectsOutOfBoundsAndUnsupportedRanges() {
        listOf("bytes=1000-", "bytes=10-5", "bytes=-0", "bytes=0-2,4-6",
            "items=0-20", "bytes=-", "bytes=abc-", "bytes=0--1", "bytes=0-9999999999999999999999999")
            .forEach { assertNull(it, LocalTorrentRange.parse(it, 1000)) }
        assertNull(LocalTorrentRange.parse(null, 0))
    }
}
