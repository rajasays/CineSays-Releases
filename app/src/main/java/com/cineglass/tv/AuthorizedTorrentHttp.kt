package com.cineglass.tv

/**
 * Optional HTTP playback of a *user-authorized* torrent source.
 *
 * The public CineSays build does not ship a torrent-to-HTTP service or credentials.
 * Integrations must be registered explicitly by trusted application code after
 * verifying the provider's terms, account permissions, and rights to the media.
 *
 * Never fetch an arbitrary resolver URL supplied by a torrent add-on: that could
 * leak a user's network identity or signed playback credentials.
 */
internal object AuthorizedTorrentHttp {
    interface Gateway {
        /** Return null for cache miss, unsupported title or unavailable service. */
        suspend fun resolve(source: PlayableStream, media: MediaRef): PlayableStream?
    }

    @Volatile private var gateway: Gateway? = null

    fun configure(approvedGateway: Gateway?) {
        gateway = approvedGateway
    }

    suspend fun resolve(source: PlayableStream, media: MediaRef): PlayableStream? {
        val provider = gateway ?: return null
        val candidate = try {
            provider.resolve(source, media)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return null // Preserve local progressive playback fallback.
        } ?: return null
        val url = candidate.url ?: return null
        if (!StreamUrls.isHttps(url) || !candidate.playable ||
            candidate.kind !in setOf(StreamKind.HLS, StreamKind.DASH, StreamKind.PROGRESSIVE) ||
            candidate.debridTorrent != null) return null
        return candidate
    }
}
