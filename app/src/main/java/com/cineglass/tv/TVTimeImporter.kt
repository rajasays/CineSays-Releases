package com.cineglass.tv

import android.content.Context
import android.net.Uri
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class TrackerImportPreview(val shows: Int, val watchedEpisodes: Int, val watchedMovies: Int, val format: String, val warnings: List<String>)
private data class RawShow(val id:String,val title:String,val followed:Boolean,val archived:Boolean,val followedAt:String)
private data class RawEpisode(val title:String,val season:Int,val episode:Int,val watchedAt:String)
private data class RawMovie(val title:String,val watchedAt:String)

class TVTimeImporter(private val context: Context) {
    private val shows = linkedMapOf<String,RawShow>()
    private val episodes = linkedMapOf<String,RawEpisode>()
    private val movies = linkedMapOf<String,RawMovie>()
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
        detectedFormat="Unknown ZIP"
        val wanted=setOf("shows.csv","watched_episodes.csv","watched_movies.csv","followed_tv_show.csv","tracking-prod-records-v2.csv","tracking-prod-records.csv")
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
        if("shows.csv" in files && "watched_episodes.csv" in files) {
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
    fun commit(store:TrackerStore): TrackerImportPreview {
        val db=store.writableDatabase
        db.beginTransaction()
        try {
            shows.values.forEach { store.addShow(it.title,it.id,it.followed,it.archived,it.followedAt) }
            episodes.values.forEach { store.mark(it.title,it.season,it.episode,true,it.watchedAt) }
            movies.values.forEach { store.addMovie(it.title,it.watchedAt) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return TrackerImportPreview(shows.size,episodes.size,movies.size,detectedFormat,warnings.toList())
    }
}
