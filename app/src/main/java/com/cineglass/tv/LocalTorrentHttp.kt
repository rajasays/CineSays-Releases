package com.cineglass.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal interface TorrentHttpSource : AutoCloseable {
    fun open(position: Long, length: Long, cancellation: TorrentReadCancellation)
    fun read(buffer: ByteArray, length: Int): Int
}

/**
 * Private, per-playback HTTP byte-range bridge for the existing verified piece-aware
 * libtorrent data source. Nothing is exposed on the LAN, and no external server is used.
 */
@OptIn(UnstableApi::class)
internal class LocalTorrentHttp private constructor(
    private val listener: ServerSocket,
    private val factory: () -> TorrentHttpSource,
    private val size: Long,
    private val log: (String) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    // No unbounded queue behind stalled ranges. Excess clients receive a closed
    // connection and can retry through Media3's bounded loader retry policy.
    private val workers = ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), { task ->
            Thread(task, "CineSays-Loopback-Worker").apply { isDaemon = true }
        })
    val url: String = "http://127.0.0.1:${listener.localPort}/video/$token"

    init {
        Thread({
            while (!closed.get()) {
                try {
                    val client = listener.accept()
                    clients.add(client)
                    try {
                        workers.execute {
                            try { serve(client) } finally { clients.remove(client) }
                        }
                    } catch (_: RejectedExecutionException) {
                        clients.remove(client)
                        client.close()
                    }
                } catch (_: SocketException) {
                    if (closed.get()) break
                } catch (_: Exception) {
                    if (closed.get()) break
                }
            }
        }, "CineSays-Loopback-Accept").apply { isDaemon = true }.start()
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            val requestStarted = (System.nanoTime() / 1_000_000)
            socket.soTimeout = 30_000
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val request = reader.readLine()?.take(2048) ?: return
                val parts = request.split(" ")
                if (parts.size != 3 || parts[1] != "/video/$token" || parts[0] !in listOf("GET", "HEAD")) {
                    reply(socket, 404, "Not Found"); return
                }
                var range: String? = null
                for (n in 0..48) {
                    val line = reader.readLine()?.take(8192) ?: return
                    if (line.isEmpty()) break
                    if (n == 48) { reply(socket, 400, "Bad Request"); return }
                    if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                }
                val bounds = LocalTorrentRange.parse(range, size)
                if (bounds == null) {
                    val out = socket.getOutputStream()
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    out.flush(); return
                }
                val (from, to) = bounds
                val requestKind = if (range == null) "full" else "range"
                val count = to - from + 1
                val status = if (range == null) "200 OK" else "206 Partial Content"
                val extra = if (range == null) "" else "Content-Range: bytes $from-$to/$size\r\n"
                val header = "HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n$extra" +
                    "Content-Length: $count\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                val source = if (parts[0] == "GET") factory() else null
                val cancellation = TorrentReadCancellation()
                val finished = AtomicBoolean(false)
                // Once headers are parsed, no request body is expected. Detect FIN
                // even while the worker is inside a missing-piece wait.
                socket.soTimeout = 1_000
                val disconnectWatcher = if (source != null) Thread({
                    try {
                        while (!finished.get() && !closed.get()) {
                            try {
                                if (reader.read() == -1) { cancellation.cancel(); break }
                                // Pipelining/request bodies are unsupported on this private endpoint.
                                cancellation.cancel(); break
                            } catch (_: SocketTimeoutException) { /* check lifecycle again */ }
                        }
                    } catch (_: Exception) {
                        cancellation.cancel()
                    }
                }, "CineSays-Loopback-Cancel").apply { isDaemon = true; start() } else null
                var headersSent = false
                var writtenBytes = 0L
                try {
                    // Do not announce a successful HTTP response until its first
                    // verified torrent piece is readable. On peer timeout, return 503
                    // instead of a misleading 200 with an abruptly truncated body.
                    source?.open(from, count, cancellation)
                    val out = socket.getOutputStream()
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    headersSent = true
                    val firstPieceMs = (System.nanoTime() / 1_000_000) - requestStarted
                    if (firstPieceMs >= 1_000) log("http_first_piece method=${parts[0]} kind=$requestKind waitMs=$firstPieceMs")
                    if (source != null) {
                        var left = count
                        val bytes = ByteArray(64 * 1024)
                        while (left > 0 && !closed.get()) {
                            val read = source.read(bytes, minOf(left, bytes.size.toLong()).toInt())
                            if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                            out.write(bytes, 0, read)
                            left -= read
                            writtenBytes += read
                        }
                    }
                    out.flush()
                    val elapsedMs = (System.nanoTime() / 1_000_000) - requestStarted
                    if (elapsedMs >= 3_000) log("http_transfer method=${parts[0]} kind=$requestKind bytes=$writtenBytes elapsedMs=$elapsedMs")
                } catch (e: Exception) {
                    // Seeking and metadata probing commonly cancel a range request.
                    // A socket closed by Media3 after headers is not a torrent failure.
                    val abortedByClient = cancellation.isCancelled() || (headersSent && e is SocketException)
                    val detail = "http_${if (abortedByClient) "client_closed" else "failure"} method=${parts[0]} kind=$requestKind headersSent=$headersSent bytes=$writtenBytes elapsedMs=${(System.nanoTime() / 1_000_000) - requestStarted} cause=${e.javaClass.simpleName}"
                    log(detail)
                    if (!headersSent && !closed.get()) runCatching {
                        reply(socket, 503, "Service Unavailable")
                    }
                } finally {
                    finished.set(true)
                    cancellation.cancel()
                    runCatching { source?.close() }
                    runCatching { socket.close() }
                    disconnectWatcher?.interrupt()
                }
            } catch (_: Exception) {
                // Client disconnect, seek interruption, or an unavailable torrent piece.
                // Media3 can retry an HTTP range read while the session remains alive.
            }
        }
    }

    private fun reply(socket: Socket, code: Int, reason: String) {
        val msg = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(msg.toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listener.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    companion object {
        internal fun start(size: Long, factory: () -> TorrentHttpSource,
            log: (String) -> Unit = {}): LocalTorrentHttp {
            require(size > 0)
            val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            return try { LocalTorrentHttp(socket, factory, size, log) }
            catch (failure: Throwable) { socket.close(); throw failure }
        }

        fun start(source: TorrentPlayback): LocalTorrentHttp {
            val dataSources = source.dataSourceFactory()
            val uri = Uri.parse(source.uri)
            return start(source.contentLength, {
                val dataSource = dataSources.createDataSource()
                object : TorrentHttpSource {
                    override fun open(position: Long, length: Long, cancellation: TorrentReadCancellation) {
                        dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position)
                            .setLength(length).setCustomData(cancellation).build())
                    }
                    override fun read(buffer: ByteArray, length: Int) = dataSource.read(buffer, 0, length)
                    override fun close() = dataSource.close()
                }
            }, { message -> Log.i("CineSaysTorrent", message) })
        }
    }
}

/** Returns inclusive bounds for RFC 7233 single byte ranges. Multiple ranges are rejected. */
internal object LocalTorrentRange {
    fun parse(header: String?, size: Long): Pair<Long, Long>? {
        if (size <= 0) return null
        if (header == null) return 0L to (size - 1)
        if (!header.startsWith("bytes=", ignoreCase = true) || ',' in header) return null
        val values = header.substringAfter('=').split('-', limit = 2)
        if (values.size != 2) return null
        val first = values[0]
        val last = values[1]
        if (first.isEmpty()) {
            val tail = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (size - tail).coerceAtLeast(0) to (size - 1)
        }
        val start = first.toLongOrNull()?.takeIf { it >= 0 && it < size } ?: return null
        val end = if (last.isBlank()) size - 1 else last.toLongOrNull()?.takeIf { it >= start }?.coerceAtMost(size - 1) ?: return null
        return start to end
    }
}
