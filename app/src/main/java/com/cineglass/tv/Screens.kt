package com.cineglass.tv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

val DISCOVER_GENRES = listOf(
    "Action" to 28, "Comedy" to 35, "Sci-Fi" to 878, "Drama" to 18, "Horror" to 27, "Thriller" to 53,
    "Crime" to 80, "Romance" to 10749, "Animation" to 16, "Documentary" to 99, "Family" to 10751,
    "Fantasy" to 14, "Mystery" to 9648, "Adventure" to 12
)

// ───────────────────────────── Home ─────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: ScreenState, upNext: List<UpNextEntry>, firstFocus: FocusRequester,
    openDetails: (TitleItem) -> Unit, toggleWatchlist: (TitleItem) -> Unit,
    openTracker: () -> Unit, openServices: () -> Unit, markWatched: (UpNextEntry) -> Unit,
    openShow: (UpNextEntry) -> Unit = { openTracker() },
    continueWatching: List<PlaybackRecord> = emptyList(), onResume: (PlaybackRecord) -> Unit = {}, onResumeDetails: (PlaybackRecord) -> Unit = {}, onRemoveContinue: (PlaybackRecord) -> Unit = {},
    /** Watch Now for an Up next episode; null when the show isn't matched to TMDB. */
    watchUpNext: (UpNextEntry) -> (() -> Unit)? = { null },
    /** Watch Now for a banner title (the main button in the Cinematic theme). */
    watchNow: ((TitleItem) -> Unit)? = null
) {
    val compact = LocalCompact.current
    val heroes = remember(state.sections) {
        state.sections.filter { it.title.startsWith("Trending") }.flatMap { it.items.take(3) }
            .filter { it.backdrop != null }.take(5)
            .ifEmpty { state.sections.flatMap { it.items }.filter { it.backdrop != null }.take(1) }
    }
    // Rotating banner: swipe it on phones, use ‹ › with the remote, and it still advances on its own every 9 s.
    // Auto-advance pauses while you're swiping or while the banner's buttons have focus.
    val pager = androidx.compose.foundation.pager.rememberPagerState { heroes.size.coerceAtLeast(1) }
    var heroFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(heroes, pager.settledPage, heroFocused) {
        if (heroes.size < 2 || heroFocused) return@LaunchedEffect
        delay(9000)
        if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.settledPage + 1) % heroes.size)
    }
    fun step(by: Int) { if (heroes.size > 1) scope.launch { pager.animateScrollToPage((pager.currentPage + by + heroes.size) % heroes.size) } }

    // Coming back up from the rows below, show the whole banner, not just the strip around the focused button.
    val heroInView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val theme = Cs.theme
    val cinematic = theme == CsTheme.CINEMATIC
    // Cinematic: edge to edge across the screen's side padding, taller, no frame.
    val bleed = if (!cinematic) 0.dp else if (compact) 16.dp else if (theme == CsTheme.FOCUS) 36.dp else 48.dp
    val heroShape = RoundedCornerShape(if (cinematic) 0.dp else 22.dp)
    val heroHeight = when { cinematic && compact -> 380.dp; cinematic -> 470.dp; compact -> 300.dp; else -> 340.dp }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.fillMaxWidth()
            .layout { m, c ->
                val extra = bleed.roundToPx() * 2
                val placeable = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
                layout(c.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
            }
            .height(heroHeight).bringIntoViewRequester(heroInView).clip(heroShape)
            .then(if (heroes.isEmpty()) Modifier.background(Cs.surface) else Modifier)
            .then(if (cinematic) Modifier else Modifier.border(1.dp, Cs.line, heroShape))
            .onFocusChanged { val gained = it.hasFocus && !heroFocused; heroFocused = it.hasFocus
                // Same frame as the focused button's own scroll, so the two merge into one smooth move (a delay made two).
                if (gained) scope.launch { heroInView.bringIntoView() } }) {
            if (heroes.isEmpty()) {
                Text(if (state.busy) "Loading CineSays…" else "Discover your next favourite", Modifier.align(Alignment.CenterStart).padding(if (compact) 20.dp else 40.dp), color = Cs.text, fontSize = 26.sp)
            } else {
                // Phones: each page carries its own text and buttons and is swiped by touch.
                // TV: only the pictures slide; one fixed text-and-buttons layer follows the current page. Buttons
                // inside the pager let D-pad focus wander into the neighbouring page, which dragged the pager along.
                androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), key = { heroes[it].ref }, userScrollEnabled = compact) { page ->
                    val hero = heroes[page]
                    Box(Modifier.fillMaxSize()) {
                        CsArt(hero.hero(), hero.label, Modifier.fillMaxSize(), placeholder = Cs.surface)
                        // TV: the shade fades out at 72% of the width, so it's only drawn over that part (less GPU work per frame).
                        if (compact) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .45f to Cs.bg.copy(alpha = .7f), 1f to Cs.bg)))
                        else Box(Modifier.fillMaxHeight().fillMaxWidth(.72f).background(Brush.horizontalGradient(0f to Cs.bg, .53f to Cs.bg.copy(alpha = .85f), 1f to Color.Transparent)))
                        if (cinematic && !compact) Box(Modifier.fillMaxWidth().fillMaxHeight(.35f).align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Cs.bg)))
                        if (compact) HeroText(hero, page, true, hero.ref in state.watchlist, Modifier.align(Alignment.BottomStart),
                            if (page == pager.currentPage) Modifier.focusRequester(firstFocus) else Modifier, { openDetails(hero) }, { toggleWatchlist(hero) },
                            watchNow?.let { w -> { w(hero) } }, bleed)
                    }
                }
                if (!compact) {
                    val page = pager.currentPage.coerceIn(0, heroes.lastIndex)
                    val hero = heroes[page]
                    HeroText(hero, page, false, hero.ref in state.watchlist, Modifier.align(Alignment.CenterStart),
                        Modifier.focusRequester(firstFocus), { openDetails(hero) }, { toggleWatchlist(hero) }, watchNow?.let { w -> { w(hero) } }, bleed)
                }
            }
            // Phones: dots under the text (swipe to move). TV: ‹ dots › at the bottom-right, clear of the buttons.
            if (heroes.size > 1) Row(Modifier.align(if (compact) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(start = 20.dp + bleed, end = (if (compact) 20.dp else 28.dp) + bleed, bottom = if (compact) 12.dp else 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (!compact) CsChip("‹", false, compact = true) { step(-1) }
                heroes.indices.forEach { i ->
                    val current = i == pager.currentPage
                    Box(Modifier.size(if (current) 26.dp else 6.dp, 6.dp).background(if (current) Cs.accent else Cs.lineStrong, RoundedCornerShape(6.dp))
                        // Tappable on phones; on TV the dots stay out of D-pad focus (they have no focus look), ‹ › move instead.
                        .then(if (compact) Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } } else Modifier))
                }
                if (!compact) CsChip("›", false, compact = true) { step(1) }
            }
        }

        if (state.myServices.isEmpty() && !state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tell CineSays which services you pay for to see what's new on them.", color = Cs.textSoft, fontSize = 15.sp)
            CsButton("Set up My Services") { openServices() }
        }

        // Titles started in CineSays' own player, resumable where they stopped.
        ContinueWatchingRow(continueWatching, onResume, onResumeDetails, onRemoveContinue)

        if (upNext.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CsSectionTitle("Up next from your Tracker", Modifier.weight(1f))
                CsChip("Open Tracker", false) { openTracker() }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(upNext.take(20), key = { it.show }) { e -> UpNextCard(e, onWatched = { markWatched(e) }, onOpen = { openShow(e) }, onWatchNow = watchUpNext(e)) }
            }
        }

        // Personal picks first, then the catalog rows.
        if (state.recommended.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CsSectionTitle("Recommended for you")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp, end = 48.dp)) {
                items(state.recommended, key = { it.ref }) { item ->
                    Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CsPoster(item) { openDetails(item) }
                        state.recommendedWhy[item.ref]?.let { Text(it, color = Cs.textMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        } else if (state.likes.isEmpty() && state.watchlist.isEmpty() && upNext.isEmpty() && !state.busy)
            Text("Tip: press ♥ Like on titles you enjoy, and CineSays will recommend more like them here.", color = Cs.textMuted, fontSize = 13.sp)

        state.sections.filter { it.kind != "tv" || !it.title.contains(" · ") }.forEach { section ->
            CsPosterRow(section.title, section.items, onClick = openDetails)
        }
    }
}

/** Selecting the card opens the series in Tracker; the ✓ button below it marks the episode watched. */
@Composable
private fun UpNextCard(e: UpNextEntry, onWatched: () -> Unit, onOpen: () -> Unit, onWatchNow: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    var within by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    // Focus theme on TV: cards stay compact and open wider, with their buttons, while the remote is on them.
    val expanding = Cs.theme == CsTheme.FOCUS && !LocalCompact.current
    val width by androidx.compose.animation.core.animateDpAsState(if (expanding && !within) 220.dp else if (expanding) 340.dp else 268.dp, label = "upNext")
    Column(Modifier.width(width).onFocusChanged { within = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape)
            .clip(shape).background(Cs.surface).clickable(onClick = onOpen)) {
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                CsArt(e.episode.still?.let { "https://image.tmdb.org/t/p/w300$it" } ?: e.poster?.let { "https://image.tmdb.org/t/p/w300$it" },
                    null, Modifier.fillMaxSize())
                CsBadge(e.badge, Modifier.align(Alignment.TopStart).padding(10.dp), dark = true)
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.show, color = Cs.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("S${e.episode.season} · E${e.episode.number} · ${e.episode.name}", color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (focused) "Select: open in Tracker" else e.detail, color = if (focused) Cs.accent else Cs.textMuted, fontSize = 11.sp)
            }
        }
        if (!expanding || within) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onWatchNow?.let { CsChip("▶ Watch Now", false, compact = true) { it() } }
            CsChip("✓ Watched S${e.episode.season}E${e.episode.number}", false, compact = true) { onWatched() }
        }
    }
}

// ───────────────────────────── Details ─────────────────────────────

@Composable
fun DetailsScreen(
    item: TitleItem, state: ScreenState, myProviders: Set<Int>, inTracker: Boolean, backFocus: FocusRequester, context: Context,
    onBack: () -> Unit, onToggleWatchlist: () -> Unit, onTrack: () -> Unit, onRecommend: () -> Unit, onOpenSimilar: (TitleItem) -> Unit,
    liked: Boolean = false, onLike: () -> Unit = {}, movieWatched: Boolean = false, onMovieWatched: (Boolean) -> Unit = {},
    onPerson: (CastMember) -> Unit = {}, onPlayTrailer: (String) -> Unit = {}, onWatchFree: (FreeFilm) -> Unit = {},
    onWatchNow: () -> Unit = {}
) {
    val compact = LocalCompact.current
    val regions = state.regions.sorted()
    val best = remember(state.providersByRegion, myProviders) {
        val offers = regions.flatMap { r -> state.providersByRegion[r]?.noExtraCost.orEmpty().map { it to r } }
        offers.firstOrNull { it.first.id in myProviders } ?: offers.firstOrNull()
    }
    val poster: @Composable () -> Unit = {
        Box(Modifier.width(if (compact) 140.dp else 220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(Cs.surfaceHigh).border(1.dp, Cs.lineStrong, RoundedCornerShape(16.dp))) {
            AsyncImage(item.image("w500"), item.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
    val wherePanel: @Composable (Modifier) -> Unit = { m ->
        CsPanel(m) {
            Text("Where to watch", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (state.detailBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else WhereToWatchGrid(regions, state.providersByRegion, myProviders) { p -> WatchNow.watch(context, p, item, regions) }
            Text(if (myProviders.isEmpty()) "Tip: set My Services in Settings to see your own services first."
                else if (WatchNow.enabled) "✓ = included with your services. Buttons open the title in its app."
                else "✓ = included with your services. Buttons open the installed app.", color = Cs.textMuted, fontSize = 12.sp)
            // Required by Watchmode's free plan wherever its data is used.
            if (WatchNow.enabled) Text("Title links by Watchmode.com", color = Cs.textMuted, fontSize = 11.sp,
                modifier = Modifier.clickable { openUrl(context, "https://www.watchmode.com/") })
        }
    }
    val castRow: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.cast.isNotEmpty()) {
                Text("Cast", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.cast.forEach { c -> CastCard(c) { onPerson(c) } }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Cs.bg).safeDrawingPadding()) {
        AsyncImage(item.hero(), null, Modifier.fillMaxWidth().height(430.dp), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxWidth().height(430.dp).background(Brush.verticalGradient(0f to Cs.bg.copy(alpha = .2f), 1f to Cs.bg)))
        Box(Modifier.fillMaxWidth().height(430.dp).background(Brush.horizontalGradient(0f to Cs.bg, .65f to Color.Transparent)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 16.dp else 56.dp, vertical = if (compact) 16.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 26.dp)) {
            if (compact) CsChip("← Back", false, Modifier.focusRequester(backFocus)) { onBack() }
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 40.dp)) {
                poster()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!compact) CsChip("← Back", false, Modifier.focusRequester(backFocus)) { onBack() }
                    Text(item.label, color = Cs.text, fontSize = if (compact) 24.sp else 42.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (compact) 28.sp else 44.sp)
                    item.tagline?.let { Text("“$it”", color = Cs.textSoft, fontSize = 16.sp, fontStyle = FontStyle.Italic) }
                    val meta = listOfNotNull(
                        item.year.ifBlank { null }, if (item.kind == "tv") "TV Series" else "Movie",
                        item.seasons?.takeIf { item.kind == "tv" && it > 0 }?.let { "$it season${if (it > 1) "s" else ""}" },
                        item.runtime?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                        item.genres?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ") { it.name })
                    Text(meta.joinToString("    "), color = Cs.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RatingTile("TMDB", if (item.rating > 0) "%.1f".format(item.rating) else "—")
                        RatingTile("IMDb", state.imdbRating ?: "—")
                        RatingTile("Rotten Tomatoes", state.rottenTomatoes ?: "—")
                    }
                    Text(item.overview.orEmpty(), color = Cs.textSoft, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 780.dp))
                    if (!compact) DetailActions(item, state, best, inTracker, liked, movieWatched, context, onToggleWatchlist, onTrack, onRecommend, onLike, onMovieWatched, onPlayTrailer, onWatchFree, onWatchNow)
                }
            }
            if (compact) DetailActions(item, state, best, inTracker, liked, movieWatched, context, onToggleWatchlist, onTrack, onRecommend, onLike, onMovieWatched, onPlayTrailer, onWatchFree, onWatchNow)
            if (compact) { wherePanel(Modifier.fillMaxWidth()); castRow(Modifier.fillMaxWidth()) }
            else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { wherePanel(Modifier.weight(1.25f)); castRow(Modifier.weight(1f)) }
            if (state.similar.isNotEmpty()) CsPosterRow("More like this", state.similar, onClick = onOpenSimilar)
            Text("Availability from TMDB / JustWatch. Provider buttons launch installed apps; exact-title links need verified content IDs.", color = Cs.textMuted, fontSize = 12.sp)
        }
    }
}

/** A cast photo + name; selecting it opens the actor's page. */
@Composable
private fun CastCard(c: CastMember, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(84.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(c.image(), c.name, Modifier.size(64.dp).border(if (focused) 3.dp else 0.dp, if (focused) Cs.focusRing else Color.Transparent, CircleShape)
            .clip(CircleShape).background(Cs.surfaceHigh), contentScale = ContentScale.Crop)
        Text(c.name, color = if (focused) Cs.text else Cs.textSoft, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        c.character?.takeIf { it.isNotBlank() }?.let { Text(it, color = Cs.textMuted, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// ───────────────────────────── Actor page ─────────────────────────────

/** An actor's page: photo, details, short bio and all their movies and shows (All / Movies / TV). */
@Composable
fun PersonScreen(person: Person, busy: Boolean, firstFocus: FocusRequester, onBack: () -> Unit, onOpen: (TitleItem) -> Unit) {
    val compact = LocalCompact.current
    var filter by remember(person.id) { mutableStateOf("all") }
    var bioOpen by remember(person.id) { mutableStateOf(false) }
    val all = person.credits?.cast.orEmpty()
    val shown = all.filter { filter == "all" || it.kind == filter }
    Column(Modifier.fillMaxSize().background(Cs.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = if (compact) 16.dp else 56.dp, vertical = if (compact) 16.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 32.dp)) {
            AsyncImage(person.image(), person.name, Modifier.width(if (compact) 110.dp else 180.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))
                .background(Cs.surfaceHigh).border(1.dp, Cs.lineStrong, RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CsChip("← Back", false, Modifier.focusRequester(firstFocus)) { onBack() }
                Text(person.name, color = Cs.text, fontSize = if (compact) 26.sp else 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (compact) 30.sp else 44.sp)
                Text(listOfNotNull(person.knownFor, person.birthday?.let { b ->
                        runCatching { val d = java.time.LocalDate.parse(b); "Born ${d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))}" }.getOrNull() },
                        person.birthplace).joinToString("  ·  "), color = Cs.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                person.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                    Text(bio, color = Cs.textSoft, fontSize = 15.sp, lineHeight = 22.sp, maxLines = if (bioOpen) 40 else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 820.dp))
                    if (bio.length > 280) CsChip(if (bioOpen) "Less" else "Read more", false, compact = true) { bioOpen = !bioOpen }
                }
            }
        }
        if (compact) CsSectionTitle("Movies & shows")
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) CsSectionTitle("Movies & shows", Modifier.padding(end = 8.dp))
            listOf("All · ${all.size}" to "all", "Movies · ${all.count { it.kind == "movie" }}" to "movie", "TV · ${all.count { it.kind == "tv" }}" to "tv")
                .forEach { (label, value) -> CsChip(label, filter == value, compact = true) { filter = value } }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
        else if (shown.isEmpty()) Text("No titles to show.", color = Cs.textMuted)
        CsPosterGrid(shown, posterWidth = if (compact) 104.dp else 128.dp, onClick = onOpen)
    }
}

@Composable
private fun DetailActions(
    item: TitleItem, state: ScreenState, best: Pair<Provider, String>?, inTracker: Boolean, liked: Boolean, movieWatched: Boolean, context: Context,
    onToggleWatchlist: () -> Unit, onTrack: () -> Unit, onRecommend: () -> Unit, onLike: () -> Unit, onMovieWatched: (Boolean) -> Unit,
    onPlayTrailer: (String) -> Unit, onWatchFree: (FreeFilm) -> Unit, onWatchNow: () -> Unit
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Watch Now: every way to watch (in CineSays, a streaming service, or Stremio); series pick an episode there.
        CsButton("▶ Watch Now", primary = true) { onWatchNow() }
        // Skipped when the main button already opens Prime Video (you subscribe): same app, nothing to add.
        val primeFree = state.primeFreeRegions.isNotEmpty() && best?.first?.id !in setOf(9, 119, 2100)
        if (best != null && !(primeFree && best.first.id == PRIME_FREE_ID)) CsButton("Watch on ${best.first.name}") { WatchNow.watch(context, best.first, item, listOf(best.second) + state.regions) }
        // Free with ads on Prime Video (the free movies IMDb lists). Marked with its regions when not free in all of yours.
        if (primeFree) {
            val where = if (state.primeFreeRegions.size < state.regions.size) " (${state.primeFreeRegions.joinToString(", ")})" else ""
            CsButton("Free on Prime Video$where") {
                WatchNow.watch(context, Provider(PRIME_FREE_ID, "Prime Video", null), item, state.primeFreeRegions)
            }
        }
        // Free, legal copy (public domain / openly licensed): plays right here.
        state.freeMatch?.let { f -> CsButton("Watch free in CineSays") { onWatchFree(f) } }
        // Trailer plays in-app (YouTube's embedded player).
        state.trailer?.let { key -> CsButton("▶ Trailer") { onPlayTrailer(key) } }
        CsButton(if (liked) "♥ Liked" else "♡ Like") { onLike() }
        CsButton(if (item.ref in state.watchlist) "✓ Saved" else "+ Watchlist") { onToggleWatchlist() }
        if (item.kind == "movie" && !state.detailBusy) CsButton(if (movieWatched) "✓ Watched" else "Mark watched") { onMovieWatched(!movieWatched) }
        if (item.kind == "tv" && !state.detailBusy) CsButton(if (inTracker) "✓ Tracking" else "+ Track episodes") { if (!inTracker) onTrack() }
        CsButton("Recommend") { onRecommend() }
        state.imdbId?.let { id -> CsButton("IMDb ↗") { openUrl(context, "https://www.imdb.com/title/$id/") } }
    }
}

@Composable
private fun RatingTile(source: String, value: String) {
    Column(Modifier.background(Cs.surface, RoundedCornerShape(12.dp)).border(1.dp, Cs.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(source, color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, softWrap = false)
        Text(value, color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Regions × offer type, one compact grid instead of a long per-region list. */
@Composable
private fun WhereToWatchGrid(regions: List<String>, offers: Map<String, ProviderRegion>, mine: Set<Int>, open: (Provider) -> Unit) {
    val columns = listOf<Pair<String, (ProviderRegion) -> List<Provider>>>(
        "Stream" to { it.flatrate }, "Free" to { it.freeWithAds }, "Rent" to { it.rent }, "Buy" to { it.buy })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(44.dp))
            columns.forEach { (label, _) -> Text(label, Modifier.weight(1f), color = Cs.textMuted, fontSize = 13.sp) }
        }
        regions.forEach { region ->
            val r = offers[region]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(region, Modifier.width(44.dp).padding(top = 8.dp), color = Cs.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                columns.forEach { (_, pick) ->
                    val list = r?.let(pick).orEmpty().distinctBy { it.id }.sortedBy { if (it.id in mine) 0 else 1 }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (list.isEmpty()) Text("—", Modifier.padding(top = 8.dp), color = Cs.textMuted, fontSize = 13.sp)
                        list.take(3).forEach { p -> OfferCell(if (p.id in mine) "✓ ${p.name}" else p.name, p.id in mine) { open(p) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferCell(text: String, highlighted: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Text(text, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 1.dp, if (focused) Cs.focusRing else if (highlighted) Cs.accent else Color.Transparent, shape)
        .background(if (highlighted) Cs.buttonFocus else Cs.surfaceHigh, shape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        color = Cs.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ───────────────────────────── Discover ─────────────────────────────

@Composable
fun DiscoverScreen(
    state: ScreenState, kind: String, genre: Int?, provider: String?, sort: String,
    onKind: (String) -> Unit, onGenre: (Int?) -> Unit, onProvider: (String?) -> Unit, onSort: (String) -> Unit, onSelect: (TitleItem) -> Unit
) {
    val services = buildList {
        add("Any" to "")
        if (state.myServices.isNotEmpty()) add("★ My services" to MY_SERVICES_PROVIDER)
        add("Free (any)" to FREE_PROVIDER)
        addAll(listOf("Netflix" to "8", "Prime" to "9|119", "Max" to "1899", "Disney+" to "337", "Apple TV+" to "350",
            "Zee5" to "232", "Tubi (free)" to "73", "Prime Video (free)" to "$PRIME_FREE_ID","Pluto TV (free)" to "300", "Plex (free)" to "538"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Discover", Modifier.weight(1f), color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            if (state.genreBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cs.accent)
            else Text("${state.genreTitles.size} titles · ${state.regions.sorted().joinToString(" · ")}", color = Cs.textMuted, fontSize = 13.sp)
        }
        CsPanel(Modifier.fillMaxWidth()) {
            CsFilterRow("Type", listOf("Movies" to "movie", "TV" to "tv", "Both" to "both"), kind) { onKind(it) }
            CsFilterRow("Service", services, provider ?: "") { onProvider(it.ifBlank { null }) }
            CsFilterRow("Genre", listOf("All" to "") + DISCOVER_GENRES.map { it.first to it.second.toString() }, genre?.toString() ?: "") { onGenre(it.toIntOrNull()) }
            CsFilterRow("Sort", listOf("Popular" to "popular", "Top rated" to "top", "Newest" to "new"), sort) { onSort(it) }
        }
        val active = listOfNotNull(
            when (kind) { "movie" -> "Movies"; "tv" -> "TV"; else -> null },
            services.firstOrNull { it.second == provider }?.first?.takeIf { provider != null },
            DISCOVER_GENRES.firstOrNull { it.second == genre }?.first)
        if (active.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Active:", color = Cs.textSoft, fontSize = 14.sp)
            CsChip(active.joinToString(" · ") + "   ✕ Clear", false) { onKind("both"); onProvider(null); onGenre(null) }
        }
        if (!state.genreBusy && state.genreTitles.isEmpty()) Text("Nothing matches these filters in your regions. Try another service or genre.", color = Cs.textMuted)
        CsPosterGrid(state.genreTitles, onClick = onSelect)
    }
}

// ───────────────────────────── Roulette ─────────────────────────────

@Composable
fun RouletteScreen(
    state: ScreenState, mood: RouletteMood, length: String, source: String, context: Context,
    onMood: (RouletteMood) -> Unit, onLength: (String) -> Unit, onSource: (String) -> Unit,
    onSpin: () -> Unit, onDetails: (TitleItem) -> Unit, onWatchlist: (TitleItem) -> Unit, onChangeMood: () -> Unit
) {
    val revealed = state.roulettePick
    if (revealed != null && !state.rouletteBusy) {
        // The pick gets the whole screen width; there isn't room for it beside the filters on a TV.
        RouletteReveal(revealed, state, mood, context, onSpin, onDetails, onWatchlist, onChangeMood)
        return
    }
    val angle = remember { Animatable(0f) }
    // Opening Roulette puts the remote on SPIN, so one press of Select spins.
    val spinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { spinFocus.requestFocus() } }
    LaunchedEffect(state.rouletteBusy) {
        if (state.rouletteBusy) while (true) angle.animateTo(angle.value + 360f, tween(700, easing = LinearEasing))
        else if (angle.value != 0f) angle.animateTo(angle.value + 330f, tween(1400, easing = FastOutSlowInEasing))
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Roulette", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Can't decide? Set a mood and spin.", color = Cs.textMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        CsTwoPane(gap = 32.dp, leftWidth = 300.dp, left = { paneMod ->
            Column(paneMod, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("TONIGHT I WANT")
                ROULETTE_MOODS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { m -> CsChip(m.label, m == mood, Modifier.weight(1f)) { onMood(m) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Label("HOW LONG")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Episode" to "episode", "Under 2h" to "short", "Any" to "any").forEach { (t, v) -> CsChip(t, v == length) { onLength(v) } }
                }
                Label("FROM")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("My services" to "mine", "My watchlist" to "watchlist", "Anything" to "any").forEach { (t, v) -> CsChip(t, v == source) { onSource(v) } }
                }
                if (source == "mine" && state.myServices.isEmpty()) Text("No services set yet; this spins across everything.", color = Cs.warn, fontSize = 12.sp)
                Text("Moods map to TMDB genres and ratings. Recent picks are skipped.", color = Cs.textMuted, fontSize = 12.sp)
            }
        }, right = { paneMod ->
            // Wheel while choosing and spinning; the pick then replaces this whole screen (see above).
            Column(paneMod, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(if (LocalCompact.current) 300.dp else 400.dp), contentAlignment = Alignment.Center) {
                    Wheel(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.value })
                    Canvas(Modifier.align(Alignment.TopCenter).size(28.dp, 26.dp)) {
                        drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, Cs.text)
                    }
                    SpinButton(state.rouletteBusy, spinFocus, onSpin)
                }
                Text(if (state.rouletteBusy) "Finding your next watch…" else "Pick a mood, then press SPIN", color = Cs.textMuted, fontSize = 14.sp)
            }
        })
    }
}

/** Big result card: backdrop, large poster, full details, and focus lands on the main action. */
@Composable
private fun RouletteReveal(
    pick: TitleItem, state: ScreenState, mood: RouletteMood, context: Context,
    onSpin: () -> Unit, onDetails: (TitleItem) -> Unit, onWatchlist: (TitleItem) -> Unit, onChangeMood: () -> Unit
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(pick.ref) { delay(150); runCatching { primaryFocus.requestFocus() } }
    BackHandler { onChangeMood() } // Back returns to the wheel, not Home
    val shape = RoundedCornerShape(22.dp)
    // Sized to fit a 960×540dp TV screen below the tab bar without scrolling.
    Box(Modifier.fillMaxWidth().clip(shape).background(Cs.surface).border(1.dp, Cs.line, shape)) {
        AsyncImage(pick.hero(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Cs.bg.copy(alpha = .96f), .5f to Cs.bg.copy(alpha = .85f), 1f to Cs.bg.copy(alpha = .35f))))
        val cmp = LocalCompact.current
        Row(Modifier.padding(if (cmp) 14.dp else 24.dp), horizontalArrangement = Arrangement.spacedBy(if (cmp) 14.dp else 30.dp)) {
            AsyncImage(pick.image("w500"), pick.label, Modifier.width(if (cmp) 110.dp else 214.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(Cs.surfaceHigh)
                .border(1.dp, Cs.lineStrong, RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("YOUR PICK · ${mood.label.uppercase()} · ${if (pick.kind == "tv") "SERIES" else "MOVIE"}", color = Cs.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text(pick.label, color = Cs.text, fontSize = if (cmp) 24.sp else 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (cmp) 28.sp else 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(
                    pick.rating.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" }, pick.year.ifBlank { null },
                    pick.runtime?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                    pick.seasons?.takeIf { pick.kind == "tv" && it > 0 }?.let { "$it season${if (it > 1) "s" else ""}" },
                    pick.genres?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ") { it.name }
                ).joinToString("    "), color = Cs.textSoft, fontSize = 16.sp)
                state.rouletteOffer?.let { (p, r) ->
                    val mine = STREAMING_SERVICES.any { s -> s.key in state.myServices && p.id in s.providerIds }
                    Text((if (mine) "✓ Included with your ${p.name}" else "Available on ${p.name}") + " · ${regionLabel(r)}",
                        color = if (mine) Cs.good else Cs.textSoft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } ?: Text("No streaming offer found in your regions; check Details for rent or buy.", color = Cs.warn, fontSize = 14.sp)
                Text(pick.overview.orEmpty(), color = Cs.textSoft, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val offer = state.rouletteOffer
                    if (offer != null) CsButton("▶ Watch on ${offer.first.name}", primary = true, modifier = Modifier.focusRequester(primaryFocus)) { WatchNow.watch(context, offer.first, pick, listOf(offer.second) + state.regions) }
                    CsButton("Details", primary = offer == null, modifier = if (offer == null) Modifier.focusRequester(primaryFocus) else Modifier) { onDetails(pick) }
                    CsButton(if (pick.ref in state.watchlist) "✓ Saved" else "+ Watchlist") { onWatchlist(pick) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CsButton("↻ Spin again") { onSpin() }
                    CsButton("Change mood") { onChangeMood() }
                }
            }
        }
    }
}

@Composable private fun Label(text: String) = Text(text, color = Cs.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

@Composable
private fun Wheel(modifier: Modifier) {
    Canvas(modifier) {
        val segments = 12
        val sweep = 360f / segments
        repeat(segments) { i ->
            drawArc(if (i % 2 == 0) Cs.buttonFocus else Cs.surfaceHigh, i * sweep, sweep, useCenter = true)
            drawArc(Cs.line, i * sweep, sweep, useCenter = true, style = Stroke(2f))
        }
        drawCircle(Cs.lineStrong, radius = size.minDimension / 2 - 3f, style = Stroke(6f))
        repeat(segments) { i ->
            val a = Math.toRadians((i * sweep + sweep / 2).toDouble())
            val r = size.minDimension * .4f
            drawCircle(if (i % 2 == 0) Cs.accent else Cs.textMuted, 5f, Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat()))
        }
    }
}

@Composable
private fun SpinButton(busy: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(128.dp).focusRequester(focus).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 6.dp else 4.dp, Cs.text, CircleShape).background(Cs.accent, CircleShape)
        .clickable(enabled = !busy, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(if (busy) "•••" else "SPIN", color = Cs.onAccent, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
}

// ───────────────────────────── First run: My Services ─────────────────────────────

@Composable
fun ServicesOnboarding(state: ScreenState, firstFocus: FocusRequester, onToggleService: (String) -> Unit, onToggleRegion: (String) -> Unit, onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(120); runCatching { firstFocus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Cs.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = if (LocalCompact.current) 16.dp else 96.dp, vertical = if (LocalCompact.current) 20.dp else 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WELCOME · YOU CAN CHANGE THIS IN SETTINGS", color = Cs.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Text("Which services do you use?", color = Cs.text, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text("CineSays puts these first everywhere: Home, Discover, Roulette and \"Where to watch\". You can still browse everything else.",
                color = Cs.textSoft, fontSize = 16.sp, modifier = Modifier.widthIn(max = 760.dp))
        }
        ServiceTiles(state.myServices, firstFocus, onToggleService)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Regions to check availability in", color = Cs.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("US", "GB", "PK", "CA", "AU", "IN", "DE", "FR", "AE", "JP").forEach { r -> CsChip(r, r in state.regions) { onToggleRegion(r) } }
            }
            Text("More regions mean more requests; 1–3 keeps Home fast.", color = Cs.textMuted, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CsButton(if (state.myServices.isEmpty()) "Skip for now" else "Continue", primary = state.myServices.isNotEmpty()) { onDone() }
            Text("Next: import from TV Time or follow shows in Tracker.", color = Cs.textMuted, fontSize = 13.sp)
        }
    }
}

/** Large toggle tiles, shared by first run and Settings. */
@Composable
fun ServiceTiles(selected: Set<String>, firstFocus: FocusRequester? = null, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        STREAMING_SERVICES.chunked(if (LocalCompact.current) 2 else 5).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { i, s ->
                    var focused by remember { mutableStateOf(false) }
                    val on = s.key in selected
                    val shape = RoundedCornerShape(14.dp)
                    val base = if (rowIndex == 0 && i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                    Row(base.then(if (LocalCompact.current) Modifier.weight(1f) else Modifier.width(196.dp)).height(72.dp).onFocusChanged { focused = it.isFocused }
                        .border(if (focused) 3.dp else if (on) 2.dp else 1.dp, if (focused) Cs.focusRing else if (on) Cs.accent else Cs.line, shape)
                        .background(if (on) Cs.buttonFocus else Cs.surface, shape).clickable { onToggle(s.key) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, color = Cs.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (s.free) Text("Free with ads", color = Cs.textMuted, fontSize = 11.sp)
                        }
                        Text(if (on) "✓" else "+", color = if (on) Cs.accent else Cs.textMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** The home banner's text and buttons for one title: inside each page on phones, one fixed layer on TV. */
@Composable
private fun HeroText(
    hero: TitleItem, page: Int, compact: Boolean, saved: Boolean, modifier: Modifier,
    detailsModifier: Modifier, onDetails: () -> Unit, onWatchlist: () -> Unit,
    onWatchNow: (() -> Unit)? = null, inset: Dp = 0.dp
) {
    val theme = Cs.theme
    val editorial = theme == CsTheme.EDITORIAL
    // Cinematic leads with Watch Now; the other themes lead with Details.
    val watchFirst = theme == CsTheme.CINEMATIC && onWatchNow != null
    Row(if (compact) modifier.padding(start = 20.dp + inset, end = 20.dp + inset, top = 20.dp, bottom = 34.dp).fillMaxWidth()
        else modifier.padding(start = 40.dp + inset).fillMaxWidth(if (editorial) .62f else .5f),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        // Editorial (TV): the poster beside the text, like a magazine feature.
        if (editorial && !compact) CsArt(hero.image("w342"), null, Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Text(if (editorial) "TONIGHT'S PICK · ${if (hero.kind == "tv") "SERIES" else "MOVIE"}" else "TRENDING #${page + 1} · ${if (hero.kind == "tv") "SERIES" else "MOVIE"}",
                color = Cs.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Text(hero.label, color = Cs.text, fontSize = if (compact) 28.sp else 42.sp, fontWeight = if (editorial) FontWeight.Bold else FontWeight.ExtraBold,
                fontFamily = Cs.titleFont, lineHeight = if (compact) 31.sp else 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf("★ ${"%.1f".format(hero.rating)}", hero.year).filter { it.isNotBlank() }.joinToString("    "), color = Cs.textSoft, fontSize = 15.sp)
            if (!compact) Text(hero.overview.orEmpty(), color = Cs.textSoft, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (watchFirst) CsButton("▶ Watch Now", primary = true, modifier = detailsModifier) { onWatchNow?.invoke() }
                CsButton("Details", primary = !watchFirst, modifier = if (watchFirst) Modifier else detailsModifier) { onDetails() }
                // Three buttons don't fit a phone's width: Cinematic shows the watchlist as a short +/✓ there.
                CsButton(if (compact && watchFirst) (if (saved) "✓" else "+") else if (saved) "✓ Saved" else "+ Watchlist") { onWatchlist() }
            }
        }
    }
}
