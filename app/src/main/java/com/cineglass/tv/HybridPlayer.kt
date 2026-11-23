package com.cineglass.tv

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ───────────────────────────── CineSays native player (Media3 ExoPlayer) ─────────────────────────────

private const val SAVE_EVERY_MS = 10_000L

/** Plain-language reason for a playback failure. No URLs (they can carry tokens). */
@OptIn(UnstableApi::class)
fun describePlaybackError(e: PlaybackException): String {
    val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when {
        http?.responseCode == 403 -> "The source refused access (403). The link may have expired: find the sources again."
        http?.responseCode == 404 -> "The video isn't there any more (404)."
        http?.responseCode == 429 -> "The source is busy (429). Wait a minute and try again."
        http != null -> "The source answered with an error (${http.responseCode})."
        else -> when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "No connection to the source. Check your internet and try again."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The source took too long to answer."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This device can't decode this video (its codec, resolution or HDR format isn't supported here)."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "This device can't play this audio format. Try another audio track."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "The file format isn't supported or is damaged."
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "The stream's playlist is invalid."
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Fell behind the live stream. Retry to rejoin."
            else -> "Playback stopped (${e.errorCodeName})."
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun NativePlayerScreen(
    playback: NativePlayback, prefs: PlaybackPrefs,
    onProgress: (Long, Long) -> Unit, onClose: () -> Unit,
    onNext: (() -> Unit)?, onFindSources: (() -> Unit)?, onSubtitlePref: (String?) -> Unit,
    onSourcePlayed: () -> Unit = {}
) {
    val context = LocalContext.current
    val player = remember(playback.url) { buildPlayer(context, playback, prefs) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var error by remember(playback.url) { mutableStateOf<String?>(null) }
    var ended by remember(playback.url) { mutableStateOf(false) }
    var loading by remember(playback.url) { mutableStateOf(true) }
    var firstFrameRendered by remember(playback.url) { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Int?>(null) } // C.TRACK_TYPE_* or SPEED
    var seekHint by remember { mutableStateOf<String?>(null) }

    var sourceReported by remember(player) { mutableStateOf(false) }
    fun report() {
        // A failed attempt can expose the requested resume position before playback starts.
        if (playback.sourceKey != null && !sourceReported) return
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (d > 0 || player.currentPosition > 0) onProgress(player.currentPosition, d)
    }

    DisposableEffect(player) {
        val torrentDiagnostics = playback.torrent != null
        val playerObservedAt = SystemClock.elapsedRealtime()
        var firstFrameLogged = false
        var bufferingStartedAt = 0L
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (torrentDiagnostics) Log.w("CineSaysTorrent", "player_error code=${e.errorCodeName} cause=${e.cause?.javaClass?.simpleName}")
                error = describePlaybackError(e); report()
            }
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                if (torrentDiagnostics && !firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i("CineSaysTorrent", "timing_first_frame sincePlayerAttachMs=${SystemClock.elapsedRealtime() - playerObservedAt} positionMs=${player.currentPosition}")
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (torrentDiagnostics && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    Log.i("CineSaysTorrent", "timing_seek fromMs=${oldPosition.positionMs} targetMs=${newPosition.positionMs}")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (torrentDiagnostics) {
                    val now = SystemClock.elapsedRealtime()
                    if (state == Player.STATE_BUFFERING) {
                        bufferingStartedAt = now
                        Log.i("CineSaysTorrent", "player_buffering positionMs=${player.currentPosition}")
                    } else if (bufferingStartedAt != 0L) {
                        Log.i("CineSaysTorrent", "player_buffering_end durationMs=${now - bufferingStartedAt} nextState=$state")
                        bufferingStartedAt = 0L
                    }
                }
                if (state == Player.STATE_ENDED) { ended = true; report() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !sourceReported) {
                    sourceReported = true
                    onSourcePlayed()
                } else if (!isPlaying) report()
            }
        }
        loading = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
        player.addListener(listener)
        onDispose { report(); player.removeListener(listener); player.release() } // one player at a time, always released
    }
    LaunchedEffect(player) { while (true) { delay(SAVE_EVERY_MS); if (player.isPlaying) report() } }
    // Save and pause when the app goes to the background (Home button, another app, screensaver).
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) { report(); player.pause() } }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(900); seekHint = null } }
    // Torrent sources can stall after a seek as well as at startup. The warning
    // is advisory: never auto-switch sources, stop the torrent or discard progress.
    var slowStart by remember(playback.url) { mutableStateOf(false) }
    var torrentBufferedMs by remember(playback.url) { mutableStateOf(0L) }
    var torrentHealth by remember(playback.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(player) {
        var waited = 0
        while (true) {
            delay(1000)
            val buffering = player.playbackState == Player.STATE_BUFFERING
            val stuck = buffering && (playback.torrent != null || player.currentPosition < 1000)
            waited = if (stuck) waited + 1 else 0
            slowStart = waited >= 15
            if (playback.torrent != null) {
                torrentBufferedMs = if (buffering) player.totalBufferedDuration.coerceAtLeast(0) else 0L
                if (!slowStart) torrentHealth = null
                else if (waited == 15 || waited % 5 == 0) {
                    torrentHealth = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        playback.torrent.sourceHealth(waited)
                    }
                }
            }
        }
    }

    // Back: close a picker, then hide the controls, then leave the player.
    BackHandler {
        when {
            picker != null -> picker = null
            playerView?.isControllerFullyVisible == true && error == null && !ended -> playerView?.hideController()
            else -> onClose()
        }
    }

    val viewFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    // Focus starts on the video's remote layer (not "Close"), so OK shows the controls instead of leaving.
    fun focusVideo() { runCatching { viewFocus.requestFocus() } }
    LaunchedEffect(playerView) { if (playerView != null) { delay(150); focusVideo() } }

    // Full screen: the video owns the whole display. Phones and folds turn to landscape and hide the system bars
    // while playing (TVs are already landscape); everything is restored when the player closes.
    FullScreenPlayback()
    var controlsVisible by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(false) } // Fit (whole picture) or Fill (crop to the screen)
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowBuffering(if (playback.torrent != null) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_ALWAYS)
                    // One settings menu (CineSays' ⚙ in the header), so the controller's own gear and CC button are hidden.
                    setShowSubtitleButton(false)
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility = android.view.View.GONE
                    keepScreenOn = true
                    // The remote is handled by CineSays' focus layer below; the view draws video and controls (and
                    // takes touch) but never takes focus, so D-pad focus can't get lost inside it.
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    subtitleView?.setApplyEmbeddedStyles(true)
                    subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleScale)
                    // CineSays draws its own "watched / remaining" times above the bar, so the built-in pair is hidden.
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_time)?.visibility = android.view.View.GONE
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == android.view.View.VISIBLE })
                    playerView = this
                }
            }, update = { v ->
                v.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }, modifier = Modifier.fillMaxSize())
            // Remote layer over the video (no pointer handling, so touches still reach the player):
            // OK shows the controls, or toggles play/pause while they're up; Left/Right seek 10 s; Play/Pause toggles;
            // Up moves to the header (Close, Next episode, ⚙ Settings).
            Box(Modifier.fillMaxSize().focusRequester(viewFocus)
                .onKeyEvent { ev ->
                    val pv = playerView
                    if (ev.type != KeyEventType.KeyDown || pv == null || error != null) return@onKeyEvent false
                    fun reveal() { pv.showController() }
                    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
                    when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (pv.isControllerFullyVisible) toggle(); reveal(); true }
                        Key.DirectionLeft, Key.MediaRewind -> { player.seekBack(); reveal(); seekHint = "⏪ ${ProgressRules.clock(player.currentPosition)}"; true }
                        Key.DirectionRight, Key.MediaFastForward -> { player.seekForward(); reveal(); seekHint = "⏩ ${ProgressRules.clock(player.currentPosition)}"; true }
                        Key.MediaPlayPause -> { toggle(); reveal(); true }
                        Key.MediaPlay -> { player.play(); true }
                        Key.MediaPause -> { player.pause(); reveal(); true }
                        // Up: first reveal the controls and header; the next Up moves focus into the header buttons.
                        Key.DirectionUp -> { if (!pv.isControllerFullyVisible) reveal() else runCatching { settingsFocus.requestFocus() }; true }
                        else -> false
                    }
                }
                .focusable())
            seekHint?.let { Text(it, color = Cs.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) }

            if (playback.torrent != null && loading && error == null && !ended) {
                TorrentPuzzleLoading(playback.title, playback.record.poster?.takeIf { it.isNotBlank() }
                    ?: playback.item?.poster,
                    if (firstFrameRendered) "Buffering video…" else "Loading stream…", torrentBufferedMs)
            }

            if (slowStart && error == null) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).widthIn(max = 640.dp)
                .background(Cs.panel.copy(alpha = .92f), RoundedCornerShape(16.dp)).border(1.dp, Cs.line, RoundedCornerShape(16.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Still loading…", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (playback.torrent != null) torrentHealth ?: "Checking the selected torrent's peers and video pieces…"
                    else "${StreamUrls.host(playback.url) ?: "The source"} is responding slowly on your connection. You can keep waiting" +
                        (if (onFindSources != null) ", or pick another source (Up, then Close, or Back)." else ", or press Back to leave."),
                    color = Cs.textSoft, fontSize = 14.sp
                )
                if (playback.torrent != null && onFindSources != null)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CsButton("Other sources", primary = true) { report(); onFindSources() }
                    }
            }

            error?.let { msg ->
                PlayerOverlay(Modifier.align(Alignment.Center)) {
                    Text("Can't play this", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(msg, color = Cs.textSoft, fontSize = 14.sp)
                    val f = remember { FocusRequester() }
                    LaunchedEffect(msg) { delay(80); runCatching { f.requestFocus() } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CsButton("Retry", primary = true, modifier = Modifier.focusRequester(f)) { error = null; player.prepare(); player.play() }
                        onFindSources?.let { CsButton("Other sources") { report(); it() } }
                        CsButton("Close") { onClose() }
                    }
                }
            }
            if (ended && error == null) {
                PlayerOverlay(Modifier.align(Alignment.Center)) {
                    Text(if (onNext != null) "Up next" else "Finished", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val f = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(80); runCatching { f.requestFocus() } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (onNext != null) CsButton("▶ Next episode", primary = true, modifier = Modifier.focusRequester(f)) { onNext() }
                        CsButton("Watch again", primary = onNext == null, modifier = if (onNext == null) Modifier.focusRequester(f) else Modifier) {
                            ended = false; player.seekTo(0); player.play() }
                        CsButton("Close") { onClose() }
                    }
                }
            }
        }

        // ── Floating header: appears with the controls (or while it has focus), never takes space from the video ──
        val showChrome = controlsVisible || headerFocused || picker != null || error != null || ended
        androidx.compose.animation.AnimatedVisibility(showChrome, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .82f), Color.Black.copy(alpha = .45f), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp)
                .onFocusChanged { headerFocused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val buttons: @Composable () -> Unit = {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onNext != null) CsChip("Next episode ▸", false, compact = true) { report(); onNext() }
                        // Quality, Audio, Subtitles, Speed and picture size live in one settings menu.
                        CsChip("⚙ Settings", false, compact = true, modifier = Modifier.focusRequester(settingsFocus)) { picker = SETTINGS }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CsChip("← Close", false, compact = true) { onClose() }
                    Column(Modifier.weight(1f)) {
                        Text(playback.title, color = Cs.text, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(playback.sourceName.ifBlank { null }, playback.licence).joinToString(" · "), color = Cs.textSoft, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    buttons()
                }
            }
        }

        // ── Time watched / time remaining, just above the progress bar while the controls are up ──
        if (controlsVisible && error == null) {
            var pos by remember { mutableLongStateOf(player.currentPosition) }
            var dur by remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    pos = player.currentPosition.coerceAtLeast(0)
                    dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    delay(500)
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(start = 20.dp, end = 20.dp, bottom = if (compact) 64.dp else 84.dp), verticalAlignment = Alignment.Bottom) {
                TimeLabel(ProgressRules.clock(pos), "watched", Alignment.Start)
                Spacer(Modifier.weight(1f))
                if (dur > 0) TimeLabel("−" + ProgressRules.clock((dur - pos).coerceAtLeast(0)), "remaining of ${ProgressRules.clock(dur)}", Alignment.End)
                else TimeLabel("Live", "or length unknown", Alignment.End)
            }
        }
    }

    when (val type = picker) {
        null -> Unit
        SETTINGS -> PlayerSettings(player, fill, onOpen = { picker = it }, onFill = { fill = !fill; picker = null; focusVideo() },
            onDone = { picker = null; focusVideo() })
        // Choosing an option closes the menus; Back returns to the settings list.
        else -> TrackPicker(player, type, onSubtitlePref, onBack = { picker = SETTINGS }) { picker = null; focusVideo() }
    }
}

private const val SPEED = -100
private const val SETTINGS = -200

/** The player's settings list: each row shows its current value and opens that option's picker. */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSettings(player: ExoPlayer, fill: Boolean, onOpen: (Int) -> Unit, onFill: () -> Unit, onDone: () -> Unit) {
    fun current(type: Int): String {
        val g = player.currentTracks.groups.filter { it.type == type }
        val overridden = player.trackSelectionParameters.overrides.keys.any { k -> g.any { it.mediaTrackGroup == k } }
        val sel = g.firstNotNullOfOrNull { gr -> (0 until gr.length).firstOrNull { gr.isTrackSelected(it) }?.let { gr.getTrackFormat(it) } }
        return when {
            type == C.TRACK_TYPE_TEXT && (sel == null || player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) -> "Off"
            sel == null -> if (g.isEmpty()) "Not offered" else "Auto"
            type == C.TRACK_TYPE_VIDEO && !overridden -> "Auto · ${trackLabel(sel, type)}"
            else -> trackLabel(sel, type)
        }
    }
    val speed = player.playbackParameters.speed
    val rows = listOf(
        Triple("Quality", current(C.TRACK_TYPE_VIDEO)) { onOpen(C.TRACK_TYPE_VIDEO) },
        Triple("Audio", current(C.TRACK_TYPE_AUDIO)) { onOpen(C.TRACK_TYPE_AUDIO) },
        Triple("Subtitles", current(C.TRACK_TYPE_TEXT)) { onOpen(C.TRACK_TYPE_TEXT) },
        Triple("Playback speed", if (speed == 1f) "Normal" else "${speed}×") { onOpen(SPEED) },
        Triple("Picture", if (fill) "Fill screen (crops edges)" else "Fit screen (whole picture)") { onFill() },
    )
    Dialog(onDismissRequest = onDone) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        Column(Modifier.widthIn(min = 320.dp, max = 520.dp).heightIn(max = 560.dp).background(Cs.panel, RoundedCornerShape(20.dp))
            .border(1.dp, Cs.line, RoundedCornerShape(20.dp)).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            rows.forEachIndexed { i, (label, value, open) ->
                OptionRow(label, value, false, if (i == 0) Modifier.focusRequester(first) else Modifier, trailing = "›") { open() }
            }
        }
    }
}

@Composable
private fun TimeLabel(value: String, caption: String, align: Alignment.Horizontal) {
    Column(Modifier.background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = align) {
        Text(value, color = Cs.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = Cs.textSoft, fontSize = 11.sp)
    }
}

/**
 * While the player is open: hide the status and navigation bars (swipe to peek) and, on phones and folds,
 * turn to landscape so the video gets the whole screen. TVs keep their orientation. Restored on close.
 */
@Composable
private fun FullScreenPlayback() {
    val view = androidx.compose.ui.platform.LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?: generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        val isTv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val previousOrientation = activity?.requestedOrientation
        window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        if (!isTv) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
            if (!isTv && previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
}


/** Preparation loading view. Artwork is optional and never blocks playback. */
@Composable
internal fun TorrentLoadingCard(title: String, poster: String?, status: String,
    modifier: Modifier = Modifier) {
    val motion = androidx.compose.animation.core.rememberInfiniteTransition(label = "torrent loading")
    val glow = motion.animateFloat(
        initialValue = .22f,
        targetValue = .62f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1150),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "poster glow"
    )
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.widthIn(max = 440.dp).background(
            Brush.verticalGradient(listOf(Cs.panel, Color.Black.copy(alpha = .96f))), shape
        ).border(1.dp, Cs.line, shape).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        poster?.takeIf { it.isNotBlank() }?.let { path ->
            val posterShape = RoundedCornerShape(12.dp)
            Box(Modifier.size(112.dp, 168.dp).clip(posterShape)) {
                AsyncImage(
                    model = if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w342$path",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.matchParentSize().graphicsLayer { alpha = glow.value }
                    .border(2.dp, Cs.accent, posterShape))
            }
        }
        Text(title, color = Cs.text, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.width(132.dp).clip(RoundedCornerShape(8.dp)),
            color = Cs.accent,
            trackColor = Cs.line
        )
        Text(status, color = Cs.textSoft, fontSize = 14.sp)
    }
}

@Composable
private fun PlayerOverlay(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.widthIn(max = 560.dp).padding(24.dp).background(Cs.panel.copy(alpha = .94f), RoundedCornerShape(20.dp))
        .border(1.dp, Cs.line, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, p: NativePlayback, prefs: PlaybackPrefs): ExoPlayer {
    val http = DefaultHttpDataSource.Factory().setUserAgent("CineSays/${BuildConfig.VERSION_NAME} (Android TV)")
        .setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(10_000).setReadTimeoutMs(if (p.url.startsWith("http://127.0.0.1:")) TorrentTransportPolicy.HTTP_READ_TIMEOUT_MS else 15_000)
    val load = if (prefs.buffering == "large") DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 6_000).build()
        else DefaultLoadControl()
    val player = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(DefaultMediaSourceFactory(if (p.torrent != null && !p.url.startsWith("http://127.0.0.1:")) p.torrent.dataSourceFactory() else DefaultDataSource.Factory(context, http)))
        .setLoadControl(load)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS) // frame-rate matching where the display allows
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
        prefs.audioLanguage.ifBlank { null }?.let { setPreferredAudioLanguage(it) }
        when (prefs.subtitleMode) {
            "off" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "on" -> setPreferredTextLanguage(prefs.subtitleLanguage.ifBlank { null }).setSelectUndeterminedTextLanguage(true)
            else -> Unit // "auto": only tracks the stream flags as default or forced
        }
        prefs.maxHeight?.let { setMaxVideoSize(Int.MAX_VALUE, it) }
    }.build()
    val mime = when (p.kind) { StreamKind.HLS -> MimeTypes.APPLICATION_M3U8; StreamKind.DASH -> MimeTypes.APPLICATION_MPD; else -> null }
    val item = MediaItem.Builder().setUri(p.url).apply { mime?.let { setMimeType(it) } }
        .setSubtitleConfigurations(p.subtitles.mapNotNull { s ->
            val m = s.mimeType ?: return@mapNotNull null // unknown formats would fail; skip them
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url)).setMimeType(m).setLanguage(s.language).setLabel(s.label).build()
        }).build()
    player.setMediaItem(item, p.startMs.coerceAtLeast(0))
    player.prepare()
    player.playWhenReady = true
    return player
}

/** Quality / Audio / Subtitles / Speed picker. Lists only what the stream actually reports. */
@OptIn(UnstableApi::class)
@Composable
private fun TrackPicker(player: ExoPlayer, type: Int, onSubtitlePref: (String?) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    data class Opt(val label: String, val detail: String?, val selected: Boolean, val apply: () -> Unit)
    val tracks = player.currentTracks
    val params = player.trackSelectionParameters
    val opts: List<Opt> = when (type) {
        SPEED -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { s ->
            Opt(if (s == 1f) "Normal" else "${s}×", null, player.playbackParameters.speed == s) { player.setPlaybackSpeed(s) } }
        else -> {
            val groups = tracks.groups.filter { it.type == type }
            val auto = Opt(if (type == C.TRACK_TYPE_TEXT) "Off" else "Auto", if (type == C.TRACK_TYPE_VIDEO) "Picks the best quality for your connection" else null,
                if (type == C.TRACK_TYPE_TEXT) params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || groups.none { it.isSelected }
                else !params.overrides.keys.any { k -> groups.any { it.mediaTrackGroup == k } }) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).apply {
                    if (type == C.TRACK_TYPE_TEXT) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }.build()
                if (type == C.TRACK_TYPE_TEXT) onSubtitlePref(null)
            }
            listOf(auto) + groups.flatMap { g ->
                (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                    val f = g.getTrackFormat(i)
                    // Video adapts across many tracks; tick only the one you picked (Auto is ticked otherwise).
                    val picked = if (type == C.TRACK_TYPE_VIDEO) params.overrides[g.mediaTrackGroup]?.trackIndices?.contains(i) == true else g.isTrackSelected(i)
                    Opt(trackLabel(f, type), trackDetail(f, type), picked) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                        if (type == C.TRACK_TYPE_TEXT) onSubtitlePref(f.language)
                    }
                }
            }
        }
    }
    val title = when (type) { C.TRACK_TYPE_VIDEO -> "Quality"; C.TRACK_TYPE_AUDIO -> "Audio"; C.TRACK_TYPE_TEXT -> "Subtitles"; else -> "Playback speed" }
    Dialog(onDismissRequest = onBack) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        Column(Modifier.widthIn(min = 320.dp, max = 520.dp).heightIn(max = 520.dp).background(Cs.panel, RoundedCornerShape(20.dp))
            .border(1.dp, Cs.line, RoundedCornerShape(20.dp)).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CsChip("‹", false, compact = true) { onBack() }
                Text(title, color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (opts.size <= 1 && type != SPEED) Text("This stream offers only one ${title.lowercase()} option.", color = Cs.textMuted, fontSize = 13.sp)
            val start = opts.indexOfFirst { it.selected }.coerceAtLeast(0)
            opts.forEachIndexed { i, o ->
                OptionRow(o.label, o.detail, o.selected, if (i == start) Modifier.focusRequester(first) else Modifier) { o.apply(); onDone() }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, detail: String?, selected: Boolean, modifier: Modifier, trailing: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (focused) Cs.surfaceHigh else Cs.surface, shape).border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Cs.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, color = Cs.textMuted, fontSize = 12.sp) }
        }
        if (selected) Text("✓", color = Cs.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        trailing?.let { Text(it, color = Cs.textSoft, fontSize = 22.sp) }
    }
}

@OptIn(UnstableApi::class)
private fun trackLabel(f: Format, type: Int): String = when (type) {
    C.TRACK_TYPE_VIDEO -> if (f.height > 0) "${f.height}p" else "Video"
    else -> f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Track"
}

/** Only what the stream's own format data says: never guessed from names. */
@OptIn(UnstableApi::class)
private fun trackDetail(f: Format, type: Int): String? = when (type) {
    C.TRACK_TYPE_VIDEO -> listOfNotNull(
        if (f.bitrate > 0) "%.1f Mbps".format(f.bitrate / 1_000_000f) else null,
        f.codecs ?: f.sampleMimeType?.substringAfter('/'),
        when {
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }).joinToString(" · ").ifBlank { null }
    C.TRACK_TYPE_AUDIO -> listOfNotNull(
        when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> null; else -> "${f.channelCount} ch" },
        when (f.sampleMimeType) { MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (E-AC-3 JOC)"; MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"; MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_DTS -> "DTS"; else -> f.sampleMimeType?.substringAfter('/') }
    ).joinToString(" · ").ifBlank { null }
    else -> when (f.sampleMimeType) {
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
        MimeTypes.TEXT_VTT -> "WebVTT"; MimeTypes.APPLICATION_SUBRIP -> "SRT"; MimeTypes.TEXT_SSA -> "SSA/ASS"; MimeTypes.APPLICATION_TTML -> "TTML"
        else -> if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) "Forced subtitles" else null
    }
}

// ───────────────────────────── Resume prompt ─────────────────────────────

@Composable
fun ResumeDialog(prompt: ResumePrompt, onResume: () -> Unit, onRestart: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val f = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(60); runCatching { f.requestFocus() } }
        Column(Modifier.widthIn(max = 520.dp).background(Cs.panel, RoundedCornerShape(20.dp)).border(1.dp, Cs.line, RoundedCornerShape(20.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(prompt.playback.title, color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CsButton("▶ Resume from ${ProgressRules.clock(prompt.positionMs)}", primary = true, modifier = Modifier.focusRequester(f)) { onResume() }
                CsButton("Start from beginning") { onRestart() }
            }
        }
    }
}

// ───────────────────────────── Watch Now dialog ─────────────────────────────
// Same behaviour as before (options, focus, actions); a richer layout: backdrop header with poster, an episode
// strip with thumbnails, a featured in-app card, and service tiles in a grid. It adapts to the screen:
// phones get a full-screen sheet with one column, folds and small tablets two columns, TVs a wide panel.

private enum class WatchLayout { PHONE, MEDIUM, WIDE }

@Composable
fun WatchNowDialog(
    w: WatchNowState, stremioInstalled: Boolean, onClose: () -> Unit, onSeason: (Int) -> Unit, onEpisode: (TrackerEpisode) -> Unit,
    onAction: (WatchAction) -> Unit, onRetry: () -> Unit, onMarkEpisode: (MediaRef, Boolean) -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val layout = when { maxWidth < 600.dp -> WatchLayout.PHONE; maxWidth < 960.dp -> WatchLayout.MEDIUM; else -> WatchLayout.WIDE }
            val shortScreen = maxHeight < 520.dp // phones in landscape
            val sheetShape = if (layout == WatchLayout.PHONE) RoundedCornerShape(0.dp) else RoundedCornerShape(28.dp)
            val sheet = when (layout) {
                WatchLayout.PHONE -> Modifier.fillMaxSize()
                WatchLayout.MEDIUM -> Modifier.fillMaxWidth(.94f).fillMaxHeight(.94f)
                WatchLayout.WIDE -> Modifier.widthIn(max = 1240.dp).fillMaxWidth(.88f).fillMaxHeight(.92f)
            }
            BoxWithConstraints(sheet.clip(sheetShape).background(Cs.panel).border(if (layout == WatchLayout.PHONE) 0.dp else 1.dp, Cs.lineStrong, sheetShape)) {
                val sheetWidth = maxWidth
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    WatchHeader(w, layout, shortScreen, onClose)
                    val pad = if (layout == WatchLayout.PHONE) 16.dp else 28.dp
                    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 8.dp, bottom = 28.dp)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                        verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        if (w.isSeries) EpisodePicker(w, layout, onSeason, onEpisode)
                        w.error?.let { Notice(it, warn = true) }
                        val sel = w.selected
                        if (sel != null && w.isSeries) SelectedEpisodeBar(sel, ((sel.season ?: 0) to (sel.episode ?: 0)) in w.watchedEpisodes) {
                            onMarkEpisode(sel, it) }
                        if (sel != null) SourcesSection(w, layout, sheetWidth - pad * 2, stremioInstalled, onAction, onRetry)
                    }
                }
            }
        }
    }
}

/** Backdrop with a soft fade into the panel, poster (not on phones), eyebrow, title, facts and Close. */
@Composable
private fun WatchHeader(w: WatchNowState, layout: WatchLayout, shortScreen: Boolean, onClose: () -> Unit) {
    val item = w.item
    val phone = layout == WatchLayout.PHONE
    val height = when { shortScreen -> 170.dp; phone -> 236.dp; layout == WatchLayout.MEDIUM -> 270.dp; else -> 300.dp }
    Box(Modifier.fillMaxWidth().height(height)) {
        AsyncImage(item.hero(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Cs.panel.copy(alpha = .35f), .55f to Cs.panel.copy(alpha = .78f), 1f to Cs.panel)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Cs.panel.copy(alpha = .9f), .6f to Color.Transparent)))
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = if (phone) 16.dp else 28.dp, vertical = if (phone) 14.dp else 22.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!phone && !shortScreen) Box(Modifier.width(if (layout == WatchLayout.WIDE) 150.dp else 124.dp).aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp)).background(Cs.surfaceHigh).border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(14.dp))) {
                AsyncImage(item.image("w342"), item.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WATCH NOW", color = Cs.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(item.label, color = Cs.text, fontSize = if (phone) 24.sp else if (layout == WatchLayout.MEDIUM) 30.sp else 36.sp,
                    fontWeight = FontWeight.ExtraBold, lineHeight = if (phone) 28.sp else 40.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    item.year.ifBlank { null },
                    if (w.isSeries) item.seasons?.takeIf { it > 0 }?.let { "Series · $it season${if (it > 1) "s" else ""}" } ?: "Series" else "Movie",
                    item.runtime?.takeIf { it > 0 && !w.isSeries }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                    item.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                    item.genres?.firstOrNull()?.name)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    facts.forEach { FactPill(it) }
                }
            }
        }
        // Close: always reachable at the top corner.
        Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
            .padding(if (phone) 12.dp else 18.dp)) {
            CsChip("✕ Close", false, compact = true) { onClose() }
        }
    }
}

@Composable
private fun FactPill(text: String, tint: Color = Cs.textSoft) {
    Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp))
}

/** Small section heading: label, optional count, a hairline to the right. */
@Composable
private fun SectionHeading(label: String, count: Int? = null, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label.uppercase(), color = Cs.textSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            count?.let { Text("$it", color = Cs.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Cs.accent.copy(alpha = .14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) }
            Box(Modifier.weight(1f).height(1.dp).background(Cs.line))
        }
        hint?.let { Text(it, color = Cs.textMuted, fontSize = 12.sp) }
    }
}

@Composable
private fun Notice(text: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(if (warn) Cs.warn.copy(alpha = .08f) else Cs.surface, RoundedCornerShape(16.dp))
        .border(1.dp, if (warn) Cs.warn.copy(alpha = .35f) else Cs.line, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (warn) "!" else "◌", color = if (warn) Cs.warn else Cs.textMuted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Cs.textSoft, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun EpisodePicker(w: WatchNowState, layout: WatchLayout, onSeason: (Int) -> Unit, onEpisode: (TrackerEpisode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("Episodes", w.episodes.size.takeIf { it > 0 })
        if (w.seasons.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            w.seasons.forEach { s -> CsChip("Season $s", s == w.season, compact = true) { onSeason(s) } }
        }
        if (w.episodesBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Cs.accent, trackColor = Cs.line)
        val listState = rememberLazyListState()
        val selIndex = w.episodes.indexOfFirst { it.number == w.selected?.episode }
        LaunchedEffect(w.season, w.episodes.size) { if (selIndex > 0) listState.scrollToItem((selIndex - 1).coerceAtLeast(0)) }
        val cardWidth = when (layout) { WatchLayout.PHONE -> 196.dp; WatchLayout.MEDIUM -> 220.dp; WatchLayout.WIDE -> 250.dp }
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(w.episodes, key = { "${it.season}:${it.number}" }) { e ->
                val p = w.seasonProgress[e.number]
                val watched = (e.season to e.number) in w.watchedEpisodes || p?.completed == true
                EpisodeCard(e, cardWidth, selected = e.number == w.selected?.episode, watched = watched,
                    progress = p?.takeIf { !it.completed && it.positionMs >= ProgressRules.MIN_RESUME_MS }?.fraction?.toFloat()) { onEpisode(e) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(e: TrackerEpisode, width: androidx.compose.ui.unit.Dp, selected: Boolean, watched: Boolean, progress: Float?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(width).graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
        .onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (selected) Cs.surfaceHigh else Cs.surface, shape)
        .border(if (focused) 3.dp else if (selected) 2.dp else 1.dp, if (focused) Cs.focusRing else if (selected) Cs.accent else Cs.line, shape)
        .clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Cs.surfaceHigh)) {
            e.still?.let { AsyncImage("https://image.tmdb.org/t/p/w300$it", e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .7f))))
            Text("E${e.number}", color = Cs.text, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            if (watched) Text("✓ Watched", color = Color(0xFF06231A), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Cs.good, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
            if (selected) Text("▶", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center)
                .background(Cs.accent.copy(alpha = .9f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 12.dp, vertical = 6.dp))
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Cs.accent, trackColor = Color.White.copy(alpha = .2f)) }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name.ifBlank { "Episode ${e.number}" }, color = Cs.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.airDate?.takeIf { it.isNotBlank() }?.let { Text(it, color = Cs.textMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SelectedEpisodeBar(sel: MediaRef, watched: Boolean, onMark: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Cs.surface, RoundedCornerShape(16.dp)).border(1.dp, Cs.line, RoundedCornerShape(16.dp))
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("SEASON ${sel.season} · EPISODE ${sel.episode}", color = Cs.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(sel.episodeName ?: "Episode ${sel.episode}", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CsChip(if (watched) "✓ Watched" else "Mark watched", watched, compact = true) { onMark(!watched) }
    }
}

@Composable
private fun SourcesSection(w: WatchNowState, layout: WatchLayout, contentWidth: androidx.compose.ui.unit.Dp, stremioInstalled: Boolean,
                           onAction: (WatchAction) -> Unit, onRetry: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(w.options) { if (w.options.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } } }
    var firstUsed = false
    fun focusMod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
    // Tile columns: one on phones, two on folds / small tablets, three on TVs.
    val columns = when (layout) { WatchLayout.PHONE -> 1; WatchLayout.MEDIUM -> 2; WatchLayout.WIDE -> 3 }
    val gap = 12.dp
    val tileWidth = (contentWidth - gap * (columns - 1)) / columns

    val previous = remember(w.options, w.lastPlayedSource) {
        w.lastPlayedSource?.let { key ->
            w.options.firstOrNull { action ->
                when (action) {
                    is WatchAction.PlayNative -> action.stream.historyKey() == key
                    is WatchAction.PlayTorrent -> action.stream.historyKey() == key
                    else -> false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        when (previous) {
            is WatchAction.PlayNative -> FeaturedSourceCard(previous.stream, featured = true,
                compact = layout == WatchLayout.PHONE, modifier = focusMod(), lastPlayed = true) { onAction(previous) }
            is WatchAction.PlayTorrent -> SourceCard(previous.stream, focusMod(),
                otherSource = true, lastPlayed = true) { onAction(previous) }
            else -> Unit
        }
        if (w.searching) SearchingPlaceholder(layout)

        val native = w.options.filterIsInstance<WatchAction.PlayNative>().filter { it != previous }
        if (native.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Play in CineSays", native.size.takeIf { it > 1 })
            native.forEachIndexed { i, a -> FeaturedSourceCard(a.stream, featured = i == 0, compact = layout == WatchLayout.PHONE, modifier = focusMod()) { onAction(a) } }
        }

        val services = w.options.filterIsInstance<WatchAction.OpenService>()
        if (services.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("On streaming services", services.size, "Opens the service's own app; sign-in and subscription are with the service.")
            var allServices by remember(w.selected?.key) { mutableStateOf(false) }
            val firstRows = columns * (if (layout == WatchLayout.PHONE) 4 else 2)
            TileGrid(if (allServices) services else services.take(firstRows), columns, gap) { a ->
                ServiceTile(a.providerName, "Region ${a.region}", mine = a.providerId in w.myServiceIds, width = tileWidth, modifier = focusMod()) { onAction(a) }
            }
            if (services.size > firstRows) CsChip(if (allServices) "Show fewer" else "Show all ${services.size} services", allServices, compact = true) { allServices = !allServices }
        }

        w.options.filterIsInstance<WatchAction.OpenStremio>().firstOrNull()?.let { a ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("Other apps")
                ServiceTile("Stremio", "Opens this title in Stremio", mine = false, width = tileWidth, modifier = focusMod()) { onAction(a) }
            }
        }

        // Preserve the discovery order within Other Sources.
        val torrents = w.options.filterIsInstance<WatchAction.PlayTorrent>().filter { it != previous }
        if (torrents.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Other Sources", torrents.size)
            torrents.forEach { a -> SourceCard(a.stream, focusMod(), otherSource = true) { onAction(a) } }
        }

        w.options.filterIsInstance<WatchAction.Unavailable>().firstOrNull()?.let { a -> Notice(a.reason) }
        if (!stremioInstalled && !w.searching && w.selected?.imdbId != null)
            Text("Stremio isn't installed, so “Open in Stremio” isn't offered.", color = Cs.textMuted, fontSize = 12.sp)

        val unsupported = w.search?.unsupported.orEmpty()
        if (unsupported.isNotEmpty()) {
            var open by remember(w.selected?.key) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CsChip((if (open) "▾ " else "▸ ") + "Found, but can't play here (${unsupported.size})", open, compact = true) { open = !open }
                if (open) unsupported.take(12).forEach { s ->
                    Text("${s.sourceName}: ${s.unsupportedReason ?: "Unsupported"}", color = Cs.textMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        val failures = w.search?.failures.orEmpty()
        if (!w.searching && (failures.isNotEmpty() || w.search != null)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (failures.isNotEmpty()) Text("Couldn't reach: " + failures.entries.joinToString { "${it.key} (${it.value})" }, color = Cs.warn, fontSize = 13.sp, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            CsChip("↻ Search again", false, compact = true, modifier = focusMod()) { onRetry() }
        }
    }
}

/** Lays [items] out in rows of [columns], each cell drawn by [cell]. */
@Composable
private fun <T> TileGrid(items: List<T>, columns: Int, gap: androidx.compose.ui.unit.Dp, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { cell(it) } } }
    }
}

/** While sources are being found: a calm placeholder in the shape of the result. */
@Composable
private fun SearchingPlaceholder(layout: WatchLayout) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "searching")
    val a by pulse.animateFloat(.35f, .7f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Cs.accent)
            Text("Finding the best ways to watch…", color = Cs.textSoft, fontSize = 15.sp)
        }
        Box(Modifier.fillMaxWidth().height(if (layout == WatchLayout.PHONE) 92.dp else 104.dp).graphicsLayer { alpha = a }
            .background(Cs.surface, RoundedCornerShape(20.dp)).border(1.dp, Cs.line, RoundedCornerShape(20.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(if (layout == WatchLayout.PHONE) 1 else 2) {
                Box(Modifier.weight(1f).height(64.dp).graphicsLayer { alpha = a }.background(Cs.surface, RoundedCornerShape(16.dp)).border(1.dp, Cs.line, RoundedCornerShape(16.dp)))
            }
        }
    }
}

/** The in-app source: big play disc, title, the facts the source stated (unknown shown as such), licence. */
@Composable
private fun FeaturedSourceCard(s: PlayableStream, featured: Boolean, compact: Boolean, modifier: Modifier, lastPlayed: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val glow = if (featured) Brush.horizontalGradient(listOf(Cs.accent.copy(alpha = .22f), Cs.surface.copy(alpha = 0f))) else Brush.horizontalGradient(listOf(Cs.surface, Cs.surface))
    Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.graphicsLayer { val k = if (focused) 1.015f else 1f; scaleX = k; scaleY = k }
        .clip(shape).background(Cs.surface, shape).background(glow, shape)
        .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else if (featured || lastPlayed) Cs.accent.copy(alpha = .55f) else Cs.line, shape)
        .clickable(onClick = onClick).padding(horizontal = if (compact) 14.dp else 22.dp, vertical = if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 20.dp)) {
        Box(Modifier.size(if (compact) 48.dp else 60.dp).background(if (focused || featured) Cs.accent else Cs.surfaceHigh, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) {
            Text("▶", color = if (focused || featured) Color(0xFF041427) else Cs.text, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.sourceName, color = Cs.text, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (lastPlayed) CsBadge("Last played")
            // Only what the source stated; anything else reads "unknown".
            val facts = listOf("Resolution" to s.resolution, "HDR" to s.hdr, "Audio" to s.audio, "Size" to s.sizeBytes?.let { formatSize(it) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                facts.forEach { (k, v) -> FactPill("$k: ${v ?: "unknown"}", if (v == null) Cs.textMuted else Cs.textSoft) }
            }
            listOfNotNull(s.label?.let { "As described by the source: $it" }, s.licence).forEach {
                Text(it, color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (!compact) CsBadge(when (s.kind) { StreamKind.HLS -> "HLS"; StreamKind.DASH -> "DASH"; else -> "Direct" })
    }
}

/** A service (or other app) tile: monogram, name, sub-line, "Your service" tag. */
@Composable
private fun ServiceTile(name: String, sub: String, mine: Boolean, width: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.width(width).onFocusChanged { focused = it.isFocused }.graphicsLayer { val k = if (focused) 1.03f else 1f; scaleX = k; scaleY = k }
        .clip(shape).background(if (focused) Cs.surfaceHigh else Cs.surface, shape)
        .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else if (mine) Cs.accent.copy(alpha = .5f) else Cs.line, shape)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val hue = (name.hashCode() and 0xFFFFFF)
        Box(Modifier.size(40.dp).background(Brush.linearGradient(listOf(Color(0xFF000000 or hue.toLong()).copy(alpha = .9f), Cs.surfaceHigh)), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Text(sub, color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (mine) Text("Yours", color = Cs.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Cs.accent.copy(alpha = .14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
        if (width >= 300.dp) Text("↗", color = if (focused) Cs.text else Cs.textMuted, fontSize = 16.sp)
    }
}

@Composable
private fun SourceCard(s: PlayableStream, modifier: Modifier, otherSource: Boolean = false, lastPlayed: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.graphicsLayer { val k = if (focused) 1.015f else 1f; scaleX = k; scaleY = k }
        .clip(shape).background(if (focused) Cs.surfaceHigh else Cs.surface, shape)
        .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else if (lastPlayed) Cs.accent else Cs.line, shape).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("▶", color = Cs.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (otherSource) SourceDetails.heading(s) else s.sourceName, color = Cs.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lastPlayed) CsBadge("Last played")
            if (otherSource) {
                val details = remember(s) { SourceDetails.summary(s) }
                Text(details, color = Cs.textMuted, fontSize = 12.sp)
                SourceDetails.description(s)?.let { Text("Reported by source:\n$it", color = Cs.textSoft, fontSize = 12.sp) }
                s.debridTorrent?.filename?.takeIf { s.label?.contains(it) != true }?.let {
                    Text("File: $it", color = Cs.textSoft, fontSize = 12.sp)
                }
            } else {
                // Source | Resolution | HDR | Audio | Size — only what the source stated; the rest shows as unknown.
                val facts = listOf("Resolution" to s.resolution, "HDR" to s.hdr, "Audio" to s.audio, "Size" to s.sizeBytes?.let { formatSize(it) })
                Text(facts.joinToString("   ") { (k, v) -> "$k: ${v ?: "unknown"}" }, color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(s.label?.let { "As described by the source: $it" }, s.licence).forEach {
                    Text(it, color = Cs.textSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        CsBadge(if (otherSource) "Source" else when (s.kind) { StreamKind.HLS -> "HLS"; StreamKind.DASH -> "DASH"; StreamKind.TORRENT -> "Source"; else -> "Direct" })
    }
}

private fun formatSize(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "%.0f MB".format(b / (1L shl 20).toDouble())

// ───────────────────────────── Home: Continue watching ─────────────────────────────

@Composable
fun ContinueWatchingRow(items: List<PlaybackRecord>, onResume: (PlaybackRecord) -> Unit, onDetails: (PlaybackRecord) -> Unit, onRemove: (PlaybackRecord) -> Unit = {}) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CsSectionTitle("Continue watching")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(items, key = { it.key }) { r -> ContinueCard(r, { onResume(r) }, { onDetails(r) }, { onRemove(r) }) }
        }
    }
}

@Composable
private fun ContinueCard(r: PlaybackRecord, onResume: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val image = r.poster?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w342$it" }
    Column(Modifier.width(150.dp).graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused }.clip(shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape).clickable(onClick = onResume)) {
            CsArt(image, r.title, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
            Text("▶ Resume", color = Cs.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
        LinearProgressIndicator(progress = { r.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Cs.accent, trackColor = Cs.line)
        Text(r.title, color = if (focused) Cs.text else Cs.textSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.subtitle, color = Cs.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.ref() != null) CardLink("Details", Cs.accent, onDetails)
            CardLink("✕ Remove", Cs.textMuted, onRemove) // also leaves Continue watching on your other devices
        }
    }
}

/** A small text action under a card, with a visible highlight when the remote is on it. */
@Composable
private fun CardLink(label: String, color: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(label, color = if (focused) Cs.bg else color, fontSize = 11.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(6.dp))
            .background(if (focused) Cs.text else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp))
}

// ───────────────────────────── Settings › Playback & Streaming ─────────────────────────────

@Composable
fun PlaybackSettingsSection(prefs: PlaybackPrefs, stremioInstalled: Boolean, streamAddons: List<Pair<Addon, Boolean>>,
                            onChange: ((PlaybackPrefs) -> PlaybackPrefs) -> Unit, onTest: (StreamKind) -> Unit = {}) {
    val context = LocalContext.current
    Text("Playback & Streaming", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("How CineSays plays titles in its own player. Your choices apply the next time a video starts.", color = Cs.textSoft, fontSize = 14.sp)

    @Composable fun Choice(label: String, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
        Text(label, color = Cs.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, l) -> CsChip(l, v == current, compact = true) { set(v) } }
        }
    }
    val langs = listOf("" to "Stream default", "en" to "English", "ur" to "Urdu", "hi" to "Hindi", "ar" to "Arabic", "es" to "Spanish", "fr" to "French", "de" to "German")
    Choice("Preferred quality", listOf("auto" to "Auto", "2160" to "Up to 4K", "1080" to "Up to 1080p", "720" to "Up to 720p", "480" to "Data saver (480p)"), prefs.quality) { v -> onChange { it.copy(quality = v) } }
    Choice("Audio language", langs, prefs.audioLanguage) { v -> onChange { it.copy(audioLanguage = v) } }
    Choice("Subtitles", listOf("off" to "Off", "auto" to "Only when the stream says so", "on" to "Always on"), prefs.subtitleMode) { v -> onChange { it.copy(subtitleMode = v) } }
    Choice("Subtitle language", langs.drop(1), prefs.subtitleLanguage) { v -> onChange { it.copy(subtitleLanguage = v) } }
    Choice("Subtitle size", listOf("0.8" to "Small", "1.0" to "Normal", "1.3" to "Large", "1.6" to "Extra large"), prefs.subtitleScale.toString()) { v -> onChange { it.copy(subtitleScale = v.toFloat()) } }
    Choice("When you come back to a title", listOf("ask" to "Ask", "always" to "Always resume", "never" to "Start over"), prefs.resume) { v -> onChange { it.copy(resume = v) } }
    Choice("Watch Now opens", listOf(PlaybackDestination.ASK.name to "Show me the choices", PlaybackDestination.CINESAYS.name to "Play in CineSays when possible",
        PlaybackDestination.SERVICE.name to "My streaming service"), prefs.destination.name) { v -> onChange { it.copy(destination = PlaybackDestination.valueOf(v)) } }
    Choice("Count as watched at", listOf("80" to "80%", "90" to "90%", "95" to "95%"), Math.round(prefs.completeAt * 100).toString()) { v -> onChange { it.copy(completeAt = v.toInt() / 100.0) } }
    Choice("Buffering", listOf("standard" to "Standard", "large" to "More buffer (unsteady internet)"), prefs.buffering) { v -> onChange { it.copy(buffering = v) } }
    Text("Source discovery: when opening Watch Now for a movie or choosing an episode.", color = Cs.textSoft, fontSize = 13.sp)
    Choice("Preferred source", listOf("direct" to "Direct HLS / MP4 first", "torrent" to "Torrent first"), prefs.preferredSource) { v -> onChange { it.copy(preferredSource = v) } }
    Choice("Progressive torrent streaming", listOf("true" to "Enabled", "false" to "Disabled"), prefs.progressiveTorrentStreaming.toString()) { v -> onChange { it.copy(progressiveTorrentStreaming = v.toBoolean()) } }
    Choice("Authorized torrent-to-HTTP", listOf("true" to "Enabled when configured", "false" to "Disabled"), prefs.authorizedTorrentToHttpPlayback.toString()) { v -> onChange { it.copy(authorizedTorrentToHttpPlayback = v.toBoolean()) } }
    OutlinedTextField(
        value = prefs.torrServerUrl,
        onValueChange = { value -> onChange { it.copy(torrServerUrl = value.trim().take(240)) } },
        label = { Text("Self-hosted TorrServer HTTPS address") },
        placeholder = { Text("https://myserver.my-tailnet.ts.net") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth())
    val gatewayReady = TorrServerGateway.normalizedBase(prefs.torrServerUrl) != null
    Text(
        if (gatewayReady) "Home gateway configured. It is used only when you select a permitted torrent source."
        else "Optional: enter your private Tailscale HTTPS address (.ts.net). Local progressive torrent playback remains the fallback.",
        color = Cs.textSoft, fontSize = 13.sp)

    Text("Stremio: " + if (stremioInstalled) "installed. “Open in Stremio” appears in Watch Now." else "not installed.", color = Cs.textSoft, fontSize = 14.sp)
    Text("Streaming sources", color = Cs.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val sourceNote = if (streamAddons.any { it.first.torrentStreams || it.first.authorizedTorrentHashes.orEmpty().isNotEmpty() })
        " Torrent sources appear in Watch Now. Use content you are authorized to access; direct torrents use device storage and upload to peers."
        else " Enable additional sources under Add-ons."
    Text("In-app sources include public-domain and openly licensed films (Internet Archive)" +
        (if (streamAddons.isNotEmpty()) " and ${streamAddons.joinToString { it.first.name }}" else "") +
        ". Turn collections on or off under Add-ons." + sourceNote, color = Cs.textSoft, fontSize = 13.sp)


    var showDiag by remember { mutableStateOf(false) }
    CsChip(if (showDiag) "Hide playback diagnostics" else "Playback diagnostics", showDiag, compact = true) { showDiag = !showDiag }
    if (showDiag) {
        val lines = remember { playbackDiagnostics(context) }
        lines.forEach { Text(it, color = Cs.textMuted, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CsChip("Test HLS playback", false, compact = true) { onTest(StreamKind.HLS) }
            CsChip("Test DASH playback", false, compact = true) { onTest(StreamKind.DASH) }
        }
        Text("Plays public test streams from Apple and Google to check this device's player.", color = Cs.textMuted, fontSize = 12.sp)
    }
}

/** What this device reports it can decode and output. Reported by Android, not guaranteed for every stream. */
@OptIn(UnstableApi::class)
fun playbackDiagnostics(context: Context): List<String> {
    val types = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.toSet() }.getOrDefault(emptySet())
    fun has(t: String) = if (t in types) "yes" else "no"
    val display: Display? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.display else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
    }.getOrNull()
    val hdr = runCatching {
        @Suppress("DEPRECATION") display?.hdrCapabilities?.supportedHdrTypes?.map { t -> when (t) {
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"; Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"; Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"; else -> "type $t" } }
    }.getOrNull().orEmpty()
    val mode = display?.mode
    val audio = runCatching { AudioCapabilities.getCapabilities(context) }.getOrNull()
    fun pass(enc: Int) = if (audio?.supportsEncoding(enc) == true) "yes" else "no"
    return listOf(
        "Display: ${mode?.let { "${it.physicalWidth}×${it.physicalHeight} @ ${"%.0f".format(it.refreshRate)} Hz" } ?: "unknown"}",
        "HDR output: ${hdr.ifEmpty { listOf("none reported") }.joinToString()}",
        "Video decoders — H.264: ${has("video/avc")}, HEVC: ${has("video/hevc")}, VP9: ${has("video/x-vnd.on2.vp9")}, AV1: ${has("video/av01")}, Dolby Vision: ${has("video/dolby-vision")}",
        "Audio passthrough — Dolby Digital: ${pass(C.ENCODING_AC3)}, Dolby Digital Plus: ${pass(C.ENCODING_E_AC3)}, Atmos (E-AC-3 JOC): ${pass(C.ENCODING_E_AC3_JOC)}, DTS: ${pass(C.ENCODING_DTS)}",
        "Max audio channels: ${audio?.maxChannelCount ?: "unknown"}"
    )
}
