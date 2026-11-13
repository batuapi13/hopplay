package com.hopplay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import com.hopplay.data.Connection
import com.hopplay.data.Protocol
import com.hopplay.ui.BrowseScreen
import com.hopplay.ui.HomeScreen
import com.hopplay.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF39DADD),
                onPrimary = Color(0xFF082548),
                secondary = Color(0xFF6CF5AB),
                background = Color(0xFF0B1233),
                surface = Color(0xFF111B40),
                surfaceVariant = Color(0xFF1B2850),
                onBackground = Color(0xFFF4F8FF),
                onSurface = Color(0xFFF4F8FF),
            )) { Root(vm) }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.controller.savePosition()
        vm.controller.player.pause()
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = Uri.decode(uri.lastPathSegment ?: "Folder").substringAfterLast(':').ifBlank { "Folder" }
            vm.save(Connection(name = name, protocol = Protocol.LOCAL, host = uri.toString()))
        }
    }
    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm, onAddLocal = { picker.launch(null) })
        Screen.BROWSE -> {
            BackHandler { if (!vm.up()) vm.home() }
            BrowseScreen(vm)
        }
        Screen.PLAYER -> {
            BackHandler { vm.closePlayer() }
            PlayerScreen(vm.controller, onBack = vm::closePlayer)
        }
    }
}
