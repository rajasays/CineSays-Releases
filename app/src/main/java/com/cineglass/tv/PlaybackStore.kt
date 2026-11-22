package com.cineglass.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// ───────────────────────────── Watch progress for in-app playback ─────────────────────────────
// One row per movie / episode / free film (key = MediaRef.key, or "ia:<id>" for Free tab films).
// Only playback inside CineSays writes here; opening another app never does.

data class PlaybackRecord(
    val key: String,
    /** "movie", "episode" or "film" (Free tab film without a TMDB match). */
    val type: String,
    val tmdbId: Int = 0,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val episodeName: String? = null,
    val poster: String? = null,
    val imdbId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
    /** The stream last played, so Continue Watching can resume it directly. */
    val streamUrl: String? = null,
    val streamKind: String? = null,
    val sourceName: String? = null,
    val licence: String? = null
) {
    val fraction get() = ProgressRules.fraction(positionMs, durationMs)
    val subtitle: String get() = listOfNotNull(
        if (type == "episode") "S$season E$episode" + (episodeName?.let { " · $it" } ?: "") else null,
        if (durationMs > 0) "${ProgressRules.clock((durationMs - positionMs).coerceAtLeast(0))} left" else null
    ).joinToString(" · ")

    fun ref(): MediaRef? = when (type) {
        "movie" -> MediaRef(MediaType.MOVIE, tmdbId, title, imdbId, poster = poster)
        "episode" -> MediaRef(MediaType.EPISODE, tmdbId, title, imdbId, season = season, episode = episode, episodeName = episodeName, poster = poster)
        else -> null
    }
}

class PlaybackStore private constructor(private val context: Context) : SQLiteOpenHelper(context, "playback.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE progress(key TEXT PRIMARY KEY, type TEXT NOT NULL, tmdb INTEGER NOT NULL DEFAULT 0,
            season INTEGER, episode INTEGER, title TEXT NOT NULL, episode_name TEXT, poster TEXT, imdb TEXT,
            position INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL,
            completed INTEGER NOT NULL DEFAULT 0, stream_url TEXT, stream_kind TEXT, source_name TEXT, licence TEXT)""")
        db.execSQL("CREATE INDEX progress_updated ON progress(updated)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun get(key: String): PlaybackRecord? = readableDatabase.rawQuery("SELECT * FROM progress WHERE key=?", arrayOf(key)).use { c ->
        if (!c.moveToFirst()) null else read(c)
    }

    fun save(r: PlaybackRecord) {
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("key", r.key); put("type", r.type); put("tmdb", r.tmdbId); put("season", r.season); put("episode", r.episode)
            put("title", r.title); put("episode_name", r.episodeName); put("poster", r.poster); put("imdb", r.imdbId)
            put("position", r.positionMs); put("duration", r.durationMs); put("updated", r.updatedAt); put("completed", if (r.completed) 1 else 0)
            put("stream_url", r.streamUrl); put("stream_kind", r.streamKind); put("source_name", r.sourceName); put("licence", r.licence)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(key: String) { writableDatabase.delete("progress", "key=?", arrayOf(key)) }

    // ── Sync across signed-in devices (part of the account backup) ──
    // Each title's newest position wins. Removing a title from Continue watching leaves a dated marker, so the
    // removal reaches other devices instead of the title coming back from their copy.

    private val removedPrefs = context.getSharedPreferences("playback_removed", Context.MODE_PRIVATE)

    /** "Remove from Continue watching": forget the position here and on your other devices. */
    fun removeEverywhere(key: String) {
        remove(key)
        removedPrefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    fun exportJson(): org.json.JSONObject {
        val records = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT * FROM progress WHERE type!='test' ORDER BY updated DESC LIMIT 300", null).use { c ->
            while (c.moveToNext()) read(c).let { r ->
                records.put(org.json.JSONObject().put("key", r.key).put("type", r.type).put("tmdb", r.tmdbId)
                    .put("season", r.season ?: org.json.JSONObject.NULL).put("episode", r.episode ?: org.json.JSONObject.NULL)
                    .put("title", r.title).put("episodeName", r.episodeName).put("poster", r.poster).put("imdb", r.imdbId)
                    .put("position", r.positionMs).put("duration", r.durationMs).put("updated", r.updatedAt).put("completed", r.completed)
                    // Only links another device can open: no addresses served from this device itself.
                    .put("streamUrl", r.streamUrl?.takeIf { shareable(it) }).put("streamKind", r.streamKind)
                    .put("sourceName", r.sourceName).put("licence", r.licence))
            }
        }
        val cutoff = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
        val removed = org.json.JSONObject()
        removedPrefs.all.forEach { (k, v) -> (v as? Long)?.takeIf { it > cutoff }?.let { removed.put(k, it) } }
        return org.json.JSONObject().put("records", records).put("removed", removed)
    }

    fun importJson(o: org.json.JSONObject) {
        val removed = o.optJSONObject("removed")
        val edit = removedPrefs.edit()
        removed?.keys()?.forEach { k ->
            val at = removed.optLong(k)
            if (at > removedPrefs.getLong(k, 0)) edit.putLong(k, at)
            get(k)?.let { if (it.updatedAt <= at) remove(k) }
        }
        edit.apply()
        val arr = o.optJSONArray("records") ?: return
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            fun str(n: String) = j.optString(n).takeIf { !j.isNull(n) && it.isNotEmpty() }
            fun int(n: String) = if (j.isNull(n) || !j.has(n)) null else j.optInt(n)
            val key = str("key") ?: continue
            val updated = j.optLong("updated")
            if (removedPrefs.getLong(key, 0) >= updated) continue
            val local = get(key)
            if (local != null && local.updatedAt >= updated) continue
            save(PlaybackRecord(key, str("type") ?: continue, j.optInt("tmdb"), int("season"), int("episode"), str("title") ?: continue,
                str("episodeName"), str("poster"), str("imdb"), j.optLong("position"), j.optLong("duration"), updated, j.optBoolean("completed"),
                str("streamUrl") ?: local?.streamUrl, str("streamKind") ?: local?.streamKind, str("sourceName"), str("licence")))
        }
    }

    private fun shareable(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return url.startsWith("http") && host != "localhost" && host != "127.0.0.1" && host != "::1" && !host.startsWith("192.168.") && !host.startsWith("10.")
    }

    /** Started but not finished, most recent first. */
    fun continueWatching(limit: Int = 20): List<PlaybackRecord> =
        readableDatabase.rawQuery("SELECT * FROM progress WHERE completed=0 AND position>=? ORDER BY updated DESC LIMIT ?",
            arrayOf(ProgressRules.MIN_RESUME_MS.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }

    /** Saved positions for one series' episodes in a season: episode number → record. */
    fun forSeason(tmdbId: Int, season: Int): Map<Int, PlaybackRecord> =
        readableDatabase.rawQuery("SELECT * FROM progress WHERE type='episode' AND tmdb=? AND season=?", arrayOf(tmdbId.toString(), season.toString())).use { c ->
            buildMap { while (c.moveToNext()) read(c).let { r -> r.episode?.let { put(it, r) } } }
        }

    private fun read(c: android.database.Cursor): PlaybackRecord {
        fun s(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) null else c.getLong(it) }
        return PlaybackRecord(s("key")!!, s("type")!!, l("tmdb")?.toInt() ?: 0, l("season")?.toInt(), l("episode")?.toInt(),
            s("title")!!, s("episode_name"), s("poster"), s("imdb"), l("position") ?: 0, l("duration") ?: 0, l("updated") ?: 0,
            (l("completed") ?: 0) == 1L, s("stream_url"), s("stream_kind"), s("source_name"), s("licence"))
    }

    companion object {
        @Volatile private var instance: PlaybackStore? = null
        fun get(context: Context): PlaybackStore = instance ?: synchronized(this) {
            instance ?: PlaybackStore(context.applicationContext).also { instance = it }
        }
    }
}

/** Settings › Playback & Streaming. Nothing sensitive is stored here (CineSays keeps no streaming credentials). */
data class PlaybackPrefs(
    /** "auto", "2160", "1080", "720", "480": the highest video height CineSays picks automatically. */
    val quality: String = "auto",
    /** ISO 639 code or "" for the stream's default. */
    val audioLanguage: String = "",
    val subtitleLanguage: String = "en",
    /** "off", "auto" (only when the audio isn't in your language), "on". */
    val subtitleMode: String = "auto",
    /** 0.8, 1.0, 1.3, 1.6 × the default size. */
    val subtitleScale: Float = 1.0f,
    /** "ask", "always", "never". */
    val resume: String = "ask",
    val destination: PlaybackDestination = PlaybackDestination.ASK,
    /** "standard" or "large" (more buffer for unsteady connections). */
    val buffering: String = "standard",
    /** 0.80 – 0.95: how far in a title counts as watched. */
    val completeAt: Double = ProgressRules.DEFAULT_THRESHOLD
) {
    val maxHeight: Int? get() = quality.toIntOrNull()

    companion object {
        private const val FILE = "playback_prefs"
        fun load(context: Context): PlaybackPrefs {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return PlaybackPrefs(
                quality = p.getString("quality", "auto")!!,
                audioLanguage = p.getString("audio", "")!!,
                subtitleLanguage = p.getString("sub_lang", "en")!!,
                subtitleMode = p.getString("sub_mode", "auto")!!,
                subtitleScale = p.getFloat("sub_scale", 1.0f),
                resume = p.getString("resume", "ask")!!,
                destination = runCatching { PlaybackDestination.valueOf(p.getString("destination", "ASK")!!) }.getOrDefault(PlaybackDestination.ASK),
                buffering = p.getString("buffering", "standard")!!,
                // Stored as a float; round back to whole percent (0.9f reads as 0.8999…).
                completeAt = Math.round(p.getFloat("complete_at", ProgressRules.DEFAULT_THRESHOLD.toFloat()) * 100) / 100.0
            )
        }
        fun save(context: Context, v: PlaybackPrefs) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("quality", v.quality).putString("audio", v.audioLanguage).putString("sub_lang", v.subtitleLanguage)
                .putString("sub_mode", v.subtitleMode).putFloat("sub_scale", v.subtitleScale).putString("resume", v.resume)
                .putString("destination", v.destination.name).putString("buffering", v.buffering)
                .putFloat("complete_at", v.completeAt.toFloat()).apply()
        }
    }
}
