package com.cineglass.tv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class TrackedShow(
    val title: String, val tvtimeId: String, val followed: Boolean, val archived: Boolean,
    val tmdbId: Int, val poster: String?, val followedAt: String,
    /** [lastSeason]/[lastEpisode] are the furthest episode watched (season first), not two separate maxima. */
    val watchedCount: Int, val lastSeason: Int, val lastEpisode: Int,
    val lastWatchedAt: String = ""
)
data class WatchedRecord(val title: String, val season: Int, val episode: Int, val watchedAt: String)
data class WatchedMovie(val title: String, val tmdbId: Int, val poster: String?, val watchedAt: String)
data class TrackerStats(val shows: Int, val watched: Int, val movies: Int, val streak: Int = 0)

/** Where a watched record came from. Imports can be removed per source; manual marks are always kept. */
object Source { const val MANUAL = "manual"; const val TVTIME = "tvtime"; const val BINGEBOXD = "bingeboxd" }

/** What one import added: shows that came from it, and its episode and movie records. */
data class ImportCounts(val shows: Int, val episodes: Int, val movies: Int) { val isEmpty get() = shows + episodes + movies == 0 }

class TrackerStore(context: Context) : SQLiteOpenHelper(context, "cinesays_tracker.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE shows (title_key TEXT PRIMARY KEY,title TEXT NOT NULL,tvtime_id TEXT NOT NULL DEFAULT '',followed INTEGER NOT NULL DEFAULT 1,archived INTEGER NOT NULL DEFAULT 0,followed_at TEXT NOT NULL DEFAULT '',tmdb_id INTEGER NOT NULL DEFAULT 0,poster TEXT)")
        db.execSQL("CREATE TABLE watched (title_key TEXT NOT NULL,season INTEGER NOT NULL,episode INTEGER NOT NULL,watched_at TEXT NOT NULL DEFAULT '',source TEXT NOT NULL DEFAULT 'manual',PRIMARY KEY(title_key,season,episode))")
        db.execSQL("CREATE TABLE movies (title_key TEXT PRIMARY KEY,title TEXT NOT NULL,watched_at TEXT NOT NULL DEFAULT '',tmdb_id INTEGER NOT NULL DEFAULT 0,poster TEXT,source TEXT NOT NULL DEFAULT 'manual')")
        db.execSQL("CREATE TABLE config (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: movies gain a TMDB id and poster (Mark as watched, BingeBoxd import). Existing rows are kept.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE movies ADD COLUMN tmdb_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE movies ADD COLUMN poster TEXT")
        }
        // v3: every record remembers its source so an import can be removed on its own.
        // Existing rows: marks made in CineSays store a plain date (yyyy-MM-dd, 10 chars); imports
        // store full timestamps. BingeBoxd shows carry a "bingeboxd:" id and its timestamps are ISO (…T…Z).
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE watched ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
            db.execSQL("ALTER TABLE movies ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
            db.execSQL("UPDATE watched SET source='bingeboxd' WHERE length(watched_at)<>10 AND title_key IN (SELECT title_key FROM shows WHERE tvtime_id LIKE 'bingeboxd:%')")
            db.execSQL("UPDATE watched SET source='tvtime' WHERE source='manual' AND length(watched_at)<>10")
            db.execSQL("UPDATE movies SET source='bingeboxd' WHERE watched_at LIKE '%T%Z' AND tmdb_id>0")
            db.execSQL("UPDATE movies SET source='tvtime' WHERE source='manual' AND (length(watched_at)<>10 OR tmdb_id=0)")
        }
        // v4: re-tag by each record's own timestamp, not its show. A show imported from TV Time and
        // BingeBoxd got all its episodes tagged "tvtime" in v3, so "Remove BingeBoxd" missed them.
        // BingeBoxd times are ISO (2018-04-25T11:55:58.000Z); TV Time's use a space; CineSays marks are yyyy-MM-dd.
        if (oldVersion < 4) {
            db.execSQL("UPDATE watched SET source='bingeboxd' WHERE watched_at LIKE '____-__-__T%Z'")
            db.execSQL("UPDATE watched SET source='tvtime' WHERE source<>'bingeboxd' AND length(watched_at)<>10")
            db.execSQL("UPDATE movies SET source='bingeboxd' WHERE watched_at LIKE '____-__-__T%Z'")
            db.execSQL("UPDATE movies SET source='tvtime' WHERE source<>'bingeboxd' AND (length(watched_at)<>10 OR tmdb_id=0)")
        }
    }

    // ── Import/removal history, so removals reach every device ──
    // {"tvtime":{"imported":ms,"removed":ms}, "bingeboxd":{…}, "_cleared": ms}
    private fun importState(): org.json.JSONObject = runCatching { org.json.JSONObject(preference("import_state") ?: "{}") }.getOrDefault(org.json.JSONObject())
    private fun saveImportState(o: org.json.JSONObject) = setPreference("import_state", o.toString())
    private fun stamp(source: String, field: String, at: Long = System.currentTimeMillis()) {
        val s = importState(); val e = s.optJSONObject(source) ?: org.json.JSONObject()
        s.put(source, e.put(field, at)); saveImportState(s)
    }
    /** Call when an import is committed: it outranks any earlier removal of that source. */
    fun noteImported(source: String) = stamp(source, "imported")
    val clearedAt: Long get() = importState().optLong("_cleared", 0)

    /** What each import contributed, for the "Remove imported history" panel. */
    fun importCounts(source: String): ImportCounts {
        fun count(sql: String, vararg a: String) = readableDatabase.rawQuery(sql, a).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val showFilter = if (source == Source.BINGEBOXD) "tvtime_id LIKE 'bingeboxd:%'" else "tvtime_id<>'' AND tvtime_id NOT LIKE 'bingeboxd:%'"
        return ImportCounts(count("SELECT COUNT(*) FROM shows WHERE $showFilter"),
            count("SELECT COUNT(*) FROM watched WHERE source=?", source), count("SELECT COUNT(*) FROM movies WHERE source=?", source))
    }

    /**
     * Deletes everything one import added: its episode and movie records, and the shows it created
     * (unless you've marked episodes of that show yourself; those shows stay, unlinked from the import).
     */
    fun removeImport(source: String, record: Boolean = true) {
        val db = writableDatabase
        val showFilter = if (source == Source.BINGEBOXD) "tvtime_id LIKE 'bingeboxd:%'" else "tvtime_id<>'' AND tvtime_id NOT LIKE 'bingeboxd:%'"
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM watched WHERE source=?", arrayOf(source))
            db.execSQL("DELETE FROM movies WHERE source=?", arrayOf(source))
            db.execSQL("DELETE FROM shows WHERE $showFilter AND title_key NOT IN (SELECT DISTINCT title_key FROM watched)")
            db.execSQL("UPDATE shows SET tvtime_id='' WHERE $showFilter")
            // Shows left with no episodes and never followed by hand are import leftovers too.
            db.execSQL("DELETE FROM shows WHERE followed=0 AND title_key NOT IN (SELECT DISTINCT title_key FROM watched)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (record) stamp(source, "removed")
    }

    /** Removes every imported record (TV Time and BingeBoxd). Your own marks stay. */
    fun removeAllImports() { removeImport(Source.TVTIME); removeImport(Source.BINGEBOXD) }

    /** Deletes all tracker data: shows, episodes, movies. Settings are kept. Synced as a "clear" to other devices. */
    fun clearAll(record: Boolean = true) {
        val db = writableDatabase
        db.beginTransaction()
        try { db.execSQL("DELETE FROM watched"); db.execSQL("DELETE FROM movies"); db.execSQL("DELETE FROM shows"); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
        if (record) { val s = importState(); s.put("_cleared", System.currentTimeMillis()); saveImportState(s) }
    }

    /**
     * Merges another device's import history and applies it: a source removed more recently than it
     * was imported gets removed here too; a newer "clear" clears this device.
     * Returns true when this device's data changed.
     */
    private fun applyImportState(remote: org.json.JSONObject): Boolean {
        val local = importState(); var changed = false
        val remoteCleared = remote.optLong("_cleared", 0)
        if (remoteCleared > local.optLong("_cleared", 0)) { clearAll(record = false); local.put("_cleared", remoteCleared); changed = true }
        for (src in listOf(Source.TVTIME, Source.BINGEBOXD)) {
            val l = local.optJSONObject(src) ?: org.json.JSONObject(); val r = remote.optJSONObject(src) ?: org.json.JSONObject()
            val imported = maxOf(l.optLong("imported", 0), r.optLong("imported", 0))
            val removed = maxOf(l.optLong("removed", 0), r.optLong("removed", 0))
            if (removed > imported && importCounts(src).let { !it.isEmpty }) { removeImport(src, record = false); changed = true }
            local.put(src, org.json.JSONObject().put("imported", imported).put("removed", removed))
        }
        saveImportState(local)
        return changed
    }
    private fun key(title: String) = title.trim().lowercase(Locale.ROOT)

    fun addShow(title: String, tvtimeId: String = "", followed: Boolean = true, archived: Boolean = false, followedAt: String = "") {
        val sql = "INSERT OR IGNORE INTO shows(title_key,title,tvtime_id,followed,archived,followed_at) VALUES(?,?,?,?,?,?)"
        writableDatabase.execSQL(sql, arrayOf(key(title), title.trim(), tvtimeId, if(followed) 1 else 0, if(archived) 1 else 0, followedAt))
    }
    fun setArchived(title: String, archived: Boolean) {
        addShow(title)
        writableDatabase.execSQL("UPDATE shows SET archived=? WHERE title_key=?", arrayOf(if(archived) 1 else 0, key(title)))
    }
    fun setFollowed(title: String, followed: Boolean) {
        addShow(title)
        writableDatabase.execSQL("UPDATE shows SET followed=? WHERE title_key=?", arrayOf(if(followed) 1 else 0,key(title)))
    }
    fun mark(title: String, season: Int, episode: Int, watched: Boolean, date: String = "", source: String = Source.MANUAL) {
        require(season >= 0 && episode >= 0)
        addShow(title, followed = false)
        // An import never overwrites an episode you marked yourself (INSERT OR IGNORE); your own marks replace.
        if (watched) writableDatabase.execSQL(
            (if (source == Source.MANUAL) "INSERT OR REPLACE" else "INSERT OR IGNORE") + " INTO watched(title_key,season,episode,watched_at,source) VALUES(?,?,?,?,?)",
            arrayOf(key(title),season,episode,date,source))
        else writableDatabase.execSQL("DELETE FROM watched WHERE title_key=? AND season=? AND episode=?",arrayOf(key(title),season,episode))
    }
    fun isWatched(title: String, season: Int, episode: Int): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM watched WHERE title_key=? AND season=? AND episode=? LIMIT 1",arrayOf(key(title),season.toString(),episode.toString())).use { return it.moveToFirst() }
    }
    fun setTmdb(title: String, id: Int, poster: String?) {
        writableDatabase.execSQL("UPDATE shows SET tmdb_id=?,poster=? WHERE title_key=?",arrayOf(id,poster,key(title)))
    }
    fun addMovie(title: String, date: String, tmdbId: Int = 0, poster: String? = null, source: String = Source.MANUAL) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO movies(title_key,title,watched_at,tmdb_id,poster,source) VALUES(?,?,?,?,?,?)",arrayOf(key(title),title,date,tmdbId,poster,source))
        if (source == Source.MANUAL) writableDatabase.execSQL("UPDATE movies SET source='manual' WHERE title_key=?", arrayOf(key(title)))
        if (tmdbId > 0) writableDatabase.execSQL("UPDATE movies SET tmdb_id=?,poster=COALESCE(?,poster) WHERE title_key=?", arrayOf(tmdbId, poster, key(title)))
    }
    fun removeMovie(title: String, tmdbId: Int) {
        writableDatabase.execSQL("DELETE FROM movies WHERE title_key=? OR (tmdb_id>0 AND tmdb_id=?)", arrayOf(key(title), tmdbId))
    }
    fun watchedMovieIds(): Set<Int> {
        val out = mutableSetOf<Int>()
        readableDatabase.rawQuery("SELECT tmdb_id FROM movies WHERE tmdb_id>0", null).use { c -> while (c.moveToNext()) out.add(c.getInt(0)) }
        return out
    }
    fun watchedMovies(limit: Int = 500): List<WatchedMovie> {
        val out = mutableListOf<WatchedMovie>()
        readableDatabase.rawQuery("SELECT title,tmdb_id,poster,watched_at FROM movies ORDER BY watched_at DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(WatchedMovie(c.getString(0), c.getInt(1), c.getString(2), c.getString(3)))
        }
        return out
    }
    /** Movies imported by title only (TV Time): fill in their TMDB id/poster later. */
    fun moviesMissingTmdb(limit: Int = 200): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT title FROM movies WHERE tmdb_id=0 LIMIT ?", arrayOf(limit.toString())).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }
    fun showTitleByTmdb(id: Int): String? =
        readableDatabase.rawQuery("SELECT title FROM shows WHERE tmdb_id=? LIMIT 1", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    // ── Backup / restore (cloud sync). Plain JSON; merged, never destructive. ──
    fun exportJson(): String {
        val root = org.json.JSONObject()
        val shows = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT title,tvtime_id,followed,archived,followed_at,tmdb_id,poster FROM shows", null).use { c ->
            while (c.moveToNext()) shows.put(org.json.JSONArray().put(c.getString(0)).put(c.getString(1)).put(c.getInt(2)).put(c.getInt(3)).put(c.getString(4)).put(c.getInt(5)).put(c.getString(6) ?: ""))
        }
        val watched = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT s.title,w.season,w.episode,w.watched_at,w.source FROM watched w JOIN shows s ON s.title_key=w.title_key", null).use { c ->
            while (c.moveToNext()) watched.put(org.json.JSONArray().put(c.getString(0)).put(c.getInt(1)).put(c.getInt(2)).put(c.getString(3)).put(c.getString(4)))
        }
        val movies = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT title,watched_at,tmdb_id,poster,source FROM movies", null).use { c ->
            while (c.moveToNext()) movies.put(org.json.JSONArray().put(c.getString(0)).put(c.getString(1)).put(c.getInt(2)).put(c.getString(3) ?: "").put(c.getString(4)))
        }
        return root.put("v", 2).put("exportedAt", System.currentTimeMillis()).put("importState", importState())
            .put("shows", shows).put("watched", watched).put("movies", movies).toString()
    }

    /**
     * Merges a backup from another device. Removals and clears recorded in it are applied first;
     * records are merged unless this device was cleared after that backup was made; then removals
     * are applied again so merged rows can't bring a removed import back.
     */
    fun importJson(json: String) {
        val root = org.json.JSONObject(json)
        val remoteState = root.optJSONObject("importState") ?: org.json.JSONObject()
        applyImportState(remoteState)
        if (clearedAt > 0 && root.optLong("exportedAt", 0) in 1 until clearedAt) return // backup predates our clear
        mergeRows(root)
        applyImportState(remoteState)
    }
    private fun mergeRows(root: org.json.JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            root.optJSONArray("shows")?.let { a -> for (i in 0 until a.length()) { val r = a.getJSONArray(i)
                addShow(r.getString(0), r.optString(1), r.optInt(2) == 1, r.optInt(3) == 1, r.optString(4))
                if (r.optInt(5) > 0) db.execSQL("UPDATE shows SET tmdb_id=?,poster=NULLIF(?, '') WHERE title_key=? AND tmdb_id=0", arrayOf(r.optInt(5), r.optString(6), key(r.getString(0))))
            } }
            root.optJSONArray("watched")?.let { a -> for (i in 0 until a.length()) { val r = a.getJSONArray(i)
                db.execSQL("INSERT OR IGNORE INTO watched(title_key,season,episode,watched_at,source) VALUES(?,?,?,?,?)",
                    arrayOf(key(r.getString(0)), r.getInt(1), r.getInt(2), r.optString(3), r.optString(4, Source.MANUAL).ifBlank { Source.MANUAL }))
            } }
            root.optJSONArray("movies")?.let { a -> for (i in 0 until a.length()) { val r = a.getJSONArray(i)
                addMovie(r.getString(0), r.optString(1), r.optInt(2), r.optString(3).ifBlank { null }, r.optString(4, Source.MANUAL).ifBlank { Source.MANUAL })
            } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun preference(name: String): String? {
        readableDatabase.rawQuery("SELECT value FROM config WHERE key=?",arrayOf(name)).use { return if(it.moveToFirst()) it.getString(0) else null }
    }
    fun setPreference(name: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO config(key,value) VALUES(?,?)",arrayOf(name,value))
    }
    fun stats(): TrackerStats {
        fun count(query: String): Int = readableDatabase.rawQuery(query,null).use { if(it.moveToFirst()) it.getInt(0) else 0 }
        return TrackerStats(count("SELECT COUNT(*) FROM shows WHERE followed=1 AND archived=0"),count("SELECT COUNT(*) FROM watched"),count("SELECT COUNT(*) FROM movies"), calculateStreak())
    }
    private fun calculateStreak(): Int {
        val dates = mutableSetOf<java.time.LocalDate>()
        // DISTINCT day in SQL: an imported TV Time history can have tens of thousands of rows,
        // and this runs after every episode toggle.
        readableDatabase.rawQuery("SELECT DISTINCT substr(watched_at,1,10) FROM watched WHERE watched_at != ''", null).use { c ->
            while(c.moveToNext()) {
                // TV Time timestamps can contain times; use only ISO calendar dates.
                val text = c.getString(0).take(10)
                runCatching { java.time.LocalDate.parse(text) }.getOrNull()?.let { dates.add(it) }
            }
        }
        var day = java.time.LocalDate.now()
        if(day !in dates) day = day.minusDays(1)
        var count = 0
        while(day in dates) { count++; day = day.minusDays(1) }
        return count
    }
    fun markBatch(title: String, episodes: List<TrackerEpisode>, watched: Boolean) {
        writableDatabase.beginTransaction()
        try {
            episodes.filter { it.number > 0 }.forEach { episode ->
                mark(title, episode.season, episode.number, watched, if(watched) java.time.LocalDate.now().toString() else "")
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun showByTmdb(id: Int): TrackedShow? = allShows().firstOrNull { it.tmdbId == id }
    fun followTmdb(item: TitleItem) {
        if(item.kind != "tv") return
        setFollowed(item.label, true)
        setTmdb(item.label, item.id, item.poster)
    }
    fun allShows(): List<TrackedShow> {
        val result = mutableListOf<TrackedShow>()
        // Furthest episode = max(season*10000+episode): MAX(season) and MAX(episode) separately mixed up seasons.
        val sql = """SELECT s.title,s.tvtime_id,s.followed,s.archived,s.tmdb_id,s.poster,s.followed_at,COUNT(w.episode) AS watched_count,COALESCE(MAX(w.season*10000+w.episode),0),COALESCE(MAX(w.watched_at),'') FROM shows s LEFT JOIN watched w ON s.title_key=w.title_key GROUP BY s.title_key ORDER BY s.followed DESC,s.archived ASC,s.title COLLATE NOCASE"""
        readableDatabase.rawQuery(sql,null).use { c -> while(c.moveToNext()) { val pos = c.getInt(8)
            result.add(TrackedShow(c.getString(0),c.getString(1),c.getInt(2)==1,c.getInt(3)==1,c.getInt(4),c.getString(5),c.getString(6),c.getInt(7),pos/10000,pos%10000,c.getString(9))) } }
        return result
    }
    fun watchedRecords(limit: Int=100): List<WatchedRecord> {
        val out=mutableListOf<WatchedRecord>()
        readableDatabase.rawQuery("SELECT s.title,w.season,w.episode,w.watched_at FROM watched w JOIN shows s ON s.title_key=w.title_key ORDER BY w.watched_at DESC LIMIT ?",arrayOf(limit.toString())).use { c -> while(c.moveToNext()) out.add(WatchedRecord(c.getString(0),c.getInt(1),c.getInt(2),c.getString(3))) }
        return out
    }
    fun allWatchedFor(title: String): Set<Pair<Int,Int>> {
        val out=mutableSetOf<Pair<Int,Int>>()
        readableDatabase.rawQuery("SELECT season,episode FROM watched WHERE title_key=?",arrayOf(key(title))).use { c -> while(c.moveToNext()) out.add(c.getInt(0) to c.getInt(1)) }
        return out
    }
}
