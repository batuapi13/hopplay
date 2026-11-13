package com.hopplay.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hopplay.player.PlayerController
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

private data class TrackOpt(val label: String, val selected: Boolean, val group: TrackGroup, val index: Int)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOpt> {
    var n = 0
    return tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            n++
            val base = f.label ?: f.language?.let { Locale(it).displayLanguage } ?: "Track $n"
            val extra = if (type == C.TRACK_TYPE_AUDIO) {
                " (${f.sampleMimeType?.substringAfter('/')}${if (f.channelCount > 0) " ${f.channelCount}ch" else ""})"
            } else ""
            TrackOpt(base + extra, g.isTrackSelected(i), g.mediaTrackGroup, i)
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerScreen(c: PlayerController, onBack: () -> Unit) {
    val player = c.player
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val view = LocalView.current
    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var controlsVisible by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(player.playerError?.message) }
    var tick by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString().orEmpty()) }
    var speed by remember { mutableStateOf(player.playbackParameters.speed) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Int?>(null) } // 0 audio, 1 subtitles, 2 speed
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var orient by remember { mutableStateOf(0) } // 0 auto, 1 landscape, 2 portrait

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "Error ${error.errorCode}: ${error.cause?.message ?: error.message ?: "Playback failed"}"
                controlsVisible = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) playbackError = null
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                title = item?.mediaMetadata?.title?.toString().orEmpty()
            }
        }
        player.addListener(l)
        title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        onDispose { player.removeListener(l) }
    }
    DisposableEffect(view) {
        val ctl = WindowCompat.getInsetsController(activity.window, view)
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.window.attributes = activity.window.attributes.also { it.screenBrightness = -1f }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }
    LaunchedEffect(player) {
        while (true) { delay(5_000); c.savePosition() }
    }
    LaunchedEffect(controlsVisible, isPlaying, tick, dialog) {
        if (controlsVisible && isPlaying && dialog == null) { delay(4_000); controlsVisible = false }
    }
    LaunchedEffect(overlay) {
        if (overlay != null) { delay(900); overlay = null }
    }

    fun seekBy(ms: Long) {
        val t = (player.currentPosition + ms).coerceAtLeast(0)
        player.seekTo(if (dur > 0) t.coerceAtMost(dur) else t)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture layer: tap toggles controls, double-tap skips, drags seek / volume / brightness.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible; tick++ },
                        onDoubleTap = { o ->
                            if (o.x < size.width / 2) { seekBy(-10_000); overlay = "⏪ 10s" }
                            else { seekBy(10_000); overlay = "10s ⏩" }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var mode = 0 // 1 seek, 2 volume, 3 brightness
                    var side = 2
                    var accX = 0f
                    var accY = 0f
                    var startPos = 0L
                    var target = 0L
                    var startVol = 0f
                    var startBright = 0.5f
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    detectDragGestures(
                        onDragStart = { o ->
                            mode = 0; accX = 0f; accY = 0f
                            side = if (o.x < size.width / 2) 3 else 2
                            startPos = player.currentPosition
                            target = startPos
                            startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                            startBright = activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                        },
                        onDrag = { change, d ->
                            change.consume()
                            accX += d.x; accY += d.y
                            if (mode == 0 && (abs(accX) > 24 || abs(accY) > 24)) mode = if (abs(accX) > abs(accY)) 1 else side
                            when (mode) {
                                1 -> {
                                    val max = if (dur > 0) dur else Long.MAX_VALUE
                                    target = (startPos + accX / size.width * 120_000).toLong().coerceIn(0, max)
                                    val delta = (target - startPos) / 1000
                                    overlay = "${fmt(target)}  (${if (delta >= 0) "+" else ""}${delta}s)"
                                }
                                2 -> {
                                    val v = (startVol - accY / size.height * 1.2f).coerceIn(0f, 1f)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVol).roundToInt(), 0)
                                    overlay = "Volume ${(v * 100).roundToInt()}%"
                                }
                                3 -> {
                                    val b = (startBright - accY / size.height * 1.2f).coerceIn(0.02f, 1f)
                                    activity.window.attributes = activity.window.attributes.also { it.screenBrightness = b }
                                    overlay = "Brightness ${(b * 100).roundToInt()}%"
                                }
                            }
                        },
                        onDragEnd = { if (mode == 1) player.seekTo(target); mode = 0 },
                        onDragCancel = { mode = 0 },
                    )
                },
        )

        playbackError?.let { message ->
            Column(Modifier.align(Alignment.Center).background(Color(0xDD330000)).padding(16.dp)) {
                Text(message, color = Color.White)
                TextButton(onClick = { playbackError = null; player.prepare(); player.play() }) {
                    Text("Retry", color = Color.White)
                }
            }
        }

        overlay?.let {
            Text(
                it, color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(16.dp),
            )
        }

        if (controlsVisible) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("←", color = Color.White) }
                Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    orient = (orient + 1) % 3
                    activity.requestedOrientation = when (orient) {
                        1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }) { Text(listOf("Auto", "Land", "Port")[orient], color = Color.White) }
                TextButton(onClick = { dialog = 0 }) { Text("Audio", color = Color.White) }
                TextButton(onClick = { dialog = 1 }) { Text("Subs", color = Color.White) }
                TextButton(onClick = { dialog = 2 }) { Text("${speed}x", color = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(pos), color = Color.White)
                    Slider(
                        value = scrubbing ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { scrubbing = it; tick++ },
                        onValueChangeFinished = { scrubbing?.let { player.seekTo((it * dur).toLong()) }; scrubbing = null },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(fmt(dur), color = Color.White)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { player.seekToPreviousMediaItem(); tick++ }) { Text("⏮", color = Color.White) }
                    TextButton(onClick = { seekBy(-10_000); tick++ }) { Text("⏪ 10", color = Color.White) }
                    TextButton(onClick = { if (isPlaying) player.pause() else player.play(); tick++ }) {
                        Text(if (isPlaying) "⏸" else "▶", color = Color.White)
                    }
                    TextButton(onClick = { seekBy(10_000); tick++ }) { Text("10 ⏩", color = Color.White) }
                    TextButton(onClick = { player.seekToNextMediaItem(); tick++ }) { Text("⏭", color = Color.White) }
                }
            }
        }
    }

    when (dialog) {
        0 -> TrackDialog("Audio track", trackOptions(tracks, C.TRACK_TYPE_AUDIO), offSelected = null, onOff = null,
            onPick = { o -> select(player, C.TRACK_TYPE_AUDIO, o) }, onClose = { dialog = null })
        1 -> {
            val opts = trackOptions(tracks, C.TRACK_TYPE_TEXT)
            TrackDialog("Subtitles", opts,
                offSelected = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || opts.none { it.selected },
                onOff = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                },
                onPick = { o -> select(player, C.TRACK_TYPE_TEXT, o) }, onClose = { dialog = null })
        }
        2 -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            title = { Text("Playback speed") },
            text = {
                Column {
                    SPEEDS.forEach { s ->
                        TextButton(onClick = { player.setPlaybackSpeed(s); speed = s; dialog = null }) {
                            Text((if (s == speed) "✓ " else "   ") + "${s}x")
                        }
                    }
                }
            },
        )
    }
}

private fun select(player: androidx.media3.exoplayer.ExoPlayer, type: Int, o: TrackOpt) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(TrackSelectionOverride(o.group, o.index))
        .build()
}

@Composable
private fun TrackDialog(
    title: String,
    options: List<TrackOpt>,
    offSelected: Boolean?,
    onOff: (() -> Unit)?,
    onPick: (TrackOpt) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                if (onOff != null) TextButton(onClick = { onOff(); onClose() }) { Text((if (offSelected == true) "✓ " else "   ") + "Off") }
                if (options.isEmpty()) Text("No tracks available")
                options.forEach { o ->
                    TextButton(onClick = { onPick(o); onClose() }) {
                        Text((if (o.selected && offSelected != true) "✓ " else "   ") + o.label)
                    }
                }
            }
        },
    )
}
