package com.cineglass.tv

import java.util.concurrent.atomic.AtomicBoolean

/** One bounded selection per live byte-range reader, including container index reads. */
internal class TorrentReadDemands(private val pieceLength: Int) {
    private val readers = LinkedHashMap<Int, List<Int>>()

    fun update(reader: Int, piece: Int, last: Int): Map<Int, Int> {
        readers[reader] = TorrentRules.adaptiveReadWindow(piece, last, pieceLength)
        return deadlines()
    }

    fun remove(reader: Int): Map<Int, Int> {
        readers.remove(reader)
        return deadlines()
    }

    private fun deadlines(): Map<Int, Int> = buildMap {
        // All current reads are urgent. A tail index probe and a seek both need
        // data; neither can be identified reliably from its byte offset alone.
        readers.values.forEach { window -> window.forEachIndexed { distance, piece ->
            val deadline = distance * 125
            put(piece, minOf(get(piece) ?: Int.MAX_VALUE, deadline))
        } }
    }
}

/** Shared with the HTTP disconnect watcher; no cross-thread DataSource.close(). */
internal class TorrentReadCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get()
}

internal object TorrentTransportPolicy {
    const val MAX_PIECE_WAIT_MS = 60_000L
    // Leaves HTTP overhead above the maximum single-piece wait before headers.
    const val HTTP_READ_TIMEOUT_MS = 70_000
}

/** Applies only changed selections; cancelled readers cannot leave priority 7 behind. */
internal class TorrentPieceSelection(
    private val havePiece: (Int) -> Boolean,
    private val setDeadline: (Int, Int) -> Unit,
    private val ignorePiece: (Int) -> Unit
) {
    private var deadlines: Map<Int, Int> = emptyMap()
    val pieces: Set<Int> get() = deadlines.keys

    fun apply(next: Map<Int, Int>) {
        (deadlines.keys - next.keys).forEach(ignorePiece)
        next.forEach { (piece, delay) ->
            val old = deadlines[piece]
            // Never push a deadline later on each read/poll: libtorrent owns
            // the adaptive duplicate-request recovery for stalled blocks.
            if ((old == null || delay < old) && !havePiece(piece)) setDeadline(piece, delay)
        }
        deadlines = next.toMap()
    }
}

/** Share the announce cooldown across all readers and HTTP retries of a playback. */
internal class TorrentPeerRefresh {
    private var nextAllowedAt = 0L

    @Synchronized
    fun claim(nowMs: Long, advertisedPeers: Int): Boolean {
        if (advertisedPeers != 0 || nowMs < nextAllowedAt) return false
        nextAllowedAt = nowMs + 20_000
        return true
    }
}
