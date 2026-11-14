package com.hopplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import com.hopplay.fs.TofuHostKeyVerifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.hopplay.AppViewModel
import com.hopplay.data.Connection
import com.hopplay.data.Protocol

@Composable
fun HomeScreen(vm: AppViewModel, onAddLocal: () -> Unit) {
    var editing by remember { mutableStateOf<Connection?>(null) }
    var trustReset by remember { mutableStateOf<Connection?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Text("HopPlay", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("Play directly from your files. No media server required.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { editing = Connection(name = "", protocol = Protocol.SFTP, port = 22) }) { Text("+ SFTP") }
                OutlinedButton(onClick = onAddLocal) { Text("+ Local") }
                OutlinedButton(onClick = { editing = Connection(name = "", protocol = Protocol.SMB, port = 445) }) { Text("+ SMB") }
            }
            if (vm.recentHistory.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CONTINUE WATCHING", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showHistory = true }) { Text("History") }
                }
                vm.recentHistory.filter { !it.completed && it.positionMs > 0 }
                    .take(3).forEach { h ->
                        Column(Modifier.fillMaxWidth().clickable { vm.openHistory(h) }.padding(vertical = 6.dp)) {
                            Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Resume ${com.hopplay.player.formatTime(h.positionMs)}" +
                                if (h.durationMs > 0) " / ${com.hopplay.player.formatTime(h.durationMs)}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
            }
            if (vm.favouriteFolders.isNotEmpty()) {
                Text("FAVOURITE FOLDERS", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary)
                vm.favouriteFolders.take(5).forEach { pin ->
                    val conn = vm.connections.firstOrNull { it.id == pin.connectionId }
                    if (conn != null) {
                        TextButton(onClick = { vm.openFavourite(pin) }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                            Text("  " + conn.name + " · " + pin.path.substringAfterLast('/').ifBlank { "/" },
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("LIBRARY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 8.dp))
            if (vm.connections.isEmpty()) Text("Add an SFTP server to browse and stream your media.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(vm.connections, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { vm.openConnection(c) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (c.protocol == Protocol.SFTP) "🔒 " else if (c.protocol == Protocol.SMB) "🖧 " else "📁 ")
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (c.protocol == Protocol.LOCAL) "On this device"
                                        else "${c.protocol.name} · ${c.host}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (c.protocol == Protocol.SFTP) TextButton(onClick = { trustReset = c }) { Text("Host key") }
                                if (c.protocol != Protocol.LOCAL) TextButton(onClick = { editing = c }) { Text("Edit") }
                                TextButton(onClick = { vm.delete(c) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Playback history") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(vm.recentHistory, key = { it.connectionId + "|" + it.path }) { h ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable {
                                showHistory = false
                                vm.openHistory(h)
                            }) {
                                Text(h.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (h.completed) "Watched" else "Resume " +
                                    com.hopplay.player.formatTime(h.positionMs),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { vm.removeHistory(h) }) { Text("Remove") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { vm.clearHistory() }) { Text("Clear all") } },
        )
    }
    editing?.let { c ->
        ConnectionDialog(c, onDismiss = { editing = null }, onSave = { vm.save(it); editing = null })
    }
    trustReset?.let { c ->
        val saved = TofuHostKeyVerifier.fingerprint(ctx, c.host, c.port)
        AlertDialog(
            onDismissRequest = { trustReset = null },
            title = { Text("SFTP server identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.host + ":" + (if (c.port > 0) c.port else 22))
                    Text(if (saved == null) "No server key pinned yet." else "Stored SHA-256 host key fingerprint (Base64):\n" + saved)
                    Text("Verify the server identity separately before forgetting its key. Your next connection will pin the first key received.")
                }
            },
            confirmButton = {
                TextButton(enabled = saved != null, onClick = {
                    TofuHostKeyVerifier.forget(ctx, c.host, c.port)
                    vm.deleteTrustSession(c.id)
                    trustReset = null
                }) { Text("Forget host key") }
            },
            dismissButton = { TextButton(onClick = { trustReset = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectionDialog(initial: Connection, onDismiss: () -> Unit, onSave: (Connection) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c.protocol == Protocol.SFTP) "SFTP connection" else "SMB / Windows share") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Name", c.name) { c = c.copy(name = it) }
                Field("Host", c.host) { c = c.copy(host = it.trim()) }
                Field("Port", c.port.toString(), number = true) { c = c.copy(port = it.toIntOrNull() ?: 0) }
                if (c.protocol == Protocol.SMB) {
                    Field("Share name", c.share) { c = c.copy(share = it.trim()) }
                    Field("Domain (optional)", c.domain) { c = c.copy(domain = it.trim()) }
                }
                Field("User (blank = guest on SMB)", c.user) { c = c.copy(user = it) }
                Field("Password", c.password, secret = true) { c = c.copy(password = it) }
                Field("Start path", c.path) { c = c.copy(path = it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = c.host.isNotBlank() && (c.protocol != Protocol.SMB || c.share.isNotBlank()),
                onClick = { onSave(c.copy(name = c.name.ifBlank { c.host })) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun BrowseScreen(vm: AppViewModel) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!vm.up()) vm.home() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder")
                }
                if (vm.current?.protocol == Protocol.SFTP) {
                    IconButton(onClick = vm::toggleCurrentFavourite) {
                        Icon(if (vm.isCurrentFavourite()) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (vm.isCurrentFavourite()) "Unpin folder" else "Pin folder",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(vm.current?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Breadcrumbs(vm.stack.toList(), onJump = vm::jumpTo)
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    vm.error != null -> Text("Error: ${vm.error}", Modifier.padding(16.dp))
                    vm.entries.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nothing playable here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn {
                        items(vm.entries, key = { it.path }) { e ->
                            val playable = !e.isDir && com.hopplay.fs.MediaTypes.isPlayable(e.name)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clickable(enabled = e.isDir || playable) { if (e.isDir) vm.openDir(e) else vm.play(e) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (e.isDir) Icons.Filled.Folder else if (playable) Icons.Filled.Movie else Icons.Filled.Subtitles,
                                    contentDescription = null,
                                    tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(e.name, Modifier.weight(1f).padding(start = 16.dp),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!e.isDir && com.hopplay.fs.MediaTypes.isAudio(e.name)) {
                                    IconButton(onClick = { vm.playBackgroundAudio(e) }) {
                                        Icon(Icons.Filled.Headphones, contentDescription = "Play audio in background")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(stack: List<String>, onJump: (Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(stack.size) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        stack.forEachIndexed { i, path ->
            val label = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
            if (i > 0) Text(" › ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                label, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = if (i == stack.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = i < stack.lastIndex) { onJump(i) }.padding(vertical = 6.dp),
            )
        }
    }
}
