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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        // Optional start screen, e.g. `adb shell am start -n com.cineglass.tv/.MainActivity -e tab Roulette`.
        val startTab = intent?.getStringExtra("tab")?.takeIf { it in TABS || it == "Search" || it == "Settings" } ?: "Home"
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Cs.bg, surface = Cs.surface, primary = Cs.accent, onBackground = Cs.text)) { CineSays(startTab = startTab) }
        }
    }
}

/** Top-level tabs. Movies, TV Shows and Genres are now one Discover screen; Recommendations lives under Friends. */
private val TABS = listOf("Home", "Discover", "Tracker", "Watchlist", "Roulette", "Friends")

@Composable
private fun CineSays(vm: CatalogViewModel = viewModel(), startTab: String = "Home") {
    val state by vm.state.collectAsState()
    val tracker: TrackerViewModel = viewModel()
    val trackerUi by tracker.ui.collectAsState()
    val updater: UpdateViewModel = viewModel()
    val updateState by updater.state.collectAsState()
    val ctx = LocalContext.current
    val social = remember { SocialRepository(ctx.applicationContext as Application) }

    var tab by remember { mutableStateOf(startTab) }
    var query by remember { mutableStateOf("") }
    var showExit by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var recommendingItem by remember { mutableStateOf<TitleItem?>(null) }
    val homeTabFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    val onboardingFocus = remember { FocusRequester() }
    var focusHomeOnReturn by remember { mutableStateOf(false) }

    // Discover filters
    var browseKind by remember { mutableStateOf("both") }
    var browseGenre by remember { mutableStateOf<Int?>(null) }
    var browseProvider by remember { mutableStateOf<String?>(null) }
    var browseSort by remember { mutableStateOf("popular") }
    // Roulette filters
    var mood by remember { mutableStateOf(ROULETTE_MOODS.first()) }
    var rouletteLength by remember { mutableStateOf("any") }
    var rouletteSource by remember { mutableStateOf("mine") }

    val upNext = remember(trackerUi.releases) { upNextFrom(trackerUi.releases) }
    val myProviders = remember(state.myServices) { vm.myProviderIds(state.myServices) }

    LaunchedEffect(Unit) { tracker.loadReleases() } // feeds Home's "Up next" row (cached 30 min)
    LaunchedEffect(tab, focusHomeOnReturn) {
        if (tab == "Home" && focusHomeOnReturn) { delay(80); runCatching { homeTabFocus.requestFocus() }; focusHomeOnReturn = false }
    }
    LaunchedEffect(tab) {
        vm.clearError()
        when (tab) {
            "Tracker" -> { tracker.refresh(); tracker.loadReleases() }
            "Watchlist" -> vm.loadWatchlist()
        }
    }
    LaunchedEffect(tab, browseKind, browseGenre, browseProvider, browseSort, state.regions, state.myServices) {
        if (tab == "Discover") vm.discoverGenre(browseKind, browseGenre, browseProvider, browseSort)
    }
    LaunchedEffect(query, tab) { if (tab == "Search") { delay(400); vm.search(query) } }

    // First run: pick services before anything else.
    if (state.onboarded == false) {
        BackHandler { vm.finishOnboarding() }
        ServicesOnboarding(state, onboardingFocus, vm::toggleService, vm::toggleRegion) { vm.finishOnboarding() }
        return
    }

    // One root handler; Tracker registers its own later handler for its sub-tabs, which wins while enabled.
    BackHandler(state.selected == null && recommendingItem == null) {
        if (tab != "Home") { tab = "Home"; focusHomeOnReturn = true }
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

    Column(Modifier.fillMaxSize().background(Cs.bg)) {
        // ── Top tab bar ──
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painterResource(R.drawable.brand_logo), "CineSays", Modifier.height(28.dp).widthIn(max = 124.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(10.dp))
            TABS.forEach { name ->
                CsChip(name, tab == name, if (name == "Home") Modifier.focusRequester(homeTabFocus) else Modifier, compact = true) { tab = name }
            }
            Spacer(Modifier.weight(1f))
            updateState.available?.let { u -> CsChip("⇩ ${u.version}", false, compact = true) { tab = "Settings" } }
            CsChip("⌕", tab == "Search", compact = true) { tab = "Search" }
            CsChip("⚙ ${state.regions.sorted().joinToString("·")}", tab == "Settings", compact = true) { tab = "Settings" }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Cs.accent, trackColor = Cs.bg)

        // ── Content ──
        // Start each screen (and each new Roulette result) at the top instead of inheriting the old scroll.
        val contentScroll = rememberScrollState()
        LaunchedEffect(tab, state.roulettePick?.ref) { contentScroll.scrollTo(0) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            state.error?.let { Text(it, color = Color(0xFFFFB0A0)) }
            when (tab) {
                "Home" -> HomeScreen(state, upNext, heroFocus, openDetails = vm::select, toggleWatchlist = vm::toggle,
                    openTracker = { tab = "Tracker" }, openServices = { tab = "Settings" },
                    markWatched = { e -> tracker.mark(e.show, e.episode.season, e.episode.number, true) })
                "Discover" -> DiscoverScreen(state, browseKind, browseGenre, browseProvider, browseSort,
                    onKind = { browseKind = it }, onGenre = { browseGenre = it }, onProvider = { browseProvider = it }, onSort = { browseSort = it },
                    onSelect = vm::select)
                "Tracker" -> CineTrackerPage(tracker)
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
                "Settings" -> SettingsScreen(state, updateState, updater, vm)
            }
            Text("Movie and TV data provided by TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Cs.textMuted, fontSize = 12.sp)
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
            DetailsScreen(item, state, myProviders,
                inTracker = trackerUi.shows.any { it.tmdbId == item.id && it.followed }, backFocus = backFocus, context = ctx,
                onBack = vm::dismiss, onToggleWatchlist = { vm.toggle(item) }, onTrack = { tracker.followTmdb(item) },
                onRecommend = { recommendingItem = item }, onOpenSimilar = vm::select)
        }
    }
}

@Composable
private fun SettingsScreen(state: ScreenState, updateState: UpdateState, updater: UpdateViewModel, vm: CatalogViewModel) {
    Text("Settings", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
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
    CsPanel(Modifier.fillMaxWidth()) { AppUpdatesSection(updateState, updater) }
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

// TMDB's provider data does not include the provider-specific title ID. We can
// launch an installed app, but cannot honestly promise a title-specific page.
fun openProviderApp(context: Context, provider: Provider, title: String) {
    val names = when (provider.id) {
        8 -> listOf("com.netflix.ninja", "com.netflix.mediaclient")
        9, 119 -> listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
        350, 2 -> listOf("com.apple.atve.androidtv.appletv")
        1899, 384 -> listOf("com.wbd.stream", "com.hbo.hbonow")
        192, 3, 235, 188 -> listOf("com.google.android.youtube.tv")
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
