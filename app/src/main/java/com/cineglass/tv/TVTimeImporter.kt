package com.cineglass.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class TrackerImportPreview(val shows: Int, val watchedEpisodes: Int, val watchedMovies: Int, val format: String, val warnings: List<String>)
private data class RawShow(val id:String,val title:String,val followed:Boolean,val archived:Boolean,val followedAt:String)
private data class RawEpisode(val title:String,val season:Int,val episode:Int,val watchedAt:String)
private data class RawMovie(val title:String,val watchedAt:String)

/** Imports a TV Time GDPR export, a CineSays-prepared TV Time ZIP, or a BingeBoxd export (TMDB ids). */
class TVTimeImporter(private val context: Context) {
    private val shows = linkedMapOf<String,RawShow>()
    private val episodes = linkedMapOf<String,RawEpisode>()
    private val movies = linkedMapOf<String,RawMovie>()
    // BingeBoxd: everything is keyed by TMDB id; titles are fetched from TMDB on commit.
    private val bbStatus = linkedMapOf<Int,String>()                  // showId -> session status
    private val bbEpisodes = linkedMapOf<String,Triple<Int,Pair<Int,Int>,String>>() // "id:s:e" -> (showId,(s,e),watchedAt)
    private val bbMovies = linkedMapOf<Int,String>()                  // movieId -> first watchedAt
    private var isBingeBoxd = false
    private var detectedFormat = "Unknown ZIP"
    private val warnings = mutableListOf<String>()

    private fun readRows(bytes: ByteArray): List<Map<String,String>> {
        InputStreamReader(ByteArrayInputStream(bytes),Charsets.UTF_8).use { reader ->
            val format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true).build()
            CSVParser(reader,format).use { csv ->
                return csv.records.map { row -> csv.headerNames.associate { name -> name.removePrefix("\uFEFF") to (row.get(name) ?: "") } }
            }
        }
    }
    private fun key(s:String) = s.trim().lowercase(java.util.Locale.ROOT)
    private fun addShow(id:String, title:String, followed:Boolean, archived:Boolean, date:String) {
        if(title.isBlank()) return
        val k=key(title)
        if(shows[k]==null || (followed && shows[k]?.followed==false)) shows[k]=RawShow(id,title.trim(),followed,archived,date)
    }
    private fun addEp(title:String, season:String?, episode:String?, date:String?) {
        val s=season?.toIntOrNull() ?: return
        val e=episode?.toIntOrNull() ?: return
        if (title.isBlank() || s<0 || e<0) return
        val k="${key(title)}:$s:$e"
        if (episodes[k]==null || (date?.isNotBlank()==true && (episodes[k]!!.watchedAt.isBlank() || date < episodes[k]!!.watchedAt)))
            episodes[k]=RawEpisode(title.trim(),s,e,date.orEmpty())
    }
    fun inspect(uri:Uri): TrackerImportPreview {
        shows.clear();episodes.clear();movies.clear();warnings.clear()
        bbStatus.clear();bbEpisodes.clear();bbMovies.clear();isBingeBoxd=false
        detectedFormat="Unknown ZIP"
        // Only tracking tables are read; account, notification and social files are ignored.
        val wanted=setOf("shows.csv","watched_episodes.csv","watched_movies.csv","followed_tv_show.csv","tracking-prod-records-v2.csv","tracking-prod-records.csv",
            "episode-watches.csv","movie-watches.csv","watch-sessions.csv")
        val files=mutableMapOf<String,ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    val name=entry.name.substringAfterLast('/')
                    if(!entry.isDirectory && name in wanted) {
                        // Limit decompression per entry to reduce malicious ZIP expansion.
                        val output=java.io.ByteArrayOutputStream()
                        val buf=ByteArray(8192)
                        while(true) {
                            val n=zip.read(buf);if(n<=0) break
                            if(output.size()+n > 15_000_000) error("CSV entry is too large: $name")
                            output.write(buf,0,n)
                        }
                        files[name]=output.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to read selected ZIP")
        if("episode-watches.csv" in files || "watch-sessions.csv" in files) {
            detectedFormat="BingeBoxd export"
            isBingeBoxd=true
            val epId=Regex("^S(\\d+)S(\\d+)E(\\d+)$")
            files["watch-sessions.csv"]?.let { readRows(it).forEach { r ->
                val id=r["showId"]?.toIntOrNull() ?: return@forEach
                // A rewatch session must not downgrade an earlier status; keep the most "active" one.
                val status=r["status"].orEmpty()
                val rank={ s:String -> when(s){ "in-progress","caught-up","paused","not-started" -> 3; "completed" -> 2; "dropped" -> 1; else -> 0 } }
                if(rank(status) >= rank(bbStatus[id].orEmpty())) bbStatus[id]=status
            } }
            files["episode-watches.csv"]?.let { readRows(it).forEach { r ->
                val m=epId.find(r["episodeId"].orEmpty()) ?: return@forEach
                val (show,s,e)=m.destructured
                val k="$show:$s:$e"; val at=r["watchedAt"].orEmpty()
                val prev=bbEpisodes[k]
                if(prev==null || (at.isNotBlank() && at < prev.third)) bbEpisodes[k]=Triple(show.toInt(), s.toInt() to e.toInt(), at)
            } }
            files["movie-watches.csv"]?.let { readRows(it).forEach { r ->
                val id=r["movieId"]?.toIntOrNull() ?: return@forEach
                val at=r["watchedAt"].orEmpty()
                if(bbMovies[id]==null || at < bbMovies.getValue(id)) bbMovies[id]=at
            } }
            val showIds=(bbStatus.keys + bbEpisodes.values.map { it.first }).toSet()
            if(showIds.isEmpty() && bbMovies.isEmpty()) warnings.add("No watch history found in this BingeBoxd export")
            warnings.add("Show and movie titles are looked up on TMDB during import; this can take a minute for large histories")
            return TrackerImportPreview(showIds.size,bbEpisodes.size,bbMovies.size,detectedFormat,warnings.toList())
        } else if("shows.csv" in files && "watched_episodes.csv" in files) {
            detectedFormat="CineSays prepared TV Time export"
            readRows(files.getValue("shows.csv")).forEach { r -> addShow(r["tvtime_show_id"].orEmpty(),r["title"].orEmpty(),r["followed"]=="true",r["archived"]=="true",r["followed_at"].orEmpty()) }
            readRows(files.getValue("watched_episodes.csv")).forEach { r -> addEp(r["show_title"].orEmpty(),r["season_number"],r["episode_number"],r["watched_at"]) }
            files["watched_movies.csv"]?.let { readRows(it).forEach { r -> val title=r["title"].orEmpty();if(title.isNotBlank())movies[key(title)]=RawMovie(title,r["watched_at"].orEmpty()) } }
        } else if("tracking-prod-records-v2.csv" in files || "followed_tv_show.csv" in files) {
            detectedFormat="Original TV Time GDPR export"
            files["followed_tv_show.csv"]?.let { readRows(it).forEach { r -> addShow(r["tv_show_id"].orEmpty(),r["tv_show_name"].orEmpty(),r["active"]!="0",r["archived"]=="1",r["created_at"].orEmpty()) } }
            files["tracking-prod-records-v2.csv"]?.let { readRows(it).forEach { r ->
                if(r["is_followed"]=="true") addShow(r["s_id"].orEmpty(),r["series_name"].orEmpty(),true,r["is_archived"]=="true",r["followed_at"].orEmpty())
                if(!r["ep_id"].isNullOrBlank()) addEp(r["series_name"].orEmpty(),r["season_number"] ?: r["s_no"],r["episode_number"] ?: r["ep_no"],r["created_at"])
            } }
            files["tracking-prod-records.csv"]?.let { readRows(it).forEach { r ->
                if(r["type"]=="watch") {
                    addEp(r["series_name"].orEmpty(),r["season_number"],r["episode_number"],r["watch_date"] ?: r["created_at"])
                    val title=r["movie_name"].orEmpty();if(title.isNotBlank()) movies[key(title)]=RawMovie(title,r["watch_date"].orEmpty())
                }
            } }
        } else error("ZIP does not contain recognizable TV Time tracking records")
        if (shows.isEmpty() && episodes.isEmpty()) warnings.add("No show tracking rows detected")
        warnings.add("TV Time identifiers are not TMDB IDs; shows will be matched separately")
        return TrackerImportPreview(shows.size,episodes.size,movies.size,detectedFormat,warnings.toList())
    }
    /** Writes the inspected data. BingeBoxd needs network (TMDB titles); [progress] reports what it is doing. */
    suspend fun commit(store:TrackerStore, progress:(String)->Unit = {}): TrackerImportPreview {
        store.noteImported(if(isBingeBoxd) Source.BINGEBOXD else Source.TVTIME) // outranks an earlier removal on every device
        if(isBingeBoxd) return commitBingeBoxd(store,progress)
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            shows.values.forEach { store.addShow(it.title,it.id,it.followed,it.archived,it.followedAt) }
            episodes.values.forEach { store.mark(it.title,it.season,it.episode,true,it.watchedAt,Source.TVTIME) }
            movies.values.forEach { store.addMovie(it.title,it.watchedAt,source=Source.TVTIME) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return TrackerImportPreview(shows.size,episodes.size,movies.size,detectedFormat,warnings.toList())
    }

    private suspend fun commitBingeBoxd(store:TrackerStore, progress:(String)->Unit): TrackerImportPreview = kotlinx.coroutines.coroutineScope {
        val key=BuildConfig.TMDB_API_KEY
        val gate=kotlinx.coroutines.sync.Semaphore(8)
        val showIds=(bbStatus.keys + bbEpisodes.values.map { it.first }).toSet().toList()
        var done=0
        // 1) Show titles + posters (reuses a title already in the tracker for the same TMDB id, e.g. from TV Time).
        val showInfo=showIds.map { id -> async(kotlinx.coroutines.Dispatchers.IO) { gate.withPermitCompat {
            val known=store.showTitleByTmdb(id)
            val info=if(known!=null) known to null else runCatching { Api.tmdb.trackerTv(id,key) }.getOrNull()?.let { it.name to it.poster }
            synchronized(this@TVTimeImporter) { done++; progress("Looking up shows… $done / ${showIds.size}") }
            id to info
        } } }.awaitAll().toMap()
        // 2) Movie titles + posters.
        val movieIds=bbMovies.keys.toList(); done=0
        val movieInfo=movieIds.map { id -> async(kotlinx.coroutines.Dispatchers.IO) { gate.withPermitCompat {
            val info=runCatching { Api.tmdb.detail("movie",id,key) }.getOrNull()?.let { it.label to it.poster }
            synchronized(this@TVTimeImporter) { done++; if(done%20==0 || done==movieIds.size) progress("Looking up movies… $done / ${movieIds.size}") }
            id to info
        } } }.awaitAll().toMap()
        progress("Saving your history…")
        var importedShows=0; var importedEps=0; var importedMovies=0
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            for(id in showIds) {
                val (title,poster)=showInfo[id] ?: continue
                val status=bbStatus[id].orEmpty()
                store.addShow(title,"bingeboxd:$id",followed = status!="dropped",archived = status=="completed")
                if(poster!=null) store.setTmdb(title,id,poster) // known titles already carry their TMDB id and poster
                importedShows++
            }
            for((showId,se,at) in bbEpisodes.values) {
                val title=showInfo[showId]?.first ?: continue
                db.execSQL("INSERT OR IGNORE INTO watched(title_key,season,episode,watched_at,source) VALUES(?,?,?,?,'bingeboxd')",
                    arrayOf(title.trim().lowercase(java.util.Locale.ROOT),se.first,se.second,at))
                importedEps++
            }
            for((id,at) in bbMovies) {
                val (title,poster)=movieInfo[id] ?: continue
                store.addMovie(title,at,id,poster,Source.BINGEBOXD); importedMovies++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val skipped=(showIds.size-importedShows)+(movieIds.size-importedMovies)
        TrackerImportPreview(importedShows,importedEps,importedMovies,detectedFormat,
            if(skipped>0) listOf("$skipped titles could not be found on TMDB and were skipped") else emptyList())
    }
}

/** Semaphore.withPermit without importing it at top level (keeps this file's imports unchanged). */
private suspend fun <T> kotlinx.coroutines.sync.Semaphore.withPermitCompat(block: suspend () -> T): T { acquire(); try { return block() } finally { release() } }
