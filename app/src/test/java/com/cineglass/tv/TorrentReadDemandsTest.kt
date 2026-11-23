package com.cineglass.tv

import org.junit.Assert.*
import org.junit.Test

class TorrentReadDemandsTest {
    @Test fun resumeAndMetadataCanProgressWhileStartupReaderIsStillClosing() {
        val demands = TorrentReadDemands(2 * 1024 * 1024)
        demands.update(1, 10, 2000)
        demands.update(2, 1999, 2000) // index probe
        val resume = demands.update(3, 810, 2000)
        assertEquals(setOf(10, 11, 12, 13, 1999, 2000, 810, 811, 812, 813), resume.keys)
        assertEquals(0, resume[810])
        assertEquals(0, resume[1999])
        assertEquals(setOf(1999, 2000, 810, 811, 812, 813), demands.remove(1).keys)
        assertEquals(setOf(810, 811, 812, 813), demands.remove(2).keys)
        assertTrue(demands.remove(3).isEmpty())
    }

    @Test fun seekBackReleasesOldSelectionAndDoesNotLeaveHighestPrioritiesBehind() {
        val demands = TorrentReadDemands(256 * 1024)
        demands.update(1, 1400, 4000)
        demands.update(2, 526, 4000)
        assertEquals((526..541).toSet(), demands.remove(1).keys)
        assertEquals((527..542).toSet(), demands.update(2, 527, 4000).keys)
        assertTrue(demands.remove(2).isEmpty())
        assertTrue(demands.remove(2).isEmpty())
    }

    @Test fun shortRangesAreBoundedAndOverlappingReadersRetainSharedPieces() {
        val demands = TorrentReadDemands(1024 * 1024)
        assertEquals(setOf(200), demands.update(1, 200, 200).keys)
        demands.update(2, 199, 202)
        val afterClose = demands.remove(1)
        assertEquals(setOf(199, 200, 201, 202), afterClose.keys)
        assertEquals(125, afterClose[200])
    }

    @Test fun finalPiecesAreAlsoPlayableAndNotPermanentlyClassifiedAsMetadata() {
        val demands = TorrentReadDemands(1024 * 1024)
        assertEquals(setOf(998, 999, 1000), demands.update(1, 998, 1000).keys)
        assertEquals(setOf(Int.MAX_VALUE), demands.update(1, Int.MAX_VALUE, Int.MAX_VALUE).keys)
    }

    @Test fun transportOutlivesMaximumPieceWaitAndCancellationIsVisibleAcrossThreads() {
        assertTrue(TorrentTransportPolicy.HTTP_READ_TIMEOUT_MS > TorrentTransportPolicy.MAX_PIECE_WAIT_MS + 1200)
        val cancellation = TorrentReadCancellation()
        val worker = Thread { cancellation.cancel() }
        worker.start(); worker.join(1000)
        assertTrue(cancellation.isCancelled())
    }
    @Test fun pickerClearsCancelledPiecesKeepsSharedOnesAndDoesNotPostponeDeadlines() {
        val deadlines = mutableListOf<Pair<Int, Int>>()
        val ignored = mutableListOf<Int>()
        val picker = TorrentPieceSelection({ it == 5 }, { piece, delay -> deadlines.add(piece to delay) }, ignored::add)
        picker.apply(mapOf(1 to 0, 2 to 125, 5 to 250))
        assertEquals(listOf(1 to 0, 2 to 125), deadlines)
        picker.apply(mapOf(1 to 0, 2 to 125, 5 to 250))
        assertEquals(2, deadlines.size)
        picker.apply(mapOf(2 to 0, 5 to 125))
        assertEquals(listOf(1), ignored)
        assertEquals(2 to 0, deadlines.last())
        picker.apply(emptyMap())
        assertEquals(setOf(1, 2, 5), ignored.toSet())
    }

    @Test fun peerRefreshIsReservedForMissingSourcesAndSharedAcrossReaders() {
        val refresh = TorrentPeerRefresh()
        assertFalse(refresh.claim(6_000, advertisedPeers = 2))
        assertTrue(refresh.claim(6_000, advertisedPeers = 0))
        assertFalse(refresh.claim(6_000, advertisedPeers = 0))
        assertFalse(refresh.claim(25_999, advertisedPeers = 0))
        assertTrue(refresh.claim(26_000, advertisedPeers = 0))
    }

}
