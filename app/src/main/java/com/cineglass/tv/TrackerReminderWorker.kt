package com.cineglass.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Best-effort daily release checks. TV listings normally have dates but not precise broadcast times. */
class TrackerReminderWorker(private val context: Context, params: androidx.work.WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = TrackerStore(context)
        try {
            if(store.preference("reminders") != "1") return@withContext Result.success()
            val date = LocalDate.now().toString()
            val shows = store.allShows().filter { it.followed && !it.archived && it.tmdbId > 0 }.take(50)
            val upcoming = mutableListOf<String>()
            var responses = 0
            for (show in shows) {
                try {
                    val detail = Api.tmdb.trackerTv(show.tmdbId,BuildConfig.TMDB_API_KEY)
                    responses++
                    if(detail.next?.airDate == date || detail.last?.airDate == date) {
                        val ep = if(detail.next?.airDate == date) detail.next else detail.last
                        if(ep != null && !store.isWatched(show.title, ep.season, ep.number)) {
                            upcoming += "${show.title} · S${ep.season}E${ep.number}"
                        }
                    }
                } catch (_: Exception) { /* One malformed/unavailable title should not prevent others. */ }
            }
            if(upcoming.isNotEmpty() && store.preference("notified_$date") != "1") {
                val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                    NotificationChannel("cinesays_episodes","CineSays episode releases",NotificationManager.IMPORTANCE_DEFAULT))
                if(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) {
                    val notification=NotificationCompat.Builder(context,"cinesays_episodes")
                        .setSmallIcon(R.drawable.cinesays_launcher)
                        .setContentTitle("New episodes today")
                        .setContentText(upcoming.take(2).joinToString(" · "))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(upcoming.take(8).joinToString("\n")))
                        .setAutoCancel(true).build()
                    manager.notify(32001,notification)
                    store.setPreference("notified_$date","1")
                }
            }
            if (responses == 0 && shows.isNotEmpty()) Result.retry() else Result.success()
        } catch (_: Exception) { Result.retry() }
        finally {store.close()}
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val work=WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork("cinesays_tracker_reminders");return }
            val request=PeriodicWorkRequestBuilder<TrackerReminderWorker>(12,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork("cinesays_tracker_reminders",ExistingPeriodicWorkPolicy.KEEP,request)
        }
    }
}
