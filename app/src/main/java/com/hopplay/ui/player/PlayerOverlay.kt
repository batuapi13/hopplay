package com.hopplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hopplay.player.PlayState
import com.hopplay.player.OrientationMode
import com.hopplay.player.formatTime

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean = true, size: Int = 48, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size.dp)) {
        Icon(icon, contentDescription = description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f))
    }
}

@Composable
fun PlayerTopBar(
    title: String,
    onBack: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    orientation: OrientationMode,
    onToggleOrientation: () -> Unit,
    onSettings: () -> Unit,
    onExportLog: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
        Text(
            title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        BarIcon(Icons.Filled.Audiotrack, "Audio track", onClick = onAudio)
        BarIcon(Icons.Filled.Subtitles, "Subtitles", onClick = onSubtitles)
        TextButton(onClick = onToggleOrientation, modifier = Modifier.size(width = 54.dp, height = 48.dp)) {
            Text(when (orientation) {
                OrientationMode.AUTO -> "Auto"
                OrientationMode.LANDSCAPE -> "Land"
                OrientationMode.PORTRAIT -> "Port"
            }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        BarIcon(Icons.Filled.Settings, "Playback settings", onClick = onSettings)
        Box {
            BarIcon(Icons.Filled.MoreVert, "More options") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Lock screen") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    onClick = { menu = false; onLock() })
                DropdownMenuItem(text = { Text("Export diagnostics log") }, onClick = { menu = false; onExportLog() })
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    state: PlayState,
    positionMs: Long,
    lengthMs: Long,
    seekable: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The slider shows the drag value locally and only seeks on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (lengthMs > 0) (positionMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(((dragging ?: fraction) * lengthMs).toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = dragging ?: fraction,
                onValueChange = { dragging = it; onInteraction() },
                onValueChangeFinished = {
                    dragging?.let { onSeekTo((it * lengthMs).toLong()) }
                    dragging = null
                },
                enabled = seekable && lengthMs > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Seek position" },
            )
            Text(formatTime(lengthMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.Filled.SkipPrevious, "Previous file", enabled = true) { onPrevious() }
            BarIcon(Icons.Filled.Replay10, "Back 10 seconds", enabled = seekable) { onSeekBy(-10_000) }
            val (icon, desc) = when (state) {
                PlayState.PLAYING, PlayState.BUFFERING -> Icons.Filled.Pause to "Pause"
                PlayState.ENDED, PlayState.FAILED -> Icons.Filled.Replay to "Replay"
                else -> Icons.Filled.PlayArrow to "Play"
            }
            BarIcon(icon, desc, size = 64) { onTogglePlay() }
            BarIcon(Icons.Filled.Forward10, "Forward 10 seconds", enabled = seekable) { onSeekBy(10_000) }
            BarIcon(Icons.Filled.SkipNext, "Next file", enabled = hasNext) { onNext() }
        }
    }
}
