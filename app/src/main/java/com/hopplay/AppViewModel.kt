package com.hopplay

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.hopplay.data.Connection
import com.hopplay.fs.FsEntry
import com.hopplay.fs.MediaTypes
import com.hopplay.fs.RemoteFs
import com.hopplay.fs.entryOrder
import com.hopplay.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, BROWSE, PLAYER, VLC }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val hp = app as HopPlayApp
    val controller = PlayerController(app, hp.resume)

    val connections = mutableStateListOf<Connection>()
    var screen by mutableStateOf(Screen.HOME)
    var current by mutableStateOf<Connection?>(null)
    val stack = mutableStateListOf<String>()
    var entries by mutableStateOf<List<FsEntry>>(emptyList())
    var vlcFiles by mutableStateOf<List<FsEntry>>(emptyList())
    var vlcStartIndex by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var fs: RemoteFs? = null

    init { refreshConnections() }

    fun deleteTrustSession(id: String) { hp.registry.invalidate(id) }

    fun refreshConnections() {
        connections.clear()
        connections.addAll(hp.store.all())
    }

    fun save(c: Connection) {
        hp.store.save(c)
        hp.registry.invalidate(c.id)
        refreshConnections()
    }

    fun delete(c: Connection) {
        hp.store.delete(c.id)
        hp.registry.invalidate(c.id)
        refreshConnections()
    }

    fun openConnection(c: Connection) {
        current = c
        fs = hp.registry.get(c.id)
        stack.clear()
        screen = Screen.BROWSE
        navigate(fs!!.rootPath, push = true)
    }

    fun openDir(e: FsEntry) = navigate(e.path, push = true)

    /** Returns false when already at the root. */
    fun up(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        navigate(stack.last(), push = false)
        return true
    }

    fun home() {
        screen = Screen.HOME
        entries = emptyList()
    }

    private fun navigate(path: String, push: Boolean) {
        if (push) stack.add(path)
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { fs!!.list(path).sortedWith(entryOrder) }
                entries = list.filter { it.isDir || MediaTypes.isPlayable(it.name) || MediaTypes.subtitleMime(it.name) != null }
            } catch (t: Throwable) {
                entries = emptyList()
                error = t.message ?: t.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    fun play(entry: FsEntry) {
        val fs = fs ?: return
        val conn = current ?: return
        val files = entries.filter { !it.isDir && MediaTypes.isPlayable(it.name) }
        if (files.isEmpty()) return
        if (conn.protocol == com.hopplay.data.Protocol.SFTP) {
            vlcFiles = files
            vlcStartIndex = files.indexOf(entry).coerceAtLeast(0)
            screen = Screen.VLC
            return
        }
        val items = files.map { buildItem(fs, conn, it) }
        controller.play(items, files.indexOf(entry).coerceAtLeast(0))
        screen = Screen.PLAYER
    }

    private fun buildItem(fs: RemoteFs, conn: Connection, e: FsEntry): MediaItem {
        val base = e.name.substringBeforeLast('.')
        val subs = entries.filter { s ->
            !s.isDir && MediaTypes.subtitleMime(s.name) != null && s.name.startsWith("$base.")
        }.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(fs.playbackUri(s.path))
                .setMimeType(MediaTypes.subtitleMime(s.name))
                .setLabel(s.name)
                .setLanguage(s.name.removeSuffix("." + MediaTypes.ext(s.name)).substringAfterLast('.', "").takeIf { it.length in 2..3 })
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(fs.playbackUri(e.path))
            .setMediaId("${conn.id}|${e.path}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).build())
            .setSubtitleConfigurations(subs)
            .build()
    }

    fun closeVlc() {
        screen = Screen.BROWSE
        vlcFiles = emptyList()
    }

    fun closePlayer() {
        controller.stop()
        screen = Screen.BROWSE
    }

    override fun onCleared() {
        controller.release()
    }
}
