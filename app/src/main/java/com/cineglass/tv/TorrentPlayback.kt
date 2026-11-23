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

    private var priorityWindow: Set<Int> = emptySet()
    private var requestedPiece = -1
    private val firstFilePiece = (fileOffset / pieceLength).toInt()
    private val lastFilePiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
    private var lastPeerDiagnosticsAt = 0L
    private val startupWarmupUsed = AtomicBoolean(false)

    @Synchronized private fun logPeerDiagnostics(now: Long) {
        if (now - lastPeerDiagnosticsAt < 5_000) return
        lastPeerDiagnosticsAt = now
        runCatching {
            val status = handle.status()
            Log.i("CineSaysTorrent", "peer_status peers=${status.numPeers()} downloadKiBps=${status.downloadRate() / 1024} pieceBytes=$pieceLength")
        }.onFailure { Log.w("CineSaysTorrent", "peer_status unavailable: ${it.javaClass.simpleName}") }
    }

    @Synchronized private fun prioritizeRead(piece: Int) {
        // MP4/MKV parsers may probe the final pieces while downloading the start.
        // Give the probe an urgent deadline but never replace the active playback
        // window with the tail probe's window.
        if (TorrentRules.isTailProbe(piece, firstFilePiece, lastFilePiece, requestedPiece)) {
            if (!handle.havePiece(piece)) handle.setPieceDeadline(piece, 0)
            Log.i("CineSaysTorrent", "tail_probe piece=$piece playbackPiece=$requestedPiece")
            return
        }
        if (requestedPiece == piece) return
        val next = TorrentRules.adaptiveReadWindow(piece, lastFilePiece, pieceLength).toSet()
        (priorityWindow - next).forEach { runCatching { handle.resetPieceDeadline(it) } }
        next.forEach { index ->
            if (!handle.havePiece(index)) handle.setPieceDeadline(index, (index - piece) * 125)
        }
        priorityWindow = next
        requestedPiece = piece
    }

    private fun warmupNextPiece(position: Long) {
        // Bounded warmup on the first forward read reduces the chance of
        // starting the decoder with one piece and immediately rebuffering.
        if (position != 0L || !startupWarmupUsed.compareAndSet(false, true)) return
        val next = TorrentRules.startupWarmupPieces(firstFilePiece, lastFilePiece).getOrNull(1) ?: return
        val startedAt = SystemClock.elapsedRealtime()
        while (!closed.get() && !handle.havePiece(next) &&
            SystemClock.elapsedRealtime() - startedAt < 1_200) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        Log.i("CineSaysTorrent", "startup_warmup nextReady=${handle.havePiece(next)} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    private fun waitForPiece(piece: Int) {
        if (closed.get()) throw IOException("Torrent session closed")
        // Maintain look-ahead even when the requested piece is already available.
        prioritizeRead(piece)
        if (handle.havePiece(piece)) return
        val startedAt = SystemClock.elapsedRealtime()
        val end = startedAt + 25_000
        while (!closed.get()) {
            val now = SystemClock.elapsedRealtime()
            if (handle.havePiece(piece)) {
                val waited = now - startedAt
                if (waited >= 500) Log.i("CineSaysTorrent", "piece_ready piece=$piece waitMs=$waited")
                return
            }
            if (now - startedAt >= 2_000) logPeerDiagnostics(now)
            if (now >= end) {
                Log.w("CineSaysTorrent", "piece_timeout piece=$piece waitMs=${now - startedAt}")
                throw IOException("Timed out waiting for torrent peers")
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
            if (remaining > 0) {
                waitForPiece(((fileOffset + position) / pieceLength).toInt())
                warmupNextPiece(position)
            }
            input = RandomAccessFile(file, "r").also { it.seek(position) }
            requestUri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val absolute = fileOffset + position
            waitForPiece((absolute / pieceLength).toInt())
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
                else session.download("magnet:?xt=urn:btih:${torrent.hash}", directory, TorrentFlags.UPLOAD_MODE)
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
                        handle.prioritizeFiles(Array(info.numFiles()) { if (it == index) Priority.NORMAL else Priority.IGNORE })
                        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                        handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
                        // Start headers and a possible end-of-file MP4 index concurrently.
                        val firstPiece = (files.fileOffset(index) / info.pieceLength()).toInt()
                        val lastPiece = ((files.fileOffset(index) + size - 1) / info.pieceLength()).toInt()
                        TorrentRules.startupPieces(firstPiece, lastPiece).forEachIndexed { order, piece ->
                            if (!handle.havePiece(piece)) handle.setPieceDeadline(piece, order * 250)
                        }
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
