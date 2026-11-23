package com.cineglass.tv

import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.*
import com.frostwire.jlibtorrent.swig.settings_pack
import com.frostwire.jlibtorrent.swig.tcp_endpoint
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real native engine test: generated data only, two local peers, no trackers or DHT. */
class TorrentNativeSelectionTest {
    private fun waitUntil(label: String, ready: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!ready()) {
            if (System.nanoTime() >= until) throw AssertionError("Timed out: $label")
            Thread.sleep(20)
        }
    }

    private fun settings() = SettingsPack().listenInterfaces("127.0.0.1:0")
        .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false)
        .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
        .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)

    @Test(timeout = 90000) fun realPeerServesStartupFarResumeBackwardSeekAndTailWithFilteredPieces() {
        assumeTrue("Use -PtorrentNativeJar=<matching desktop runtime jar>",
            System.getProperty("cinesays.nativeTorrentTests") == "true")
        val root = Files.createTempDirectory("cinesays-owned-torrent-").toFile()
        val seedDir = File(root, "seed").apply { mkdirs() }
        val leechDir = File(root, "leech").apply { mkdirs() }
        val original = File(seedDir, "owned-test-video.mp4")
        // Synthetic bytes, not encoded video: this verifies native scheduling and disk reads.
        val data = ByteArray(32 * 1024 * 1024).also { java.util.Random(20261123).nextBytes(it) }
        original.writeBytes(data)
        val info = TorrentInfo.bdecode(TorrentBuilder().path(original).pieceSize(256 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode())
        val seed = SessionManager()
        val leech = SessionManager()
        try {
            seed.start(SessionParams(settings()).apply { setPosixDiskIO() })
            leech.start(SessionParams(settings()).apply { setPosixDiskIO() })
            seed.addListener(object : AlertListener {
                override fun types() = intArrayOf(AlertType.TORRENT_ERROR.swig(), AlertType.FILE_ERROR.swig(),
                    AlertType.TORRENT_CHECKED.swig(), AlertType.STATE_CHANGED.swig(), AlertType.LISTEN_FAILED.swig())
                override fun alert(alert: Alert<*>) { println("seed_alert ${alert.message()}") }
            })
            println("native_source file=${info.files().filePath(0)} hash=${info.infoHashV1()}")
            seed.download(info, seedDir, null, null, null, TorrentFlags.SEED_MODE)
            waitUntil("seed handle") { seed.find(info.infoHashV1()) != null }
            val seedHandle = seed.find(info.infoHashV1())!!
            seedHandle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            seedHandle.resume()
            waitUntil("seed ready") { seedHandle.status(true).isSeeding }
            waitUntil("seed listener") { seed.swig().listen_port() > 0 }
            println("seed_port ${seed.swig().listen_port()}")
            leech.addListener(object : AlertListener {
                override fun types() = intArrayOf(AlertType.TORRENT_ERROR.swig(), AlertType.FILE_ERROR.swig(),
                    AlertType.PEER_CONNECT.swig(), AlertType.PEER_DISCONNECTED.swig(), AlertType.PEER_ERROR.swig(),
                    AlertType.PEER_BLOCKED.swig(), AlertType.LISTEN_FAILED.swig())
                override fun alert(alert: Alert<*>) { println("leech_alert ${alert.message()}") }
            })
            leech.download(info, leechDir, null, null, null, TorrentFlags.UPLOAD_MODE)
            waitUntil("leecher metadata") { leech.find(info.infoHashV1())?.torrentFile() != null }
            val handle = leech.find(info.infoHashV1())!!
            val fileReady = CountDownLatch(1)
            val listener = object : AlertListener {
                override fun types() = intArrayOf(AlertType.FILE_PRIO.swig())
                override fun alert(alert: Alert<*>) { if (alert is FilePrioAlert) fileReady.countDown() }
            }
            leech.addListener(listener)
            try {
                handle.prioritizeFiles(arrayOf(Priority.NORMAL))
                assertTrue("file priorities complete", fileReady.await(10, TimeUnit.SECONDS))
            } finally { leech.removeListener(listener) }
            handle.prioritizePieces(Array(info.numPieces()) { Priority.IGNORE })
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
            handle.resume()
            handle.swig().connect_peer(tcp_endpoint(Address("127.0.0.1").swig(), seed.swig().listen_port()))
            val demands = TorrentReadDemands(info.pieceLength())
            val selection = TorrentPieceSelection(handle::havePiece,
                { piece, deadline -> handle.setPieceDeadline(piece, deadline) },
                { piece -> handle.resetPieceDeadline(piece); handle.piecePriority(piece, Priority.IGNORE) })
            var reader = 0
            // Equivalent byte regions for startup, far resume, backward seek and final index.
            for (piece in listOf(0, 56, 20, 52, 127)) {
                val started = System.nanoTime()
                if (reader > 0) selection.apply(demands.remove(reader))
                reader++
                selection.apply(demands.update(reader, piece, info.numPieces() - 1))
                try { waitUntil("piece $piece") { handle.havePiece(piece) } }
                catch (failure: AssertionError) {
                    println("leech_status peers=${handle.status(true).numPeers()} state=${handle.status(true).state()} error=${handle.status(true).errorCode().message()} priority=${handle.piecePriority(piece)} queue=${handle.getDownloadQueue().map { it.pieceIndex() }}")
                    throw failure
                }
                val actual = ByteArray(4096)
                RandomAccessFile(File(leechDir, original.name), "r").use {
                    it.seek(piece.toLong() * info.pieceLength()); it.readFully(actual)
                }
                val offset = piece * info.pieceLength()
                assertArrayEquals(data.copyOfRange(offset, offset + actual.size), actual)
                println("native_read piece=$piece waitMs=${(System.nanoTime() - started) / 1_000_000}")
            }
            selection.apply(demands.remove(reader))
            assertEquals(Priority.IGNORE, handle.piecePriority(56))
            assertEquals(Priority.IGNORE, handle.piecePriority(127))
            assertTrue(selection.pieces.isEmpty())
        } finally {
            leech.stop(); seed.stop(); root.deleteRecursively()
        }
    }
}
