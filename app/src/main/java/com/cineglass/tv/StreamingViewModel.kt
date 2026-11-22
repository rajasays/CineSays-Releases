package com.cineglass.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────────── Watch Now, native playback and progress ─────────────────────────────

/** The Watch Now dialog: the title, the chosen episode (series), and every way to watch it. */
data class WatchNowState(
    val item: TitleItem,
    val base: MediaRef,
    val seasons: List<Int> = emptyList(),
    val season: Int? = null,
    val episodes: List<TrackerEpisode> = emptyList(),
    val episodesBusy: Boolean = false,
    val watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
    val seasonProgress: Map<Int, PlaybackRecord> = emptyMap(),
    /** The movie, or the episode picked. */
    val selected: MediaRef? = null,
    val searching: Boolean = false,
    val search: StreamSearch? = null,
    val options: List<WatchAction> = emptyList(),
    /** (provider id, name, region) offers from TMDB for this title. */
    val services: List<Triple<Int, String, String>> = emptyList(),
    val myServiceIds: Set<Int> = emptySet(),
    val regions: List<String> = emptyList(),
    val error: String? = null
) { val isSeries get() = base.type == MediaType.EPISODE }

/** A native stream ready for the player. */
data class NativePlayback(
    val title: String,
    val url: String,
    val kind: StreamKind,
    val subtitles: List<SubtitleTrack>,
    val record: PlaybackRecord,
    val licence: String?,
    val sourceName: String,
    val startMs: Long = 0,
    /** The next episode, for series. */
    val next: MediaRef? = null,
    /** The title, so "Find other sources" can reopen Watch Now. */
    val item: TitleItem? = null,
    val torrent: TorrentPlayback? = null
)

data class ResumePrompt(val playback: NativePlayback, val positionMs: Long)

data class StreamingState(
    val watchNow: WatchNowState? = null,
    val playing: NativePlayback? = null,
    val resume: ResumePrompt? = null,
    val preparing: Boolean = false,
    val continueWatching: List<PlaybackRecord> = emptyList(),
    val prefs: PlaybackPrefs = PlaybackPrefs(),
    val stremioInstalled: Boolean = false,
    val message: String? = null
)

class StreamingViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(StreamingState(prefs = PlaybackPrefs.load(app)))
    val state: StateFlow<StreamingState> = _state
    private val store get() = PlaybackStore.get(app)
    private val tmdbKey get() = BuildConfig.TMDB_API_KEY
    private var searchJob: Job? = null
    private var episodesJob: Job? = null
    private var sourcePreparationJob: Job? = null
    private var activeTorrent: TorrentPlayback? = null
    /** Set by a Watch Now press: the first search result may act on the viewer's saved destination. */
    private var pendingAuto = false

    /** Titles that crossed the completion threshold during in-app playback, for the Tracker to record. */
    private val _completed = MutableSharedFlow<PlaybackRecord>(extraBufferCapacity = 8)
    val completed: SharedFlow<PlaybackRecord> = _completed

    init { refreshContinueWatching(); refreshStremio() }

    fun refreshStremio() {
        val installed = runCatching {
            app.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///")), 0).isNotEmpty()
        }.getOrDefault(false)
        _state.update { it.copy(stremioInstalled = installed) }
    }

    fun refreshContinueWatching() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { store.continueWatching() }.getOrDefault(emptyList()) }
            _state.update { it.copy(continueWatching = list) }
        }
    }

    fun updatePrefs(transform: (PlaybackPrefs) -> PlaybackPrefs) {
        val v = transform(_state.value.prefs)
        PlaybackPrefs.save(app, v)
        _state.update { it.copy(prefs = v) }
    }

    fun cancelSourcePreparation() { sourcePreparationJob?.cancel() }

    fun clearMessage() { _state.update { it.copy(message = null) } }

    // ── Watch Now ──

    /**
     * Opens Watch Now for a title from its details page. [offers] are TMDB's providers by region;
     * [watched] the Tracker's watched episodes for this show; [preselect] an episode to start on.
     */
    fun openWatchNow(
        item: TitleItem, imdbId: String?, offers: Map<String, ProviderRegion>, regions: Collection<String>,
        myServiceIds: Set<Int>, watched: Set<Pair<Int, Int>> = emptySet(), preselect: MediaRef? = null
    ) {
        refreshStremio()
        val services = regions.sorted().flatMap { r -> offers[r]?.noExtraCost.orEmpty().map { p -> Triple(p.id, p.name, r) } }
        val isTv = item.kind == "tv"
        val base = MediaRef(if (isTv) MediaType.EPISODE else MediaType.MOVIE, item.id, item.label, imdbId, item.year.ifBlank { null }, poster = item.poster)
        val ws = WatchNowState(item, base, services = services, myServiceIds = myServiceIds, regions = regions.sorted(), watchedEpisodes = watched,
            selected = if (isTv) null else base)
        _state.update { it.copy(watchNow = ws) }
        pendingAuto = _state.value.prefs.destination != PlaybackDestination.ASK
        if (!isTv) search(base) else loadSeasons(item, preselect)
    }

    fun closeWatchNow() { sourcePreparationJob?.cancel(); searchJob?.cancel(); episodesJob?.cancel(); pendingAuto = false; _state.update { it.copy(watchNow = null) } }

    private fun loadSeasons(item: TitleItem, preselect: MediaRef?) {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            _state.update { s -> s.copy(watchNow = s.watchNow?.copy(episodesBusy = true)) }
            val count = item.seasons ?: runCatching { withContext(Dispatchers.IO) { Api.tmdb.trackerTv(item.id, tmdbKey).seasons } }.getOrNull() ?: 1
            val seasons = (1..count.coerceAtLeast(1)).toList()
            val w = _state.value.watchNow ?: return@launch
            // Start where the viewer is: the preselected episode, else the latest season they have progress in, else season 1.
            val recent = withContext(Dispatchers.IO) { store.continueWatching(50) }.firstOrNull { it.type == "episode" && it.tmdbId == item.id }
            val startSeason = preselect?.season ?: recent?.season ?: w.watchedEpisodes.maxOfOrNull { it.first } ?: 1
            _state.update { s -> s.copy(watchNow = s.watchNow?.copy(seasons = seasons)) }
            selectSeason(startSeason.coerceIn(1, seasons.last()), preselect?.episode ?: recent?.episode)
        }
    }

    fun selectSeason(season: Int, episode: Int? = null) {
        val w = _state.value.watchNow ?: return
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            _state.update { s -> s.copy(watchNow = s.watchNow?.copy(season = season, episodesBusy = true, episodes = emptyList(), error = null)) }
            val eps = runCatching { withContext(Dispatchers.IO) { Api.tmdb.trackerSeason(w.item.id, season, tmdbKey).episodes } }
            val progress = withContext(Dispatchers.IO) { store.forSeason(w.item.id, season) }
            val list = eps.getOrNull().orEmpty()
            _state.update { s -> s.copy(watchNow = s.watchNow?.copy(episodes = list, seasonProgress = progress, episodesBusy = false,
                error = if (eps.isFailure) "Couldn't load season $season. Check your connection." else null)) }
            // Pick: the requested episode, else the first one not yet watched, else the first.
            val pick = list.firstOrNull { it.number == episode }
                ?: list.firstOrNull { (season to it.number) !in w.watchedEpisodes && progress[it.number]?.completed != true }
                ?: list.firstOrNull()
            pick?.let { selectEpisode(it) }
        }
    }

    fun selectEpisode(e: TrackerEpisode) {
        val w = _state.value.watchNow ?: return
        val ref = w.base.copy(season = e.season, episode = e.number, episodeName = e.name.ifBlank { null })
        _state.update { s -> s.copy(watchNow = s.watchNow?.copy(selected = ref)) }
        search(ref)
    }

    fun retry() { _state.value.watchNow?.selected?.let { search(it, force = true) } }

    private fun search(ref: MediaRef, force: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { s -> s.copy(watchNow = s.watchNow?.copy(searching = true, search = null, options = emptyList())) }
            val result = StreamRepository.search(app, ref, force)
            val w = _state.value.watchNow ?: return@launch
            if (w.selected?.key != ref.key) return@launch
            // Service offers are per title (TMDB doesn't list per-episode availability).
            val options = PlaybackRouter.options(ref, result, w.services, w.myServiceIds, _state.value.stremioInstalled, _state.value.prefs.maxHeight)
            _state.update { s -> s.copy(watchNow = s.watchNow?.copy(searching = false, search = result, options = options)) }
            // A saved destination ("Play in CineSays when possible" / "My streaming service") acts once, on the press that opened Watch Now.
            if (pendingAuto) {
                pendingAuto = false
                PlaybackRouter.automatic(options, _state.value.prefs.destination)?.let { act(it, app) }
            }
        }
    }

    /** Runs one Watch Now option. Every call comes from the viewer pressing that option. */
    fun act(action: WatchAction, context: android.content.Context) {
        val w = _state.value.watchNow ?: return
        val ref = w.selected ?: return
        when (action) {
            is WatchAction.PlayNative -> startNative(action.stream, ref, w.item)
            is WatchAction.PlayTorrent -> playTorrent(action.stream, ref, w.item)
            is WatchAction.OpenService -> WatchNow.watch(context, Provider(action.providerId, action.providerName, null), w.item, listOf(action.region) + w.regions)
            is WatchAction.OpenStremio -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.deepLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { Toast.makeText(context, "Stremio isn't installed.", Toast.LENGTH_LONG).show(); refreshStremio() }
            is WatchAction.Unavailable -> Unit
        }
    }

    /** Marks the picked episode watched / unwatched from Watch Now (the Tracker records it). */
    fun setEpisodeWatched(ref: MediaRef, watched: Boolean) {
        _state.update { s -> s.copy(watchNow = s.watchNow?.let { w ->
            val k = (ref.season ?: 0) to (ref.episode ?: 0)
            w.copy(watchedEpisodes = if (watched) w.watchedEpisodes + k else w.watchedEpisodes - k) }) }
    }

    // ── Native playback ──

    private fun playTorrent(stream: PlayableStream, ref: MediaRef, item: TitleItem) {
        val torrent = stream.debridTorrent ?: return
        if (!supportsDirectTorrent(torrent) || sourcePreparationJob?.isActive == true || activeTorrent != null) return
        if (Addons.enabled(app).none {
                it.id == stream.providerId && Addons.permitsTorrent(it, torrent.hash)
            }) return
        sourcePreparationJob = viewModelScope.launch {
            _state.update { it.copy(preparing = true) }
            val pending = java.util.concurrent.atomic.AtomicReference<TorrentPlayback>()
            try {
                withContext(Dispatchers.IO) { pending.set(TorrentPlayback.prepare(app, torrent)) }
                if (_state.value.watchNow?.selected?.key != ref.key) return@launch
                val source = requireNotNull(pending.getAndSet(null))
                activeTorrent = source
                startNative(stream.copy(url = source.uri, kind = StreamKind.PROGRESSIVE,
                    sourceName = "${stream.sourceName} · Torrent"), ref, item, saveUrl = false, torrent = source)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: TorrentPreparationException) {
                android.util.Log.w("CineSaysTorrent", e.message.orEmpty())
                _state.update { it.copy(message = e.message) }
            } catch (e: LinkageError) {
                android.util.Log.w("CineSaysTorrent", "Native torrent engine failed to load", e)
                _state.update { it.copy(message = "The torrent engine is unavailable on this device.") }
            } catch (e: Exception) {
                android.util.Log.w("CineSaysTorrent", "Torrent preparation failed", e)
                _state.update { it.copy(message = "Torrent setup failed. See CineSaysTorrent in Android Studio Logcat.") }
            } finally {
                pending.getAndSet(null)?.close()
                _state.update { it.copy(preparing = false) }
            }
        }
    }

    private fun stopTorrent() {
        activeTorrent?.close()
        activeTorrent = null
    }

    override fun onCleared() {
        stopTorrent()
        super.onCleared()
    }

    private fun startNative(stream: PlayableStream, ref: MediaRef, item: TitleItem?, saveUrl: Boolean = true, torrent: TorrentPlayback? = null) {
        val url = stream.url ?: return
        val record = PlaybackRecord(key = ref.key, type = if (ref.type == MediaType.MOVIE) "movie" else "episode", tmdbId = ref.tmdbId,
            season = ref.season, episode = ref.episode, title = ref.title, episodeName = ref.episodeName, poster = ref.poster, imdbId = ref.imdbId,
            streamUrl = url.takeIf { saveUrl }, streamKind = stream.kind.name, sourceName = stream.sourceName, licence = stream.licence)
        begin(NativePlayback(ref.display + (ref.episodeName?.let { " · $it" } ?: ""), url, stream.kind, stream.subtitles, record, stream.licence,
            stream.sourceName, next = ref.next(), item = item, torrent = torrent))
    }

    /** A Free tab film, or "Watch free in CineSays" on a movie's page ([ref] = that movie, so progress follows the movie). */
    fun playFreeFilm(film: FreeFilm, ref: MediaRef? = null, item: TitleItem? = null) {
        _state.update { it.copy(preparing = true) }
        viewModelScope.launch {
            val url = runCatching { FreeFilms.streamUrl(film.id) }.getOrNull()?.takeIf { StreamUrls.allowed(it, listOf("archive.org")) }
            if (url == null) { _state.update { it.copy(preparing = false, message = "That film isn't available to stream right now.") }; return@launch }
            val record = if (ref != null) PlaybackRecord(ref.key, "movie", ref.tmdbId, title = ref.title, poster = ref.poster, imdbId = ref.imdbId,
                streamUrl = url, streamKind = StreamKind.PROGRESSIVE.name, sourceName = "Internet Archive", licence = film.licence)
            else PlaybackRecord("ia:${film.id}", "film", title = film.title, poster = film.thumbnail, streamUrl = url,
                streamKind = StreamKind.PROGRESSIVE.name, sourceName = "Internet Archive", licence = film.licence)
            _state.update { it.copy(preparing = false) }
            begin(NativePlayback(film.title, url, StreamKind.PROGRESSIVE, emptyList(), record, film.licence, "Internet Archive", item = item))
        }
    }

    /** Settings › Playback diagnostics: public HLS / DASH test streams (Apple and Google samples). Never saved to progress. */
    fun playTest(kind: StreamKind) {
        val (name, url) = when (kind) {
            StreamKind.HLS -> "HLS test stream (Apple sample)" to "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"
            else -> "DASH test stream (Shaka sample)" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd"
        }
        _state.update { it.copy(playing = NativePlayback(name, url, kind, emptyList(), PlaybackRecord("test:${kind.name}", "test", title = name), null, "Playback test")) }
    }

    /** Continue Watching: resume the stream played last, from where it stopped. */
    fun resume(record: PlaybackRecord, onNeedsSources: (PlaybackRecord) -> Unit) {
        val url = record.streamUrl
        if (url == null) { onNeedsSources(record); return }
        val kind = runCatching { StreamKind.valueOf(record.streamKind ?: "") }.getOrDefault(StreamUrls.kindOf(url))
        val title = record.ref()?.let { r -> r.display + (r.episodeName?.let { " · $it" } ?: "") } ?: record.title
        _state.update { it.copy(playing = NativePlayback(title, url, kind, emptyList(), record, record.licence, record.sourceName ?: "",
            startMs = record.positionMs, next = record.ref()?.next())) }
    }

    /** Applies the resume preference: ask, always resume, or always start over. */
    private fun begin(p: NativePlayback) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { store.get(p.record.key) }
            val prefs = _state.value.prefs
            val pos = saved?.positionMs ?: 0
            val resumable = saved != null && ProgressRules.canResume(pos, saved.durationMs, prefs.completeAt)
            when {
                resumable && prefs.resume == "ask" -> _state.update { it.copy(resume = ResumePrompt(p, pos)) }
                resumable && prefs.resume == "always" -> _state.update { it.copy(playing = p.copy(startMs = pos)) }
                else -> _state.update { it.copy(playing = p.copy(startMs = 0)) }
            }
        }
    }

    fun answerResume(fromSaved: Boolean) {
        val r = _state.value.resume ?: return
        _state.update { it.copy(resume = null, playing = r.playback.copy(startMs = if (fromSaved) r.positionMs else 0)) }
    }
    fun dismissResume() {
        if (_state.value.resume?.playback?.torrent != null) stopTorrent()
        _state.update { it.copy(resume = null) }
    }

    fun closePlayer() {
        stopTorrent()
        _state.update { it.copy(playing = null) }
        refreshContinueWatching()
    }

    /** Called by the player every few seconds, on pause/stop/exit, and at the end. */
    fun saveProgress(p: NativePlayback, positionMs: Long, durationMs: Long) {
        if (p.record.type == "test") return
        viewModelScope.launch(Dispatchers.IO) {
            val prev = store.get(p.record.key)
            val already = prev?.completed == true
            val threshold = _state.value.prefs.completeAt
            val nowComplete = already || ProgressRules.isComplete(positionMs, durationMs, threshold)
            val r = p.record.copy(positionMs = positionMs, durationMs = durationMs.coerceAtLeast(0), updatedAt = System.currentTimeMillis(), completed = nowComplete)
            store.save(r)
            if (ProgressRules.shouldMarkWatched(already, positionMs, durationMs, threshold)) _completed.tryEmit(r)
        }
    }

    /** "Next episode": find the next episode's sources and play the best native one, else open Watch Now on it. */
    fun playNext(onOpenWatchNow: (MediaRef) -> Unit) {
        val p = _state.value.playing ?: return
        val next = p.next ?: return
        stopTorrent()
        _state.update { it.copy(playing = null, preparing = true) }
        viewModelScope.launch {
            val result = StreamRepository.search(app, next)
            val best = PlaybackRouter.rankNative(result.playable, _state.value.prefs.maxHeight).firstOrNull()
            val name = runCatching { withContext(Dispatchers.IO) { Api.tmdb.trackerSeason(next.tmdbId, next.season ?: 1, tmdbKey).episodes } }.getOrNull()
                ?.firstOrNull { it.number == next.episode }
            _state.update { it.copy(preparing = false) }
            when {
                name == null -> _state.update { it.copy(message = "That was the last episode of the season.") }.also { refreshContinueWatching() }
                best != null -> startNative(best, next.copy(episodeName = name.name.ifBlank { null }), p.item)
                else -> { refreshContinueWatching(); onOpenWatchNow(next.copy(episodeName = name.name.ifBlank { null })) }
            }
        }
    }
}
