package com.cineglass.tv

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.view.Display
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
    onNext: (() -> Unit)?, onFindSources: (() -> Unit)?, onSubtitlePref: (String?) -> Unit
) {
    val context = LocalContext.current
    val player = remember(playback.url) { buildPlayer(context, playback, prefs) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var error by remember(playback.url) { mutableStateOf<String?>(null) }
    var ended by remember(playback.url) { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Int?>(null) } // C.TRACK_TYPE_* or SPEED
    var seekHint by remember { mutableStateOf<String?>(null) }

    fun report() {
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (d > 0 || player.currentPosition > 0) onProgress(player.currentPosition, d)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = describePlaybackError(e); report() }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { ended = true; report() } }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) report() } // pause / stop
        }
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

    // Back: close a picker, then hide the controls, then leave the player.
    BackHandler {
        when {
            picker != null -> picker = null
            playerView?.isControllerFullyVisible == true && error == null && !ended -> playerView?.hideController()
            else -> onClose()
        }
    }

    val viewFocus = remember { FocusRequester() }
    // Focus starts on the video's remote layer (not "Close"), so OK shows the controls instead of leaving.
    fun focusVideo() { runCatching { viewFocus.requestFocus() } }
    LaunchedEffect(playerView) { if (playerView != null) { delay(150); focusVideo() } }

    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700
    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        // Header: its own bar, so it never covers the video's controls. Up from the controls reaches it.
        // Phones put the buttons on a second line so the title stays readable.
        val buttons: @Composable () -> Unit = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CsChip("Quality", false, compact = true) { picker = C.TRACK_TYPE_VIDEO }
                CsChip("Audio", false, compact = true) { picker = C.TRACK_TYPE_AUDIO }
                CsChip("Subtitles", false, compact = true) { picker = C.TRACK_TYPE_TEXT }
                CsChip("Speed", false, compact = true) { picker = SPEED }
                if (onNext != null) CsChip("Next episode ▸", false, compact = true) { report(); onNext() }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CsChip("← Close", false, compact = true) { onClose() }
            Column(Modifier.weight(1f)) {
                Text(playback.title, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(playback.sourceName.ifBlank { null }, playback.licence).joinToString(" · "), color = Cs.textMuted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!compact) buttons()
        }
        if (compact) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { buttons() }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    // The remote is handled by CineSays' focus layer below; the view draws video and controls (and
                    // takes touch) but never takes focus, so D-pad focus can't get lost inside it.
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    subtitleView?.setApplyEmbeddedStyles(true)
                    subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleScale)
                    playerView = this
                }
            }, modifier = Modifier.fillMaxSize())
            // Remote layer over the video (no pointer handling, so touches still reach the player):
            // OK shows the controls, or toggles play/pause while they're up; Left/Right seek 10 s; Play/Pause toggles;
            // Up moves to Quality / Audio / Subtitles / Speed.
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
                        else -> false
                    }
                }
                .focusable())
            seekHint?.let { Text(it, color = Cs.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) }

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
    }

    picker?.let { type -> TrackPicker(player, type, onSubtitlePref) { picker = null; focusVideo() } }
}

private const val SPEED = -100


@Composable
private fun PlayerOverlay(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.widthIn(max = 560.dp).padding(24.dp).background(Cs.panel.copy(alpha = .94f), RoundedCornerShape(20.dp))
        .border(1.dp, Cs.line, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, p: NativePlayback, prefs: PlaybackPrefs): ExoPlayer {
    val http = DefaultHttpDataSource.Factory().setUserAgent("CineSays/${BuildConfig.VERSION_NAME} (Android TV)")
        .setAllowCrossProtocolRedirects(false).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
    val load = if (prefs.buffering == "large") DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 6_000).build()
        else DefaultLoadControl()
    val player = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
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
private fun TrackPicker(player: ExoPlayer, type: Int, onSubtitlePref: (String?) -> Unit, onDone: () -> Unit) {
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
                    Opt(trackLabel(f, type), trackDetail(f, type), g.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                        if (type == C.TRACK_TYPE_TEXT) onSubtitlePref(f.language)
                    }
                }
            }
        }
    }
    val title = when (type) { C.TRACK_TYPE_VIDEO -> "Quality"; C.TRACK_TYPE_AUDIO -> "Audio"; C.TRACK_TYPE_TEXT -> "Subtitles"; else -> "Playback speed" }
    Dialog(onDismissRequest = onDone) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        Column(Modifier.widthIn(min = 320.dp, max = 520.dp).heightIn(max = 520.dp).background(Cs.panel, RoundedCornerShape(20.dp))
            .border(1.dp, Cs.line, RoundedCornerShape(20.dp)).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (opts.size <= 1 && type != SPEED) Text("This stream offers only one ${title.lowercase()} option.", color = Cs.textMuted, fontSize = 13.sp)
            val start = opts.indexOfFirst { it.selected }.coerceAtLeast(0)
            opts.forEachIndexed { i, o ->
                OptionRow(o.label, o.detail, o.selected, if (i == start) Modifier.focusRequester(first) else Modifier) { o.apply(); onDone() }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, detail: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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

@Composable
fun WatchNowDialog(
    w: WatchNowState, stremioInstalled: Boolean, onClose: () -> Unit, onSeason: (Int) -> Unit, onEpisode: (TrackerEpisode) -> Unit,
    onAction: (WatchAction) -> Unit, onRetry: () -> Unit, onMarkEpisode: (MediaRef, Boolean) -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700
        val shape = RoundedCornerShape(24.dp)
        Box(Modifier.fillMaxWidth(if (compact) .96f else .84f).fillMaxHeight(.9f).clip(shape)
            .background(Brush.verticalGradient(listOf(Cs.surface.copy(alpha = .97f), Cs.panel.copy(alpha = .98f)))).border(1.dp, Cs.lineStrong, shape)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (compact) 16.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("WATCH NOW", color = Cs.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Text(w.item.label + (w.item.year.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), color = Cs.text,
                            fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    CsChip("✕ Close", false, compact = true) { onClose() }
                }

                if (w.isSeries) EpisodePicker(w, onSeason, onEpisode)
                w.error?.let { Text(it, color = Cs.warn, fontSize = 14.sp) }

                val sel = w.selected
                if (sel != null && w.isSeries) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("S${sel.season} E${sel.episode}" + (sel.episodeName?.let { " · $it" } ?: ""), color = Cs.text, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val watched = ((sel.season ?: 0) to (sel.episode ?: 0)) in w.watchedEpisodes
                    CsChip(if (watched) "✓ Watched" else "Mark watched", watched, compact = true) { onMarkEpisode(sel, !watched) }
                }

                if (sel != null) SourcesSection(w, stremioInstalled, onAction, onRetry)
            }
        }
    }
}

@Composable
private fun EpisodePicker(w: WatchNowState, onSeason: (Int) -> Unit, onEpisode: (TrackerEpisode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (w.seasons.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            w.seasons.forEach { s -> CsChip("Season $s", s == w.season, compact = true) { onSeason(s) } }
        }
        if (w.episodesBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
        val listState = rememberLazyListState()
        val selIndex = w.episodes.indexOfFirst { it.number == w.selected?.episode }
        LaunchedEffect(w.season, w.episodes.size) { if (selIndex > 0) listState.scrollToItem((selIndex - 1).coerceAtLeast(0)) }
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(w.episodes, key = { "${it.season}:${it.number}" }) { e ->
                val p = w.seasonProgress[e.number]
                val watched = (e.season to e.number) in w.watchedEpisodes || p?.completed == true
                EpisodeCard(e, selected = e.number == w.selected?.episode, watched = watched,
                    progress = p?.takeIf { !it.completed && it.positionMs >= ProgressRules.MIN_RESUME_MS }?.fraction?.toFloat()) { onEpisode(e) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(e: TrackerEpisode, selected: Boolean, watched: Boolean, progress: Float?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(210.dp).graphicsLayer { val s = if (focused) 1.04f else 1f; scaleX = s; scaleY = s }
        .onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (selected) Cs.surfaceHigh else Cs.surface, shape)
        .border(if (focused) 3.dp else if (selected) 2.dp else 1.dp, if (focused) Cs.focusRing else if (selected) Cs.accent else Cs.line, shape)
        .clickable(onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("E${e.number}", color = Cs.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (watched) Text("✓ Watched", color = Cs.good, fontSize = 11.sp)
        }
        Text(e.name.ifBlank { "Episode ${e.number}" }, color = Cs.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Cs.accent, trackColor = Cs.line) }
    }
}

@Composable
private fun SourcesSection(w: WatchNowState, stremioInstalled: Boolean, onAction: (WatchAction) -> Unit, onRetry: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(w.options) { if (w.options.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (w.searching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Cs.accent)
            Text("Finding sources…", color = Cs.textSoft, fontSize = 15.sp)
        }
        var firstUsed = false
        fun focusMod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier

        val native = w.options.filterIsInstance<WatchAction.PlayNative>()
        if (native.isNotEmpty()) {
            CsSectionTitle("Play in CineSays")
            native.forEach { a -> SourceCard(a.stream, focusMod()) { onAction(a) } }
        }
        val services = w.options.filterIsInstance<WatchAction.OpenService>()
        if (services.isNotEmpty()) {
            CsSectionTitle("On streaming services")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                services.forEach { a -> CsButton("Open in ${a.providerName}" + (if (a.providerId in w.myServiceIds) " ✓" else "") + " · ${a.region}",
                    primary = native.isEmpty() && a == services.first(), modifier = focusMod()) { onAction(a) } }
            }
            Text("Opens the service's own app (sign-in and subscription are with the service).", color = Cs.textMuted, fontSize = 12.sp)
        }
        w.options.filterIsInstance<WatchAction.OpenStremio>().firstOrNull()?.let { a ->
            CsSectionTitle("Other apps")
            CsButton("Open in Stremio", modifier = focusMod()) { onAction(a) }
        }
        if (!stremioInstalled && !w.searching && w.selected?.imdbId != null)
            Text("Stremio isn't installed, so “Open in Stremio” isn't offered.", color = Cs.textMuted, fontSize = 12.sp)
        w.options.filterIsInstance<WatchAction.Unavailable>().firstOrNull()?.let { a ->
            PlayerOverlayInline { Text(a.reason, color = Cs.textSoft, fontSize = 15.sp) }
        }
        val unsupported = w.search?.unsupported.orEmpty()
        if (unsupported.isNotEmpty()) {
            CsSectionTitle("Found, but can't play here")
            unsupported.take(8).forEach { s ->
                Text("${s.sourceName}: ${s.unsupportedReason ?: "Unsupported"}", color = Cs.textMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun PlayerOverlayInline(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Cs.surface, RoundedCornerShape(14.dp)).border(1.dp, Cs.line, RoundedCornerShape(14.dp)).padding(16.dp), content = content)
}

@Composable
private fun SourceCard(s: PlayableStream, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.graphicsLayer { val k = if (focused) 1.015f else 1f; scaleX = k; scaleY = k }
        .clip(shape).background(if (focused) Cs.surfaceHigh else Cs.surface, shape)
        .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("▶", color = Cs.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.sourceName, color = Cs.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Source | Resolution | HDR | Audio | Size — only what the source stated; the rest shows as unknown.
            val facts = listOf("Resolution" to s.resolution, "HDR" to s.hdr, "Audio" to s.audio, "Size" to s.sizeBytes?.let { formatSize(it) })
            Text(facts.joinToString("   ") { (k, v) -> "$k: ${v ?: "unknown"}" }, color = Cs.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(s.label?.let { "As described by the source: $it" }, s.licence).forEach {
                Text(it, color = Cs.textSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        CsBadge(when (s.kind) { StreamKind.HLS -> "HLS"; StreamKind.DASH -> "DASH"; else -> "Direct" })
    }
}

private fun formatSize(b: Long): String = if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "%.0f MB".format(b / (1L shl 20).toDouble())

// ───────────────────────────── Home: Continue watching ─────────────────────────────

@Composable
fun ContinueWatchingRow(items: List<PlaybackRecord>, onResume: (PlaybackRecord) -> Unit, onDetails: (PlaybackRecord) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CsSectionTitle("Continue watching")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(items, key = { it.key }) { r -> ContinueCard(r, { onResume(r) }, { onDetails(r) }) }
        }
    }
}

@Composable
private fun ContinueCard(r: PlaybackRecord, onResume: () -> Unit, onDetails: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val image = r.poster?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w342$it" }
    Column(Modifier.width(150.dp).graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused }.clip(shape).background(Cs.surfaceHigh)
            .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape).clickable(onClick = onResume)) {
            image?.let { AsyncImage(it, r.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
            Text("▶ Resume", color = Cs.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
        LinearProgressIndicator(progress = { r.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Cs.accent, trackColor = Cs.line)
        Text(r.title, color = if (focused) Cs.text else Cs.textSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.subtitle, color = Cs.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (r.ref() != null) Text("Details", color = Cs.accent, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onDetails).padding(vertical = 2.dp))
    }
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

    Text("Stremio: " + if (stremioInstalled) "installed. “Open in Stremio” appears in Watch Now." else "not installed.", color = Cs.textSoft, fontSize = 14.sp)
    Text("Streaming sources", color = Cs.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Text("In-app sources come from CineSays' curated list: public-domain and openly licensed films (Internet Archive)" +
        (if (streamAddons.isNotEmpty()) " and ${streamAddons.joinToString { it.first.name }}" else "") +
        ". Turn collections on or off under Add-ons. CineSays doesn't play torrents and doesn't take add-on addresses typed in by hand.",
        color = Cs.textSoft, fontSize = 13.sp)

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
