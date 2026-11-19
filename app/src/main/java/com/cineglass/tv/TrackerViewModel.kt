package com.cineglass.tv

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.format.DateTimeFormatter


/** [watched] is resolved on the IO thread when releases load, so the UI never queries SQLite during composition. */
data class TrackerRelease(val show: String, val episode: TrackerEpisode, val poster: String?, val watched: Boolean = false)
/** Tracker opens on Up Next; Back from any other Tracker tab returns here. */
const val TRACKER_HOME_TAB = "Up Next"

/** One card per show: the earliest aired episode not yet watched, or tonight's premiere. */
data class UpNextEntry(val show: String, val episode: TrackerEpisode, val poster: String?, val badge: String, val detail: String, val behind: Int)

/** Builds the "Up next" queue from loaded releases (TMDB window: ~30 days back, 35 ahead). */
fun upNextFrom(releases: List<TrackerRelease>, today: LocalDate = LocalDate.now()): List<UpNextEntry> =
    releases.groupBy { it.show }.mapNotNull { (show, list) ->
        fun date(r: TrackerRelease) = runCatching { LocalDate.parse(r.episode.airDate) }.getOrNull()
        val pending = list.filter { !it.watched && date(it)?.let { d -> !d.isAfter(today) } == true }
            .sortedWith(compareBy({ it.episode.season }, { it.episode.number }))
        val first = pending.firstOrNull() ?: return@mapNotNull null
        val d = date(first)!!
        val badge = when {
            d == today -> "TONIGHT"
            pending.size > 1 -> "${pending.size} TO WATCH"
            d.isAfter(today.minusDays(3)) -> "NEW"
            else -> "NEXT"
        }
        val detail = when {
            d == today -> "Airs today"
            d == today.minusDays(1) -> "Aired yesterday"
            else -> "Aired ${java.time.temporal.ChronoUnit.DAYS.between(d, today)} days ago"
        } + if (pending.size > 1) " · ${pending.size - 1} more after this" else ""
        UpNextEntry(show, first.episode, first.poster, badge, detail, pending.size)
    }.sortedWith(compareByDescending<UpNextEntry> { it.badge == "TONIGHT" }.thenBy { it.episode.airDate })

data class TrackerUiState(
    val shows: List<TrackedShow> = emptyList(),
    val watched: List<WatchedRecord> = emptyList(),
    val stats: TrackerStats = TrackerStats(0,0,0),
    val releases: List<TrackerRelease> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<TrackerTmdbShow> = emptyList(),
    val searching: Boolean = false,
    val chosen: TrackedShow? = null,
    val season: Int = 1,
    val episodes: List<TrackerEpisode> = emptyList(),
    val chosenWatched: Set<Pair<Int,Int>> = emptySet(),
    val preview: TrackerImportPreview? = null,
    val message: String? = null,
    val loading: Boolean = false,
    val reminders: Boolean = false,
    val tab: String = TRACKER_HOME_TAB,
    val matchStatus: String = "",
    /** In-progress shows outside the release window: next unwatched episode per show. */
    val continueWatching: List<UpNextEntry> = emptyList(),
    val movies: List<WatchedMovie> = emptyList(),
    val watchedMovieIds: Set<Int> = emptySet(),
    /** "Build your tracker": popular shows to answer Watching / Watched / Not for me. */
    val picker: List<TitleItem> = emptyList(),
    val pickerBusy: Boolean = false,
    /** Source.TVTIME / Source.BINGEBOXD -> what that import added (empty imports are omitted). */
    val imports: Map<String, ImportCounts> = emptyMap(),
    /** Bumped after imports/removals so the app pushes the change to the cloud right away. */
    val dataVersion: Int = 0
)

/** Up Next = recent releases you haven't watched, then shows you're partway through. One card per show. */
fun mergeUpNext(fromReleases: List<UpNextEntry>, continuing: List<UpNextEntry>): List<UpNextEntry> {
    val seen = fromReleases.map { it.show }.toMutableSet()
    return fromReleases + continuing.filter { seen.add(it.show) }
}
class TrackerViewModel(app: Application): AndroidViewModel(app) {
    private val store = TrackerStore(app.applicationContext)
    private val importer = TVTimeImporter(app.applicationContext)
    private val _ui = MutableStateFlow(TrackerUiState())
    val ui: StateFlow<TrackerUiState> = _ui
    private val key = BuildConfig.TMDB_API_KEY
    private var releasesLoadedAt = 0L
    private var releasesJob: kotlinx.coroutines.Job? = null
    init {
        refresh()
        TrackerReminderWorker.schedule(app, store.preference("reminders") == "1")
    }
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list=store.allShows()
            val stats=store.stats()
            val records=store.watchedRecords(30)
            val movies=store.watchedMovies()
            val movieIds=store.watchedMovieIds()
            val imports=listOf(Source.TVTIME, Source.BINGEBOXD).associateWith { store.importCounts(it) }.filterValues { !it.isEmpty }
            withContext(Dispatchers.Main) { _ui.value=_ui.value.copy(shows=list,stats=stats,watched=records,movies=movies,watchedMovieIds=movieIds,imports=imports,reminders=store.preference("reminders")=="1") }
        }
    }
    fun selectTab(tab:String) { _ui.value=_ui.value.copy(tab=tab) }
    fun searchShows(query: String) {
        _ui.value = _ui.value.copy(searchQuery=query, searchResults=emptyList(), searching=query.trim().length >= 2)
        if(query.trim().length < 2) return
        viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            if(_ui.value.searchQuery != query) return@launch
            try {
                val matches = withContext(Dispatchers.IO) { Api.tmdb.searchTv(key,query.trim()).results.take(12) }
                if(_ui.value.searchQuery == query) _ui.value = _ui.value.copy(searchResults=matches,searching=false)
            } catch(e:Exception) {
                if(_ui.value.searchQuery == query) _ui.value = _ui.value.copy(searching=false,message="Search failed: ${e.localizedMessage}")
            }
        }
    }
    fun followTmdb(item: TitleItem) {
        if(item.kind != "tv") return
        viewModelScope.launch(Dispatchers.IO) {
            store.followTmdb(item)
            withContext(Dispatchers.Main) { refresh(); loadReleases(force = true); _ui.value=_ui.value.copy(message="Added ${item.label} to Tracker") }
        }
    }
    fun followSearchResult(item: TrackerTmdbShow) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setFollowed(item.name,true)
            store.setTmdb(item.name,item.id,item.poster)
            withContext(Dispatchers.Main) {
                _ui.value=_ui.value.copy(searchQuery="",searchResults=emptyList(),message="Following ${item.name}")
                refresh()
                loadReleases(force = true)
            }
        }
    }
    fun isFollowingTmdb(id:Int):Boolean = ui.value.shows.any { it.tmdbId==id && it.followed }
    fun markPrevious(title: String, episodes: List<TrackerEpisode>, upTo: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            store.markBatch(title,episodes.filter{it.number in 1..upTo},true)
            withContext(Dispatchers.Main) { refresh(); _ui.value.chosen?.let { loadChosenWatched(it.title) } }
        }
    }
    fun resetSelected(){ closeShow(); selectTab("Calendar & Releases") }

    fun setReminders(value:Boolean) {
        store.setPreference("reminders",if(value) "1" else "0")
        TrackerReminderWorker.schedule(getApplication(), value)
        _ui.value=_ui.value.copy(reminders=value,message=if(value) "Release checks scheduled. Enable TV notifications to receive alerts." else "Reminders disabled")
    }
    fun prepare(uri: Uri) {
        viewModelScope.launch {
            _ui.value=_ui.value.copy(loading=true,message=null,preview=null)
            try {
                val result=withContext(Dispatchers.IO) { importer.inspect(uri) }
                _ui.value=_ui.value.copy(preview=result,loading=false)
            } catch(e:Exception) { _ui.value=_ui.value.copy(message="Import preview failed: ${e.message}",loading=false) }
        }
    }
    fun confirmImport() {
        viewModelScope.launch {
            _ui.value=_ui.value.copy(loading=true)
            try {
                val result=withContext(Dispatchers.IO) { importer.commit(store) { p -> _ui.value=_ui.value.copy(matchStatus=p) } }
                _ui.value=_ui.value.copy(preview=null,loading=false,matchStatus="",
                    message="Imported ${result.shows} shows, ${result.watchedEpisodes} watched episodes and ${result.watchedMovies} movies" +
                        result.warnings.joinToString("") { ". $it" })
                _ui.value=_ui.value.copy(dataVersion=_ui.value.dataVersion+1)
                refresh()
                loadReleases(force = true)
            } catch(e:Exception) { _ui.value=_ui.value.copy(loading=false,matchStatus="",message="Import failed: ${e.message}") }
        }
    }
    fun dismissPreview() { _ui.value=_ui.value.copy(preview=null) }

    /**
     * Removes an import. [source]: Source.TVTIME / Source.BINGEBOXD; "all" removes every import;
     * "everything" clears all tracker data. The removal is recorded so other devices apply it on sync.
     */
    fun removeImport(source: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            withContext(Dispatchers.IO) {
                when (source) { "all" -> store.removeAllImports(); "everything" -> store.clearAll(); else -> store.removeImport(source) }
            }
            _ui.value = _ui.value.copy(loading = false, chosen = null, continueWatching = emptyList(), releases = emptyList(),
                dataVersion = _ui.value.dataVersion + 1,
                message = when (source) {
                    "all" -> "Removed all imported history"; "everything" -> "Cleared all tracker data"
                    Source.BINGEBOXD -> "Removed your BingeBoxd import"; else -> "Removed your TV Time import"
                } + ". Other devices will follow on their next sync.")
            refresh()
            loadReleases(force = true)
        }
    }

    // ── Movies ──
    fun setMovieWatched(item: TitleItem, watched: Boolean) {
        if (item.kind != "movie") return
        viewModelScope.launch(Dispatchers.IO) {
            if (watched) store.addMovie(item.label, LocalDate.now().toString(), item.id, item.poster) else store.removeMovie(item.label, item.id)
            withContext(Dispatchers.Main) { refresh(); _ui.value = _ui.value.copy(message = if (watched) "Marked ${item.label} as watched" else "Removed ${item.label} from watched movies") }
        }
    }

    /**
     * In-app playback crossed the completion threshold: record it once. Never called for titles opened in
     * another app. A series not yet in the Tracker is followed first so the episode has somewhere to go.
     */
    fun recordPlaybackCompleted(r: PlaybackRecord) {
        if (r.tmdbId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val today = LocalDate.now().toString()
            val msg = when (r.type) {
                "movie" -> if (r.tmdbId in store.watchedMovieIds()) null
                    else { store.addMovie(r.title, today, r.tmdbId, r.poster); "Marked ${r.title} as watched" }
                "episode" -> {
                    val s = r.season ?: return@launch; val e = r.episode ?: return@launch
                    val title = store.showTitleByTmdb(r.tmdbId) ?: run {
                        store.followTmdb(TitleItem(id = r.tmdbId, name = r.title, mediaType = "tv", poster = r.poster))
                        store.showTitleByTmdb(r.tmdbId) ?: r.title
                    }
                    if (store.isWatched(title, s, e)) null else { store.mark(title, s, e, true, today); "Marked ${r.title} S$s E$e as watched" }
                }
                else -> null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                refresh(); loadReleases(force = true)
                _ui.value = _ui.value.copy(message = msg, dataVersion = _ui.value.dataVersion + 1)
            }
        }
    }

    /** Watched episodes for a series, for Watch Now's episode list. */
    suspend fun watchedFor(tmdbId: Int): Set<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        store.showTitleByTmdb(tmdbId)?.let { store.allWatchedFor(it) }.orEmpty()
    }

    /** Watch Now › Mark watched on one episode (follows the series first if needed). */
    fun markEpisodeByTmdb(tmdbId: Int, showTitle: String, poster: String?, season: Int, episode: Int, watched: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = store.showTitleByTmdb(tmdbId) ?: run {
                store.followTmdb(TitleItem(id = tmdbId, name = showTitle, mediaType = "tv", poster = poster))
                store.showTitleByTmdb(tmdbId) ?: showTitle
            }
            withContext(Dispatchers.Main) { mark(title, season, episode, watched) }
        }
    }

    // ── Build your tracker (no import needed) ──
    private var pickerPage = 0
    private val pickerSkipped get() = store.preference("picker_skipped").orEmpty().split(",").mapNotNull { it.toIntOrNull() }.toSet()
    /** Loads popular series not already in the tracker or skipped before. */
    fun loadPicker(more: Boolean = false) {
        if (key.isBlank() || _ui.value.pickerBusy) return
        if (!more && _ui.value.picker.isNotEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(pickerBusy = true)
            val known = _ui.value.shows.map { it.tmdbId }.toSet() + pickerSkipped
            val found = mutableListOf<TitleItem>()
            var attempts = 0
            while (found.size < 12 && attempts < 4) {
                pickerPage++; attempts++
                val page = runCatching { withContext(Dispatchers.IO) { Api.tmdb.discover("tv", key, "US", sort = "popularity.desc", page = pickerPage,
                    extra = mapOf("vote_count.gte" to "200")) } }.getOrNull()?.results.orEmpty()
                found += page.map { it.copy(mediaType = "tv") }.filter { it.poster != null && it.id !in known && found.none { f -> f.id == it.id } }
            }
            _ui.value = _ui.value.copy(picker = (if (more) _ui.value.picker else emptyList()) + found, pickerBusy = false)
        }
    }
    private fun dropFromPicker(id: Int) {
        _ui.value = _ui.value.copy(picker = _ui.value.picker.filterNot { it.id == id })
        if (_ui.value.picker.size < 4) loadPicker(more = true)
    }
    /** answer: "watching" (follow), "watched" (follow, archive, mark every aired episode), "skip". */
    fun answerPicker(item: TitleItem, answer: String) {
        dropFromPicker(item.id)
        viewModelScope.launch(Dispatchers.IO) {
            when (answer) {
                "watching" -> store.followTmdb(item)
                "watched" -> {
                    store.followTmdb(item)
                    store.setArchived(item.label, true)
                    val tv = runCatching { Api.tmdb.trackerTv(item.id, key) }.getOrNull()
                    val today = LocalDate.now()
                    for (s in 1..(tv?.seasons ?: 0)) {
                        val eps = runCatching { Api.tmdb.trackerSeason(item.id, s, key).episodes }.getOrNull().orEmpty()
                            .filter { e -> e.airDate?.let { runCatching { !LocalDate.parse(it).isAfter(today) }.getOrDefault(false) } ?: false }
                        store.markBatch(item.label, eps, true)
                    }
                }
                else -> store.setPreference("picker_skipped", (pickerSkipped + item.id).joinToString(","))
            }
            withContext(Dispatchers.Main) {
                refresh()
                if (answer != "skip") { loadReleases(force = true); _ui.value = _ui.value.copy(message = if (answer == "watched") "Marked ${item.label} as watched" else "Following ${item.label}") }
            }
        }
    }

    // ── Backup / restore (Account sync) ──
    fun exportData(): String = store.exportJson()
    suspend fun importData(json: String) {
        withContext(Dispatchers.IO) { store.importJson(json) }
        _ui.value = _ui.value.copy(continueWatching = emptyList())
        refresh(); loadReleases(force = true)
    }
    fun addShow(title:String) {
        if(title.isBlank())return
        viewModelScope.launch(Dispatchers.IO) { store.setFollowed(title,true);withContext(Dispatchers.Main){refresh()} }
    }
    fun follow(show:TrackedShow, follow:Boolean) {
        viewModelScope.launch(Dispatchers.IO){store.setFollowed(show.title,follow);withContext(Dispatchers.Main){refresh()} }
    }
    fun mark(title:String,season:Int,number:Int,watched:Boolean) {
        viewModelScope.launch(Dispatchers.IO){
            store.mark(title,season,number,watched,if(watched) LocalDate.now().toString() else "")
            val nowWatched = store.allWatchedFor(title)
            withContext(Dispatchers.Main){
                // Update the tile and release flags in place; no need to re-download the season.
                if(_ui.value.chosen?.title==title) _ui.value=_ui.value.copy(chosenWatched=nowWatched)
                _ui.value=_ui.value.copy(releases=_ui.value.releases.map { r ->
                    if(r.show==title && r.episode.season==season && r.episode.number==number) r.copy(watched=watched) else r },
                    continueWatching=_ui.value.continueWatching.filterNot { c -> watched && c.show==title && c.episode.season==season && c.episode.number==number })
                refresh()
            }
        }
    }
    private fun loadChosenWatched(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val set = store.allWatchedFor(title)
            withContext(Dispatchers.Main) { if(_ui.value.chosen?.title==title) _ui.value=_ui.value.copy(chosenWatched=set) }
        }
    }
    fun selectShow(show:TrackedShow) {
        _ui.value=_ui.value.copy(chosen=show,episodes=emptyList(),chosenWatched=emptySet(),season=if(show.lastSeason>0)show.lastSeason else 1)
        selectSeason(if(show.lastSeason>0)show.lastSeason else 1)
    }
    fun closeShow(){_ui.value=_ui.value.copy(chosen=null,episodes=emptyList(),chosenWatched=emptySet())}
    fun selectSeason(number:Int){
        _ui.value=_ui.value.copy(season=number,episodes=emptyList())
        val show=_ui.value.chosen ?: return
        loadChosenWatched(show.title)
        if(show.tmdbId==0){_ui.value=_ui.value.copy(message="Match this series to TMDB first to load its episode guide");return}
        viewModelScope.launch {
            try {
                val list=withContext(Dispatchers.IO){Api.tmdb.trackerSeason(show.tmdbId,number,key).episodes}
                _ui.value=_ui.value.copy(episodes=list)
            }catch(e:Exception){_ui.value=_ui.value.copy(message="Could not load episodes: ${e.message}")}
        }
    }

    fun chooseMatch(show: TrackedShow, candidate: TrackerTmdbShow) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setTmdb(show.title,candidate.id,candidate.poster)
            withContext(Dispatchers.Main) {
                _ui.value = _ui.value.copy(chosen=show.copy(tmdbId=candidate.id,poster=candidate.poster),
                    searchResults=emptyList(),searchQuery="",message="Matched ${show.title} to ${candidate.name}")
                refresh()
                selectSeason(_ui.value.season)
            }
        }
    }
    fun watchedFor(title:String):Set<Pair<Int,Int>> = store.allWatchedFor(title)
    fun resolveShow(show:TrackedShow) {
        if(key.isBlank())return
        viewModelScope.launch {
            _ui.value=_ui.value.copy(loading=true)
            try {
                val candidates=withContext(Dispatchers.IO){Api.tmdb.searchTv(key,show.title).results}
                val exact=candidates.firstOrNull{it.name.equals(show.title,true)}
                if(exact==null) {
                    _ui.value=_ui.value.copy(message="No exact TMDB match for ${show.title}. Manual matching is required.",loading=false)
                } else {
                    withContext(Dispatchers.IO){store.setTmdb(show.title,exact.id,exact.poster)}
                    _ui.value=_ui.value.copy(loading=false,message="Matched ${show.title} to TMDB")
                    refresh()
                    _ui.value.chosen?.takeIf{it.title==show.title}?.let { current ->
                        _ui.value=_ui.value.copy(chosen=current.copy(tmdbId=exact.id,poster=exact.poster))
                        selectSeason(_ui.value.season)
                    }
                }
            } catch(e:Exception){_ui.value=_ui.value.copy(loading=false,message="TMDB matching error: ${e.message}")}
        }
    }

    fun matchAllShows() {
        if (key.isBlank()) {
            _ui.value = _ui.value.copy(message="TMDB_API_KEY is missing")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading=true, message=null, matchStatus="Matching shows to TMDB…")
            val unmatched = withContext(Dispatchers.IO) { store.allShows().filter { it.tmdbId == 0 } }
            var matched = 0
            var completed = 0
            val semaphore = Semaphore(4)
            unmatched.map { show -> async(Dispatchers.IO) {
                semaphore.withPermit {
                    val exact = try {
                        Api.tmdb.searchTv(key,show.title).results.firstOrNull { it.name.equals(show.title,true) }
                    } catch (_: Exception) { null }
                    if(exact != null) {
                        store.setTmdb(show.title,exact.id,exact.poster)
                        synchronized(this@TrackerViewModel) { matched++ }
                    }
                    synchronized(this@TrackerViewModel) { completed++ }
                    withContext(Dispatchers.Main) { _ui.value = _ui.value.copy(matchStatus="Matched $matched / $completed checked (${unmatched.size} total)") }
                }
            } }.awaitAll()
            _ui.value = _ui.value.copy(loading=false,matchStatus="Matched $matched of ${unmatched.size} shows. Unresolved shows need manual review.")
            refresh()
            loadReleases(force = true)
        }
    }
    /**
     * Release data changes slowly, so opening the Tracker tab reuses results for 30 minutes
     * (previously up to ~90 TMDB requests on every visit). Buttons pass force = true.
     */
    fun loadReleases(force: Boolean = false) {
        if(key.isBlank()) return
        if(!force && System.currentTimeMillis()-releasesLoadedAt < 30*60_000L) return
        if(releasesJob?.isActive == true && !force) return
        releasesJob?.cancel()
        releasesJob = viewModelScope.launch {
            _ui.value=_ui.value.copy(loading=true)
            // Read the shows from the DB, not _ui: on first open refresh() may not have finished yet.
            // Every followed, matched show (this used to stop at the first 30 alphabetically, so most
            // of a large imported library was never checked). One light call per show; whole seasons
            // are only fetched for shows whose last/next episode falls inside the window.
            val candidates=withContext(Dispatchers.IO){store.allShows()}.filter{it.followed && !it.archived && it.tmdbId!=0}
            val semaphore=Semaphore(8)
            val windowStart = LocalDate.now().minusDays(30); val windowEnd = LocalDate.now().plusDays(35)
            fun inWindow(date: String?) = date?.let { runCatching { val d = LocalDate.parse(it); !d.isBefore(windowStart) && d.isBefore(windowEnd) }.getOrDefault(false) } ?: false
            val perShow=candidates.map { show -> async(Dispatchers.IO){
                semaphore.withPermit {
                    try {
                        val tv=Api.tmdb.trackerTv(show.tmdbId,key)
                        val episodes = mutableListOf<TrackerEpisode>()
                        listOfNotNull(tv.next,tv.last).forEach { episodes.add(it) }
                        val seasonNumbers = listOfNotNull(tv.next?.takeIf { inWindow(it.airDate) }?.season, tv.last?.takeIf { inWindow(it.airDate) }?.season)
                            .distinct().filter { it >= 0 }
                        for (season in seasonNumbers) {
                            try { episodes.addAll(Api.tmdb.trackerSeason(show.tmdbId, season, key).episodes) }
                            catch (_: Exception) { /* A show may have season metadata without episodes. */ }
                        }
                        val releases = episodes.distinctBy { it.season to it.number }
                            .filter { ep -> ep.airDate?.let { date ->
                                runCatching { val d=LocalDate.parse(date); !d.isBefore(LocalDate.now().minusDays(30)) && d.isBefore(LocalDate.now().plusDays(35)) }.getOrDefault(false)
                            } ?: false }
                            .map { TrackerRelease(show.title,it,show.poster,store.isWatched(show.title,it.season,it.number)) }
                        // Started but behind the latest aired episode → a "Continue watching" candidate.
                        val last = tv.last
                        val behind = if (show.watchedCount > 0 && last != null && last.season > 0 &&
                            last.season * 10000 + last.number > show.lastSeason * 10000 + show.lastEpisode) show to last else null
                        releases to behind
                    } catch(_:Exception){ emptyList<TrackerRelease>() to null }
                }
            } }.awaitAll()
            val result = perShow.flatMap { it.first }.filter{!it.episode.airDate.isNullOrBlank()}.sortedBy{it.episode.airDate}
            releasesLoadedAt=System.currentTimeMillis()
            _ui.value=_ui.value.copy(releases=result,loading=false)
            _ui.value=_ui.value.copy(continueWatching = continueWatchingFor(perShow.mapNotNull { it.second }))
        }
    }
    /**
     * Next unwatched episode for shows you're partway through, most recently watched first (up to 40).
     * Uses the season listing to find the episode after your furthest one, rolling into the next season.
     */
    private suspend fun continueWatchingFor(candidates: List<Pair<TrackedShow, TrackerEpisode>>): List<UpNextEntry> = kotlinx.coroutines.coroutineScope {
        val today = LocalDate.now()
        val gate = Semaphore(6)
        candidates.sortedByDescending { it.first.lastWatchedAt }.take(40).map { (show, lastAired) -> async(Dispatchers.IO) { gate.withPermit {
            runCatching {
                val season = Api.tmdb.trackerSeason(show.tmdbId, show.lastSeason.coerceAtLeast(1), key).episodes
                val next = season.firstOrNull { it.number > show.lastEpisode }
                    ?: Api.tmdb.trackerSeason(show.tmdbId, show.lastSeason + 1, key).episodes.firstOrNull()
                    ?: return@runCatching null
                val aired = next.airDate?.let { runCatching { !LocalDate.parse(it).isAfter(today) }.getOrDefault(false) } ?: false
                if (!aired) return@runCatching null
                val left = if (next.season == lastAired.season) lastAired.number - next.number + 1 else null
                val since = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(show.lastWatchedAt.take(10)), today) }.getOrNull()
                UpNextEntry(show.title, next, show.poster,
                    badge = left?.let { if (it > 1) "$it TO WATCH" else "NEXT" } ?: "NEW SEASON",
                    detail = since?.let { if (it <= 1) "Watched recently" else "Last watched $it days ago" } ?: "Continue where you left off",
                    behind = left ?: 1)
            }.getOrNull()
        } } }.awaitAll().filterNotNull()
    }
    override fun onCleared(){store.close();super.onCleared()}
}
