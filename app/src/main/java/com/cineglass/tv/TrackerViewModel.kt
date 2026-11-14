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
    val matchStatus: String = ""
)
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
            withContext(Dispatchers.Main) { _ui.value=_ui.value.copy(shows=list,stats=stats,watched=records,reminders=store.preference("reminders")=="1") }
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
                val result=withContext(Dispatchers.IO) { importer.commit(store) }
                _ui.value=_ui.value.copy(preview=null,loading=false,message="Imported ${result.shows} shows, ${result.watchedEpisodes} watched episodes and ${result.watchedMovies} movies")
                refresh()
            } catch(e:Exception) { _ui.value=_ui.value.copy(loading=false,message="Import failed: ${e.message}") }
        }
    }
    fun dismissPreview() { _ui.value=_ui.value.copy(preview=null) }
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
                    if(r.show==title && r.episode.season==season && r.episode.number==number) r.copy(watched=watched) else r })
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
            val result=candidates.map { show -> async(Dispatchers.IO){
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
                        episodes.distinctBy { it.season to it.number }
                            .filter { ep -> ep.airDate?.let { date ->
                                runCatching { val d=LocalDate.parse(date); !d.isBefore(LocalDate.now().minusDays(30)) && d.isBefore(LocalDate.now().plusDays(35)) }.getOrDefault(false)
                            } ?: false }
                            .map { TrackerRelease(show.title,it,show.poster,store.isWatched(show.title,it.season,it.number)) }
                    } catch(_:Exception){emptyList()}
                }
            } }.awaitAll().flatten().filter{!it.episode.airDate.isNullOrBlank()}.sortedBy{it.episode.airDate}
            releasesLoadedAt=System.currentTimeMillis()
            _ui.value=_ui.value.copy(releases=result,loading=false)
        }
    }
    override fun onCleared(){store.close();super.onCleared()}
}
