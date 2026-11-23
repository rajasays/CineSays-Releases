package com.cineglass.tv

import org.junit.Assert.*
import org.junit.Test

class TorrentStartupTest {
    @Test fun startupIncludesHeadersAndTailWithinSelectedFile() {
        assertEquals(listOf(10, 11, 12, 13, 98, 99), TorrentRules.startupPieces(10, 99))
        assertEquals(listOf(10), TorrentRules.startupPieces(10, 10))
        assertEquals(listOf(10, 11), TorrentRules.startupPieces(10, 11))
    }
    @Test fun readAheadIsBoundedAtEndAndIntegerLimit() {
        assertEquals(listOf(98, 99), TorrentRules.readWindow(98, 99))
        assertEquals(listOf(Int.MAX_VALUE), TorrentRules.readWindow(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(TorrentRules.readWindow(10, 9).isEmpty())
    }
}
