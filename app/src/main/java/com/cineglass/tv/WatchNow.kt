package com.cineglass.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

// ───────────────────────────── Watch now: exact-title links ─────────────────────────────
// TMDB says *which* service has a title but not the title's page on that service. Watchmode's free
// Developer plan (non-commercial, attribution required) returns that page as `web_url`, e.g.
// https://www.netflix.com/title/80057281. One lookup costs 2 of the plan's 2,500 monthly credits,
// so lookups happen only when a Watch button is pressed and are cached for 30 days (the most the free
// plan allows). Without a WATCHMODE_API_KEY, or when nothing matches, buttons open the service's app.

data class WatchSource(
    @SerializedName("source_id") val sourceId: Int = 0, val name: String = "", val type: String = "",
    val region: String? = null, @SerializedName("web_url") val webUrl: String? = null
)

interface WatchmodeApi {
    /** [id] is TMDB's id in Watchmode's form: "movie-550" or "tv-1396". Sources for every region on the plan. */
    @GET("v1/title/{id}/sources/") suspend fun sources(@Path("id") id: String, @Header("X-API-Key") key: String): JsonElement
}

object WatchNow {
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private val gson = Gson()
    private val scope = MainScope()
    private val key get() = BuildConfig.WATCHMODE_API_KEY
    val enabled get() = key.isNotBlank()
    private val api: WatchmodeApi by lazy {
        Retrofit.Builder().baseUrl("https://api.watchmode.com/").addConverterFactory(GsonConverterFactory.create())
            .build().create(WatchmodeApi::class.java)
    }
    private data class Cached(val at: Long = 0, val sources: List<WatchSource> = emptyList())

    private suspend fun sources(context: Context, kind: String, tmdbId: Int): List<WatchSource> {
        val id = "$kind-$tmdbId"
        val prefs = context.getSharedPreferences("watch_links", Context.MODE_PRIVATE)
        withContext(Dispatchers.IO) {
            prefs.getString(id, null)?.let { runCatching { gson.fromJson(it, Cached::class.java) }.getOrNull() }
        }?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.sources }
        val json = api.sources(id, key)
        // The endpoint returns a bare array; accept {"sources": [...]} too in case that changes.
        val array = if (json.isJsonArray) json else json.asJsonObject.get("sources") ?: return emptyList()
        val list: List<WatchSource> = gson.fromJson(array, object : TypeToken<List<WatchSource>>() {}.type)
        withContext(Dispatchers.IO) { prefs.edit().putString(id, gson.toJson(Cached(System.currentTimeMillis(), list))).apply() }
        return list
    }

    /** Does a Watchmode source name describe this TMDB provider? */
    private fun sameService(providerId: Int, providerName: String, sourceName: String): Boolean {
        val s = sourceName.lowercase().filter { it.isLetterOrDigit() }
        return when (providerId) {
            8 -> "netflix" in s
            9, 119, 2100, PRIME_FREE_ID -> "prime" in s || "amazon" in s
            350, 2 -> "appletv" in s
            1899, 384 -> s == "max" || "hbo" in s
            337 -> "disney" in s
            531 -> "paramount" in s
            15 -> "hulu" in s
            232 -> "zee5" in s
            2336 -> "hotstar" in s
            237, 2792 -> "sonyliv" in s
            11 -> "mubi" in s
            73 -> "tubi" in s
            300 -> "pluto" in s
            538 -> "plex" in s
            192, 235, 188 -> "youtube" in s
            3 -> "google" in s
            else -> providerName.lowercase().filter { it.isLetterOrDigit() }.let { it.isNotEmpty() && (it in s || s in it) }
        }
    }

    /** The title's page on [provider], preferring the viewer's regions in order, then no-extra-cost offers. */
    suspend fun link(context: Context, kind: String, tmdbId: Int, provider: Provider, regions: List<String>): String? {
        val matches = sources(context, kind, tmdbId).filter { !it.webUrl.isNullOrBlank() && it.webUrl.startsWith("http") && sameService(provider.id, provider.name, it.name) }
        val typeOrder = if (provider.id == PRIME_FREE_ID) listOf("free", "sub", "tve", "rent", "buy") else listOf("sub", "free", "tve", "rent", "buy")
        return matches.sortedWith(compareBy(
            { regions.indexOf(it.region).let { i -> if (i < 0) regions.size else i } },
            { typeOrder.indexOf(it.type).let { i -> if (i < 0) typeOrder.size else i } }
        )).firstOrNull()?.webUrl
    }

    /** Opens [url] in the service's own app when installed, else in whatever handles the link. */
    fun open(context: Context, url: String, providerId: Int): Boolean {
        val uri = Uri.parse(url)
        for (pkg in providerPackages(providerId)) {
            // Netflix's TV app takes /watch/ID links; Watchmode gives /title/ID.
            val target = if (pkg == "com.netflix.ninja") Uri.parse(url.replace("/title/", "/watch/")) else uri
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, target).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply { if (pkg == "com.netflix.ninja") putExtra("source", "30") })
                return true
            } catch (_: ActivityNotFoundException) { /* Not installed or doesn't take links: try the next one. */ }
            catch (_: SecurityException) { }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: ActivityNotFoundException) { false }
    }

    /** Used by every "Open in …" button: exact title when known, otherwise the service's app. */
    fun watch(context: Context, provider: Provider, item: TitleItem, regions: Collection<String>) {
        val app = context.applicationContext
        if (!enabled) { openProviderApp(context, provider, item.label); return }
        scope.launch {
            val url = runCatching { link(app, item.kind, item.id, provider, regions.toList()) }.getOrNull()
            if (url == null || !open(app, url, provider.id)) openProviderApp(context, provider, item.label)
        }
    }
}
