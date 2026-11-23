package com.cineglass.tv

import java.io.File

/** Pure selection rules shared by torrent preparation and JVM regression tests. */
internal object TorrentRules {
    private val videoExtensions = setOf("avi", "mp4", "m4v", "mov", "mkv", "webm", "ogg", "ogv", "ts", "m2ts")
    private fun video(path: String) = path.substringAfterLast('.', "").lowercase() in videoExtensions
    private fun normalized(path: String) = path.replace('\\', '/').removePrefix("/")

    fun selectVideo(paths: List<String>, fileIndex: Int?, filename: String?): Int? {
        // Stremio fileIdx is a zero-based index into ALL torrent files, including non-video files.
        if (fileIndex != null) return fileIndex.takeIf { it in paths.indices && video(paths[it]) }
        val candidates = paths.indices.filter { video(paths[it]) }
        if (!filename.isNullOrBlank()) {
            val expected = normalized(filename)
            candidates.singleOrNull { normalized(paths[it]) == expected }?.let { return it }
            return candidates.singleOrNull {
                normalized(paths[it]).substringAfterLast('/').equals(expected.substringAfterLast('/'), ignoreCase = true)
            }
        }
        // Do not guess an episode from a multi-video season pack.
        return candidates.singleOrNull()
    }

    // A leading "/" is rejected explicitly: on Windows, File("/video.mp4").isAbsolute is false (no drive letter),
    // so isAbsolute alone doesn't catch Unix-style rooted paths on every platform.
    fun safePath(directory: File, relative: String): Boolean = runCatching {
        relative.isNotBlank() && !relative.startsWith("/") && !File(relative).isAbsolute && !relative.contains('\\') &&
            !relative.contains(':') && relative.split('/').none { it == ".." } &&
            File(directory, relative).canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())
    }.getOrDefault(false)

    fun readWindow(first: Int, last: Int): List<Int> =
        if (first < 0 || last < first) emptyList()
        else (first..minOf(first.toLong() + 3, last.toLong()).toInt()).toList()

    /**
     * Keep roughly 8 MiB of verified pieces scheduled ahead of the current read.
     * Torrent piece sizes vary substantially between sources; a fixed four-piece
     * window can represent less than one second of high-bitrate video.
     * Bound the window to 4..16 pieces so a slow peer swarm is not overwhelmed.
     */
    fun adaptiveReadWindow(first: Int, last: Int, pieceLength: Int): List<Int> {
        if (first < 0 || last < first || pieceLength <= 0) return emptyList()
        val targetBytes = 8L * 1024 * 1024
        val desired = ((targetBytes + pieceLength - 1) / pieceLength).coerceIn(4L, 16L)
        return (first.toLong()..minOf(first.toLong() + desired - 1, last.toLong()))
            .map { it.toInt() }
    }

    /**
     * Container parsers may probe the end of a large file for an MP4 index while
     * playback consumes its beginning. Keep the playback window anchored when
     * that independent tail probe occurs; the tail piece gets its own deadline.
     */
    fun isTailProbe(requestedPiece: Int, firstPiece: Int, lastPiece: Int, playbackPiece: Int): Boolean =
        lastPiece - firstPiece > 16 &&
            playbackPiece in firstPiece..(firstPiece.toLong() + 8).coerceAtMost(lastPiece.toLong()).toInt() &&
            requestedPiece >= lastPiece - 2

    /** Only ask for an extra startup piece when its time budget is bounded. */
    fun startupWarmupPieces(first: Int, last: Int): List<Int> =
        if (first < 0 || last < first) emptyList() else listOf(first, minOf(first.toLong() + 1, last.toLong()).toInt()).distinct()

    fun startupPieces(first: Int, last: Int): List<Int> =
        if (first < 0 || last < first) emptyList()
        else (readWindow(first, last) + listOf(maxOf(first, last - 1), last)).distinct()

    fun hasSpace(size: Long, free: Long, pieceLength: Int): Boolean {
        // Leave room for metadata, boundary pieces, and normal app operation. Avoid Long overflow.
        val reserve = 128L * 1024 * 1024 + 2L * pieceLength.coerceAtLeast(0)
        return size > 0 && pieceLength > 0 && free > reserve && size <= free - reserve
    }
}

/**
 * Tracks concurrent HTTP range readers without tying the priority window to the
 * most recent request. The oldest active reader owns forward playback scheduling;
 * when it closes, ownership transfers to the next active reader (including seeks).
 * Callers synchronize access to this class with their torrent-session lock.
 */
internal class TorrentPriorityOwners {
    private val readers = LinkedHashSet<Int>()
    var primary: Int? = null
        private set

    fun enter(readerId: Int) {
        if (readers.add(readerId) && primary == null) primary = readerId
    }

    /** True when the playback scheduling owner changed. */
    fun leave(readerId: Int): Boolean {
        if (!readers.remove(readerId)) return false
        if (primary != readerId) return false
        primary = readers.firstOrNull()
        return true
    }

    fun isPrimary(readerId: Int): Boolean = primary == readerId
}

internal fun supportsDirectTorrent(torrent: DebridTorrent?): Boolean {
    if (torrent == null || !Regex("[0-9a-f]{40}").matches(torrent.hash)) return false
    if (torrent.directPlayback) return true
    // Preserve the two existing opt-in test sources without changing their registry configuration.
    return when (torrent.hash) {
        "0e876ce2a1a504f849ca72a5e2bc07347b3bc957" -> torrent.filename in
            setOf("big_buck_bunny_720p_surround.avi", "big_buck_bunny_720p_surround.mp4")
        "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c" -> torrent.filename == "Big Buck Bunny.mp4"
        else -> false
    }
}
