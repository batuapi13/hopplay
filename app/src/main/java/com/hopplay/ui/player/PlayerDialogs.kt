package com.hopplay.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hopplay.player.AspectMode
import com.hopplay.player.PlayState
import com.hopplay.player.SPEED_OPTIONS
import com.hopplay.player.SubtitleColor
import com.hopplay.player.SubtitleSize
import com.hopplay.player.TrackOption
import com.hopplay.player.VlcSession
import com.hopplay.player.formatSpeed

@Composable
fun TrackDialog(
    title: String,
    options: List<TrackOption>,
    emptyText: String,
    offSelected: Boolean? = null,
    onOff: (() -> Unit)? = null,
    extraAction: (@Composable () -> Unit)? = null,
    onSelect: (TrackOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (onOff != null) item { TrackRow("Off", offSelected == true, onOff) }
                if (options.isEmpty() && onOff == null) item { Text(emptyText) }
                items(options) { o -> TrackRow(o.label, o.selected) { onSelect(o) } }
                if (extraAction != null) item { Box(Modifier.padding(top = 8.dp)) { extraAction() } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    session: VlcSession,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("Speed")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPEED_OPTIONS.forEach { s ->
                        FilterChip(selected = session.speed == s, onClick = { session.changeSpeed(s) }, label = { Text(formatSpeed(s)) })
                    }
                }
                SectionLabel("Seeking")
                Text("Fast (keyframes) avoids expensive decoding after a jump on older phones. Precise is more accurate but may briefly show damaged frames.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = session.fastSeek, onClick = { session.setFastSeek(true) },
                        label = { Text("Fast (keyframes)") })
                    FilterChip(selected = !session.fastSeek, onClick = { session.setFastSeek(false) },
                        label = { Text("Precise") })
                }
                SectionLabel("Aspect ratio")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AspectMode.values().forEach { m ->
                        FilterChip(selected = session.aspect == m, onClick = { session.changeAspect(m) }, label = { Text(m.label) })
                    }
                }
                SectionLabel("Subtitle size (restarts at current position)")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubtitleSize.values().forEach { m ->
                        FilterChip(selected = session.subtitleSize == m, onClick = { session.setSubtitleStyle(size = m) }, label = { Text(m.label) })
                    }
                }
                SectionLabel("Subtitle colour")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubtitleColor.values().forEach { m ->
                        FilterChip(selected = session.subtitleColor == m, onClick = { session.setSubtitleStyle(color = m) }, label = { Text(m.label) })
                    }
                }
                SectionLabel("Subtitle delay: ${session.subtitleDelayMs} ms")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { session.setSubtitleDelay(session.subtitleDelayMs - 500) }) { Text("-0.5s") }
                    OutlinedButton(onClick = { session.setSubtitleDelay(session.subtitleDelayMs + 500) }) { Text("+0.5s") }
                    TextButton(onClick = { session.setSubtitleDelay(0) }) { Text("Reset") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Loading / buffering / ended / failure indicator shown over the video. */
@Composable
fun PlayerStatusIndicator(
    state: PlayState,
    bufferingPercent: Float,
    longBuffering: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val show = state == PlayState.LOADING || state == PlayState.BUFFERING || state == PlayState.FAILED || state == PlayState.ENDED
    if (!show) return
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            PlayState.LOADING -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text("Opening…", color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            PlayState.BUFFERING -> {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text("Buffering ${bufferingPercent.toInt()}%", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                if (longBuffering) {
                    Text(
                        "Still buffering. The connection may be slow or interrupted.",
                        color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedButton(onClick = onExport) { Text("Export diagnostics") }
                }
            }
            PlayState.FAILED -> {
                Text(errorMessage ?: "Playback failed", color = Color.White, textAlign = TextAlign.Center)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("Retry") }
                    OutlinedButton(onClick = onExport) { Text("Export log") }
                }
            }
            PlayState.ENDED -> Text("Playback finished", color = Color.White)
            else -> {}
        }
    }
}

@Composable
fun FeedbackChip(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text, color = Color.White, style = MaterialTheme.typography.titleMedium,
        modifier = modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
