package com.cineglass.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * In-app updates from GitHub Releases.
 *
 * Set UPDATE_REPO=owner/repo in local.properties. Publish a release whose tag is the new
 * versionName (e.g. "v1.2.0") and attach the APK. The repository (or at least its releases)
 * must be public: the app calls the unauthenticated GitHub API.
 *
 * Android only installs an update signed with the same key as the installed app, so every
 * release must be built with the same keystore (see RELEASING.md).
 */

private data class GhAsset(
    val name: String = "",
    @SerializedName("browser_download_url") val url: String = "",
    val size: Long = 0,
    /** "sha256:<hex>"; GitHub fills this in for uploaded assets. */
    val digest: String? = null
)
private data class GhRelease(
    @SerializedName("tag_name") val tag: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList()
)

data class AvailableUpdate(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long, val sha256: String?)

data class UpdateState(
    val configured: Boolean = BuildConfig.UPDATE_REPO.isNotBlank(),
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val checking: Boolean = false,
    val available: AvailableUpdate? = null,
    val upToDate: Boolean = false,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    val message: String? = null
)

class UpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state
    private var downloadJob: Job? = null
    private val prefs = app.getSharedPreferences("cinesays_updates", 0)

    init {
        // Quiet check at most once a day so a new version shows up without visiting Settings.
        val day = 24 * 60 * 60 * 1000L
        if (_state.value.configured && System.currentTimeMillis() - prefs.getLong("last_check", 0) > day) check(silent = true)
    }

    fun check(silent: Boolean = false) {
        if (!_state.value.configured || _state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, message = null, upToDate = false) }
            try {
                val release = withContext(Dispatchers.IO) { fetchLatest() }
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                val version = release.tag.removePrefix("v").removePrefix("V")
                when {
                    !isNewer(version, BuildConfig.VERSION_NAME) ->
                        _state.update { it.copy(checking = false, available = null, upToDate = true) }
                    apk == null ->
                        _state.update { it.copy(checking = false, message = if (silent) null else "Release ${release.tag} has no APK attached.") }
                    else -> _state.update { it.copy(checking = false, available = AvailableUpdate(
                        version, release.body.orEmpty().trim().take(600), apk.url, apk.size,
                        apk.digest?.takeIf { d -> d.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase())) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, message = if (silent) null else "Couldn't check for updates: ${e.message}") }
            }
        }
    }

    fun downloadAndInstall() {
        val update = _state.value.available ?: return
        if (downloadJob?.isActive == true) return
        // Android 8+: the user must allow CineSays to install apps once.
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            try {
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                _state.update { it.copy(message = "Allow CineSays to install apps, then press Download again.") }
            } catch (_: Exception) {
                _state.update { it.copy(message = "Turn on Settings › Apps › Special app access › Install unknown apps › CineSays, then try again.") }
            }
            return
        }
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(progress = 0f, message = null) }
            try {
                val file = withContext(Dispatchers.IO) { download(update) }
                _state.update { it.copy(progress = null, message = "Downloaded ${update.version}. Confirm the install on screen.") }
                install(file)
            } catch (e: Exception) {
                _state.update { it.copy(progress = null, message = "Update failed: ${e.message}") }
            }
        }
    }

    private fun fetchLatest(): GhRelease {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "CineSays-Android")
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        try {
            when (conn.responseCode) {
                200 -> return conn.inputStream.bufferedReader().use { Gson().fromJson(it, GhRelease::class.java) }
                404 -> error("no published release found for ${BuildConfig.UPDATE_REPO} (is the repo public?)")
                403 -> error("GitHub rate limit reached, try again later")
                else -> error("GitHub returned HTTP ${conn.responseCode}")
            }
        } finally { conn.disconnect() }
    }

    private suspend fun download(update: AvailableUpdate): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest download
        val target = File(dir, "CineSays-${update.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(update.apkUrl).openConnection() as HttpURLConnection // follows GitHub's redirect to its CDN
        conn.setRequestProperty("User-Agent", "CineSays-Android")
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) error("download returned HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (!kotlin.coroutines.coroutineContext.isActive) error("cancelled")
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (total > 0) _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        } finally { conn.disconnect() }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (update.sha256 != null && update.sha256 != actual) { target.delete(); error("checksum mismatch, download discarded") }
        return target
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** Compares dotted numeric versions: 1.10.0 > 1.9.2. Non-numeric suffixes (-beta) are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
