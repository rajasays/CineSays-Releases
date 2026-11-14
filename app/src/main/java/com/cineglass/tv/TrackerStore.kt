package com.cineglass.tv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class TrackedShow(
    val title: String, val tvtimeId: String, val followed: Boolean, val archived: Boolean,
    val tmdbId: Int, val poster: String?, val followedAt: String,
    val watchedCount: Int, val lastSeason: Int, val lastEpisode: Int
)
data class WatchedRecord(val title: String, val season: Int, val episode: Int, val watchedAt: String)
data class TrackerStats(val shows: Int, val watched: Int, val movies: Int, val streak: Int = 0)

class TrackerStore(context: Context) : SQLiteOpenHelper(context, "cinesays_tracker.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE shows (title_key TEXT PRIMARY KEY,title TEXT NOT NULL,tvtime_id TEXT NOT NULL DEFAULT '',followed INTEGER NOT NULL DEFAULT 1,archived INTEGER NOT NULL DEFAULT 0,followed_at TEXT NOT NULL DEFAULT '',tmdb_id INTEGER NOT NULL DEFAULT 0,poster TEXT)")
        db.execSQL("CREATE TABLE watched (title_key TEXT NOT NULL,season INTEGER NOT NULL,episode INTEGER NOT NULL,watched_at TEXT NOT NULL DEFAULT '',PRIMARY KEY(title_key,season,episode))")
        db.execSQL("CREATE TABLE movies (title_key TEXT PRIMARY KEY,title TEXT NOT NULL,watched_at TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE config (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun key(title: String) = title.trim().lowercase(Locale.ROOT)

    fun addShow(title: String, tvtimeId: String = "", followed: Boolean = true, archived: Boolean = false, followedAt: String = "") {
        val sql = "INSERT OR IGNORE INTO shows(title_key,title,tvtime_id,followed,archived,followed_at) VALUES(?,?,?,?,?,?)"
        writableDatabase.execSQL(sql, arrayOf(key(title), title.trim(), tvtimeId, if(followed) 1 else 0, if(archived) 1 else 0, followedAt))
    }
    fun setFollowed(title: String, followed: Boolean) {
        addShow(title)
        writableDatabase.execSQL("UPDATE shows SET followed=? WHERE title_key=?", arrayOf(if(followed) 1 else 0,key(title)))
    }
    fun mark(title: String, season: Int, episode: Int, watched: Boolean, date: String = "") {
        require(season >= 0 && episode >= 0)
        addShow(title, followed = false)
        if (watched) writableDatabase.execSQL("INSERT OR REPLACE INTO watched(title_key,season,episode,watched_at) VALUES(?,?,?,?)",arrayOf(key(title),season,episode,date))
        else writableDatabase.execSQL("DELETE FROM watched WHERE title_key=? AND season=? AND episode=?",arrayOf(key(title),season,episode))
    }
    fun isWatched(title: String, season: Int, episode: Int): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM watched WHERE title_key=? AND season=? AND episode=? LIMIT 1",arrayOf(key(title),season.toString(),episode.toString())).use { return it.moveToFirst() }
    }
    fun setTmdb(title: String, id: Int, poster: String?) {
        writableDatabase.execSQL("UPDATE shows SET tmdb_id=?,poster=? WHERE title_key=?",arrayOf(id,poster,key(title)))
    }
    fun addMovie(title: String, date: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO movies(title_key,title,watched_at) VALUES(?,?,?)",arrayOf(key(title),title,date))
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
        val sql = """SELECT s.title,s.tvtime_id,s.followed,s.archived,s.tmdb_id,s.poster,s.followed_at,COUNT(w.episode) AS watched_count,COALESCE(MAX(w.season),0),COALESCE(MAX(w.episode),0) FROM shows s LEFT JOIN watched w ON s.title_key=w.title_key GROUP BY s.title_key ORDER BY s.followed DESC,s.archived ASC,s.title COLLATE NOCASE"""
        readableDatabase.rawQuery(sql,null).use { c -> while(c.moveToNext()) result.add(TrackedShow(c.getString(0),c.getString(1),c.getInt(2)==1,c.getInt(3)==1,c.getInt(4),c.getString(5),c.getString(6),c.getInt(7),c.getInt(8),c.getInt(9))) }
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
