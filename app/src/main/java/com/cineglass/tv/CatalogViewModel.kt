package com.cineglass.tv

import android.app.Application
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import kotlin.random.Random

private val Application.store by preferencesDataStore("cineglass_settings")
private val WATCHLIST = stringPreferencesKey("watchlist")
private val REGION = stringPreferencesKey("region")
private val REGIONS = stringPreferencesKey("regions")
private val AVAILABLE_REGIONS = setOf("US", "GB", "PK", "CA", "AU", "IN", "DE", "FR", "AE", "JP")
private val MY_SERVICES = stringPreferencesKey("my_services")
private val ONBOARDED = stringPreferencesKey("onboarded")
private val LIKES = stringPreferencesKey("likes")

/** A subscription the user can mark as theirs. Some services have more than one TMDB provider ID. */
/** [free] = no subscription needed (ad-supported); TMDB lists those under "free"/"ads", not "flatrate". */
data class StreamingService(val key: String, val label: String, val providerIds: List<Int>, val free: Boolean = false)
// Provider IDs checked against TMDB's /watch/providers lists for US, GB, IN and PK (Nov 2026).
val STREAMING_SERVICES = listOf(
    StreamingService("netflix", "Netflix", listOf(8)),
    StreamingService("prime", "Prime Video", listOf(9, 119)),
    StreamingService("max", "Max", listOf(1899, 384)),
    StreamingService("disney", "Disney+", listOf(337)),
    StreamingService("apple", "Apple TV+", listOf(350)),
    StreamingService("paramount", "Paramount+", listOf(531)),
    StreamingService("hulu", "Hulu", listOf(15)),
    // Regional subscriptions
    StreamingService("zee5", "Zee5", listOf(232)),
    StreamingService("jiohotstar", "JioHotstar", listOf(2336)),
    StreamingService("sonyliv", "Sony LIV", listOf(237, 2792)),
    StreamingService("mubi", "MUBI", listOf(11)),
    // Free, ad-supported
    StreamingService("tubi", "Tubi", listOf(73), free = true),
    StreamingService("pluto", "Pluto TV", listOf(300), free = true),
    StreamingService("plex", "Plex", listOf(538), free = true),
    StreamingService("youtubefree", "YouTube (free)", listOf(235), free = true),
    // Amazon's free-with-ads catalogue (formerly Freevee): the free movies IMDb links to. US, UK and India only.
    StreamingService("primefree", "Prime Video (free)", listOf(PRIME_FREE_ID), free = true)
)
/** TMDB provider "Amazon Prime Video Free with Ads" (not 2100, which is the paid Prime tier with ads). */
const val PRIME_FREE_ID = 613
/** Provider IDs whose catalogue is ad-supported, so discovery must ask for free|ads offers. */
val FREE_PROVIDER_IDS: Set<Int> = STREAMING_SERVICES.filter { it.free }.flatMap { it.providerIds }.toSet()
/** "My services" can mix paid and free services, so it asks for every no-extra-cost offer type. */
private const val MY_MONETIZATION = "flatrate|free|ads"
/** Sentinel provider value meaning "whatever the user subscribes to"; resolved at request time. */
const val MY_SERVICES_PROVIDER = "mine"
/** Sentinel provider value meaning "free on any service (with ads or not)". */
const val FREE_PROVIDER = "free"

/** A home/browse row. [kind] lets the Movies and TV tabs filter rows without string matching on titles. */
data class CatalogSection(val title: String, val kind: String, val items: List<TitleItem>)

private class RowRequest(val title: String, val kind: String, val fetch: suspend () -> List<TitleItem>)

data class ScreenState(
    val regions: Set<String> = setOf("US"), val sections: List<CatalogSection> = emptyList(),
    val results: List<TitleItem> = emptyList(), val searching: Boolean = false,
    val watchlistItems: List<TitleItem> = emptyList(), val watchlistBusy: Boolean = false,
    val selected: TitleItem? = null, val detailBusy: Boolean = false,
    val providersByRegion: Map<String, ProviderRegion> = emptyMap(), val trailer: String? = null,
    val imdbRating: String? = null, val rottenTomatoes: String? = null,
    val watchlist: Set<String> = emptySet(), val busy: Boolean = false, val error: String? = null,
    /** Keys from [STREAMING_SERVICES] the user pays for. */
    val myServices: Set<String> = emptySet(),
    val genreTitles: List<TitleItem> = emptyList(),
    val roulettePick: TitleItem? = null, val rouletteBusy: Boolean = false, val genreBusy: Boolean = false,
    /** Best place to watch the roulette pick: a no-extra-cost offer, the user's own services first. */
    val rouletteOffer: Pair<Provider, String>? = null,
    val cast: List<CastMember> = emptyList(), val similar: List<TitleItem> = emptyList(),
    /** null until preferences load; false shows the first-run "My Services" step. */
    val onboarded: Boolean? = null,
    /** Titles the user marked ♥ ("movie:550"); the strongest signal for recommendations. */
    val likes: Set<String> = emptySet(),
    val recommended: List<TitleItem> = emptyList(),
    /** ref -> "Because you liked X" */
    val recommendedWhy: Map<String, String> = emptyMap(),
    /** Actor page shown over Details; [personBusy] while it loads. */
    val person: Person? = null, val personBusy: Boolean = false,
    /** In-app player (trailer, live channel or public-domain film), shown full screen. */
    val playing: Playable? = null, val playerBusy: Boolean = false,
    /** Free tab: public-domain classics (Internet Archive) and free-with-ads titles (TMDB). */
    val freeCatalogs: List<AddonCatalog> = emptyList(), val freeTitles: List<TitleItem> = emptyList(), val freeBusy: Boolean = false,
    /** Add-ons (Settings › Add-ons) with whether each is on, and the live channels from enabled ones. */
    val addons: List<Pair<Addon, Boolean>> = emptyList(), val liveChannels: List<LiveChannel> = emptyList(),
    /** A free, legal copy of the movie open in Details, if the Internet Archive has one. */
    val freeMatch: FreeFilm? = null,
    /** Free tab: Amazon's free-with-ads titles (the free movies on IMDb). */
    val primeFreeTitles: List<TitleItem> = emptyList(),
    /** Details: the title's IMDb ID, and which of your regions have it free on Prime Video. */
    val imdbId: String? = null, val primeFreeRegions: List<String> = emptyList()
)

/** Roulette moods map to TMDB genres (OR-ed) plus a rating floor. Movie genre IDs; TV is mapped. */
data class RouletteMood(val label: String, val genres: List<Int>, val minRating: Double = 0.0)
val ROULETTE_MOODS = listOf(
    RouletteMood("Something light", listOf(35, 10751, 16)),
    RouletteMood("Edge of my seat", listOf(53, 28, 80)),
    RouletteMood("A good cry", listOf(18, 10749), 7.0),
    RouletteMood("Mind-bending", listOf(878, 9648, 14)),
    RouletteMood("Scary", listOf(27)),
    RouletteMood("Family night", listOf(10751, 16, 12)),
    RouletteMood("Critically loved", emptyList(), 7.8),
    RouletteMood("Surprise me", emptyList())
)

class CatalogViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state
    private val key get() = BuildConfig.TMDB_API_KEY
    private val providerRows = listOf("Netflix" to "8", "Prime Video" to "9", "Apple TV+" to "350", "HBO Max" to "1899", "Disney+" to "337")

    // One in-flight job per feature: a newer request cancels the older one so stale
    // responses can never overwrite fresher results.
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var genreJob: Job? = null
    private var detailJob: Job? = null
    private var watchlistJob: Job? = null
    private val recentPicks = ArrayDeque<String>()

    init {
        viewModelScope.launch {
            val p = app.store.data.first()
            val saved = p[REGIONS]?.split(",")?.filter { it in AVAILABLE_REGIONS }?.toSet().orEmpty()
            val migrated = p[REGION]?.takeIf { it in AVAILABLE_REGIONS } ?: "US"
            val services = p[MY_SERVICES].orEmpty().split(",").filter { k -> STREAMING_SERVICES.any { it.key == k } }.toSet()
            _state.update { it.copy(regions = saved.ifEmpty { setOf(migrated) }, myServices = services,
                onboarded = p[ONBOARDED] == "1" || services.isNotEmpty(),
                likes = p[LIKES].orEmpty().split(",").filter { s -> s.isNotBlank() }.toCollection(LinkedHashSet()),
                watchlist = p[WATCHLIST].orEmpty().split(",").filter { s -> s.isNotBlank() }.toCollection(LinkedHashSet())) }
            refresh()
        }
        viewModelScope.launch { publishAddons() } // built-ins right away; the registry loads with the Free tab
    }

    /** Call with the provider value as the UI chose it (before [resolveProvider]). */
    private fun monetizationFor(providerId: String?) = when {
        providerId == null -> null
        providerId == "192" -> "rent"
        providerId == FREE_PROVIDER -> "free|ads"   // any free / ad-supported service
        providerId == MY_SERVICES_PROVIDER -> MY_MONETIZATION
        providerId.split("|").mapNotNull { it.toIntOrNull() }.all { it in FREE_PROVIDER_IDS } -> "free|ads"
        else -> "flatrate"
    }

    /** TMDB provider IDs of the user's services, e.g. [8, 9, 119]. */
    fun myProviderIds(services: Set<String> = _state.value.myServices): Set<Int> =
        STREAMING_SERVICES.filter { it.key in services }.flatMap { it.providerIds }.toSet()

    /** TMDB accepts "8|9|337" as OR. Returns null when the user has not picked any services. */
    private fun myServicesFilter(): String? = myProviderIds().takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|")

    /** Turns the [MY_SERVICES_PROVIDER] sentinel into real IDs; passes other values through. */
    private fun resolveProvider(providerId: String?): String? = when (providerId) {
        MY_SERVICES_PROVIDER -> myServicesFilter()
        FREE_PROVIDER -> null // no specific service; the free|ads monetization does the filtering
        else -> providerId
    }

    // ── Free watching ──
    /** Add-ons with their on/off state, for Settings › Add-ons. Also refreshes CineSays' registry. */
    private fun publishAddons() {
        _state.update { it.copy(addons = Addons.all.map { a -> a to Addons.isOn(app, a) },
            liveChannels = Addons.enabled(app).filter { a -> a.engine == Addons.YOUTUBE_LIVE }.flatMap { a -> a.channels.orEmpty() }.distinctBy { c -> c.youtubeChannelId }) }
    }
    private var addonsLoaded = false
    private suspend fun ensureAddons() {
        if (addonsLoaded) return
        Addons.refresh(app); addonsLoaded = true; publishAddons()
    }
    fun loadAddons() { viewModelScope.launch { ensureAddons() } }
    fun toggleAddon(id: String) {
        val a = Addons.all.firstOrNull { it.id == id } ?: return
        Addons.setOn(app, id, !Addons.isOn(app, a))
        publishAddons()
        _state.update { it.copy(freeCatalogs = emptyList()) } // reload rows on the next visit to Free
    }

    /** Loads the Free tab: a row per enabled archive add-on, plus free-with-ads titles in your regions. */
    fun loadFree() {
        if (_state.value.freeBusy || (_state.value.freeCatalogs.isNotEmpty() && _state.value.freeTitles.isNotEmpty())) return
        viewModelScope.launch {
            _state.update { it.copy(freeBusy = true) }
            ensureAddons()
            val catalogs = Addons.enabled(app).filter { it.engine == Addons.ARCHIVE }
                .map { a -> async { AddonCatalog(a, runCatching { FreeFilms.catalog(a) }.getOrDefault(emptyList())) } }
            val titles = async { runCatching { freeWithAds() }.getOrDefault(emptyList()) }
            val prime = async { runCatching { primeFree() }.getOrDefault(emptyList()) }
            _state.update { it.copy(freeCatalogs = catalogs.awaitAll().filter { c -> c.films.isNotEmpty() },
                freeTitles = titles.await(), primeFreeTitles = prime.await(), freeBusy = false) }
        }
    }
    private suspend fun freeWithAds(): List<TitleItem> = coroutineScope {
        listOf("movie", "tv").map { k -> async { discoverRegions(k, null, "free|ads") } }.awaitAll()
            .flatten().filter { it.poster != null }.distinctBy { it.ref }.sortedByDescending { it.popularity }.take(30)
    }
    /** Free on Prime Video (Amazon's free-with-ads catalogue) in your regions, best-known first. */
    private suspend fun primeFree(): List<TitleItem> = coroutineScope {
        listOf("movie", "tv").map { k -> async { discoverRegions(k, PRIME_FREE_ID.toString(), "free|ads", extra = mapOf("vote_count.gte" to "50")) } }.awaitAll()
            .flatten().filter { it.poster != null }.distinctBy { it.ref }.sortedByDescending { it.popularity }.take(30)
    }
    fun play(item: Playable) {
        if (item !is Playable.Live || item.videoId != null) { _state.update { it.copy(playing = item) }; return }
        // Live: find the broadcast that is on air now, then embed that video.
        _state.update { it.copy(playerBusy = true) }
        viewModelScope.launch {
            val id = FreeFilms.liveVideoId(item.channelId)
            _state.update { it.copy(playerBusy = false, playing = item.copy(videoId = id)) }
        }
    }
    fun closePlayer() { _state.update { it.copy(playing = null, playerBusy = false) } }
    /** Finds the film's video file, then opens the player. */
    fun playFilm(film: FreeFilm) {
        _state.update { it.copy(playerBusy = true) }
        viewModelScope.launch {
            val url = runCatching { FreeFilms.streamUrl(film.id) }.getOrNull()
            _state.update { if (url != null) it.copy(playing = Playable.Film(film.title, film, url), playerBusy = false)
                else it.copy(playerBusy = false, error = "That film isn't available to stream right now.") }
        }
    }
    private var freeMatchJob: Job? = null
    /** For a movie in Details: is there a free, legal copy we can play in-app? */
    private fun checkFreeMatch(item: TitleItem) {
        freeMatchJob?.cancel()
        _state.update { it.copy(freeMatch = null) }
        if (item.kind != "movie") return
        freeMatchJob = viewModelScope.launch {
            ensureAddons()
            val m = runCatching { FreeFilms.match(item.label, item.year.ifBlank { null }, Addons.enabled(app)) }.getOrNull()
            if (_state.value.selected?.ref == item.ref) _state.update { it.copy(freeMatch = m) }
        }
    }

    // ── Backup / restore (Account sync): watchlist, likes, services and regions ──
    fun exportPrefs(): String = org.json.JSONObject()
        .put("watchlist", org.json.JSONArray(_state.value.watchlist.toList()))
        .put("likes", org.json.JSONArray(_state.value.likes.toList()))
        .put("services", org.json.JSONArray(_state.value.myServices.toList()))
        .put("regions", org.json.JSONArray(_state.value.regions.toList())).toString()

    /** Merges a backup into this device (union of lists; regions/services only filled in if empty here). */
    fun importPrefs(json: String) {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val s = _state.value
        val watchlist = LinkedHashSet(s.watchlist).apply { addAll(list("watchlist")) }
        val likes = LinkedHashSet(s.likes).apply { addAll(list("likes")) }
        val services = s.myServices + list("services").filter { k -> STREAMING_SERVICES.any { it.key == k } }
        val regions = (s.regions + list("regions").filter { it in AVAILABLE_REGIONS }).ifEmpty { setOf("US") }
        _state.update { it.copy(watchlist = watchlist, likes = likes, myServices = services, regions = regions, onboarded = true) }
        viewModelScope.launch {
            app.store.edit {
                it[WATCHLIST] = watchlist.joinToString(","); it[LIKES] = likes.joinToString(",")
                it[MY_SERVICES] = services.sorted().joinToString(","); it[REGIONS] = regions.sorted().joinToString(","); it[ONBOARDED] = "1"
            }
        }
        refresh()
    }

    fun toggleLike(item: TitleItem) {
        val updated = LinkedHashSet(_state.value.likes).apply { if (!add(item.ref)) remove(item.ref) }
        _state.update { it.copy(likes = updated) }
        viewModelScope.launch { app.store.edit { it[LIKES] = updated.joinToString(",") } }
    }

    private var recommendKey = ""
    /**
     * "Recommended for you": TMDB recommendations for your strongest signals, scored by how many of
     * them point at a title (likes ×3, watchlist ×2, recently watched/tracked ×1). Anything you've
     * already saved, tracked or watched is left out; ties go to the higher-rated title.
     * [watchedSeeds]: recent tracker refs ("tv:1399", "movie:550"); [exclude]: everything tracked/watched.
     */
    fun loadRecommendations(watchedSeeds: List<String>, exclude: Set<String>) {
        val s = _state.value
        val seeds = (s.likes.reversed().take(6).map { it to 3 } + s.watchlist.reversed().take(5).map { it to 2 } + watchedSeeds.take(6).map { it to 1 })
            .distinctBy { it.first }
        val newKey = seeds.joinToString() + exclude.size
        if (key.isBlank() || seeds.isEmpty() || newKey == recommendKey) return
        recommendKey = newKey
        viewModelScope.launch {
            val skip = exclude + s.watchlist + s.likes
            val scores = linkedMapOf<String, Int>(); val items = mutableMapOf<String, TitleItem>(); val why = mutableMapOf<String, Pair<Int, String>>()
            val known = (s.sections.flatMap { it.items } + s.watchlistItems).associateBy { it.ref }
            coroutineScope {
                seeds.map { (ref, weight) -> async {
                    val (kind, id) = ref.split(":").let { it.getOrNull(0) to it.getOrNull(1)?.toIntOrNull() }
                    if (kind == null || id == null) return@async null
                    val recs = runCatching { Api.tmdb.recommendations(kind, id, key).results }.getOrNull().orEmpty()
                    val seedName = known[ref]?.label ?: runCatching { Api.tmdb.detail(kind, id, key).label }.getOrNull().orEmpty()
                    Triple(recs.map { it.copy(mediaType = it.mediaType ?: kind) }, weight, seedName)
                } }.awaitAll()
            }.filterNotNull().forEach { (recs, weight, seedName) ->
                recs.filter { it.poster != null && it.ref !in skip && it.rating >= 6.0 }.forEach { r ->
                    scores[r.ref] = (scores[r.ref] ?: 0) + weight; items[r.ref] = r
                    if (seedName.isNotBlank() && (why[r.ref]?.first ?: 0) < weight)
                        why[r.ref] = weight to (if (weight == 3) "Because you liked $seedName" else if (weight == 2) "Like $seedName on your watchlist" else "Because you watched $seedName")
                }
            }
            val ranked = scores.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { items[it.key]?.rating ?: 0.0 })
                .take(20).mapNotNull { items[it.key] }
            _state.update { it.copy(recommended = ranked, recommendedWhy = why.mapValues { e -> e.value.second }) }
        }
    }

    /** Leaves the Roulette result and returns to the filters and wheel. */
    fun clearRoulettePick() { _state.update { it.copy(roulettePick = null, rouletteOffer = null) } }

    fun finishOnboarding() {
        _state.update { it.copy(onboarded = true) }
        viewModelScope.launch { app.store.edit { it[ONBOARDED] = "1" } }
    }

    fun toggleService(key: String) {
        if (STREAMING_SERVICES.none { it.key == key }) return
        val current = _state.value.myServices
        val updated = if (key in current) current - key else current + key
        _state.update { it.copy(myServices = updated) }
        viewModelScope.launch { app.store.edit { it[MY_SERVICES] = updated.sorted().joinToString(",") } }
        refresh() // Rebuilds the "New on your services" row.
    }

    // Union the results by TMDB kind + ID; a title available in two selected regions
    // appears only once in discovery, but its regional offers stay separate on details.
    // Regions are queried in parallel instead of one after another.
    private suspend fun discoverRegions(
        kind: String, providerId: String? = null, monetization: String? = null,
        genreId: Int? = null, sort: String = "popularity.desc", page: Int = 1,
        extra: Map<String, String> = emptyMap()
    ): List<TitleItem> = coroutineScope {
        val regions = _state.value.regions.sorted()
        val pages = regions.map { region ->
            async { runCatching { Api.tmdb.discover(kind, key, region, providerId, monetization, sort, genreId, page, extra = extra) } }
        }.awaitAll()
        val combined = linkedMapOf<String, TitleItem>()
        pages.forEach { r -> r.getOrNull()?.results?.forEach { t -> combined.putIfAbsent("$kind:${t.id}", t.copy(mediaType = kind)) } }
        if (combined.isEmpty()) pages.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        combined.values.toList()
    }

    /** "New" rows: released (not future/unknown) titles that actually have an offer in the selected regions. */
    private fun recentExtra(kind: String): Map<String, String> {
        val today = LocalDate.now().toString()
        return if (kind == "movie") mapOf("primary_release_date.lte" to today, "vote_count.gte" to "10")
        else mapOf("first_air_date.lte" to today, "vote_count.gte" to "5")
    }

    fun refresh() {
        if (key.isBlank()) { _state.update { it.copy(error = "Add TMDB_API_KEY to local.properties and rebuild the app.") }; return }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val requests: List<RowRequest> = listOf(
                RowRequest("Trending Movies", "movie") { Api.tmdb.trending("movie", key).results.map { it.copy(mediaType = "movie") } },
                RowRequest("Trending TV", "tv") { Api.tmdb.trending("tv", key).results.map { it.copy(mediaType = "tv") } },
                RowRequest("New Movies", "movie") { discoverRegions("movie", monetization = "flatrate|rent|buy", sort = "primary_release_date.desc", extra = recentExtra("movie")) },
                RowRequest("New TV", "tv") { discoverRegions("tv", monetization = "flatrate", sort = "first_air_date.desc", extra = recentExtra("tv")) }
            ) + listOfNotNull(myServicesFilter()?.let { mine ->
                // Movies and series together, newest first, only from services the user pays for.
                RowRequest("New on your services", "both") {
                    coroutineScope {
                        val movies = async { discoverRegions("movie", mine, MY_MONETIZATION, sort = "primary_release_date.desc", extra = recentExtra("movie")) }
                        val shows = async { discoverRegions("tv", mine, MY_MONETIZATION, sort = "first_air_date.desc", extra = recentExtra("tv")) }
                        (movies.await() + shows.await()).sortedByDescending { it.releaseDate ?: it.firstAirDate ?: "" }
                    }
                }
            }) + listOf(
                // Free on Tubi / Pluto TV / Plex / YouTube in your regions (these open in their own apps).
                RowRequest("Free to watch", "free") { freeWithAds() }
            ) + providerRows.flatMap { (name, id) ->
                listOf(
                    RowRequest("$name · Movies", "movie") { discoverRegions("movie", id, "flatrate") },
                    RowRequest("$name · TV", "tv") { discoverRegions("tv", id, "flatrate") }
                )
            }
            // All rows load concurrently (bounded so a multi-region setup doesn't burst TMDB).
            val gate = Semaphore(6)
            val loaded = coroutineScope {
                requests.map { r ->
                    async { gate.withPermit { runCatching { CatalogSection(r.title, r.kind, r.fetch().filter { it.poster != null }.distinctBy { it.ref }) } } }
                }.awaitAll()
            }
            // "New on your services" goes first on Home when present.
            val sections = loaded.mapNotNull { it.getOrNull() }.filter { it.items.isNotEmpty() }
                .sortedBy { if (it.kind == "both") 0 else 1 }
            val error = if (sections.isEmpty()) loaded.firstNotNullOfOrNull { it.exceptionOrNull() }?.message ?: "Unable to load catalog" else null
            _state.update { it.copy(sections = sections, busy = false, error = error) }
        }
    }

    fun clearError() { if (_state.value.error != null) _state.update { it.copy(error = null) } }

    // TMDB defines several different genre IDs for movie and TV catalogues.
    private fun genreForKind(kind: String, genreId: Int?): Int? {
        if (kind != "tv") return genreId
        return when (genreId) {
            28, 12 -> 10759 // Action & Adventure
            878, 14 -> 10765 // Sci-Fi & Fantasy
            10749 -> 18     // Romance: closest TV equivalent is Drama
            27, 53 -> 9648  // Horror / Thriller: closest TV equivalent is Mystery
            else -> genreId
        }
    }

    /** Discover sort keys: "popular", "top" (well-rated with enough votes), "new" (released, newest first). */
    private fun sortParams(kind: String, sort: String): Pair<String, Map<String, String>> = when (sort) {
        "top" -> "vote_average.desc" to mapOf("vote_count.gte" to if (kind == "movie") "300" else "150")
        "new" -> (if (kind == "movie") "primary_release_date.desc" else "first_air_date.desc") to recentExtra(kind)
        else -> "popularity.desc" to emptyMap()
    }

    fun discoverGenre(kind: String, genreId: Int?, requestedProvider: String?, sort: String = "popular") {
        if (key.isBlank()) return
        val providerId = resolveProvider(requestedProvider)
        val monetization = monetizationFor(requestedProvider)
        genreJob?.cancel()
        genreJob = viewModelScope.launch {
            _state.update { it.copy(genreBusy = true, genreTitles = emptyList()) }
            try {
                val kinds = if (kind == "both") listOf("movie", "tv") else listOf(kind)
                val titles = coroutineScope {
                    kinds.map { c -> async {
                        val (sortBy, extra) = sortParams(c, sort)
                        discoverRegions(c, providerId, monetization, genreForKind(c, genreId), sort = sortBy, extra = extra)
                    } }.awaitAll()
                }.flatten().distinctBy { it.ref }.filter { it.poster != null }
                    .let { list -> if (sort == "top") list.sortedByDescending { it.rating } else list }
                _state.update { it.copy(genreTitles = titles, genreBusy = false) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message, genreBusy = false) } }
        }
    }

    /**
     * Mood roulette.
     * [length]: "episode" (TV only), "short" (movies up to 2 h), "any".
     * [source]: "mine" (the user's services), "watchlist" (saved titles only), "any".
     */
    fun spin(mood: RouletteMood, length: String, source: String) {
        if (key.isBlank() || _state.value.rouletteBusy) return
        val requestedProvider = if (source == "mine" && _state.value.myServices.isNotEmpty()) MY_SERVICES_PROVIDER else null
        val providerId = resolveProvider(requestedProvider)
        val monetization = monetizationFor(requestedProvider)
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            _state.update { it.copy(rouletteBusy = true, roulettePick = null, rouletteOffer = null, error = null) }
            try {
                val kinds = when (length) { "episode" -> listOf("tv"); "short" -> listOf("movie"); else -> listOf("movie", "tv") }
                fun genresFor(kind: String) = mood.genres.mapNotNull { genreForKind(kind, it) }.distinct().joinToString("|").ifBlank { null }
                fun extraFor(kind: String) = buildMap {
                    if (mood.minRating > 0) { put("vote_average.gte", mood.minRating.toString()); put("vote_count.gte", "100") }
                    if (kind == "movie" && length == "short") put("with_runtime.lte", "120")
                    genresFor(kind)?.let { put("with_genres", it) }
                }
                suspend fun fetch(page: Int): List<TitleItem> = if (source == "watchlist") {
                    // Watchlist source: pick from saved titles, still honouring type and mood genre filters loosely.
                    if (_state.value.watchlistItems.isEmpty()) loadWatchlistNow()
                    _state.value.watchlistItems.filter { it.kind in kinds }
                } else coroutineScope {
                    kinds.map { c -> async { discoverRegions(c, providerId, monetization, null, page = page, extra = extraFor(c)) } }.awaitAll()
                }.flatten().filter { it.poster != null && !it.overview.isNullOrBlank() }
                // Randomise among the first five popular pages, but niche filter combinations
                // may have fewer pages: fall back to page 1 instead of reporting "nothing found".
                val page = if (source == "watchlist") 1 else Random.nextInt(1, 6)
                var candidates = fetch(page)
                if (candidates.isEmpty() && page > 1) candidates = fetch(1)
                // Avoid handing back something the wheel picked in the last few spins.
                val pick = candidates.filter { it.ref !in recentPicks }.randomOrNull() ?: candidates.randomOrNull()
                pick?.let { recentPicks.addLast(it.ref); if (recentPicks.size > 15) recentPicks.removeFirst() }
                // Where to watch it, so the result card can open the right app directly.
                val detail = pick?.let { p -> runCatching { Api.tmdb.fullDetail(p.kind, p.id, key) }.getOrNull() }
                val offer = detail?.providers?.results?.let { byRegion -> bestOffer(byRegion) }
                val enriched = pick?.let { p -> detail?.toItem(p.kind) ?: p }
                // Let the wheel visibly rotate for at least ~2.2 s in total, not 2.2 s on top of the network time.
                val remaining = 2200 - (SystemClock.elapsedRealtime() - started)
                if (remaining > 0) delay(remaining)
                _state.update { it.copy(roulettePick = enriched, rouletteOffer = offer,
                    error = if (pick == null) "No titles found for this mood. Try another mood, length or source." else null) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Roulette search failed") } }
            finally { _state.update { it.copy(rouletteBusy = false) } }
        }
    }

    /** First no-extra-cost offer in the selected regions, preferring the user's own services. */
    fun bestOffer(byRegion: Map<String, ProviderRegion>): Pair<Provider, String>? {
        val mine = myProviderIds()
        val offers = _state.value.regions.sorted().flatMap { r -> byRegion[r]?.noExtraCost.orEmpty().map { it to r } }
        return offers.firstOrNull { it.first.id in mine } ?: offers.firstOrNull()
    }

    private suspend fun loadWatchlistNow() {
        val items = coroutineScope {
            _state.value.watchlist.toList().map { ref -> async {
                val parts = ref.split(":")
                if (parts.size != 2) null else runCatching { Api.tmdb.detail(parts[0], parts[1].toInt(), key).copy(mediaType = parts[0]) }.getOrNull()
            } }.awaitAll().filterNotNull()
        }
        _state.update { it.copy(watchlistItems = items) }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank() || key.isBlank()) { _state.update { it.copy(results = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            try {
                val hits = Api.tmdb.search(key, query.trim()).results
                    .filter { (it.mediaType == "movie" || it.mediaType == "tv") && it.poster != null }
                _state.update { it.copy(results = hits, searching = false) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message, searching = false) } }
        }
    }

    fun select(item: TitleItem) {
        _state.update { it.copy(selected = item, detailBusy = true, providersByRegion = emptyMap(), trailer = null, imdbRating = null, rottenTomatoes = null,
            cast = emptyList(), similar = emptyList(), imdbId = null, primeFreeRegions = emptyList()) }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                // A single append_to_response call replaces four sequential round trips.
                val full = Api.tmdb.fullDetail(item.kind, item.id, key)
                checkFreeMatch(full.toItem(item.kind)) // free public-domain copy we can play in-app?
                val regions = _state.value.regions
                val videos = full.videos?.results.orEmpty()
                val trailer = videos.firstOrNull { it.site == "YouTube" && it.type == "Trailer" && it.official }
                    ?: videos.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }
                _state.update { it.copy(selected = full.toItem(item.kind), detailBusy = false,
                    providersByRegion = full.providers?.results.orEmpty().filterKeys { r -> r in regions }, trailer = trailer?.key,
                    imdbId = full.externalIds?.imdbId?.takeIf { id -> id.startsWith("tt") },
                    primeFreeRegions = full.providers?.results.orEmpty().filter { (r, p) -> r in regions &&
                        (p.ads.orEmpty() + p.free.orEmpty()).any { pr -> pr.id == PRIME_FREE_ID } }.keys.sorted(),
                    cast = full.credits?.cast.orEmpty().take(10),
                    similar = full.recommendations?.results.orEmpty().filter { r -> r.poster != null }
                        .map { r -> r.copy(mediaType = r.mediaType ?: item.kind) }.take(12)) }
                // Optional ratings; failures must not prevent the details page from working.
                val omdbKey = BuildConfig.OMDB_API_KEY
                val imdbId = full.externalIds?.imdbId
                if (omdbKey.isNotBlank() && !imdbId.isNullOrBlank()) {
                    runCatching { Api.omdb.detail(omdbKey, imdbId) }.getOrNull()?.let { ratings ->
                        val imdb = ratings.imdbRating?.takeUnless { it == "N/A" || it.isBlank() }
                        val rotten = ratings.Ratings.firstOrNull { it.Source == "Rotten Tomatoes" }?.Value?.takeUnless { it == "N/A" || it.isBlank() }
                        _state.update { it.copy(imdbRating = imdb, rottenTomatoes = rotten) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message, detailBusy = false) } }
        }
    }

    fun selectById(kind: String, id: Int) {
        if (id <= 0 || (kind != "movie" && kind != "tv")) return
        // select() fetches full details itself, so no extra pre-fetch is needed.
        select(TitleItem(id = id, mediaType = kind))
    }

    /** Opens an actor's page: their details and every movie/show they're in, most popular first. */
    fun openPerson(cast: CastMember) {
        if (cast.id <= 0) return
        _state.update { it.copy(person = Person(id = cast.id, name = cast.name, profile = cast.profile), personBusy = true) }
        viewModelScope.launch {
            val p = runCatching { Api.tmdb.person(cast.id, key) }.getOrNull()
            if (_state.value.person?.id != cast.id) return@launch
            // Talk (10767), news (10763) and reality (10764) TV are guest spots, not roles: leave them out.
            // Order by how many people rated a title, so an actor's major work comes first.
            val guestGenres = setOf(10767, 10763, 10764)
            val credits = p?.credits?.cast.orEmpty()
                .filter { it.poster != null && (it.mediaType == "movie" || it.mediaType == "tv") }
                .filterNot { it.mediaType == "tv" && it.genreIds.orEmpty().any { g -> g in guestGenres } }
                .filterNot { it.character.orEmpty().let { c -> c.equals("Self", true) || c.startsWith("Himself", true) || c.startsWith("Herself", true) } }
                .groupBy { it.ref }.map { (_, roles) -> roles.first().copy(character = roles.mapNotNull { r -> r.character?.takeIf { c -> c.isNotBlank() } }.distinct().joinToString(" / ").ifBlank { null }) }
                .sortedWith(compareByDescending<TitleItem> { it.voteCount }.thenByDescending { it.popularity })
            _state.update { it.copy(person = (p ?: it.person)?.copy(credits = PersonCredits(credits)), personBusy = false,
                error = if (p == null) "Couldn't load ${cast.name}'s page" else it.error) }
        }
    }
    fun closePerson() { _state.update { it.copy(person = null, personBusy = false) } }

    fun dismiss() { detailJob?.cancel(); _state.update { it.copy(selected = null, detailBusy = false) } }

    fun toggleRegion(region: String) {
        if (region !in AVAILABLE_REGIONS) return
        val current = _state.value.regions
        val updated = if (region in current) current - region else current + region
        if (updated.isEmpty()) return // At least one region is required.
        _state.update { it.copy(regions = updated, providersByRegion = emptyMap()) }
        viewModelScope.launch { app.store.edit { it[REGIONS] = updated.sorted().joinToString(",") } }
        refresh() // Cancels any in-flight refresh, so rapid toggling costs one load, not many.
    }

    fun toggle(item: TitleItem) {
        val id = item.ref
        val updated = LinkedHashSet(_state.value.watchlist).apply { if (!add(id)) remove(id) }
        _state.update { s ->
            // Keep the Watchlist screen in sync without refetching everything.
            val items = if (id in updated) (s.watchlistItems.filterNot { it.ref == id } + item) else s.watchlistItems.filterNot { it.ref == id }
            s.copy(watchlist = updated, watchlistItems = items)
        }
        viewModelScope.launch { app.store.edit { it[WATCHLIST] = updated.joinToString(",") } }
    }

    fun loadWatchlist() {
        watchlistJob?.cancel()
        watchlistJob = viewModelScope.launch {
            _state.update { it.copy(watchlistBusy = true) }
            val known = (_state.value.watchlistItems + _state.value.sections.flatMap { it.items }).associateBy { it.ref }
            val gate = Semaphore(6)
            val items = coroutineScope {
                _state.value.watchlist.toList().reversed().map { ref ->
                    async {
                        known[ref]?.takeIf { it.poster != null } ?: gate.withPermit {
                            val parts = ref.split(":")
                            if (parts.size != 2) null
                            else runCatching { Api.tmdb.detail(parts[0], parts[1].toInt(), key).copy(mediaType = parts[0]) }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            _state.update { it.copy(watchlistItems = items, watchlistBusy = false) }
        }
    }
}
