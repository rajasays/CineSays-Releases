package com.cineglass.tv

import org.junit.Assert.*
import org.junit.Test

class TorrentStartupTest {
    @Test fun startupIncludesHeadersAndTailWithinSelectedFile() {
        assertEquals(listOf(10, 11, 12, 13, 98, 99), TorrentRules.startupPieces(10, 99))
        assertEquals(listOf(10), TorrentRules.startupPieces(10, 10))
        assertEquals(listOf(10, 11), TorrentRules.startupPieces(10, 11))
    }
    @Test fun adaptiveWindowRespectsPieceSizeAndEndOfFile() {
        // 256 KiB pieces produce a bounded 16-piece window.
        assertEquals((10..25).toList(), TorrentRules.adaptiveReadWindow(10, 100, 256 * 1024))
        // 2 MiB pieces need only four scheduled pieces.
        assertEquals((10..13).toList(), TorrentRules.adaptiveReadWindow(10, 100, 2 * 1024 * 1024))
        // Even large pieces retain a minimum near-term look-ahead.
        assertEquals((10..13).toList(), TorrentRules.adaptiveReadWindow(10, 100, 4 * 1024 * 1024))
        assertEquals(listOf(98, 99), TorrentRules.adaptiveReadWindow(98, 99, 256 * 1024))
        assertEquals(listOf(Int.MAX_VALUE), TorrentRules.adaptiveReadWindow(Int.MAX_VALUE, Int.MAX_VALUE, 256 * 1024))
        assertTrue(TorrentRules.adaptiveReadWindow(10, 9, 256 * 1024).isEmpty())
        assertTrue(TorrentRules.adaptiveReadWindow(10, 20, 0).isEmpty())
    }
    @Test fun readAheadIsBoundedAtEndAndIntegerLimit() {
        assertEquals(listOf(98, 99), TorrentRules.readWindow(98, 99))
        assertEquals(listOf(Int.MAX_VALUE), TorrentRules.readWindow(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(TorrentRules.readWindow(10, 9).isEmpty())
    }
}
