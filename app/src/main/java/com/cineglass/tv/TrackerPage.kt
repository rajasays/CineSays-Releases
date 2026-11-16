package com.cineglass.tv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val trackerBg=Color(0xFF071424)
private val trackerPanel=Color(0xE0142941)
private val trackerBlue=Color(0xFF118CFF)
private val trackerWhite=Color(0xFFF4F8FF)
private val trackerMuted=Color(0xFFB7C9E1)

@Composable
private fun TrackPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Column(modifier.border(1.dp,Color(0xFF385674),RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
        .background(Brush.verticalGradient(listOf(Color(0xF020344E),trackerPanel)))
        .padding(13.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable
private fun TrackButton(text:String, active:Boolean=false, modifier:Modifier=Modifier,onClick:()->Unit){
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused=it.isFocused }.border(if(focused)2.dp else 1.dp,if(focused)Color.Cyan else if(active)trackerBlue else Color(0xFF5A7897),RoundedCornerShape(11.dp))
        .background(if(focused||active)Brush.verticalGradient(listOf(Color(0xFF168DF5),Color(0xFF043C9A))) else Brush.verticalGradient(listOf(Color(0xFF223750),Color(0xFF122136))),RoundedCornerShape(11.dp))
        .clickable{onClick()}.padding(horizontal=14.dp,vertical=11.dp),contentAlignment=Alignment.Center){
        Text(text,color=trackerWhite,fontWeight=if(focused||active)FontWeight.Bold else FontWeight.Medium,fontSize=13.sp,maxLines=1)
    }
}
@Composable
fun CineTrackerPage(vm:TrackerViewModel, openTitle: (String, Int) -> Unit = { _, _ -> }){
    val state by vm.ui.collectAsState()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri: Uri?->uri?.let(vm::prepare)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->vm.setReminders(ok)}
    val context=androidx.compose.ui.platform.LocalContext.current

    var addTitle by remember { mutableStateOf("") }
    var addExpanded by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var weekOffset by remember { mutableIntStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var letter by remember { mutableStateOf<Char?>(null) } // My Shows A–Z filter
    var manageImports by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    BackHandler(state.chosen != null || state.tab != TRACKER_HOME_TAB) {
        if(state.chosen != null) vm.closeShow() else vm.selectTab(TRACKER_HOME_TAB)
    }
    val today=LocalDate.now()
    val dateFmt=remember{DateTimeFormatter.ofPattern("MMM d")}
    val monday=today.minusDays((today.dayOfWeek.value-1).toLong())
    val upNext = remember(state.releases, state.continueWatching) { mergeUpNext(upNextFrom(state.releases, today), state.continueWatching) }
    val thisWeek = state.releases.count { runCatching{val d=LocalDate.parse(it.episode.airDate);!d.isBefore(today)&&d.isBefore(today.plusDays(7))}.getOrDefault(false)}
    Column(Modifier.fillMaxWidth().background(Cs.bg),verticalArrangement=Arrangement.spacedBy(16.dp)){
        // Header: title, tabs and the three numbers that matter, on one line.
        // Two rows: a single row overflowed on a 960dp-wide TV and crushed the last counter.
        val narrow = LocalCompact.current
        val numbers: @Composable () -> Unit = {
            TrackerNumber("${upNext.sumOf { it.behind }}", "to watch")
            TrackerNumber("$thisWeek", "this week")
            TrackerNumber("${state.stats.streak}", "day streak")
        }
        // Phones: counters go on their own line under the title instead of squeezing beside it.
        if (narrow) {
            Text("Tracker",color=Cs.text,fontSize=28.sp,fontWeight=FontWeight.ExtraBold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 0.dp), verticalAlignment = Alignment.Bottom) { numbers() }
        } else Row(verticalAlignment=Alignment.CenterVertically){
            Text("Tracker",color=Cs.text,fontSize=28.sp,fontWeight=FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            if (state.loading) Text("Updating releases…", color = Cs.textMuted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            numbers()
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(TRACKER_HOME_TAB to TRACKER_HOME_TAB, "My Shows · ${state.shows.count{it.followed}}" to "My Shows", "Movies · ${state.stats.movies}" to "Movies",
                "+ Add shows" to "Add shows", "Calendar" to "Calendar & Releases", "Timeline" to "Release Timeline", "Reminders" to "Reminders")
                .forEach{ (label, tab) -> CsChip(label, state.tab==tab, compact = true){ vm.selectTab(tab) } }
        }
        // One import for both services: the importer recognises a TV Time or BingeBoxd ZIP by its files.
        if (state.tab in listOf(TRACKER_HOME_TAB, "My Shows", "Movies") && state.chosen == null && state.preview == null)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                CsButton(if (narrow) "⇧ Import" else "⇧ Import from TV Time or BingeBoxd") { picker.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream")) }
                if (state.imports.isNotEmpty() || state.shows.isNotEmpty() || state.movies.isNotEmpty()) CsButton(if (manageImports) "Close" else if (state.imports.isNotEmpty()) "Manage imports" else "Manage data") { manageImports = !manageImports; confirmRemove = null }
                if (!LocalCompact.current) Text("Use the ZIP export from either app. Duplicates are skipped.", color = Cs.textMuted, fontSize = 12.sp)
            }
        // Remove an import entirely (two presses, because it can't be undone).
        if (manageImports && state.chosen == null) CsPanel(Modifier.fillMaxWidth()) {
            Text("Imported history", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Removing an import deletes the shows, episodes and movies it added. Anything you marked yourself in CineSays stays.",
                color = Cs.textSoft, fontSize = 13.sp)
            state.imports.forEach { (source, c) ->
                val name = if (source == Source.BINGEBOXD) "BingeBoxd" else "TV Time"
                val info: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        Text(name, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${c.shows} shows · ${c.episodes} episodes · ${c.movies} movies", color = Cs.textMuted, fontSize = 12.sp)
                    }
                }
                val actions: @Composable () -> Unit = {
                    if (confirmRemove == source) {
                        CsButton("Yes, remove $name", primary = true) { vm.removeImport(source); confirmRemove = null }
                        CsButton("Cancel") { confirmRemove = null }
                    } else CsButton("Remove $name import") { confirmRemove = source }
                }
                // Phones: buttons under the text, so the name and counts keep their full width.
                if (narrow) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info(Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    info(Modifier.weight(1f)); actions()
                }
            }
            if (state.imports.isEmpty()) Text("No imported history left on this device.", color = Cs.textMuted, fontSize = 13.sp)
            // Stronger options: every import at once, or a completely fresh tracker.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.imports.size > 1) {
                    if (confirmRemove == "all") {
                        CsButton("Yes, remove all imports", primary = true) { vm.removeImport("all"); confirmRemove = null }
                        CsButton("Cancel") { confirmRemove = null }
                    } else CsButton("Remove all imported history") { confirmRemove = "all" }
                }
                if (confirmRemove == "everything") {
                    CsButton("Yes, delete all tracker data", primary = true) { vm.removeImport("everything"); confirmRemove = null }
                    CsButton("Cancel") { confirmRemove = null }
                } else CsButton("Clear all tracker data") { confirmRemove = "everything" }
            }
            Text("Signed in? Removals are saved to your account, and your other devices apply them when they next sync.",
                color = Cs.textMuted, fontSize = 12.sp)
            if (confirmRemove == "everything") Text("This deletes every show, episode and movie in your tracker on all your devices, including ones you marked yourself.",
                color = Cs.warn, fontSize = 12.sp)
            else if (confirmRemove != null) Text("This can't be undone. You could import the ZIP again later.", color = Cs.warn, fontSize = 12.sp)
        }
        if(state.tab == "My Shows" && state.chosen == null) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                TrackButton(if(showSearch) "Close Search" else "⌕ Search TV Shows", active=showSearch) { showSearch=!showSearch; if(!showSearch) vm.searchShows("") }
                Text("Add series by searching TMDB or import your TV Time history.",color=trackerMuted,fontSize=12.sp)
            }
            if(showSearch) {
                BasicTextField(state.searchQuery, vm::searchShows, Modifier.fillMaxWidth()
                    .onFocusChanged { }.border(1.dp,trackerBlue,RoundedCornerShape(10.dp))
                    .background(trackerPanel,RoundedCornerShape(10.dp)).padding(12.dp),singleLine=true,
                    textStyle=androidx.compose.ui.text.TextStyle(color=trackerWhite,fontSize=18.sp),
                    decorationBox={inner-> Box {if(state.searchQuery.isBlank()) Text("Search series by name…",color=trackerMuted);inner()} })
                if(state.searching) LinearProgressIndicator(Modifier.fillMaxWidth(),color=trackerBlue)
                state.searchResults.chunked(3).forEach { group ->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        group.forEach { show ->
                            TrackButton("+ ${show.name}",modifier=Modifier.weight(1f)) { vm.followSearchResult(show) }
                        }
                        repeat(3-group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=trackerBlue)
        state.message?.let{Text(it,color=Color(0xFFFFD8A4),fontSize=12.sp)}
        if(state.matchStatus.isNotBlank()) Text(state.matchStatus,color=Color(0xFF90CCFF),fontSize=12.sp)
        state.preview?.let{preview->
            TrackPanel(Modifier.fillMaxWidth()){
                Text("Import preview",fontWeight=FontWeight.Bold,color=trackerWhite,fontSize=19.sp)
                Text("${preview.format} · ${preview.shows} shows · ${preview.watchedEpisodes} watched episodes · ${preview.watchedMovies} movies",color=trackerMuted)
                preview.warnings.forEach{Text(it,color=Color(0xFFFFCF88),fontSize=12.sp)}
                Text("Import merges with existing tracking data. Nothing is uploaded to Firebase.",color=trackerWhite,fontSize=12.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    TrackButton("Import tracking history",true){vm.confirmImport()}
                    TrackButton("Cancel"){vm.dismissPreview()}
                }
            }
        }
        state.chosen?.let{selected->
            TrackerSeriesDetail(selected,state,vm)
        } ?: when(state.tab){
            TRACKER_HOME_TAB -> UpNextPanel(state, upNext, today, vm,
                onImport = { picker.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream")) })
            "Calendar & Releases"->{
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TrackButton("⇧  Import tracking from TVTime ZIP"){picker.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    TrackPanel(Modifier.weight(1.35f)){
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("▣ Release Calendar",color=trackerWhite,fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            TrackButton("‹") { weekOffset-- }
                            Text("  ${monday.plusDays((weekOffset*7).toLong()).format(dateFmt)} – ${monday.plusDays((weekOffset*7+6).toLong()).format(dateFmt)}  ",color=trackerMuted,fontSize=11.sp)
                            TrackButton("›") { weekOffset++ }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            (0..6).forEach{n->
                                val d=monday.plusDays((weekOffset*7+n).toLong())
                                val count=state.releases.count{it.episode.airDate==d.toString()}
                                var focused by remember(d){mutableStateOf(false)}
                                Column(Modifier.weight(1f).onFocusChanged { focused=it.isFocused }
                                    .border(if(focused)2.dp else 1.dp,if(focused)Color.Cyan else if(d==selectedDate)trackerBlue else Color(0xFF36546E),RoundedCornerShape(10.dp))
                                    .background(if(d==selectedDate)Color(0xFF074A89) else Color(0xFF1B2D43),RoundedCornerShape(10.dp))
                                    .clickable { selectedDate=d }.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                    Text(d.dayOfWeek.name.take(3),fontSize=11.sp,color=trackerMuted)
                                    Text("${d.dayOfMonth}",fontWeight=FontWeight.Bold,color=trackerWhite,fontSize=20.sp)
                                    Text("$count",color=Color(0xFF89D7FF),fontSize=11.sp)
                                }
                            }
                        }
                    }
                    TrackPanel(Modifier.weight(1f)){
                        TrackerSectionTitle("⌁ Release Timeline · Next 14 Days")
                        val upcoming=state.releases.filter{runCatching{!LocalDate.parse(it.episode.airDate).isBefore(today)}.getOrDefault(false)}.take(5)
                        if(upcoming.isEmpty())Text("Match shows to TMDB and refresh to load their release dates.",color=trackerMuted,fontSize=12.sp)
                        else upcoming.forEach{TrackerReleaseLine(it)}
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    TrackPanel(Modifier.weight(1f)){
                        TrackerSectionTitle("☾ ${if(selectedDate==today) "Tonight" else selectedDate.format(dateFmt)}")
                        val list=state.releases.filter{it.episode.airDate==selectedDate.toString()}
                        if(list.isEmpty())TrackerEmpty("No confirmed releases for this date")
                        list.take(3).forEach{TrackerReleaseLine(it)}
                    }
                    TrackPanel(Modifier.weight(1f)){
                        TrackerSectionTitle("▣ This Week")
                        val list=state.releases.filter{runCatching{val d=LocalDate.parse(it.episode.airDate); !d.isBefore(today)&&d.isBefore(today.plusDays(7))}.getOrDefault(false)}
                        if(list.isEmpty())TrackerEmpty("No confirmed upcoming releases")
                        list.take(3).forEach{TrackerReleaseLine(it)}
                    }
                    TrackPanel(Modifier.weight(1f)){
                        TrackerSectionTitle("ⓘ Missed Episodes")
                        val missed=state.releases.filter { release ->
                            runCatching { LocalDate.parse(release.episode.airDate).isBefore(today) }.getOrDefault(false)
                            && !release.watched // precomputed off the main thread (was one DB query per release per frame)
                        }.take(3)
                        if(missed.isEmpty()) TrackerEmpty("No tracked episodes yet")
                        missed.forEach { release ->
                            TrackButton("${release.show} · S${release.episode.season} E${release.episode.number}",modifier=Modifier.fillMaxWidth()) {
                                state.shows.firstOrNull{it.title==release.show}?.let(vm::selectShow)
                            }
                        }
                    }
                    TrackPanel(Modifier.weight(.85f)){
                        TrackerSectionTitle("♨ Watch Streak")
                        Text("${state.stats.streak} Days",color=Color(0xFF80D0FF),fontWeight=FontWeight.Bold,fontSize=26.sp)
                        Text("Consecutive days watched, from history with valid dates",color=trackerMuted,fontSize=11.sp)
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth()){
                    TrackPanel(Modifier.weight(1f)){
                        TrackerSectionTitle("♧ Release Reminders")
                        Text("Remember my preference for upcoming episode alerts",color=trackerMuted,fontSize=12.sp)
                        Switch(checked=state.reminders,onCheckedChange={ enabled ->
                            if(enabled && android.os.Build.VERSION.SDK_INT>=33 &&
                                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else vm.setReminders(enabled)
                        })
                    }
                    TrackPanel(Modifier.weight(.55f)){
                        TrackerSectionTitle("Highlights")
                        Text("${state.stats.shows} followed shows · ${state.stats.watched} episodes watched",color=trackerMuted)
                        TrackButton("Refresh releases"){vm.loadReleases(force = true)}
                    }
                }
            }
            "My Shows"->{
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    TrackButton(if(addExpanded)"Close Add Show" else "+ Follow a show"){addExpanded=!addExpanded}
                    TrackButton("Match imported shows to TMDB"){vm.matchAllShows()}
                    TrackButton("Refresh releases"){vm.loadReleases(force = true)}
                }
                if(addExpanded) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    BasicTextField(addTitle,{addTitle=it},Modifier.weight(1f).background(trackerPanel,RoundedCornerShape(12.dp)).padding(15.dp),singleLine=true,
                        textStyle=androidx.compose.ui.text.TextStyle(color=trackerWhite,fontSize=17.sp),decorationBox={inner->Box{if(addTitle.isBlank()) Text("Show title",color=trackerMuted);inner()}})
                    TrackButton("Follow"){vm.addShow(addTitle);addTitle="";addExpanded=false}
                }
                // A–Z: pick a letter to jump to the series starting with it (# = digits/symbols).
                val letters = remember(state.shows) { state.shows.map { s -> s.title.trim().firstOrNull()?.uppercaseChar()?.let { if (it in 'A'..'Z') it else '#' } ?: '#' }.toSet() }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CsChip("All", letter == null, compact = true) { letter = null }
                    ("#" + ('A'..'Z').joinToString("")).forEach { ch ->
                        if (ch in letters) CsChip(ch.toString(), letter == ch, compact = true) { letter = ch }
                        else Text(ch.toString(), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = Cs.line, fontSize = 13.sp)
                    }
                }
                val shown = state.shows.filter { s -> letter == null || (s.title.trim().firstOrNull()?.uppercaseChar()?.let { if (it in 'A'..'Z') it else '#' } ?: '#') == letter }
                Text(if (letter == null) "${shown.size} series" else "${shown.size} series starting with $letter", color = Cs.textMuted, fontSize = 13.sp)
                val perRow = if (LocalCompact.current) 1 else 3
                shown.chunked(perRow).forEach{group->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    group.forEach{show->TrackerShowCard(show,Modifier.weight(1f)){vm.selectShow(show)}}
                    repeat(perRow-group.size){Spacer(Modifier.weight(1f))}
                }}
            }
            "Movies" -> {
                if (state.movies.isEmpty()) TrackerEmpty("No watched movies yet. Use “✓ Watched” on any movie's details, or import from TV Time / BingeBoxd.")
                else {
                    Text("${state.stats.movies} movies watched · newest first", color = Cs.textMuted, fontSize = 13.sp)
                    CsPosterGrid(state.movies.map { m -> TitleItem(id = m.tmdbId, title = m.title, poster = m.poster, mediaType = "movie", releaseDate = m.watchedAt.take(10)) }
                        .filter { it.id > 0 } , posterWidth = 120.dp) { openTitle("movie", it.id) }
                    val unmatched = state.movies.count { it.tmdbId == 0 }
                    if (unmatched > 0) Text("$unmatched imported movies have no TMDB match yet and aren't shown.", color = Cs.textMuted, fontSize = 12.sp)
                }
                Unit
            }
            "Add shows" -> AddShowsPanel(state, vm)
            "Release Timeline"->{
                TrackPanel(Modifier.fillMaxWidth()){
                    TrackerSectionTitle("Coming up on your followed series")
                    TrackButton("Refresh release information"){vm.loadReleases(force = true)}
                    if(state.releases.isEmpty())TrackerEmpty("No releases loaded. Match your shows to TMDB under My Shows.")
                    state.releases.forEach{TrackerReleaseLine(it)}
                }
            }
            "Reminders"->{
                TrackPanel(Modifier.fillMaxWidth()){
                    TrackerSectionTitle("Release reminders")
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("Enable reminder preference",color=trackerWhite,modifier=Modifier.weight(1f))
                        Switch(state.reminders,onCheckedChange={enabled ->
                            if(enabled && android.os.Build.VERSION.SDK_INT>=33 &&
                                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else vm.setReminders(enabled)
                        })
                    }
                    Text("Best-effort release notifications run about twice daily when connected. Air dates are supplied by TMDB and may vary by region.",color=trackerMuted)
                }
            }
            else -> Unit // Keep the Elvis/when expression exhaustive.
        }
    }
}
@Composable private fun TrackerNumber(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 14.dp)) {
        Text(value, color = Cs.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(" $label", color = Cs.textMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** Height of the scrolling Continue watching / Coming soon boxes (about four cards). */
private val UP_NEXT_BOX_HEIGHT = 440.dp

/** Up Next: week strip, one card per show with one-press "Watched", and Coming soon. */
@Composable private fun UpNextPanel(state: TrackerUiState, upNext: List<UpNextEntry>, today: LocalDate, vm: TrackerViewModel, onImport: () -> Unit) {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val narrow = LocalCompact.current
    Row(horizontalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 8.dp)) {
        (0..6).forEach { n ->
            val d = monday.plusDays(n.toLong())
            val count = state.releases.count { it.episode.airDate == d.toString() }
            val isToday = d == today
            // Phones: tighter cells, centred, and just the release count, so nothing wraps.
            Column(Modifier.weight(1f).border(1.dp, if (isToday) Cs.accent else Cs.line, RoundedCornerShape(12.dp))
                .background(if (isToday) Color(0xFF0A3A6E) else Cs.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = if (narrow) 2.dp else 12.dp, vertical = if (narrow) 8.dp else 10.dp),
                horizontalAlignment = if (narrow) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, color = Cs.textMuted, fontSize = if (narrow) 11.sp else 12.sp, maxLines = 1)
                Text("${d.dayOfMonth}", color = Cs.text, fontSize = if (narrow) 17.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (count == 0) "—" else if (narrow) "$count" else "$count release${if (count > 1) "s" else ""}", color = Cs.accent, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
    val compact = LocalCompact.current
    CsTwoPane(gap = 22.dp, leftWeight = 1.6f, left = { paneMod ->
        Column(paneMod, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CsSectionTitle("Continue watching")
            if (state.loading && upNext.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
            if (!state.loading && upNext.isEmpty()) {
                Text(if (state.shows.none { it.followed }) "You're not following any shows yet. Pick the shows you watch, or import your history from TV Time or BingeBoxd."
                     else "You're all caught up.", color = Cs.textSoft, fontSize = 15.sp)
                if (state.shows.none { it.followed }) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CsButton("Build my tracker", primary = true) { vm.selectTab("Add shows") }
                    CsButton("Import") { onImport() }
                }
            }
            if (upNext.isNotEmpty()) Text("${upNext.size} shows: new episodes first, then shows you're partway through", color = Cs.textMuted, fontSize = 12.sp)
            // A fixed-height box that scrolls on its own, so a long list doesn't stretch the whole page.
            // With a remote, moving down through the cards scrolls the box; the page stays put.
            if (upNext.isNotEmpty()) Column(Modifier.fillMaxWidth().height(UP_NEXT_BOX_HEIGHT)
                .border(1.dp, Cs.line, RoundedCornerShape(16.dp)).background(Cs.panel, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
            upNext.forEach { e ->
                Row(Modifier.fillMaxWidth().border(1.dp, Cs.line, RoundedCornerShape(14.dp)).background(Cs.surface, RoundedCornerShape(14.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(e.episode.still?.let { "https://image.tmdb.org/t/p/w300$it" } ?: e.poster?.let { "https://image.tmdb.org/t/p/w185$it" },
                        null, Modifier.size(if (compact) 88.dp else 120.dp, if (compact) 50.dp else 68.dp).clip(RoundedCornerShape(9.dp)).background(Cs.surfaceHigh), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.show, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("S${e.episode.season} E${e.episode.number} · ${e.episode.name}", color = Cs.textSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.detail, color = Cs.textMuted, fontSize = 12.sp)
                        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CsChip("✓ Watched", false, compact = true) { vm.mark(e.show, e.episode.season, e.episode.number, true) }
                            CsChip("Open", false, compact = true) { state.shows.firstOrNull { it.title == e.show }?.let(vm::selectShow) }
                        }
                    }
                    if (!compact) {
                        CsButton("✓ Watched", primary = true) { vm.mark(e.show, e.episode.season, e.episode.number, true) }
                        CsButton("Open") { state.shows.firstOrNull { it.title == e.show }?.let(vm::selectShow) }
                    }
                }
            }
            }
        }
    }, right = { paneMod ->
        Column(paneMod, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CsSectionTitle("Coming soon")
            val soon = state.releases.filter { r -> runCatching { LocalDate.parse(r.episode.airDate).isAfter(today) }.getOrDefault(false) }.take(20)
            if (upNext.isNotEmpty() && !compact) Text("${soon.size} announced", color = Cs.textMuted, fontSize = 12.sp)
            // Same height as Continue watching, so both columns line up.
            Column(Modifier.fillMaxWidth().then(if (compact) Modifier.heightIn(max = UP_NEXT_BOX_HEIGHT) else Modifier.height(UP_NEXT_BOX_HEIGHT))
                .border(1.dp, Cs.line, RoundedCornerShape(16.dp)).background(Cs.panel, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (soon.isEmpty()) Text("No announced episodes in the next 5 weeks.", color = Cs.textMuted, fontSize = 13.sp)
                soon.forEach { r ->
                    val d = LocalDate.parse(r.episode.airDate)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.month.name.take(3), color = Cs.textMuted, fontSize = 11.sp)
                            Text("${d.dayOfMonth}", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Column {
                            Text(r.show, color = Cs.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("S${r.episode.season} E${r.episode.number}" + if (r.episode.number == 1) " · premiere" else "", color = Cs.textMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            val unmatched = state.shows.count { it.followed && it.tmdbId == 0 }
            if (unmatched > 0) Row(Modifier.fillMaxWidth().border(1.dp, Cs.lineStrong, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$unmatched imported show${if (unmatched > 1) "s" else ""} still need a TMDB match.", Modifier.weight(1f), color = Cs.textSoft, fontSize = 13.sp)
                CsChip("Match now", false) { vm.matchAllShows() }
            }
        }
    })
}

@Composable private fun TrackerStat(label:String,value:String){Column{Text(label,color=trackerMuted,fontSize=12.sp);Text(value,color=trackerWhite,fontSize=19.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun TrackerSectionTitle(title:String){Text(title,color=trackerWhite,fontSize=17.sp,fontWeight=FontWeight.Bold)}
@Composable private fun TrackerEmpty(label:String){Text(label,color=trackerMuted,fontSize=12.sp)}
@Composable private fun TrackerReleaseLine(release:TrackerRelease){
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        AsyncImage(model=release.poster?.let{"https://image.tmdb.org/t/p/w185$it"},contentDescription=null,modifier=Modifier.size(43.dp,54.dp).clip(RoundedCornerShape(6.dp)),contentScale=ContentScale.Crop)
        Column {Text(release.show,color=trackerWhite,fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("S${release.episode.season} E${release.episode.number} · ${release.episode.name}",color=trackerMuted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(release.episode.airDate.orEmpty(),color=Color(0xFF73C7FF),fontSize=10.sp)}
    }
}
@Composable private fun TrackerShowCard(show:TrackedShow, modifier:Modifier=Modifier,onClick:()->Unit){
    var focused by remember{mutableStateOf(false)}
    Row(modifier.onFocusChanged { focused=it.isFocused }.border(if(focused)2.dp else 1.dp,if(focused)Color.Cyan else Color(0xFF385674),RoundedCornerShape(13.dp))
        .background(trackerPanel,RoundedCornerShape(13.dp)).clickable{onClick()}.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        AsyncImage(show.poster?.let{"https://image.tmdb.org/t/p/w185$it"},contentDescription=show.title,modifier=Modifier.size(55.dp,79.dp).clip(RoundedCornerShape(6.dp)),contentScale=ContentScale.Crop)
        Column {
            Text(show.title,color=trackerWhite,fontWeight=FontWeight.Bold,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("${show.watchedCount} episodes watched",color=Color(0xFF85CBFF),fontSize=12.sp)
            Text(if(show.followed) "Following" else "Archived / Unfollowed",color=trackerMuted,fontSize=11.sp)
            if(show.tmdbId==0)Text("TMDB match needed",color=Color(0xFFFFCD86),fontSize=11.sp)
        }
    }
}
@Composable private fun TrackerSeriesDetail(show:TrackedShow,state:TrackerUiState,vm:TrackerViewModel){
    val watched=state.chosenWatched // loaded on IO by the view model
    TrackPanel(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            TrackButton("← Back to tracker"){vm.closeShow()}
            Column(Modifier.weight(1f)){
                Text(show.title,color=trackerWhite,fontWeight=FontWeight.Bold,fontSize=26.sp)
                Text("${show.watchedCount} watched episodes · ${if(show.followed)"Following" else "Not following"}",color=trackerMuted)
            }
            TrackButton(if(show.followed)"Unfollow" else "Follow"){vm.follow(show,!show.followed)}
            if(show.tmdbId==0)TrackButton("Match with TMDB",true){vm.resolveShow(show)}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            TrackButton("◀ Season"){vm.selectSeason((state.season-1).coerceAtLeast(0))}
            Text("Season ${state.season}",color=trackerWhite,fontWeight=FontWeight.Bold)
            TrackButton("Season ▶"){vm.selectSeason(state.season+1)}
            if(state.episodes.isNotEmpty())TrackButton("Mark season watched"){
                vm.markPrevious(show.title,state.episodes,state.episodes.maxOfOrNull{it.number} ?: 0)
            }
        }
        if(show.tmdbId==0) {
            Text("Match this imported series to its TMDB listing. Watch history will be preserved.",color=trackerMuted)
            TrackButton("Find matching TV shows") {vm.searchShows(show.title)}
            state.searchResults.take(6).forEach { match ->
                TrackButton("Use: ${match.name} (${match.firstAirDate.orEmpty().take(4)})", modifier=Modifier.fillMaxWidth()) {
                    vm.chooseMatch(show,match)
                }
            }
        }
        else if(state.episodes.isEmpty()) TrackerEmpty("No episode guide available for this season")
        state.episodes.chunked(4).forEach{group-> Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            group.forEach{ep->
                val done=(ep.season to ep.number) in watched
                var focused by remember(show.title,ep.season,ep.number){mutableStateOf(false)}
                Column(Modifier.weight(1f).onFocusChanged{ focused=it.isFocused }
                    .border(if(focused)3.dp else 1.dp,if(focused)Color.Cyan else if(done)Color(0xFF2CA876) else Color(0xFF416587),RoundedCornerShape(10.dp))
                    .background(Color(0xFF10273B),RoundedCornerShape(10.dp)).clickable{vm.mark(show.title,ep.season,ep.number,!done)}.padding(9.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    AsyncImage(model=ep.still?.let{"https://image.tmdb.org/t/p/w300$it"},contentDescription=null,modifier=Modifier.fillMaxWidth().height(75.dp).clip(RoundedCornerShape(7.dp)),contentScale=ContentScale.Crop)
                    Text("E${ep.number} · ${ep.name}",color=trackerWhite,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(ep.airDate.orEmpty(),color=trackerMuted,fontSize=10.sp)
                    Text(if(done)"✓ Watched" else "○ Mark watched",color=if(done)Color(0xFF72E8AE) else Color(0xFF8BC8FF),fontSize=12.sp)
                }
            }
            repeat(4-group.size){Spacer(Modifier.weight(1f))}
        }}
    }
}
