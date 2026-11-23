package com.cineglass.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class AuthorizedTorrentHttpTest {
    private val movie = MediaRef(MediaType.MOVIE, 1, "Authorized test film")
    private val torrent = PlayableStream("test", "Test", "Torrent", null, StreamKind.TORRENT)
    private fun direct(url: String) = PlayableStream(
        "approved", "Approved gateway", "HTTP playback", url, StreamKind.HLS, playable = true)

    @After fun reset() = AuthorizedTorrentHttp.configure(null)

    @Test fun noGatewayFallsBack() = runBlocking {
        assertNull(AuthorizedTorrentHttp.resolve(torrent, movie))
    }

    @Test fun acceptsOnlyPlayableHttpsMedia() = runBlocking {
        AuthorizedTorrentHttp.configure(object : AuthorizedTorrentHttp.Gateway {
            override suspend fun resolve(source: PlayableStream, media: MediaRef) =
                direct("https://approved.example/movie/master.m3u8")
        })
        assertEquals(StreamKind.HLS, AuthorizedTorrentHttp.resolve(torrent, movie)?.kind)
        AuthorizedTorrentHttp.configure(object : AuthorizedTorrentHttp.Gateway {
            override suspend fun resolve(source: PlayableStream, media: MediaRef) =
                direct("http://unsecured.example/movie/master.m3u8")
        })
        assertNull(AuthorizedTorrentHttp.resolve(torrent, movie))
    }

    @Test fun failingGatewayKeepsFallbackAvailable() = runBlocking {
        AuthorizedTorrentHttp.configure(object : AuthorizedTorrentHttp.Gateway {
            override suspend fun resolve(source: PlayableStream, media: MediaRef): PlayableStream? =
                throw java.io.IOException("Unavailable")
        })
        assertNull(AuthorizedTorrentHttp.resolve(torrent, movie))
    }
    @Test fun gatewayOnlyAcceptsPrivateHttpsTailnet() {
        assertEquals("https://nas.example-tailnet.ts.net",
            TorrServerGateway.normalizedBase("https://nas.example-tailnet.ts.net/"))
        assertNull(TorrServerGateway.normalizedBase("http://nas.example-tailnet.ts.net"))
        assertNull(TorrServerGateway.normalizedBase("https://example.org"))
        assertNull(TorrServerGateway.normalizedBase("https://nas.example-tailnet.ts.net.evil.org"))
        assertNull(TorrServerGateway.normalizedBase("https://user:pass@nas.example-tailnet.ts.net"))
        assertNull(TorrServerGateway.normalizedBase("https://nas.example-tailnet.ts.net/api"))
    }

}
