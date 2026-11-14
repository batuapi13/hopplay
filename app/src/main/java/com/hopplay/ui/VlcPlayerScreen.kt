package com.hopplay.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hopplay.HopPlayApp
import com.hopplay.data.Connection
import com.hopplay.fs.FsEntry
import com.hopplay.player.BUFFERING_WARNING_MS
import com.hopplay.player.CONTROLS_AUTO_HIDE_MS
import com.hopplay.player.DragAxis
import com.hopplay.player.OrientationMode
import com.hopplay.player.PlayState
import com.hopplay.player.PlaybackDiagnostics
import com.hopplay.player.RESUME_SAVE_INTERVAL_MS
import com.hopplay.player.SftpLoopbackBridge
import com.hopplay.player.VlcSession
import com.hopplay.player.dragLevel
import com.hopplay.player.dragSeekTarget
import com.hopplay.player.formatTime
import com.hopplay.player.next
import com.hopplay.ui.player.FeedbackChip
import com.hopplay.ui.player.GestureActions
import com.hopplay.ui.player.GestureLayer
import com.hopplay.ui.player.PlayerBottomBar
import com.hopplay.ui.player.PlayerStatusIndicator
import com.hopplay.ui.player.PlayerTopBar
import com.hopplay.ui.player.SettingsDialog
import com.hopplay.ui.player.TrackDialog
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

private enum class Dialog { NONE, AUDIO, SUBTITLES, SETTINGS }

/**
 * Embedded VLC software-decoding player. SFTP itself is still handled by SSHJ
 * via the loopback range gateway; credentials never reach libVLC.
 */
@Composable
fun VlcPlayerScreen(
    connection: Connection,
    files: List<FsEntry>,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val app = ctx.applicationContext as HopPlayApp
    val diagnostics = remember(connection.id) { PlaybackDiagnostics() }
    val bridge = remember(connection.id, files) {
        SftpLoopbackBridge(app.registry.get(connection.id), files, diagnostics::record)
    }
    fun key(i: Int) = connection.id + "|" + files[i].path
    val session = remember(bridge) {
        VlcSession(
            ctx = ctx, files = files, urlFor = bridge::url, diagnostics = diagnostics,
            resumeGet = { app.resume.get(key(it)) },
            resumePut = { i, p -> app.resume.put(key(i), p) },
            resumeClear = { app.resume.clear(key(it)) },
            startIndex = initialIndex,
        )
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    var locked by remember { mutableStateOf(false) }
    var orientation by remember { mutableStateOf(OrientationMode.AUTO) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackToken by remember { mutableStateOf(0) }
    var longBuffering by remember { mutableStateOf(false) }
    val seekDrag = remember { SeekDragState() }

    fun showFeedback(text: String) { feedback = text; feedbackToken++ }
    fun poke() { interaction++ }

    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(diagnostics.exportText().toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open diagnostic destination")
            }.onSuccess { showFeedback("Log saved") }
                .onFailure { showFeedback("Unable to save log") }
        }
    }
    fun exportLog() {
        diagnostics.record("Manual export index=${session.index} posMs=${session.positionMs} state=${session.state}")
        saveLog.launch("hopplay-vlc-${System.currentTimeMillis()}.txt")
    }
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showFeedback(if (session.addExternalSubtitle(uri)) "Subtitle loaded" else "Could not load subtitle")
        }
    }

    // Auto-hide: restarts on any interaction; never hides while paused, in a dialog, or on a problem state.
    val stickyState = session.state != PlayState.PLAYING
    LaunchedEffect(controlsVisible, interaction, dialog, stickyState) {
        if (controlsVisible && dialog == Dialog.NONE && !stickyState) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(feedbackToken) {
        if (feedback != null) { delay(1200); feedback = null }
    }
    LaunchedEffect(session.bufferingSinceMs, session.state) {
        longBuffering = false
        if (session.bufferingSinceMs != 0L && session.state == PlayState.BUFFERING) {
            delay(BUFFERING_WARNING_MS)
            longBuffering = true
            diagnostics.record("VLC buffering exceeded ${BUFFERING_WARNING_MS}ms posMs=${session.positionMs}")
        }
    }
    LaunchedEffect(session) {
        session.load(initialIndex)
        while (true) {
            delay(RESUME_SAVE_INTERVAL_MS)
            session.savePosition()
        }
    }

    LaunchedEffect(orientation) {
        activity.requestedOrientation = when (orientation) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                session.savePosition()
                if (session.state == PlayState.PLAYING) session.player.pause()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val originalBrightness = remember { activity.window.attributes.screenBrightness }
    // Immersive full screen (bars + cutout) for the lifetime of the player; restored on exit.
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val oldCutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = oldCutout }
            }
        }
    }
    // No permission needed: the window flag keeps the display awake only while actually playing.
    val keepAwake = session.state == PlayState.PLAYING || session.state == PlayState.BUFFERING || session.state == PlayState.LOADING
    LaunchedEffect(keepAwake) {
        if (keepAwake) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    DisposableEffect(session) {
        onDispose {
            session.release()
            bridge.close()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.attributes = activity.window.attributes.also { it.screenBrightness = originalBrightness }
        }
    }

    BackHandler {
        when {
            locked -> showFeedback("Screen locked")
            dialog != Dialog.NONE -> dialog = Dialog.NONE
            else -> onBack()
        }
    }

    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val gestures = remember(session) {
        GestureActions(
            onTap = { controlsVisible = !controlsVisible; poke() },
            onDoubleTap = { left ->
                session.seekBy(if (left) -10_000 else 10_000)
                showFeedback(if (left) "« 10s" else "10s »")
            },
            onSeekStart = { seekDrag.start = session.positionMs },
            onSeekDrag = { dx, w ->
                val t = dragSeekTarget(seekDrag.start, dx, w, session.lengthMs)
                seekDrag.target = t
                showFeedback("${formatTime(t)} / ${formatTime(session.lengthMs)}")
            },
            onSeekEnd = { session.seekTo(seekDrag.target) },
            onLevelStart = { axis ->
                if (axis == DragAxis.BRIGHTNESS) {
                    val b = activity.window.attributes.screenBrightness
                    seekDrag.level = if (b < 0f) 0.5f else b
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    seekDrag.level = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                }
            },
            onLevelDrag = { axis, dy, h ->
                if (axis == DragAxis.BRIGHTNESS) {
                    val v = dragLevel(seekDrag.level, dy, h, min = 0.02f)
                    activity.window.attributes = activity.window.attributes.also { it.screenBrightness = v }
                    showFeedback("Brightness ${(v * 100).toInt()}%")
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val v = dragLevel(seekDrag.level, dy, h)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                    showFeedback("Volume ${(v * 100).toInt()}%")
                }
            },
            onLevelEnd = {},
            onLongPress = { on ->
                session.boost(on)
                if (on) showFeedback("2x speed")
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> VLCVideoLayout(c).also { session.attach(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (locked) {
            // Lock swallows gestures; only the unlock button responds.
            Box(Modifier.fillMaxSize().clickableNoRipple { controlsVisible = true; poke() })
            if (controlsVisible) {
                Button(onClick = { locked = false; showFeedback("Unlocked") }, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null)
                    Text("  Unlock")
                }
            }
        } else {
            GestureLayer(gestures)
            if (controlsVisible) {
                PlayerTopBar(
                    title = session.currentFile.name,
                    onBack = onBack,
                    onAudio = { dialog = Dialog.AUDIO },
                    onSubtitles = { dialog = Dialog.SUBTITLES },
                    orientation = orientation,
                    onToggleOrientation = { orientation = orientation.next(); poke() },
                    onSettings = { dialog = Dialog.SETTINGS },
                    onExportLog = ::exportLog,
                    onLock = { locked = true; controlsVisible = false },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                PlayerBottomBar(
                    state = session.state,
                    positionMs = session.positionMs,
                    lengthMs = session.lengthMs,
                    seekable = session.seekable,
                    hasPrevious = session.hasPrevious,
                    hasNext = session.hasNext,
                    onTogglePlay = { session.togglePlay(); poke() },
                    onPrevious = { session.previous(); poke() },
                    onNext = { session.next(); poke() },
                    onSeekBy = { session.seekBy(it); poke() },
                    onSeekTo = { session.seekTo(it); poke() },
                    onInteraction = ::poke,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        PlayerStatusIndicator(
            state = session.state,
            bufferingPercent = session.bufferingPercent,
            longBuffering = longBuffering,
            errorMessage = session.errorMessage,
            onRetry = session::retry,
            onExport = ::exportLog,
            modifier = Modifier.align(Alignment.Center),
        )
        FeedbackChip(feedback, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
    }

    when (dialog) {
        Dialog.AUDIO -> TrackDialog(
            title = "Audio track", options = session.audioTracks, emptyText = "No audio tracks reported",
            onSelect = { session.selectAudio(it); dialog = Dialog.NONE },
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.SUBTITLES -> TrackDialog(
            title = "Subtitles", options = session.subtitleTracks, emptyText = "",
            offSelected = session.subtitlesOff, onOff = { session.selectSubtitle(null); dialog = Dialog.NONE },
            extraAction = {
                TextButton(onClick = { dialog = Dialog.NONE; pickSubtitle.launch(arrayOf("*/*")) }) { Text("Load external subtitle (SRT/ASS/SSA/VTT)…") }
            },
            onSelect = { session.selectSubtitle(it); dialog = Dialog.NONE },
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.SETTINGS -> SettingsDialog(
            session = session, onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.NONE -> {}
    }
}

private class SeekDragState {
    var start = 0L
    var target = 0L
    var level = 0f
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
