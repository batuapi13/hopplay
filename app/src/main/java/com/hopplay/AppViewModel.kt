package com.hopplay

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.hopplay.player.HopPlayAudioService
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
import com.hopplay.data.FolderFavourite
import com.hopplay.data.HistoryEntry
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
    val favouriteFolders = mutableStateListOf<FolderFavourite>()
    fun refreshFavourites() { favouriteFolders.clear(); favouriteFolders.addAll(hp.favourites.all()) }
    fun isCurrentFavourite(): Boolean = current?.let { hp.favourites.contains(it.id, stack.lastOrNull() ?: "") } ?: false
    fun toggleCurrentFavourite() {
        val conn = current ?: return
        if (conn.protocol != com.hopplay.data.Protocol.SFTP) return
        hp.favourites.toggle(conn.id, stack.lastOrNull() ?: return)
        refreshFavourites()
    }
    fun openFavourite(pin: FolderFavourite) {
        val conn = hp.store.get(pin.connectionId) ?: return
        current = conn
        fs = hp.registry.get(conn.id)
        stack.clear()
        screen = Screen.BROWSE
        navigate(pin.path, push = true)
    }
    val recentHistory = mutableStateListOf<HistoryEntry>()
    fun refreshHistory() { recentHistory.clear(); recentHistory.addAll(hp.history.recent()) }
    fun removeHistory(h: HistoryEntry) { hp.history.remove(h.connectionId, h.path); refreshHistory() }
    fun clearHistory() { hp.history.clear(); refreshHistory() }
    var screen by mutableStateOf(Screen.HOME)
    var current by mutableStateOf<Connection?>(null)
    val stack = mutableStateListOf<String>()
    var entries by mutableStateOf<List<FsEntry>>(emptyList())
    var vlcFiles by mutableStateOf<List<FsEntry>>(emptyList())
    var vlcSubtitleFiles by mutableStateOf<List<FsEntry>>(emptyList())
    var vlcStartIndex by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var fs: RemoteFs? = null
    private var backgroundController: MediaController? = null

    init { refreshConnections(); refreshHistory(); refreshFavourites() }

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
        hp.favourites.removeConnection(c.id)
        refreshFavourites()
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

    fun jumpTo(stackIndex: Int) {
        if (stackIndex !in 0 until stack.lastIndex) return
        while (stack.lastIndex > stackIndex) stack.removeAt(stack.lastIndex)
        navigate(stack.last(), push = false)
    }

    fun home() {
        screen = Screen.HOME
        entries = emptyList()
        refreshHistory()
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
            // Reuse the listing already fetched for browsing; no extra SFTP list call.
            vlcSubtitleFiles = entries.filter { !it.isDir && MediaTypes.subtitleMime(it.name) != null }
            vlcStartIndex = files.indexOf(entry).coerceAtLeast(0)
            screen = Screen.VLC
            return
        }
        val items = files.map { buildItem(fs, conn, it) }
        controller.play(items, files.indexOf(entry).coerceAtLeast(0))
        screen = Screen.PLAYER
    }

    /** Open a history item without losing the current folder's playlist semantics. */
    fun openHistory(item: HistoryEntry) {
        val conn = hp.store.get(item.connectionId)
        if (conn == null) { error = "Saved connection no longer exists"; return }
        current = conn
        val target = item.path
        val folder = target.substringBeforeLast('/', "").ifBlank { "/" }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val remote = hp.registry.get(conn.id)
                val list = withContext(Dispatchers.IO) { remote.list(folder).sortedWith(entryOrder) }
                val media = list.filter { !it.isDir && MediaTypes.isPlayable(it.name) }
                val start = media.indexOfFirst { it.path == target }
                if (start < 0) { error = "The file is no longer in that folder"; return@launch }
                fs = remote
                stack.clear()
                stack.add(folder)
                entries = list.filter { it.isDir || MediaTypes.isPlayable(it.name) || MediaTypes.subtitleMime(it.name) != null }
                if (conn.protocol == com.hopplay.data.Protocol.SFTP) {
                    vlcFiles = media
                    vlcSubtitleFiles = list.filter { !it.isDir && MediaTypes.subtitleMime(it.name) != null }
                    vlcStartIndex = start
                    screen = Screen.VLC
                } else {
                    controller.play(media.map { buildItem(remote, conn, it) }, start)
                    screen = Screen.PLAYER
                }
            } catch (t: Throwable) {
                error = "Cannot reopen media: " + t.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    /** Background playback is explicit and queues audio files only. */
    fun playBackgroundAudio(entry: FsEntry) {
        val remote = fs ?: return
        val conn = current ?: return
        val audio = entries.filter { !it.isDir && MediaTypes.isAudio(it.name) }
        val start = audio.indexOfFirst { it.path == entry.path }
        if (start < 0) return
        val items = audio.map { file ->
            MediaItem.Builder()
                .setUri(remote.playbackUri(file.path))
                .setMediaId(conn.id + "|" + file.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name).build())
                .build()
        }
        // This must be a started AND bound service: binding alone can disappear
        // when the browsing activity is reclaimed after the display locks.
        // The visible user's headphone tap is a foreground service-start context.
        try {
            hp.startService(Intent(hp, HopPlayAudioService::class.java))
        } catch (t: RuntimeException) {
            error = "Unable to start background audio service: " + t.javaClass.simpleName
            return
        }
        val token = SessionToken(hp, ComponentName(hp, HopPlayAudioService::class.java))
        val controllerFuture = MediaController.Builder(hp, token).buildAsync()
        controllerFuture.addListener({
            runCatching {
                val controller = controllerFuture.get()
                backgroundController?.release()
                backgroundController = controller
                controller.setMediaItems(items, start, C.TIME_UNSET)
                controller.prepare()
                controller.play()
            }.onFailure { error = "Background audio failed: " + it.javaClass.simpleName }
        }, ContextCompat.getMainExecutor(hp))
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
        refreshHistory()
        screen = Screen.BROWSE
        vlcFiles = emptyList()
        vlcSubtitleFiles = emptyList()
    }

    fun closePlayer() {
        controller.stop()
        screen = Screen.BROWSE
    }

    override fun onCleared() {
        backgroundController?.release()
        controller.release()
    }
}
