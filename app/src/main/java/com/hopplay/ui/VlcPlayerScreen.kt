package com.hopplay.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hopplay.HopPlayApp
import com.hopplay.data.Connection
import com.hopplay.fs.FsEntry
import com.hopplay.player.PlaybackDiagnostics
import com.hopplay.player.SftpLoopbackBridge
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

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
    val vlc = remember { LibVLC(ctx, arrayListOf("--no-video-title-show", "--network-caching=500")) }
    val mediaPlayer = remember(vlc) { MediaPlayer(vlc) }
    val videoLayout = remember { VLCVideoLayout(ctx) }
    var index by remember(files) { mutableStateOf(initialIndex.coerceIn(0, files.lastIndex)) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var orientation by remember { mutableStateOf(0) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(diagnostics.exportText().toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open diagnostic destination")
            }.onSuccess { exportStatus = "Log saved" }
                .onFailure { exportStatus = "Unable to save log: " + it.javaClass.simpleName }
        }
    }

    fun savePosition() {
        val item = files.getOrNull(index) ?: return
        val key = connection.id + "|" + item.path
        if (pos < 5_000L || length > 0 && pos >= length - 5_000L) app.resume.clear(key)
        else app.resume.put(key, pos)
    }

    DisposableEffect(mediaPlayer) {
        mediaPlayer.attachViews(videoLayout, null, true, false)
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    playing = true
                    error = null
                    diagnostics.record("VLC playing fileExt=" + files[index].name.substringAfterLast('.', ""))
                }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EncounteredError -> {
                    playing = false
                    error = "VLC playback error. Export the diagnostic log."
                    diagnostics.record("VLC EncounteredError index=" + index)
                }
                MediaPlayer.Event.EndReached -> {
                    val ended = files.getOrNull(index)
                    if (ended != null) app.resume.clear(connection.id + "|" + ended.path)
                    if (index < files.lastIndex) index++ else playing = false
                }
                MediaPlayer.Event.Buffering -> diagnostics.record("VLC buffering")
            }
        }
        onDispose {
            mediaPlayer.setEventListener(null)
            runCatching { savePosition() }
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            vlc.release()
            bridge.close()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(index) {
        val item = files[index]
        diagnostics.record("VLC open index=" + index + " extension=" + item.name.substringAfterLast('.', "") + " softwareDecode=true")
        mediaPlayer.stop()
        error = null
        val media = Media(vlc, android.net.Uri.parse(bridge.url(index)))
        // Software decoding is deliberate for Hi10P AVC / Main10 HEVC / MPEG4 AVI.
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=500")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        val resume = app.resume.get(connection.id + "|" + item.path)
        if (resume != null && resume > 0L) {
            delay(600)
            mediaPlayer.time = resume
        }
    }

    LaunchedEffect(mediaPlayer) {
        var lastPos = -1L
        var stalledFor = 0
        while (true) {
            pos = mediaPlayer.time.coerceAtLeast(0)
            length = mediaPlayer.length.coerceAtLeast(0)
            if (playing && pos == lastPos) stalledFor++ else stalledFor = 0
            if (stalledFor == 20) diagnostics.record("VLC stall 10s at ms=" + pos)
            lastPos = pos
            delay(500)
        }
    }

    BackHandler { onBack() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { videoLayout }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0xCC0C1233)).padding(top = 32.dp, start = 8.dp, end = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", color = Color.White) }
                Text(files[index].name, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    orientation = (orientation + 1) % 3
                    activity.requestedOrientation = when (orientation) {
                        1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }) { Text(listOf("Auto", "Land", "Port")[orientation], color = Color.White) }
                TextButton(onClick = {
                    diagnostics.record("Manual export index=" + index + " posMs=" + pos)
                    saveLog.launch("hopplay-vlc-" + System.currentTimeMillis() + ".txt")
                }) { Text("Log", color = Color.White) }
            }
            error?.let { Text(it, color = Color.Red, modifier = Modifier.padding(8.dp)) }
            exportStatus?.let { Text(it, color = Color.White) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0C1233)).padding(8.dp)) {
            Slider(value = if (length > 0) (pos.toFloat() / length).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (length > 0) mediaPlayer.time = (it * length).toLong() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { savePosition(); if (index > 0) index-- }) { Text("⏮", color = Color.White) }
                TextButton(onClick = { mediaPlayer.time = (pos - 10_000).coerceAtLeast(0) }) { Text("−10", color = Color.White) }
                TextButton(onClick = { if (playing) mediaPlayer.pause() else mediaPlayer.play() }) {
                    Text(if (playing) "⏸" else "▶", color = Color.White)
                }
                TextButton(onClick = { mediaPlayer.time = pos + 10_000 }) { Text("+10", color = Color.White) }
                TextButton(onClick = { savePosition(); if (index < files.lastIndex) index++ }) { Text("⏭", color = Color.White) }
            }
        }
    }
}
