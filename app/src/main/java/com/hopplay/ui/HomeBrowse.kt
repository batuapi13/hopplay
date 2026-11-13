package com.hopplay.ui

import androidx.compose.foundation.clickable
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
import com.hopplay.AppViewModel
import com.hopplay.data.Connection
import com.hopplay.data.Protocol

@Composable
fun HomeScreen(vm: AppViewModel, onAddLocal: () -> Unit) {
    var editing by remember { mutableStateOf<Connection?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Text("HopPlay", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddLocal) { Text("+ Local folder") }
                OutlinedButton(onClick = { editing = Connection(name = "", protocol = Protocol.SFTP, port = 22) }) { Text("+ SFTP") }
                OutlinedButton(onClick = { editing = Connection(name = "", protocol = Protocol.SMB, port = 445) }) { Text("+ SMB") }
            }
            if (vm.connections.isEmpty()) Text("Add a local folder or a network share to start.")
            LazyColumn {
                items(vm.connections, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openConnection(c) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                            Text(
                                if (c.protocol == Protocol.LOCAL) "Local" else "${c.protocol.name.lowercase()}://${c.host}",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (c.protocol != Protocol.LOCAL) TextButton(onClick = { editing = c }) { Text("Edit") }
                        TextButton(onClick = { vm.delete(c) }) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    editing?.let { c ->
        ConnectionDialog(c, onDismiss = { editing = null }, onSave = { vm.save(it); editing = null })
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
                TextButton(onClick = { if (!vm.up()) vm.home() }) { Text("←") }
                Text(
                    vm.current?.name.orEmpty() + vm.stack.lastOrNull()?.let { if (vm.current?.protocol == Protocol.LOCAL) "" else "  $it" }.orEmpty(),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    vm.loading -> Text("Loading…", Modifier.padding(16.dp))
                    vm.error != null -> Text("Error: ${vm.error}", Modifier.padding(16.dp))
                    vm.entries.isEmpty() -> Text("Nothing playable here.", Modifier.padding(16.dp))
                    else -> LazyColumn {
                        items(vm.entries, key = { it.path }) { e ->
                            val playable = !e.isDir && com.hopplay.fs.MediaTypes.isPlayable(e.name)
                            Text(
                                (if (e.isDir) "📁 " else if (playable) "🎞 " else "💬 ") + e.name,
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = e.isDir || playable) { if (e.isDir) vm.openDir(e) else vm.play(e) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
