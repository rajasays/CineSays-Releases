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
    @Test fun tailProbeDoesNotDisplaceStartupPlaybackWindow() {
        assertTrue(TorrentRules.isTailProbe(1024, 0, 1024, 0))
        assertTrue(TorrentRules.isTailProbe(1023, 0, 1024, 3))
        assertFalse(TorrentRules.isTailProbe(1024, 0, 1024, 250))
        assertFalse(TorrentRules.isTailProbe(15, 0, 1024, 0))
        assertFalse(TorrentRules.isTailProbe(12, 0, 12, 0))
        assertFalse(TorrentRules.isTailProbe(200, 50, 500, -1))
    }
    @Test fun startupWarmupIsBoundedToSelectedVideo() {
        assertEquals(listOf(0, 1), TorrentRules.startupWarmupPieces(0, 1024))
        assertEquals(listOf(12), TorrentRules.startupWarmupPieces(12, 12))
        assertEquals(listOf(Int.MAX_VALUE), TorrentRules.startupWarmupPieces(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(TorrentRules.startupWarmupPieces(3, 2).isEmpty())
    }
    @Test fun overlappingReadersKeepOriginalPlaybackOwner() {
        val owners = TorrentPriorityOwners()
        owners.enter(10)
        owners.enter(11) // metadata probe
        owners.enter(12) // range request
        assertEquals(10, owners.primary)
        assertTrue(owners.isPrimary(10))
        assertFalse(owners.isPrimary(11))
        assertFalse(owners.leave(11))
        assertEquals(10, owners.primary)
        assertFalse(owners.leave(11)) // idempotent cleanup
    }

    @Test fun ownershipMovesToWaitingSeekWhenOriginalReaderCloses() {
        val owners = TorrentPriorityOwners()
        owners.enter(1)
        owners.enter(2)
        owners.enter(3)
        assertTrue(owners.leave(1))
        assertEquals(2, owners.primary)
        assertTrue(owners.leave(2))
        assertEquals(3, owners.primary)
        assertTrue(owners.leave(3))
        assertNull(owners.primary)
        owners.enter(4)
        assertEquals(4, owners.primary)
    }

    @Test fun readAheadIsBoundedAtEndAndIntegerLimit() {
        assertEquals(listOf(98, 99), TorrentRules.readWindow(98, 99))
        assertEquals(listOf(Int.MAX_VALUE), TorrentRules.readWindow(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(TorrentRules.readWindow(10, 9).isEmpty())
    }
}
