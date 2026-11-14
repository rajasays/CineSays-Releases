package com.cineglass.tv

import android.content.Context
import com.google.gson.annotations.SerializedName
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import java.io.File
import java.util.concurrent.TimeUnit

data class CatalogPage(val results: List<TitleItem> = emptyList(), @SerializedName("total_pages") val totalPages: Int = 1)
data class Genre(val id: Int = 0, val name: String = "")
data class GenrePage(val genres: List<Genre> = emptyList())
data class TitleItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerializedName("poster_path") val poster: String? = null,
    @SerializedName("backdrop_path") val backdrop: String? = null,
    val overview: String? = null,
    @SerializedName("vote_average") val rating: Double = 0.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    // Only filled in by the details call.
    val runtime: Int? = null,
    @SerializedName("number_of_seasons") val seasons: Int? = null,
    val genres: List<Genre>? = null,
    val tagline: String? = null
) {
    val label: String get() = title ?: name ?: "Untitled"
    val kind: String get() = if (mediaType == "tv" || (mediaType == null && name != null && title == null)) "tv" else "movie"
    /** Stable watchlist / list key, e.g. "movie:550". */
    val ref: String get() = "$kind:$id"
    val year: String get() = (releaseDate ?: firstAirDate).orEmpty().take(4)
    // w342 is sharp for ~150dp TV posters and about half the bytes of w500.
    fun image(size: String = "w342") = poster?.let { "https://image.tmdb.org/t/p/$size$it" }
    fun hero() = backdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }
}
data class VideoPage(val results: List<Video> = emptyList())
data class Video(val key: String = "", val site: String = "", val type: String = "", val official: Boolean = false)
data class ProviderPage(val results: Map<String, ProviderRegion> = emptyMap())
data class ProviderRegion(
    val link: String? = null, val flatrate: List<Provider> = emptyList(), val rent: List<Provider> = emptyList(), val buy: List<Provider> = emptyList(),
    // Free and ad-supported offers (Tubi, Pluto TV, Plex…). Gson leaves absent lists null, hence nullable.
    val free: List<Provider>? = null, val ads: List<Provider>? = null
) {
    /** Offers that cost nothing beyond a subscription the viewer may already have. */
    val noExtraCost: List<Provider> get() = (flatrate + free.orEmpty() + ads.orEmpty()).distinctBy { it.id }
    val freeWithAds: List<Provider> get() = (free.orEmpty() + ads.orEmpty()).distinctBy { it.id }
}
data class Provider(@SerializedName("provider_id") val id: Int, @SerializedName("provider_name") val name: String, @SerializedName("logo_path") val logo: String?)

data class Credits(val cast: List<CastMember> = emptyList())
data class CastMember(val name: String = "", val character: String? = null, @SerializedName("profile_path") val profile: String? = null) {
    fun image() = profile?.let { "https://image.tmdb.org/t/p/w185$it" }
}

/** One request instead of four: details + videos + providers + external IDs via append_to_response. */
data class FullDetail(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerializedName("poster_path") val poster: String? = null,
    @SerializedName("backdrop_path") val backdrop: String? = null,
    val overview: String? = null,
    @SerializedName("vote_average") val rating: Double = 0.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("episode_run_time") val episodeRunTime: List<Int>? = null,
    @SerializedName("number_of_seasons") val seasons: Int? = null,
    val genres: List<Genre>? = null,
    val tagline: String? = null,
    val videos: VideoPage? = null,
    @SerializedName("watch/providers") val providers: ProviderPage? = null,
    @SerializedName("external_ids") val externalIds: ExternalIds? = null,
    val credits: Credits? = null,
    val recommendations: CatalogPage? = null
) {
    fun toItem(kind: String) = TitleItem(
        id, title, name, poster, backdrop, overview, rating, releaseDate, firstAirDate, kind,
        runtime ?: episodeRunTime?.firstOrNull(), seasons, genres, tagline?.takeIf { it.isNotBlank() }
    )
}

data class TrackerTmdbSearch(val results: List<TrackerTmdbShow> = emptyList())
data class TrackerTmdbShow(
    val id: Int = 0, val name: String = "",
    @SerializedName("poster_path") val poster: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null
)
data class TrackerEpisode(
    @SerializedName("season_number") val season: Int = 0,
    @SerializedName("episode_number") val number: Int = 0,
    val name: String = "",
    @SerializedName("air_date") val airDate: String? = null,
    @SerializedName("still_path") val still: String? = null
)
data class TrackerTvDetails(
    val id: Int = 0,
    val name: String = "",
    @SerializedName("number_of_episodes") val episodeCount: Int = 0,
    @SerializedName("next_episode_to_air") val next: TrackerEpisode? = null,
    @SerializedName("last_episode_to_air") val last: TrackerEpisode? = null,
    @SerializedName("poster_path") val poster: String? = null
)
data class TrackerSeasonDetails(val episodes: List<TrackerEpisode> = emptyList())
interface TmdbApi {
    @GET("search/tv") suspend fun searchTv(@Query("api_key") key: String, @Query("query") title: String): TrackerTmdbSearch
    @GET("tv/{id}") suspend fun trackerTv(@Path("id") id: Int, @Query("api_key") key: String): TrackerTvDetails
    @GET("tv/{id}/season/{season}") suspend fun trackerSeason(@Path("id") id: Int, @Path("season") season: Int, @Query("api_key") key: String): TrackerSeasonDetails

    @GET("trending/{kind}/week") suspend fun trending(@Path("kind") kind: String, @Query("api_key") key: String, @Query("language") language: String = "en-US"): CatalogPage
    @GET("discover/{kind}") suspend fun discover(@Path("kind") kind: String, @Query("api_key") key: String, @Query("watch_region") region: String, @Query("with_watch_providers") providers: String? = null, @Query("with_watch_monetization_types") monetization: String? = null, @Query("sort_by") sort: String = "popularity.desc", @Query("with_genres") genre: Int? = null, @Query("page") page: Int = 1, @Query("include_adult") adult: Boolean = false, @QueryMap extra: Map<String, String> = emptyMap()): CatalogPage
    @GET("genre/{kind}/list") suspend fun genres(@Path("kind") kind: String, @Query("api_key") key: String): GenrePage
    @GET("search/multi") suspend fun search(@Query("api_key") key: String, @Query("query") query: String, @Query("include_adult") adult: Boolean = false): CatalogPage
    @GET("{kind}/{id}/videos") suspend fun videos(@Path("kind") kind: String, @Path("id") id: Int, @Query("api_key") key: String): VideoPage
    @GET("{kind}/{id}/watch/providers") suspend fun providers(@Path("kind") kind: String, @Path("id") id: Int, @Query("api_key") key: String): ProviderPage
    @GET("{kind}/{id}/external_ids") suspend fun externalIds(@Path("kind") kind: String, @Path("id") id: Int, @Query("api_key") key: String): ExternalIds
    @GET("{kind}/{id}") suspend fun detail(@Path("kind") kind: String, @Path("id") id: Int, @Query("api_key") key: String): TitleItem
    @GET("{kind}/{id}") suspend fun fullDetail(
        @Path("kind") kind: String, @Path("id") id: Int, @Query("api_key") key: String,
        @Query("append_to_response") append: String = "videos,watch/providers,external_ids,credits,recommendations"
    ): FullDetail
}
data class ExternalIds(@SerializedName("imdb_id") val imdbId: String? = null)
data class OmdbRating(val Source: String = "", val Value: String = "")
data class OmdbResult(val Response: String = "False", val imdbRating: String? = null, val Ratings: List<OmdbRating> = emptyList())
interface OmdbApi {
    @GET("/") suspend fun detail(@Query("apikey") key: String, @Query("i") imdbId: String): OmdbResult
}
object Api {
    @Volatile private var cacheDir: File? = null
    /** Call once from the Activity so TMDB responses share a 20 MB HTTP cache. Safe to skip (e.g. in workers). */
    fun init(context: Context) { if (cacheDir == null) cacheDir = File(context.cacheDir, "http") }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .apply { cacheDir?.let { cache(Cache(it, 20L * 1024 * 1024)) } }
            .build()
    }
    val omdb: OmdbApi by lazy {
        Retrofit.Builder().baseUrl("https://www.omdbapi.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(OmdbApi::class.java)
    }
    val tmdb: TmdbApi by lazy {
        Retrofit.Builder().baseUrl("https://api.themoviedb.org/3/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(TmdbApi::class.java)
    }
}
