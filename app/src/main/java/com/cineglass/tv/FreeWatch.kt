package com.cineglass.tv

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// ───────────────────────────── Legal free watching ─────────────────────────────
// 1) Public-domain / openly licensed films from the Internet Archive's "Feature Films" collection,
//    streamed in-app. 2) Official 24/7 live channels that broadcasters publish on YouTube, played with
//    YouTube's own embedded player. 3) Trailers in the same player. Nothing else is streamed in-app:
//    free-with-ads services (Tubi, Pluto TV, Plex) open in their own apps.

data class ArchiveDoc(val identifier: String = "", val title: String? = null, val year: String? = null,
                      val licenseurl: String? = null, val downloads: Int = 0)
data class ArchiveResponse(val docs: List<ArchiveDoc> = emptyList())
data class ArchiveSearch(val response: ArchiveResponse = ArchiveResponse())
data class ArchiveFile(val name: String = "", val format: String? = null, val size: String? = null)
/** `metadata` is read as raw JSON: some fields are a string on one item and a list on another. */
data class ArchiveMetadata(val files: List<ArchiveFile> = emptyList(), val metadata: JsonObject? = null)

interface ArchiveApi {
    @GET("advancedsearch.php") suspend fun search(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf("identifier", "title", "year", "licenseurl", "downloads"),
        @Query("sort[]") sort: String = "downloads desc",
        @Query("rows") rows: Int = 20,
        @Query("output") output: String = "json"
    ): ArchiveSearch
    @GET("metadata/{id}") suspend fun metadata(@Path("id") id: String): ArchiveMetadata
}

/** A film CineSays may play in-app. [licence] is shown to the viewer. */
data class FreeFilm(val id: String, val title: String, val year: String?, val licence: String) {
    val thumbnail get() = "https://archive.org/services/img/$id"
    val page get() = "https://archive.org/details/$id"
}

object FreeFilms {
    val api: ArchiveApi by lazy {
        Retrofit.Builder().baseUrl("https://archive.org/").addConverterFactory(GsonConverterFactory.create()).build().create(ArchiveApi::class.java)
    }
    // The Internet Archive's curated public-domain / openly licensed feature films.
    // Only items that still hold a playable MP4 (some catalogue entries have had their video removed).
    private const val SCOPE = "collection:(feature_films) AND mediatype:(movies) AND format:(h.264 OR MPEG4)"
    // The default classics grid skips exploitation titles; explicit title matches still use SCOPE.
    private const val CLASSICS = "$SCOPE AND NOT title:(sex) AND NOT subject:(sex OR exploitation OR adult)"

    private fun licence(url: String?): String = when {
        url == null -> "Public domain (Internet Archive Feature Films)"
        "publicdomain" in url -> "Public domain"
        "creativecommons" in url -> "Creative Commons licence"
        else -> "Openly licensed"
    }
    private fun clean(s: String) = s.lowercase().replace(Regex("\\(\\d{4}\\)"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun quote(s: String) = s.replace("\"", " ").replace(":", " ")

    /** Most-watched free classics, for the Free tab. */
    suspend fun classics(rows: Int = 40): List<FreeFilm> =
        api.search(CLASSICS, rows = rows).response.docs.filter { !it.title.isNullOrBlank() }
            .map { FreeFilm(it.identifier, it.title!!, it.year?.take(4), licence(it.licenseurl)) }

    /** Finds an exact title (+ year when known) match for a TMDB movie, or null. */
    suspend fun match(title: String, year: String?): FreeFilm? {
        val want = clean(title)
        val docs = api.search("title:(\"${quote(title)}\") AND $SCOPE", rows = 10).response.docs
        return docs.filter { d -> d.title != null && clean(d.title) == want }
            .filter { d -> year.isNullOrBlank() || d.year.isNullOrBlank() || kotlin.math.abs((d.year.take(4).toIntOrNull() ?: 0) - (year.toIntOrNull() ?: 0)) <= 1 }
            .maxByOrNull { it.downloads }
            ?.let { FreeFilm(it.identifier, it.title!!, it.year?.take(4), licence(it.licenseurl)) }
    }

    /** The video ID a channel is broadcasting live right now (from its /live page), or null. */
    suspend fun liveVideoId(channelId: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL("https://www.youtube.com/channel/$channelId/live").openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "en")
            c.connectTimeout = 8000; c.readTimeout = 8000
            val page = c.inputStream.bufferedReader().use { it.readText() }
            Regex("""<link rel="canonical" href="https://www\.youtube\.com/watch\?v=([\w-]{11})"""").find(page)?.groupValues?.get(1)
        }.getOrNull()
    }

    /** Direct MP4 address for an item: prefers the full-quality h.264 file over the 512 Kb preview. */
    suspend fun streamUrl(id: String): String? {
        val files = api.metadata(id).files.filter { it.name.endsWith(".mp4", true) }
        val best = files.firstOrNull { it.format?.contains("h.264", true) == true && !it.name.contains("512kb", true) }
            ?: files.firstOrNull { !it.name.contains("512kb", true) } ?: files.firstOrNull()
        return best?.let { "https://archive.org/download/$id/" + java.net.URLEncoder.encode(it.name, "UTF-8").replace("+", "%20") }
    }
}

/** Official 24/7 live streams that these broadcasters publish on their own YouTube channels (IDs verified). */
data class LiveChannel(val name: String, val about: String, val youtubeChannelId: String)
val LIVE_CHANNELS = listOf(
    LiveChannel("Al Jazeera English", "World news, live", "UCNye-wNBqNL5ZzHSJj3l8Bg"),
    LiveChannel("DW News", "German international news", "UCknLrEdhRCp1aegoMqRaCZg"),
    LiveChannel("France 24 English", "International news from Paris", "UCQfwfsi5VrQ8yKZ-UWmAEFg"),
    LiveChannel("Sky News", "UK and world news", "UCoMdktPbSTixAyNGwb-UYkQ"),
    LiveChannel("ABC News", "US news, live", "UCBi2mrWuNuyYy4gbM6fU18Q"),
    LiveChannel("Bloomberg Television", "Markets and business", "UCIALMKvObZNtJ6AmdCLP7Lg"),
    LiveChannel("euronews", "European news", "UCSrZ3UV4jOidv8ppoVuvW9Q"),
    LiveChannel("NASA", "Space, launches and live views", "UCLA_DiR1FfKNvjuUpBHmylQ")
)

/** What the in-app player is showing. */
sealed class Playable(open val title: String) {
    data class Trailer(override val title: String, val youtubeKey: String) : Playable(title)
    data class Live(override val title: String, val channelId: String, val videoId: String? = null) : Playable(title)
    data class Film(override val title: String, val film: FreeFilm, val url: String) : Playable(title)
}

// ───────────────────────────── Player ─────────────────────────────

/** Full-screen player: ExoPlayer for films, YouTube's embedded player for trailers and live channels. */
@Composable
fun PlayerScreen(item: Playable, onClose: () -> Unit) {
    // Header sits in its own bar so it never covers the player's own controls.
    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CsChip("← Close", false, compact = true) { onClose() }
            Text(item.title, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (item is Playable.Film) Text("${item.film.licence} · Internet Archive", color = Cs.textMuted, fontSize = 11.sp, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (item) {
                is Playable.Film -> FilmPlayer(item.url)
                is Playable.Trailer -> YouTubeEmbed("${item.youtubeKey}?autoplay=1&rel=0&playsinline=1")
                is Playable.Live -> YouTubeEmbed(item.videoId?.let { "$it?autoplay=1&playsinline=1" }
                    ?: "live_stream?channel=${item.channelId}&autoplay=1&playsinline=1")
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun FilmPlayer(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // PlayerView's own controls handle the remote (play/pause, seek) and touch.
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); keepScreenOn = true } },
        modifier = Modifier.fillMaxSize().focusRequester(focus))
}

/** YouTube's official IFrame player in a WebView. [path] is "VIDEO_ID?…" or "live_stream?channel=…". */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeEmbed(path: String) {
    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head>
        <body><iframe src="https://www.youtube.com/embed/$path" referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe></body></html>"""
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            keepScreenOn = true
            // YouTube refuses embeds with no referrer (error 152/153), so the page gets the app's own https origin.
            loadDataWithBaseURL("https://com.cineglass.tv", html, "text/html", "utf-8", null)
            web = this
        }
    }, modifier = Modifier.fillMaxSize())
}

// ───────────────────────────── Free tab ─────────────────────────────

@Composable
fun FreeScreen(
    state: ScreenState, context: Context,
    onPlayFilm: (FreeFilm) -> Unit, onPlayLive: (LiveChannel) -> Unit, onOpenTitle: (TitleItem) -> Unit
) {
    val compact = LocalCompact.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Free to watch", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Everything here is legally free: public-domain films and live channels play right in CineSays; free-with-ads titles open in their own apps.",
                color = Cs.textSoft, fontSize = 14.sp)
        }

        // Live TV
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CsSectionTitle("Live TV · free")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LIVE_CHANNELS.forEach { ch -> LiveCard(ch) { onPlayLive(ch) } }
            }
            Text("Official live streams that these broadcasters publish on YouTube.", color = Cs.textMuted, fontSize = 12.sp)
        }

        // Public-domain films (in-app)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CsSectionTitle("Classic films · play in CineSays")
            if (state.freeBusy && state.freeFilms.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
            if (!state.freeBusy && state.freeFilms.isEmpty()) Text("Couldn't reach the Internet Archive right now.", color = Cs.textMuted, fontSize = 13.sp)
            FilmGrid(state.freeFilms, if (compact) 150.dp else 200.dp, onPlayFilm)
            Text("Public-domain and openly licensed films from the Internet Archive.", color = Cs.textMuted, fontSize = 12.sp)
        }

        // Free with ads (open in their apps)
        if (state.freeTitles.isNotEmpty()) CsPosterRow("Free with ads on Tubi, Pluto TV & Plex", state.freeTitles, onClick = onOpenTitle)

        // Free TV apps
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CsSectionTitle("More free TV apps")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Provider(300, "Pluto TV (live channels)", null), Provider(73, "Tubi", null), Provider(538, "Plex (live TV)", null), Provider(235, "YouTube", null))
                    .forEach { p -> CsButton("Open ${p.name}") { openProviderApp(context, p, "free TV") } }
            }
        }
    }
}

@Composable
private fun LiveCard(ch: LiveChannel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(200.dp).graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
        .onFocusChanged { focused = it.isFocused }.border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape)
        .background(Cs.surface, shape).clip(shape).clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(Color(0xFFFF4D4D), RoundedCornerShape(8.dp)))
            Text("LIVE", color = Color(0xFFFF8A8A), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Text(ch.name, color = Cs.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(ch.about, color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FilmGrid(films: List<FreeFilm>, cardWidth: Dp, onPlay: (FreeFilm) -> Unit) {
    if (films.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 14.dp
        val columns = ((maxWidth + gap) / (cardWidth + gap)).toInt().coerceAtLeast(2)
        val w = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            films.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { f -> key(f.id) { FilmCard(f, w) { onPlay(f) } } } }
            }
        }
    }
}

@Composable
private fun FilmCard(f: FreeFilm, width: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(width).graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape).clip(shape).background(Cs.surfaceHigh).clickable(onClick = onClick)) {
            AsyncImage(f.thumbnail, f.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            CsBadge("▶ Free", Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Text(listOfNotNull(f.title, f.year).joinToString(" · "), color = if (focused) Cs.text else Cs.textSoft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
