package com.cineglass.tv

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import okhttp3.Request
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class TorrentPreparationException(message: String) : IOException(message)

private const val TEST_HASH = "0e876ce2a1a504f849ca72a5e2bc07347b3bc957"
private const val TEST_FILENAME = "big_buck_bunny_720p_surround.avi"
private const val WEBTORRENT_HASH = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
private const val WEBTORRENT_URL = "https://webtorrent.io/torrents/big-buck-bunny.torrent"
private const val LEGACY_TEST_FILENAME = "big_buck_bunny_720p_surround.mp4"

@OptIn(UnstableApi::class)
class TorrentPlayback private constructor(
    private val session: SessionManager,
    private val directory: File,
    private val handle: TorrentHandle,
    private val file: File,
    private val fileSize: Long,
    private val fileOffset: Long,
    private val pieceLength: Int,
) {
    private val closed = AtomicBoolean()
    val uri: String = Uri.fromFile(file).toString()
    val contentLength: Long get() = fileSize
    private var loopback: LocalTorrentHttp? = null

    @Synchronized fun loopbackUrl(): String {
        if (closed.get()) throw IOException("Torrent session closed")
        return (loopback ?: LocalTorrentHttp.start(this).also { loopback = it }).url
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { TorrentDataSource() }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) { loopback?.close(); loopback = null }
            cleanup.execute { dispose(session, directory) }
        }
    }

    private val readDemands = TorrentReadDemands(pieceLength)
    private val nextReaderId = AtomicInteger(1)
    private val selection = TorrentPieceSelection(handle::havePiece,
        { piece, deadline -> handle.setPieceDeadline(piece, deadline) },
        { piece -> handle.resetPieceDeadline(piece); handle.piecePriority(piece, Priority.IGNORE) })
    private var lastReadyAheadLog = 0L
    private var requestedPiece = -1
    private val firstFilePiece = (fileOffset / pieceLength).toInt()
    private val lastFilePiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
    private var lastPeerDiagnosticsAt = 0L
    /** Snapshot for the player warning; does not change torrent priorities or playback. */
    @Synchronized fun sourceHealth(bufferedSeconds: Int): String {
        if (closed.get()) return "This torrent session has ended."
        return runCatching {
            val peers = handle.status().numPeers()
            val piece = requestedPiece
            val ahead = if (piece >= 0) TorrentRules.adaptiveReadWindow(piece, lastFilePiece, pieceLength)
                .takeWhile { handle.havePiece(it) }.size else 0
            val advertised = if (piece >= 0) handle.pieceAvailability().getOrNull(piece) else null
            TorrentHealthRules.message(peers, advertised, ahead, bufferedSeconds)
        }.getOrDefault("This torrent is taking longer than expected. Try another source.")
    }


    @Synchronized private fun logPeerDiagnostics(now: Long, waitingPiece: Int) {
        if (now - lastPeerDiagnosticsAt < 5_000) return
        lastPeerDiagnosticsAt = now
        runCatching {
            val status = handle.status()
            val ahead = if (requestedPiece >= 0) {
                TorrentRules.adaptiveReadWindow(requestedPiece, lastFilePiece, pieceLength)
                    .takeWhile { handle.havePiece(it) }.size
            } else 0
            Log.i("CineSaysTorrent", "peer_status peers=${status.numPeers()} downloadKiBps=${status.downloadRate() / 1024} pieceBytes=$pieceLength readyAheadPieces=$ahead")

            // These are snapshots, not a peer identity trace. Availability is
            // the count of connected peers advertising the requested piece.
            // Absence from the partial queue means it is not currently listed
            // as an active partial piece, NOT proof that the swarm lacks it.
            val availability = handle.pieceAvailability()
            val advertised = availability.getOrNull(waitingPiece)
            val queue = handle.getDownloadQueue()
            val partial = queue.firstOrNull { it.pieceIndex() == waitingPiece }
            val state = if (handle.havePiece(waitingPiece)) "verified"
                else if (partial != null) "partial" else "not_in_partial_queue"
            val actualPriority = handle.piecePriority(waitingPiece)
            Log.i("CineSaysTorrent", "piece_diagnostic piece=$waitingPiece state=$state advertisedPeers=${advertised ?: -1} priority=${actualPriority.name} " +
                "finishedBlocks=${partial?.finished() ?: -1} writingBlocks=${partial?.writing() ?: -1} " +
                "requestedBlocks=${partial?.requested() ?: -1} totalBlocks=${partial?.blocksInPiece() ?: -1} " +
                "queueEntries=${queue.size} queuePieces=${queue.take(12).joinToString(",") { it.pieceIndex().toString() }}")
            val peers = handle.peerInfo()
            val unchoked = peers.count { it.flags() and com.frostwire.jlibtorrent.swig.peer_info.remote_choked.to_int() == 0 }
            val interested = peers.count { it.flags() and com.frostwire.jlibtorrent.swig.peer_info.interesting.to_int() != 0 }
            val snubbed = peers.count { it.flags() and com.frostwire.jlibtorrent.swig.peer_info.snubbed.to_int() != 0 }
            Log.i("CineSaysTorrent", "peer_eligibility connected=${peers.size} unchoked=$unchoked interested=$interested snubbed=$snubbed")
        }.onFailure { Log.w("CineSaysTorrent", "peer_diagnostics unavailable: ${it.javaClass.simpleName}") }
    }

    @Synchronized private fun releaseReader(readerId: Int) {
        applyDemands(readDemands.remove(readerId))
    }

    @Synchronized private fun prioritizeRead(piece: Int, readerId: Int, last: Int) {
        if (closed.get()) throw IOException("Torrent session closed")
        applyDemands(readDemands.update(readerId, piece, last))
        requestedPiece = piece
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadyAheadLog >= 5_000) {
            lastReadyAheadLog = now
            Log.i("CineSaysTorrent", "read_demand anchor=$piece activePieces=${selection.pieces.size}")
        }
    }

    /** Only live selections are eligible. Removing a deadline alone leaves priority 7 behind. */
    private fun applyDemands(deadlines: Map<Int, Int>) {
        if (closed.get()) return
        selection.apply(deadlines)
    }

    private fun waitForPiece(piece: Int, readerId: Int, last: Int, cancellation: TorrentReadCancellation?) {
        if (closed.get()) throw IOException("Torrent session closed")
        // Maintain look-ahead even when the requested piece is already available.
        if (cancellation?.isCancelled() == true) throw IOException("Torrent read cancelled")
        prioritizeRead(piece, readerId, last)
        if (handle.havePiece(piece)) return
        val startedAt = SystemClock.elapsedRealtime()
        val budget = TorrentPieceWaitBudget(startedAt)
        var nextProgressCheckAt = startedAt + 2_000
        var reportedExtension = false
        while (!closed.get()) {
            if (cancellation?.isCancelled() == true) throw IOException("Torrent read cancelled")
            val now = SystemClock.elapsedRealtime()
            if (handle.havePiece(piece)) {
                val waited = now - startedAt
                if (waited >= 500) Log.i("CineSaysTorrent", "piece_ready piece=$piece waitMs=$waited")
                return
            }
            if (now - startedAt >= 2_000) logPeerDiagnostics(now, piece)
            if (now >= nextProgressCheckAt) {
                // Only real downloaded blocks may extend a wait. The overall
                // torrent download rate may reflect unrelated pieces.
                runCatching {
                    val finished = handle.getDownloadQueue()
                        .firstOrNull { it.pieceIndex() == piece }?.finished() ?: 0
                    budget.observe(now, finished)
                }.onFailure {
                    Log.w("CineSaysTorrent", "piece_progress unavailable: ${it.javaClass.simpleName}")
                }
                nextProgressCheckAt = now + 2_000
            }
            if (!reportedExtension && budget.extended(now)) {
                reportedExtension = true
                Log.i("CineSaysTorrent", "piece_wait_extended piece=$piece waitedMs=${now - startedAt} maxWaitMs=60000")
            }
            if (budget.expired(now)) {
                Log.w("CineSaysTorrent", "piece_timeout piece=$piece waitMs=${now - startedAt} progressAware=true")
                throw IOException("Timed out waiting for required torrent piece")
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Torrent read cancelled", e)
            }
        }
        throw IOException("Torrent session closed")
    }

    private inner class TorrentDataSource : BaseDataSource(true) {
        private val readerId = nextReaderId.getAndIncrement()
        private var registered = false
        private var lastReadPiece = 0
        private var cancellation: TorrentReadCancellation? = null
        private var input: RandomAccessFile? = null
        private var requestUri: Uri? = null
        private var position = 0L
        private var remaining = 0L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            if (closed.get() || dataSpec.uri.toString() != this@TorrentPlayback.uri || dataSpec.position > fileSize) {
                throw IOException("Torrent media unavailable")
            }
            transferInitializing(dataSpec)
            position = dataSpec.position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) fileSize - position
                else minOf(dataSpec.length, fileSize - position)
            cancellation = dataSpec.customData as? TorrentReadCancellation
            lastReadPiece = ((fileOffset + (position + remaining - 1).coerceAtLeast(position)) / pieceLength).toInt()
            registered = true
            try {
                if (remaining > 0) {
                    waitForPiece(((fileOffset + position) / pieceLength).toInt(), readerId, lastReadPiece, cancellation)
                }
                input = RandomAccessFile(file, "r").also { it.seek(position) }
                requestUri = dataSpec.uri
                opened = true
                transferStarted(dataSpec)
                return remaining
            } catch (failure: Throwable) {
                releaseReader(readerId)
                registered = false
                throw failure
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val absolute = fileOffset + position
            waitForPiece((absolute / pieceLength).toInt(), readerId, lastReadPiece, cancellation)
            val count = minOf(length.toLong(), remaining, pieceLength - absolute % pieceLength).toInt()
            val bytes = input?.read(buffer, offset, count) ?: -1
            if (bytes <= 0) throw IOException("Torrent piece could not be read")
            remaining -= bytes
            position += bytes
            bytesTransferred(bytes)
            return bytes
        }

        override fun getUri(): Uri? = requestUri

        override fun close() {
            try {
                input?.close()
            } finally {
                input = null
                requestUri = null
                if (registered) {
                    registered = false
                    releaseReader(readerId)
                }
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }

    companion object {
        private val cleanup = Executors.newSingleThreadExecutor()

        private fun dispose(session: SessionManager, directory: File) {
            try {
                session.stop()
            } finally {
                directory.deleteRecursively()
            }
        }

        /** Run on an IO dispatcher, after checking that the source is still enabled. */
        internal suspend fun prepare(context: Context, torrent: DebridTorrent): TorrentPlayback {
            require(supportsDirectTorrent(torrent))
            val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "torrent-playback-").toFile()
            var manager: SessionManager? = null
            try {
                val session = SessionManager()
                manager = session
                val descriptor = if (torrent.hash == WEBTORRENT_HASH) {
                    val request = Request.Builder().url(WEBTORRENT_URL).build()
                    val metadataFile = File(directory, "source.torrent")
                    StreamRepository.http.newCall(request).execute().use { response ->
                        val body = response.body?.source()
                        if (!response.isSuccessful || body == null || body.request(1_048_577L)) {
                            throw TorrentPreparationException("Could not fetch the WebTorrent test metadata.")
                        }
                        metadataFile.writeBytes(body.readByteArray())
                    }
                    TorrentInfo(metadataFile).also {
                        if (it.infoHashV1()?.toHex() != WEBTORRENT_HASH) {
                            throw TorrentPreparationException("The WebTorrent metadata did not match the approved hash.")
                        }
                    }
                } else null
                currentCoroutineContext().ensureActive()
                session.start()
                if (descriptor != null) session.download(descriptor, directory, null, null, null, TorrentFlags.UPLOAD_MODE)
                else session.download(torrent.magnetUri(), directory, TorrentFlags.UPLOAD_MODE)
                val hash = Sha1Hash(torrent.hash)
                val expectedName = if (torrent.hash == TEST_HASH && torrent.filename == LEGACY_TEST_FILENAME)
                    TEST_FILENAME else torrent.filename
                repeat(300) {
                    currentCoroutineContext().ensureActive()
                    val handle = session.find(hash)
                    val info = handle?.torrentFile()
                    if (handle != null && info != null) {
                        val files = info.files()
                        val indexes = 0 until info.numFiles()
                        val index = TorrentRules.selectVideo(indexes.map { files.filePath(it) },
                            torrent.fileIndex, expectedName) ?: run {
                            android.util.Log.w("CineSaysTorrent", "Approved torrent files (first 20): " +
                                indexes.take(20).joinToString { files.filePath(it).take(160) })
                            throw TorrentPreparationException("Could not identify a unique video in the approved torrent. See CineSaysTorrent in Logcat.")
                        }
                        val path = File(directory, files.filePath(index)).canonicalFile
                        val size = files.fileSize(index)
                        if (!path.toPath().startsWith(directory.canonicalFile.toPath()) ||
                            size <= 0 || info.pieceLength() <= 0) {
                            throw TorrentPreparationException("The torrent video did not pass the file checks.")
                        }
                        // Validate every metadata path before enabling payload download.
                        if (indexes.any { !TorrentRules.safePath(directory, files.filePath(it)) }) {
                            throw TorrentPreparationException("The torrent contains an unsafe file path.")
                        }
                        if (!TorrentRules.hasSpace(size, directory.usableSpace, info.pieceLength())) {
                            throw TorrentPreparationException("Not enough free device storage for this video. Choose a smaller source or free some space.")
                        }
                        // File priorities control storage mapping; wait for that disk round-trip
                        // before overriding piece priorities, which file updates can reset.
                        val prioritiesReady = AtomicBoolean(false)
                        val priorityListener = object : com.frostwire.jlibtorrent.AlertListener {
                            override fun types() = intArrayOf(com.frostwire.jlibtorrent.alerts.AlertType.FILE_PRIO.swig())
                            override fun alert(alert: com.frostwire.jlibtorrent.alerts.Alert<*>) {
                                if (alert is com.frostwire.jlibtorrent.alerts.FilePrioAlert) prioritiesReady.set(true)
                            }
                        }
                        session.addListener(priorityListener)
                        try {
                            handle.prioritizeFiles(Array(info.numFiles()) { if (it == index) Priority.NORMAL else Priority.IGNORE })
                            val waitStarted = SystemClock.elapsedRealtime()
                            while (!prioritiesReady.get()) {
                                currentCoroutineContext().ensureActive()
                                if (SystemClock.elapsedRealtime() - waitStarted >= 10_000)
                                    throw TorrentPreparationException("The torrent storage could not be prepared. Try again or choose another source.")
                                delay(50)
                            }
                        } finally {
                            session.removeListener(priorityListener)
                        }
                        handle.prioritizePieces(Array(info.numPieces()) { Priority.IGNORE })
                        // A fixed sequential picker competes with Media3 seeks and
                        // our explicit, sliding piece deadlines. Let the deadline
                        // window control streaming while keeping the selected-file
                        // priorities unchanged.
                        handle.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                        handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
                        // This single playback session is driven by live read demand,
                        // not the automatic download/seed queue when all pieces are filtered.
                        handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
                        handle.resume()
                        Log.i("CineSaysTorrent", "scheduler_mode live_read_demands sequential=false")
                        // Readers schedule headers, indexes and resume positions on demand.
                        // Do not leave startup deadlines competing with a later seek.
                        return TorrentPlayback(session, directory, handle, path, size, files.fileOffset(index), info.pieceLength())
                    }
                    delay(200)
                }
                throw TorrentPreparationException(
                    if (descriptor == null) "CineSays could not obtain torrent metadata from peers within 60 seconds."
                    else "The torrent engine did not open the verified WebTorrent descriptor."
                )
            } catch (e: Throwable) {
                val session = manager
                if (session == null) directory.deleteRecursively()
                else cleanup.execute { dispose(session, directory) }
                throw e
            }
        }
    }
}
