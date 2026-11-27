package com.cineglass.tv

import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LocalTorrentHttpTest {
    private fun connect(server: LocalTorrentHttp, method: String = "GET", range: String = "bytes=4-7"): Socket {
        val uri = URI(server.url)
        return Socket(uri.host, uri.port).apply {
            soTimeout = 3000
            getOutputStream().write("$method ${uri.path} HTTP/1.1\r\nHost: localhost\r\nRange: $range\r\n\r\n".toByteArray())
            getOutputStream().flush()
        }
    }

    private class BytesSource : TorrentHttpSource {
        var position = 0L
        var remaining = 0L
        override fun open(position: Long, length: Long, cancellation: TorrentReadCancellation) {
            this.position = position; remaining = length
        }
        override fun read(buffer: ByteArray, length: Int): Int {
            val count = minOf(length.toLong(), remaining).toInt()
            repeat(count) { buffer[it] = ('a'.code + position++).toByte() }
            remaining -= count
            return if (count == 0) -1 else count
        }
        override fun close() {}
    }

    @Test(timeout = 5000) fun servesExactRangeAndHeadDoesNotStartTorrentReader() {
        val created = AtomicInteger()
        LocalTorrentHttp.start(20, { created.incrementAndGet(); BytesSource() }).use { server ->
            val response = connect(server).use { it.getInputStream().readBytes().toString(Charsets.US_ASCII) }
            assertTrue(response.startsWith("HTTP/1.1 206"))
            assertTrue(response.contains("Content-Range: bytes 4-7/20"))
            assertEquals("efgh", response.substringAfter("\r\n\r\n"))
            val head = connect(server, "HEAD").use { it.getInputStream().readBytes().toString(Charsets.US_ASCII) }
            assertEquals("", head.substringAfter("\r\n\r\n"))
            assertEquals(1, created.get())
        }
    }

    @Test(timeout = 7000) fun disconnectedMissingPieceReadersReleaseWorkersBeforeNextSeek() {
        val opened = CountDownLatch(3)
        val released = CountDownLatch(3)
        LocalTorrentHttp.start(20, {
            object : TorrentHttpSource {
                var delegate: BytesSource? = null
                override fun open(position: Long, length: Long, cancellation: TorrentReadCancellation) {
                    if (position == 4L) {
                        opened.countDown()
                        while (!cancellation.isCancelled()) Thread.sleep(5)
                        throw IOException("cancelled")
                    }
                    delegate = BytesSource().also { it.open(position, length, cancellation) }
                }
                override fun read(buffer: ByteArray, length: Int) = delegate!!.read(buffer, length)
                override fun close() { if (delegate == null) released.countDown() }
            }
        }).use { server ->
            val stale = List(3) { connect(server) }
            try {
                assertTrue(opened.await(2, TimeUnit.SECONDS))
                stale.forEach { it.close() }
                assertTrue("cancelled reads must release immediately", released.await(2, TimeUnit.SECONDS))
                val response = connect(server, range = "bytes=10-12").use { it.getInputStream().readBytes().toString(Charsets.US_ASCII) }
                assertEquals("klm", response.substringAfter("\r\n\r\n"))
            } finally { stale.forEach { it.close() } }
        }
    }

    @Test(timeout = 5000) fun missingFirstPieceReturns503AndInvalidRangeDoesNotOpenSource() {
        val created = AtomicInteger()
        LocalTorrentHttp.start(20, {
            created.incrementAndGet()
            object : TorrentHttpSource {
                override fun open(position: Long, length: Long, cancellation: TorrentReadCancellation) { throw IOException("unavailable") }
                override fun read(buffer: ByteArray, length: Int) = -1
                override fun close() {}
            }
        }).use { server ->
            assertTrue(connect(server).use { it.getInputStream().readBytes().toString(Charsets.US_ASCII) }.startsWith("HTTP/1.1 503"))
            assertTrue(connect(server, range = "bytes=20-").use { it.getInputStream().readBytes().toString(Charsets.US_ASCII) }.startsWith("HTTP/1.1 416"))
            assertEquals(1, created.get())
        }
    }

    @Test(timeout = 5000) fun closingServerCancelsBlockedReadersAndClosesClientSockets() {
        val opened = CountDownLatch(1)
        val released = CountDownLatch(1)
        val server = LocalTorrentHttp.start(20, {
            object : TorrentHttpSource {
                override fun open(position: Long, length: Long, cancellation: TorrentReadCancellation) {
                    opened.countDown()
                    while (!cancellation.isCancelled()) Thread.sleep(5)
                    throw IOException("closed")
                }
                override fun read(buffer: ByteArray, length: Int) = -1
                override fun close() { released.countDown() }
            }
        })
        connect(server).use { client ->
            try {
                assertTrue(opened.await(2, TimeUnit.SECONDS))
                server.close()
                assertTrue(released.await(2, TimeUnit.SECONDS))
                assertEquals(-1, client.getInputStream().read())
            } finally { server.close() }
        }
    }
}
