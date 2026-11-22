package com.cineglass.tv

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged

/** Where an outside request (a notification tap, a launcher shortcut) wants the app to go. */
data class NavRequest(val tab: String? = null, val showTmdb: Int = 0, val kind: String? = null, val id: Int = 0, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private val nav = mutableStateOf<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        // Optional start screen, e.g. `adb shell am start -n com.cineglass.tv/.MainActivity -e tab Roulette`.
        val startTab = intent?.getStringExtra("tab")?.takeIf { it in TABS || it == "Search" || it == "Settings" } ?: "Home"
        if (savedInstanceState == null) nav.value = navFrom(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Cs.bg, surface = Cs.surface, primary = Cs.accent, onBackground = Cs.text)) {
                CineSays(startTab = startTab, nav = nav.value, onNavHandled = { nav.value = null })
            }
        }
    }

    // A notification tapped while the app is already open arrives here (launchMode singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nav.value = navFrom(intent)
    }

    private fun navFrom(i: Intent?): NavRequest? {
        i ?: return null
        val show = i.getIntExtra(EXTRA_SHOW_TMDB, 0)
        val id = i.getIntExtra(EXTRA_TITLE_ID, 0)
        val kind = i.getStringExtra(EXTRA_TITLE_KIND)
        val tab = i.getStringExtra("tab")?.takeIf { it in TABS || it == "Search" || it == "Settings" }
        return if (show > 0 || (id > 0 && kind != null) || tab != null) NavRequest(tab, show, kind, id) else null
    }

    companion object {
        private const val EXTRA_SHOW_TMDB = "open_show_tmdb"
        private const val EXTRA_TITLE_KIND = "open_title_kind"
        private const val EXTRA_TITLE_ID = "open_title_id"
        private fun base(ctx: android.content.Context) = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        /** Opens a tracked series in Tracker (its seasons and episodes). */
        fun openShowIntent(ctx: android.content.Context, tmdbId: Int) = base(ctx).putExtra(EXTRA_SHOW_TMDB, tmdbId).setAction("open_show_$tmdbId")
        /** Opens a movie or show's details. */
        fun openTitleIntent(ctx: android.content.Context, kind: String, id: Int) = base(ctx).putExtra(EXTRA_TITLE_KIND, kind).putExtra(EXTRA_TITLE_ID, id).setAction("open_${kind}_$id")
        fun openTabIntent(ctx: android.content.Context, tab: String) = base(ctx).putExtra("tab", tab).setAction("open_tab_$tab")
    }
}

/** Top-level tabs. Movies, TV Shows and Genres are now one Discover screen; Recommendations lives under Friends. */
private val TABS = listOf("Home", "Discover", "Free", "Tracker", "Watchlist", "Roulette", "Friends")

@Composable
private fun CineSays(vm: CatalogViewModel = viewModel(), startTab: String = "Home", nav: NavRequest? = null, onNavHandled: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val tracker: TrackerViewModel = viewModel()
    val trackerUi by tracker.ui.collectAsState()
    val updater: UpdateViewModel = viewModel()
    val updateState by updater.state.collectAsState()
    val ctx = LocalContext.current
    val social = remember { SocialRepository(ctx.applicationContext as Application) }
    // Hybrid player: Watch Now, native playback, resume and progress.
    val streaming: StreamingViewModel = viewModel()
    val streamState by streaming.state.collectAsState()
    // In-app playback that crossed the completion threshold goes to the Tracker, once per movie / episode.
    LaunchedEffect(Unit) { streaming.completed.collect { r -> tracker.recordPlaybackCompleted(r) } }
    LaunchedEffect(streamState.message) { streamState.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); streaming.clearMessage() } }

    var tab by remember { mutableStateOf(startTab) }
    var query by remember { mutableStateOf("") }
    var showExit by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var recommendingItem by remember { mutableStateOf<TitleItem?>(null) }
    val homeTabFocus = remember { FocusRequester() }
    // One focus target per top-bar item, so Back can return focus to the current tab first.
    val tabFocus = remember { (TABS + listOf("Search", "Settings")).associateWith { FocusRequester() } }
    var tabBarFocused by remember { mutableStateOf(false) }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    val socialState by social.state.collectAsState()
    val heroFocus = remember { FocusRequester() }
    val onboardingFocus = remember { FocusRequester() }
    var focusHomeOnReturn by remember { mutableStateOf(false) }

    /** Opens Watch Now for [item]; offers and the IMDb id come from Details when it's showing that title. */
    fun openWatchNow(item: TitleItem, preselect: MediaRef? = null) {
        scope.launch {
            val watched = if (item.kind == "tv") tracker.watchedFor(item.id) else emptySet()
            val shown = state.selected?.let { it.id == item.id && it.kind == item.kind } == true
            streaming.openWatchNow(item, if (shown) state.imdbId ?: preselect?.imdbId else preselect?.imdbId, if (shown) state.providersByRegion else emptyMap(),
                state.regions, vm.myProviderIds(), watched, preselect)
        }
    }
    /** Watch Now for a saved movie / episode (Continue Watching, next episode, "Other sources"). */
    fun openWatchNowFor(ref: MediaRef) {
        val item = if (ref.type == MediaType.MOVIE) TitleItem(id = ref.tmdbId, title = ref.title, mediaType = "movie", poster = ref.poster)
            else TitleItem(id = ref.tmdbId, name = ref.title, mediaType = "tv", poster = ref.poster)
        openWatchNow(item, ref)
    }

    // Discover filters
    var browseKind by remember { mutableStateOf("both") }
    var browseGenre by remember { mutableStateOf<Int?>(null) }
    var browseProvider by remember { mutableStateOf<String?>(null) }
    var browseSort by remember { mutableStateOf("popular") }
    // Roulette filters
    var mood by remember { mutableStateOf(ROULETTE_MOODS.first()) }
    var rouletteLength by remember { mutableStateOf("any") }
    var rouletteSource by remember { mutableStateOf("mine") }

    val upNext = remember(trackerUi.releases, trackerUi.continueWatching) { mergeUpNext(upNextFrom(trackerUi.releases), trackerUi.continueWatching) }
    val myProviders = remember(state.myServices) { vm.myProviderIds(state.myServices) }

    // Recommended for you: recent tracker activity as seeds, and never suggest what you've already tracked or watched.
    LaunchedEffect(state.likes, state.watchlist, trackerUi.shows.size, trackerUi.movies.size, state.sections.isNotEmpty()) {
        if (!state.sections.isNotEmpty()) return@LaunchedEffect
        val seeds = trackerUi.shows.filter { it.tmdbId > 0 && it.watchedCount > 0 }.sortedByDescending { it.lastWatchedAt }.take(5).map { "tv:${it.tmdbId}" } +
            trackerUi.movies.filter { it.tmdbId > 0 }.take(3).map { "movie:${it.tmdbId}" }
        val exclude = trackerUi.shows.filter { it.tmdbId > 0 }.map { "tv:${it.tmdbId}" }.toSet() + trackerUi.watchedMovieIds.map { "movie:$it" }
        vm.loadRecommendations(seeds, exclude)
    }

    // Cloud backup: everything that should follow you to another device.
    suspend fun backupJson(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        org.json.JSONObject().put("v", 1).put("tracker", org.json.JSONObject(tracker.exportData())).put("prefs", org.json.JSONObject(vm.exportPrefs())).toString()
    }
    suspend fun restoreJson(json: String) {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        o.optJSONObject("prefs")?.let { vm.importPrefs(it.toString()) }
        o.optJSONObject("tracker")?.let { tracker.importData(it.toString()) }
    }
    // Outside requests (notification taps): go to the tab, the tracked show, or the title's details.
    LaunchedEffect(nav) {
        val n = nav ?: return@LaunchedEffect
        n.tab?.let { tab = it }
        if (n.showTmdb > 0) {
            tab = "Tracker"; tracker.selectTab(TRACKER_HOME_TAB)
            // On a cold start the tracker may still be loading its shows.
            val shows = kotlinx.coroutines.withTimeoutOrNull(8000) { tracker.ui.first { it.shows.isNotEmpty() }.shows } ?: trackerUi.shows
            shows.firstOrNull { it.tmdbId == n.showTmdb }?.let(tracker::selectShow) ?: vm.selectById("tv", n.showTmdb)
        }
        if (n.kind != null && n.id > 0) vm.selectById(n.kind, n.id)
        onNavHandled()
    }
    // Two-way sync for signed-in accounts: on start, pull the cloud backup and merge it (removals and
    // clears made on other devices apply here), then push the merged result back.
    LaunchedEffect(socialState.email) {
        if (socialState.email == null) return@LaunchedEffect
        social.downloadBackup()?.let { restoreJson(it) }
        social.uploadBackup(backupJson())
    }
    // Push imports/removals/clears right away, so other devices see them on their next sync.
    LaunchedEffect(trackerUi.dataVersion) {
        if (trackerUi.dataVersion == 0 || socialState.email == null) return@LaunchedEffect
        delay(1500)
        social.uploadBackup(backupJson())
    }
    // Back up automatically when the app goes to the background (signed-in accounts only, at most every 10 min).
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, socialState.email) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && socialState.email != null && System.currentTimeMillis() - socialState.lastBackupAt > 10 * 60_000L)
                scope.launch { social.uploadBackup(backupJson()) }
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) { tracker.loadReleases() } // feeds Home's "Up next" row (cached 30 min)
    LaunchedEffect(tab, focusHomeOnReturn) {
        if (tab == "Home" && focusHomeOnReturn) { delay(80); runCatching { homeTabFocus.requestFocus() }; focusHomeOnReturn = false }
    }
    LaunchedEffect(tab) {
        vm.clearError()
        when (tab) {
            "Tracker" -> { tracker.refresh(); tracker.loadReleases() }
            "Watchlist" -> vm.loadWatchlist()
            "Free" -> vm.loadFree()
            "Settings" -> vm.loadAddons()
        }
    }
    LaunchedEffect(tab, browseKind, browseGenre, browseProvider, browseSort, state.regions, state.myServices) {
        if (tab == "Discover") vm.discoverGenre(browseKind, browseGenre, browseProvider, browseSort)
    }
    LaunchedEffect(query, tab) { if (tab == "Search") { delay(400); vm.search(query) } }

    // First run: pick services before anything else.
    if (state.onboarded == false) {
        BackHandler { vm.finishOnboarding() }
        CompositionLocalProvider(LocalCompact provides (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700)) {
            ServicesOnboarding(state, onboardingFocus, vm::toggleService, vm::toggleRegion) { vm.finishOnboarding() }
        }
        return
    }

    // One root handler; Tracker registers its own later handler for its sub-tabs, which wins while enabled.
    // With a remote: Back first lifts focus from the page to its tab in the top bar, then goes Home.
    // With touch (phones) there's no focus to lift, so Back goes straight Home.
    BackHandler(state.selected == null && recommendingItem == null) {
        val remote = inputMode.inputMode != androidx.compose.ui.input.InputMode.Touch
        if (remote && !tabBarFocused) { runCatching { tabFocus[tab]?.requestFocus() } }
        else if (tab != "Home") { tab = "Home"; focusHomeOnReturn = true }
        else {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBack < 2200L) { showExit = true; lastBack = 0L }
            else { lastBack = now; Toast.makeText(ctx, "Press Back again to exit CineSays", Toast.LENGTH_SHORT).show() }
        }
    }
    if (showExit) AlertDialog(
        onDismissRequest = { showExit = false },
        title = { Text("Exit CineSays?") }, text = { Text("Do you want to close the application?") },
        confirmButton = { TextButton(onClick = { showExit = false; (ctx as? android.app.Activity)?.finish() }) { Text("Exit") } },
        dismissButton = { TextButton(onClick = { showExit = false }) { Text("Cancel") } })

    BoxWithConstraints(Modifier.fillMaxSize().background(Cs.bg).safeDrawingPadding()) { // keep clear of phone status/navigation bars (Android 15 is edge-to-edge)
    // Phones and narrow windows get stacked layouts and tighter padding.
    val compact = maxWidth < 700.dp
    val sidePad = if (compact) 16.dp else 48.dp
    CompositionLocalProvider(LocalCompact provides compact) {
    Column(Modifier.fillMaxSize()) {
        // ── Top tab bar (scrolls sideways on phones) ──
        Row(Modifier.fillMaxWidth().onFocusChanged { tabBarFocused = it.hasFocus }
            .then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(start = sidePad, end = sidePad, top = if (compact) 10.dp else 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.brand_logo), "CineSays", Modifier.height(if (compact) 22.dp else 28.dp).widthIn(max = if (compact) 96.dp else 124.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(10.dp))
            TABS.forEach { name ->
                CsChip(name, tab == name, Modifier.focusRequester(tabFocus.getValue(name)).then(if (name == "Home") Modifier.focusRequester(homeTabFocus) else Modifier), compact = true) { tab = name }
            }
            if (!compact) Spacer(Modifier.weight(1f))
            updateState.available?.let { u -> CsChip("⇩ ${u.version}", false, compact = true) { tab = "Settings" } }
            CsChip("⌕", tab == "Search", Modifier.focusRequester(tabFocus.getValue("Search")), compact = true) { tab = "Search" }
            CsChip("⚙ ${state.regions.sorted().joinToString("·")}", tab == "Settings", Modifier.focusRequester(tabFocus.getValue("Settings")), compact = true) { tab = "Settings" }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Cs.accent, trackColor = Cs.bg)

        // ── Content ──
        // Start each screen (and each new Roulette result) at the top instead of inheriting the old scroll.
        val contentScroll = rememberScrollState()
        LaunchedEffect(tab, state.roulettePick?.ref) { contentScroll.scrollTo(0) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(start = sidePad, end = sidePad, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            state.error?.let { Text(it, color = Color(0xFFFFB0A0)) }
            when (tab) {
                "Home" -> HomeScreen(state, upNext, heroFocus, openDetails = vm::select, toggleWatchlist = vm::toggle,
                    continueWatching = streamState.continueWatching,
                    onResume = { r -> streaming.resume(r) { rec -> rec.ref()?.let(::openWatchNowFor) } },
                    onResumeDetails = { r -> r.ref()?.let { ref -> vm.selectById(if (ref.type == MediaType.MOVIE) "movie" else "tv", ref.tmdbId) } },
                    openTracker = { tab = "Tracker" }, openServices = { tab = "Settings" },
                    markWatched = { e -> tracker.mark(e.show, e.episode.season, e.episode.number, true) },
                    // Up next card → that series in Tracker (season and episodes), not "mark watched".
                    openShow = { e -> tab = "Tracker"; tracker.selectTab(TRACKER_HOME_TAB)
                        trackerUi.shows.firstOrNull { it.title == e.show }?.let(tracker::selectShow) })
                "Discover" -> DiscoverScreen(state, browseKind, browseGenre, browseProvider, browseSort,
                    onKind = { browseKind = it }, onGenre = { browseGenre = it }, onProvider = { browseProvider = it }, onSort = { browseSort = it },
                    onSelect = vm::select)
                // Legal free watching: live channels and public-domain films play in-app; free-with-ads titles open their apps.
                "Free" -> FreeScreen(state, ctx, onPlayFilm = { f -> streaming.playFreeFilm(f) },
                    onPlayLive = { ch -> vm.play(Playable.Live("${ch.name} · Live", ch.youtubeChannelId)) }, onOpenTitle = vm::select)
                "Tracker" -> CineTrackerPage(tracker) { kind, id -> vm.selectById(kind, id) }
                "Watchlist" -> {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Watchlist", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${state.watchlist.size} saved", color = Cs.textMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    if (state.watchlistBusy && state.watchlistItems.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
                    if (!state.watchlistBusy && state.watchlist.isEmpty()) Text("Nothing saved yet. Use + Watchlist on any title.", color = Cs.textSoft)
                    CsPosterGrid(state.watchlistItems.filter { it.ref in state.watchlist }, onClick = vm::select)
                }
                "Roulette" -> RouletteScreen(state, mood, rouletteLength, rouletteSource, ctx,
                    onMood = { mood = it }, onLength = { rouletteLength = it }, onSource = { rouletteSource = it },
                    onSpin = { vm.spin(mood, rouletteLength, rouletteSource) }, onDetails = vm::select, onWatchlist = vm::toggle,
                    onChangeMood = vm::clearRoulettePick)
                // People and picks on one screen (Recommendations is no longer a separate sub-tab).
                "Friends" -> FriendsScreen(social) { kind, id -> vm.selectById(kind, id) }
                "Search" -> {
                    Text("Search", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    BasicTextField(query, { query = it }, Modifier.fillMaxWidth().background(Cs.surface, RoundedCornerShape(14.dp)).padding(18.dp), singleLine = true,
                        textStyle = TextStyle(color = Cs.text, fontSize = 21.sp),
                        decorationBox = { inner -> Box { if (query.isBlank()) Text("Type a movie or series title…", color = Cs.textMuted, fontSize = 21.sp); inner() } })
                    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
                    if (!state.searching && query.isNotBlank() && state.results.isEmpty()) Text("No movies or series match \"$query\".", color = Cs.textSoft)
                    CsPosterGrid(state.results, onClick = vm::select)
                }
                "Settings" -> SettingsScreen(state, updateState, updater, vm, socialState, streamState, streaming::updatePrefs, streaming::playTest,
                    onCreateAccount = { email, pw -> scope.launch { social.createAccount(email, pw, backupJson()) } },
                    onSignIn = { email, pw -> scope.launch { social.signIn(email, pw)?.let { restoreJson(it) } } },
                    onBackupNow = { scope.launch { if (social.uploadBackup(backupJson())) Toast.makeText(ctx, "Backed up", Toast.LENGTH_SHORT).show() } },
                    onRestoreNow = { scope.launch { social.downloadBackup()?.let { restoreJson(it); Toast.makeText(ctx, "Restored from your backup", Toast.LENGTH_SHORT).show() } } },
                    onResetPassword = { email -> scope.launch { social.sendPasswordReset(email) } })
            }
            Text("Movie and TV data provided by TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Cs.textMuted, fontSize = 12.sp)
        }
    }
    }
    }

    recommendingItem?.let { movie ->
        Dialog(onDismissRequest = { recommendingItem = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxWidth(.72f).verticalScroll(rememberScrollState())) {
                RecommendToFriendsDialog(movie, social) { recommendingItem = null }
            }
        }
    }
    // Details is its own window so the screen behind cannot take D-pad input.
    if (recommendingItem == null) state.selected?.let { item ->
        Dialog(onDismissRequest = vm::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val backFocus = remember { FocusRequester() }
            LaunchedEffect(item.id, item.kind) { delay(60); runCatching { backFocus.requestFocus() } }
            // The dialog is its own window: provide the phone/TV layout flag to it too.
            CompositionLocalProvider(LocalCompact provides (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700)) {
                DetailsScreen(item, state, myProviders,
                    inTracker = trackerUi.shows.any { it.tmdbId == item.id && it.followed }, backFocus = backFocus, context = ctx,
                    onBack = vm::dismiss, onToggleWatchlist = { vm.toggle(item) }, onTrack = { tracker.followTmdb(item) },
                    onRecommend = { recommendingItem = item }, onOpenSimilar = vm::select,
                    liked = item.ref in state.likes, onLike = { vm.toggleLike(item) },
                    movieWatched = item.id in trackerUi.watchedMovieIds, onMovieWatched = { tracker.setMovieWatched(item, it) },
                    onPerson = vm::openPerson,
                    onPlayTrailer = { key -> vm.play(Playable.Trailer("Trailer · ${item.label}", key)) },
                    onWatchFree = { film -> streaming.playFreeFilm(film, MediaRef(MediaType.MOVIE, item.id, item.label, state.imdbId, item.year.ifBlank { null }, poster = item.poster), item) },
                    onWatchNow = { openWatchNow(item) })
            }
        }
    }
    // Actor page: its own window over Details. Back returns to Details; a title opens its details.
    state.person?.let { person ->
        Dialog(onDismissRequest = vm::closePerson, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val personFocus = remember { FocusRequester() }
            LaunchedEffect(person.id) { delay(60); runCatching { personFocus.requestFocus() } }
            CompositionLocalProvider(LocalCompact provides (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700)) {
                PersonScreen(person, state.personBusy, personFocus, onBack = vm::closePerson) { title -> vm.closePerson(); vm.select(title) }
            }
        }
    }
    // In-app player: its own full-screen window above everything; Back or "← Close" returns.
    state.playing?.let { item ->
        Dialog(onDismissRequest = vm::closePlayer, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            PlayerScreen(item, onClose = vm::closePlayer)
        }
    }
    // Watch Now over Details; the native player over Watch Now (Back returns to it, focus intact).
    streamState.watchNow?.let { w ->
        WatchNowDialog(w, streamState.stremioInstalled, onClose = streaming::closeWatchNow, onSeason = { streaming.selectSeason(it) },
            onEpisode = streaming::selectEpisode, onAction = { a -> streaming.act(a, ctx) }, onRetry = streaming::retry,
            onMarkEpisode = { ref, watched ->
                val s = ref.season; val e = ref.episode
                if (s != null && e != null) { tracker.markEpisodeByTmdb(ref.tmdbId, ref.title, ref.poster, s, e, watched); streaming.setEpisodeWatched(ref, watched) }
            })
    }
    streamState.resume?.let { p -> ResumeDialog(p, onResume = { streaming.answerResume(true) }, onRestart = { streaming.answerResume(false) }, onDismiss = streaming::dismissResume) }
    streamState.playing?.let { p ->
        Dialog(onDismissRequest = streaming::closePlayer, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            NativePlayerScreen(p, streamState.prefs, onProgress = { pos, dur -> streaming.saveProgress(p, pos, dur) }, onClose = streaming::closePlayer,
                onNext = p.next?.let { { streaming.playNext { ref -> openWatchNowFor(ref) } } },
                onFindSources = p.record.ref()?.let { ref -> { streaming.closePlayer(); if (streamState.watchNow == null) openWatchNowFor(ref) } },
                onSubtitlePref = { lang -> streaming.updatePrefs { if (lang == null) it.copy(subtitleMode = "off") else it.copy(subtitleMode = "on", subtitleLanguage = lang) } })
        }
    }
    if (streamState.preparing) Dialog(onDismissRequest = streaming::cancelSourcePreparation) {
        Row(Modifier.background(Cs.panel, RoundedCornerShape(16.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cs.accent)
            Text("Getting the video ready…", color = Cs.text)
        }
    }
    if (state.playerBusy) Dialog(onDismissRequest = vm::closePlayer) {
        Row(Modifier.background(Cs.panel, RoundedCornerShape(16.dp)).padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cs.accent)
            Text("Getting the film ready…", color = Cs.text)
        }
    }
}

@Composable
private fun SettingsScreen(
    state: ScreenState, updateState: UpdateState, updater: UpdateViewModel, vm: CatalogViewModel, social: SocialState,
    streamState: StreamingState, onPlaybackPrefs: ((PlaybackPrefs) -> PlaybackPrefs) -> Unit, onPlaybackTest: (StreamKind) -> Unit,
    onCreateAccount: (String, String) -> Unit, onSignIn: (String, String) -> Unit, onBackupNow: () -> Unit, onRestoreNow: () -> Unit,
    onResetPassword: (String) -> Unit
) {
    Text("Settings", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
    CsPanel(Modifier.fillMaxWidth()) { AccountSection(social, onCreateAccount, onSignIn, onBackupNow, onRestoreNow, onResetPassword) }
    CsPanel(Modifier.fillMaxWidth()) {
        Text("My Services", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Services you use are listed first, power Home's \"New on your services\" row, and the ★ My services filter in Discover and Roulette.", color = Cs.textSoft, fontSize = 14.sp)
        ServiceTiles(state.myServices, onToggle = vm::toggleService)
    }
    CsPanel(Modifier.fillMaxWidth()) {
        Text("Streaming regions", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Results are combined across regions; offers are shown per region on each title. At least one must stay selected.", color = Cs.textSoft, fontSize = 14.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("US", "GB", "PK", "CA", "AU", "IN", "DE", "FR", "AE", "JP").forEach { r -> CsChip("$r · ${regionLabel(r)}", r in state.regions) { vm.toggleRegion(r) } }
        }
    }
    CsPanel(Modifier.fillMaxWidth()) { PlaybackSettingsSection(streamState.prefs, streamState.stremioInstalled,
        state.addons.filter { it.first.engine == Addons.STREMIO && it.second }, onPlaybackPrefs, onPlaybackTest) }
    CsPanel(Modifier.fillMaxWidth()) { AddonsSection(state.addons, vm::toggleAddon) }
    CsPanel(Modifier.fillMaxWidth()) { AppUpdatesSection(updateState, updater) }
    AboutCredit(updateState.currentVersion)
}

/** Settings › Add-ons: turn each legal catalogue on or off. */
@Composable
private fun AddonsSection(addons: List<Pair<Addon, Boolean>>, onToggle: (String) -> Unit) {
    Text("Add-ons", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Collections for the Free tab and sources for Watch Now. Torrent add-ons are optional; enable them only for content you are authorized to access.",
        color = Cs.textSoft, fontSize = 14.sp)
    addons.forEach { (a, on) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(a.name, color = Cs.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(a.description, color = Cs.textMuted, fontSize = 13.sp)
            }
            CsChip(if (on) "✓ On" else "Off", on, compact = true) { onToggle(a.id) }
        }
    }
}

/** "Imagined by" credit at the end of Settings. */
@Composable
private fun AboutCredit(version: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Khanpur Boys logo: three friends behind one shared laptop.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.khanpur_logo),
            contentDescription = "Khanpur Boys logo", modifier = Modifier.size(96.dp))
        Text("Imagined by Khanpur Boys", color = Cs.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("CineSays $version", color = Cs.textMuted, fontSize = 13.sp)
    }
}

/**
 * Account & sync: link this device's identity to an email account (keeps friends), and back up /
 * restore tracker history, watched movies, watchlist, likes, services and regions.
 */
@Composable
private fun AccountSection(
    s: SocialState, onCreateAccount: (String, String) -> Unit, onSignIn: (String, String) -> Unit,
    onBackupNow: () -> Unit, onRestoreNow: () -> Unit, onResetPassword: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text("Account & sync", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (!s.enabled) { Text(s.message.ifBlank { "Connecting…" }, color = Cs.textMuted, fontSize = 14.sp); return }
    if (s.email != null) {
        Text("Signed in as ${s.email}. Your tracker, watched movies, watchlist, likes, services and friends follow you to any device you sign in on.",
            color = Cs.textSoft, fontSize = 14.sp)
        Text(if (s.lastBackupAt > 0) "Last backup: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(s.lastBackupAt))} (backs up automatically when you leave the app)"
            else "No backup yet.", color = Cs.textMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CsButton("Back up now", primary = true, enabled = !s.accountBusy) { onBackupNow() }
            CsButton("Restore from backup", enabled = !s.accountBusy) { onRestoreNow() }
        }
    } else {
        Text("Right now your data and friends live only on this device. Create an account to back them up and keep them when you use CineSays on another TV or phone. Already have one? Sign in.",
            color = Cs.textSoft, fontSize = 14.sp)
        CsTextField(email, "Email", Modifier.widthIn(max = 520.dp).fillMaxWidth()) { email = it.trim() }
        CsPasswordField(password, "Password (6+ characters)", Modifier.widthIn(max = 520.dp).fillMaxWidth()) { password = it }
        val ready = email.contains("@") && password.length >= 6 && !s.accountBusy
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CsButton("Create account", primary = true, enabled = ready) { onCreateAccount(email, password); password = "" }
            CsButton("Sign in", enabled = ready) { onSignIn(email, password); password = "" }
            CsButton("Forgot password", enabled = email.contains("@") && !s.accountBusy) { onResetPassword(email) }
        }
        Text("Create account keeps this device's friend code and friends. Sign in switches this device to your existing account and merges its data with this device's.",
            color = Cs.textMuted, fontSize = 12.sp)
    }
    if (s.accountBusy) LinearProgressIndicator(Modifier.widthIn(max = 520.dp).fillMaxWidth(), color = Cs.accent)
    if (s.accountMessage.isNotBlank()) Text(s.accountMessage, color = Cs.warn, fontSize = 14.sp)
}

@Composable
private fun CsPasswordField(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(value, onChange, modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.lineStrong, shape)
        .background(Cs.surfaceHigh, shape).padding(horizontal = 14.dp, vertical = 12.dp), singleLine = true,
        textStyle = TextStyle(color = Cs.text, fontSize = 17.sp),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Cs.textMuted, fontSize = 16.sp); inner() } })
}

@Composable
private fun AppUpdatesSection(state: UpdateState, updater: UpdateViewModel) {
    Text("App updates", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Installed version ${state.currentVersion}", color = Cs.textSoft, fontSize = 14.sp)
    if (!state.configured) {
        Text("Updates are off: add UPDATE_REPO=owner/repo to local.properties and rebuild (see RELEASING.md).", color = Cs.textMuted, fontSize = 14.sp)
        return
    }
    val update = state.available
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CsButton(if (state.checking) "Checking…" else "Check for updates") { updater.check() }
        if (update != null) CsButton(if (state.progress != null) "Downloading ${((state.progress ?: 0f) * 100).toInt()}%" else "⇩ Download & install ${update.version}", primary = true) {
            if (state.progress == null) updater.downloadAndInstall()
        }
    }
    if (state.progress != null) LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(), color = Cs.accent)
    when {
        update != null -> {
            Text("Version ${update.version} is available" + (if (update.sizeBytes > 0) " · ${"%.1f".format(update.sizeBytes / 1_048_576f)} MB" else ""), color = Cs.accent, fontSize = 16.sp)
            if (update.notes.isNotBlank()) Text(update.notes, color = Cs.textSoft, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 900.dp))
        }
        state.upToDate -> Text("You're on the latest version.", color = Cs.good, fontSize = 15.sp)
    }
    state.message?.let { Text(it, color = Cs.warn, fontSize = 14.sp) }
}

/** Android (TV first, then phone) packages for a TMDB provider. */
fun providerPackages(providerId: Int): List<String> = when (providerId) {
    8 -> listOf("com.netflix.ninja", "com.netflix.mediaclient")
    9, 119, 2100, PRIME_FREE_ID -> listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
    350, 2 -> listOf("com.apple.atve.androidtv.appletv")
    1899, 384 -> listOf("com.wbd.stream", "com.hbo.hbonow")
    192, 3, 235, 188 -> listOf("com.google.android.youtube.tv", "com.google.android.youtube")
    337 -> listOf("com.disney.disneyplus")
    73 -> listOf("com.tubitv")
    300 -> listOf("tv.pluto.android")
    538 -> listOf("com.plexapp.android")
    232 -> listOf("com.graymatrix.did")
    2336 -> listOf("in.startv.hotstar")
    237, 2792 -> listOf("com.sonyliv")
    11 -> listOf("com.mubi")
    else -> emptyList()
}

// Fallback when no title-specific link is known (see WatchNow): launch the installed app.
fun openProviderApp(context: Context, provider: Provider, title: String) {
    val names = providerPackages(provider.id)
    for (pkg in names) {
        try {
            val launch = context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "Opened ${provider.name}. Find '$title' in the app.", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) { /* Try the next TV package. */ }
    }
    // Not installed: offer its Play Store page so it can be installed straight from here.
    names.firstOrNull()?.let { pkg ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(context, "${provider.name} isn't installed. Opening it in the Play Store.", Toast.LENGTH_LONG).show()
            return
        } catch (_: Exception) { /* No Play Store on this device. */ }
    }
    Toast.makeText(context, "${provider.name} TV app not found. Install it to open here.", Toast.LENGTH_LONG).show()
}

fun openUrl(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { Toast.makeText(context, "No compatible app found to open this link", Toast.LENGTH_SHORT).show() }
}

fun regionLabel(code: String) = mapOf(
    "US" to "United States", "GB" to "United Kingdom", "PK" to "Pakistan", "CA" to "Canada", "AU" to "Australia",
    "IN" to "India", "DE" to "Germany", "FR" to "France", "AE" to "UAE", "JP" to "Japan"
)[code] ?: code
