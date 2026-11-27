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
        assertEquals((10..11).toList(), TorrentRules.adaptiveReadWindow(10, 100, 4 * 1024 * 1024))
        assertEquals((10..11).toList(), TorrentRules.adaptiveReadWindow(10, 100, 8 * 1024 * 1024))
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

    @Test fun metadataTailNeverInheritsPlaybackPriority() {
        assertTrue(TorrentRules.isMetadataTailRange(1024, 0, 1024))
        assertTrue(TorrentRules.isMetadataTailRange(1022, 0, 1024))
        assertFalse(TorrentRules.isMetadataTailRange(1021, 0, 1024))
        assertFalse(TorrentRules.isMetadataTailRange(15, 0, 15))
        assertFalse(TorrentRules.isMetadataTailRange(1024, 100, 200))

        val owners = TorrentPriorityOwners()
        owners.enter(1) // main playback
        owners.enter(2, eligible = false) // tail metadata
        owners.enter(3) // next video playback range
        assertTrue(owners.leave(1))
        assertEquals(3, owners.primary)
        assertTrue(owners.isPrimary(3))
        assertFalse(owners.leave(2))
        assertTrue(owners.leave(3))
        assertNull(owners.primary)

        owners.enter(4, eligible = false)
        assertNull(owners.primary)
        owners.enter(5)
        assertEquals(5, owners.primary)
    }

    @Test fun sourceHealthWarningsAreAdvisoryAndGroundedInPeerState() {
        assertTrue(TorrentHealthRules.message(0, 0, 0, 4).contains("Connecting"))
        assertTrue(TorrentHealthRules.message(0, 0, 0, 15).contains("No torrent peers"))
        assertTrue(TorrentHealthRules.message(10, 0, 0, 20).contains("aren't advertising"))
        assertTrue(TorrentHealthRules.message(10, 1, 0, 20).contains("Only a few"))
        assertTrue(TorrentHealthRules.message(10, 8, 0, 20).contains("too slowly"))
        assertTrue(TorrentHealthRules.message(10, null, 0, 20).contains("too slowly"))
        assertTrue(TorrentHealthRules.message(10, 8, 2, 20).contains("may resume"))
    }

    @Test fun farSeekTakesOwnershipWithoutOldReaderReclaimingIt() {
        assertTrue(TorrentRules.isSeekJump(310, 1, 0, 1024))
        assertTrue(TorrentRules.isSeekJump(20, 400, 0, 1024))
        assertFalse(TorrentRules.isSeekJump(10, 1, 0, 1024))
        assertFalse(TorrentRules.isSeekJump(1024, 1, 0, 1024))
        assertFalse(TorrentRules.isSeekJump(1025, 1, 0, 1024))
        val owners = TorrentPriorityOwners()
        owners.enter(1)
        owners.enter(2)
        owners.enter(3, eligible = false)
        assertTrue(owners.promote(2))
        assertEquals(2, owners.primary)
        assertFalse(owners.canPromote(1))
        assertFalse(owners.promote(1))
        assertFalse(owners.promote(3))
        assertFalse(owners.leave(1))
        assertTrue(owners.leave(2))
        assertNull(owners.primary)
        owners.enter(4)
        assertEquals(4, owners.primary)
    }

    @Test fun waitingPieceExtendsOnlyWhileItsOwnBlocksProgress() {
        val start = 100_000L
        val budget = TorrentPieceWaitBudget(start)
        assertFalse(budget.expired(start + 24_999))
        assertTrue(budget.expired(start + 25_000)) // no piece progress
        val progressing = TorrentPieceWaitBudget(start)
        progressing.observe(start + 20_000, 60)
        assertTrue(progressing.extended(start + 25_000))
        progressing.observe(start + 28_000, 90)
        assertTrue(progressing.extended(start + 33_000))
        assertTrue(progressing.expired(start + 38_000)) // 10s without progress
        val capped = TorrentPieceWaitBudget(start)
        capped.observe(start + 24_000, 4)
        capped.observe(start + 32_000, 8)
        capped.observe(start + 40_000, 12)
        capped.observe(start + 48_000, 16)
        capped.observe(start + 56_000, 20)
        assertFalse(capped.expired(start + 59_999))
        assertTrue(capped.expired(start + 60_000))
    }

    @Test fun readAheadIsBoundedAtEndAndIntegerLimit() {
        assertEquals(listOf(98, 99), TorrentRules.readWindow(98, 99))
        assertEquals(listOf(Int.MAX_VALUE), TorrentRules.readWindow(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(TorrentRules.readWindow(10, 9).isEmpty())
    }
    @Test fun restartedPieceCanExtendWaitEvenBelowPreviousHighWaterMark() {
        val budget = TorrentPieceWaitBudget(0)
        budget.observe(6_000, 131)
        budget.observe(12_000, 1)
        assertTrue(budget.expired(25_000)) // A reset alone is not progress.
        val restarted = TorrentPieceWaitBudget(0)
        restarted.observe(6_000, 131)
        restarted.observe(12_000, 1)
        restarted.observe(22_000, 121)
        assertFalse(restarted.expired(25_000))
        assertTrue(restarted.expired(32_000))
    }

    @Test fun seekRetryRequiresRecentTimeoutAfterSeekAndIsBounded() {
        val retry = TorrentSeekRetry()
        assertFalse(retry.claim(30_000, 29_000))
        retry.begin(30_000)
        assertFalse(retry.claim(31_000, 29_000))
        assertFalse(retry.claim(56_000, 50_000))
        assertTrue(retry.claim(56_001, 56_000))
        assertFalse(retry.claim(57_000, 56_000))
        retry.clear()
        assertFalse(retry.claim(57_000, 56_000))
    }

}
