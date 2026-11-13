package com.hopplay.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hopplay.fs.FsEntry
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

enum class SubtitleSize(val label: String, val relFontSize: Int) {
    SMALL("Small", 20), NORMAL("Normal", 16), LARGE("Large", 12), HUGE("Huge", 9)
}

enum class SubtitleColor(val label: String, val rgb: Int) {
    WHITE("White", 0xFFFFFF), YELLOW("Yellow", 0xFFFF00), CYAN("Cyan", 0x39DADD)
}

/**
 * Owns the single LibVLC instance + MediaPlayer for one player screen and exposes
 * Compose-observable state. All remote I/O still goes through the SFTP loopback bridge.
 */
class VlcSession(
    private val ctx: Context,
    private val files: List<FsEntry>,
    private val urlFor: (Int) -> String,
    private val diagnostics: PlaybackDiagnostics,
    private val resumeGet: (Int) -> Long?,
    private val resumePut: (Int, Long) -> Unit,
    private val resumeClear: (Int) -> Unit,
    startIndex: Int,
) {
    private val vlc = LibVLC(ctx, arrayListOf("--no-video-title-show", "--network-caching=500"))
    val player = MediaPlayer(vlc)

    var index by mutableIntStateOf(startIndex.coerceIn(0, files.lastIndex)); private set
    var state by mutableStateOf(PlayState.LOADING); private set
    var positionMs by mutableLongStateOf(0L); private set
    var lengthMs by mutableLongStateOf(0L); private set
    var seekable by mutableStateOf(false); private set
    var bufferingPercent by mutableFloatStateOf(100f); private set
    var bufferingSinceMs by mutableLongStateOf(0L); private set
    var errorMessage by mutableStateOf<String?>(null); private set

    var audioTracks by mutableStateOf<List<TrackOption>>(emptyList()); private set
    var subtitleTracks by mutableStateOf<List<TrackOption>>(emptyList()); private set
    var subtitlesOff by mutableStateOf(true); private set

    var speed by mutableFloatStateOf(1f); private set
    var aspect by mutableStateOf(AspectMode.FIT); private set
    var subtitleDelayMs by mutableLongStateOf(0L); private set
    var subtitleSize by mutableStateOf(SubtitleSize.NORMAL); private set
    var subtitleColor by mutableStateOf(SubtitleColor.WHITE); private set

    // Session-only track preferences, reapplied to the following files when a matching label exists.
    private var preferredAudio: String? = null
    private var preferredSubtitle: String? = null
    private var preferenceApplied = false
    private var pendingResumeMs: Long? = null
    private var released = false
    private var lastResumeWriteMs = -1L

    val currentFile: FsEntry get() = files[index]
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < files.lastIndex

    init {
        player.setEventListener { e -> onEvent(e) }
    }

    fun attach(layout: VLCVideoLayout) {
        player.attachViews(layout, null, true, false)
        applyAspect()
    }

    fun load(newIndex: Int, startAtMs: Long? = null) {
        if (released) return
        savePosition()
        index = newIndex.coerceIn(0, files.lastIndex)
        state = PlayState.LOADING
        errorMessage = null
        positionMs = 0L
        lengthMs = 0L
        preferenceApplied = false
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        pendingResumeMs = startAtMs ?: resumeGet(index)
        diagnostics.record("VLC open index=$index ext=${currentFile.name.substringAfterLast('.', "")} softwareDecode=true")
        player.stop()
        val media = Media(vlc, Uri.parse(urlFor(index)))
        // Software decoding is deliberate for Hi10P AVC / Main10 HEVC / MPEG4 AVI.
        media.setHWDecoderEnabled(false, false)
        media.addOption(":network-caching=500")
        media.addOption(":freetype-rel-fontsize=${subtitleSize.relFontSize}")
        media.addOption(":freetype-color=${subtitleColor.rgb}")
        player.media = media
        media.release()
        player.play()
    }

    fun retry() = load(index, positionMs.takeIf { it > 0 })

    private fun onEvent(e: MediaPlayer.Event) {
        if (released) return
        when (e.type) {
            MediaPlayer.Event.Opening -> diagnostics.record("VLC opening")
            MediaPlayer.Event.Playing -> {
                state = PlayState.PLAYING
                errorMessage = null
                bufferingSinceMs = 0L
                diagnostics.record("VLC playing")
                applySessionSettings()
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> state = PlayState.PAUSED
            MediaPlayer.Event.Stopped -> if (state == PlayState.PLAYING) state = PlayState.PAUSED
            MediaPlayer.Event.Buffering -> {
                bufferingPercent = e.buffering
                if (e.buffering < 100f) {
                    if (state != PlayState.BUFFERING && state != PlayState.FAILED) {
                        if (state == PlayState.PLAYING || state == PlayState.LOADING) {
                            bufferingSinceMs = System.currentTimeMillis()
                            diagnostics.record("VLC buffering start posMs=$positionMs")
                        }
                        if (state != PlayState.LOADING) state = PlayState.BUFFERING
                    }
                } else if (bufferingSinceMs != 0L) {
                    diagnostics.record("VLC buffering done durationMs=${System.currentTimeMillis() - bufferingSinceMs}")
                    bufferingSinceMs = 0L
                    if (state == PlayState.BUFFERING) state = PlayState.PLAYING
                }
            }
            MediaPlayer.Event.TimeChanged -> positionMs = e.timeChanged
            MediaPlayer.Event.LengthChanged -> lengthMs = e.lengthChanged
            MediaPlayer.Event.SeekableChanged -> seekable = e.seekable
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.EncounteredError -> {
                state = PlayState.FAILED
                bufferingSinceMs = 0L
                errorMessage = "Playback failed. Check the connection, then retry or export diagnostics."
                diagnostics.record("VLC EncounteredError index=$index posMs=$positionMs")
            }
            MediaPlayer.Event.EndReached -> {
                resumeClear(index)
                val next = nextIndex(index, files.size)
                diagnostics.record("VLC end reached index=$index next=${next ?: "none"}")
                if (next != null) load(next) else state = PlayState.ENDED
            }
        }
    }

    private fun applySessionSettings() {
        pendingResumeMs?.let {
            pendingResumeMs = null
            if (it > 0) player.time = it
        }
        if (speed != 1f) player.rate = speed
        if (subtitleDelayMs != 0L) player.spuDelay = subtitleDelayMs * 1000
        applyAspect()
    }

    fun refreshTracks() {
        val audio = player.audioTracks.orEmpty().filter { it.id != -1 }
        val currentAudio = player.audioTrack
        audioTracks = audio.mapIndexed { i, t -> TrackOption(t.id, cleanTrackName(t.name, i + 1), t.id == currentAudio) }
        val spu = player.spuTracks.orEmpty().filter { it.id != -1 }
        val currentSpu = player.spuTrack
        subtitleTracks = spu.mapIndexed { i, t -> TrackOption(t.id, cleanTrackName(t.name, i + 1), t.id == currentSpu) }
        subtitlesOff = currentSpu == -1 || subtitleTracks.none { it.selected }
        if (!preferenceApplied && (audio.isNotEmpty() || spu.isNotEmpty())) {
            preferenceApplied = true
            preferredAudio?.let { pref -> audioTracks.firstOrNull { it.label == pref && !it.selected }?.let { player.audioTrack = it.id } }
            preferredSubtitle?.let { pref ->
                if (pref.isEmpty()) player.spuTrack = -1
                else subtitleTracks.firstOrNull { it.label == pref && !it.selected }?.let { player.spuTrack = it.id }
            }
        }
        diagnostics.record("VLC tracks audio=${audioTracks.size} subtitles=${subtitleTracks.size} seekable=$seekable")
    }

    fun selectAudio(option: TrackOption) {
        player.audioTrack = option.id
        preferredAudio = option.label
        diagnostics.record("Audio track switched")
        refreshTracks()
    }

    fun selectSubtitle(option: TrackOption?) {
        player.spuTrack = option?.id ?: -1
        preferredSubtitle = option?.label ?: ""
        diagnostics.record(if (option == null) "Subtitles off" else "Subtitle track switched")
        refreshTracks()
    }

    /** Copies the picked document into app cache so libVLC can open it as a plain file. */
    fun addExternalSubtitle(uri: Uri): Boolean {
        return runCatching {
            val dir = File(ctx.cacheDir, "ext-subs").apply { mkdirs() }
            val target = File(dir, "sub-${System.nanoTime()}.${extensionOf(ctx, uri)}")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot open subtitle")
            val ok = player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(target), true)
            diagnostics.record("External subtitle added ok=$ok ext=${target.extension}")
            preferredSubtitle = null
            refreshTracks()
            ok
        }.getOrElse {
            diagnostics.record("External subtitle failed: ${it.javaClass.simpleName}")
            false
        }
    }

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = clampSubtitleDelayMs(ms)
        player.spuDelay = subtitleDelayMs * 1000
    }

    /** libVLC reads freetype options when media opens, so a style change reloads at the current position. */
    fun setSubtitleStyle(size: SubtitleSize = subtitleSize, color: SubtitleColor = subtitleColor) {
        if (size == subtitleSize && color == subtitleColor) return
        subtitleSize = size
        subtitleColor = color
        load(index, positionMs.takeIf { it > 0 })
    }

    fun changeSpeed(value: Float) {
        speed = value
        player.rate = value
    }

    /** Temporary boost used by long-press; does not change the user's chosen [speed]. */
    fun boost(on: Boolean) {
        player.rate = if (on) 2f else speed
    }

    fun changeAspect(mode: AspectMode) {
        aspect = mode
        applyAspect()
    }

    fun applyAspect() {
        val type = when (aspect) {
            AspectMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectMode.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            AspectMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            AspectMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
            AspectMode.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
            AspectMode.CROP -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        }
        runCatching { player.videoScale = type }
    }

    fun togglePlay() {
        when (state) {
            PlayState.ENDED -> load(index, 0)
            PlayState.FAILED -> retry()
            PlayState.PLAYING, PlayState.BUFFERING -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(ms: Long) {
        if (!seekable && lengthMs <= 0) return
        val target = if (lengthMs > 0) ms.coerceIn(0, lengthMs) else ms.coerceAtLeast(0)
        player.time = target
        positionMs = target
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun previous() {
        val target = previousIndex(index, positionMs)
        if (target == index) load(index, 0L) else load(target)
    }

    fun next() {
        nextIndex(index, files.size)?.let { load(it) }
    }

    /** Called periodically and on exit/file change; writes only when the position moved meaningfully. */
    fun savePosition() {
        if (released || state == PlayState.LOADING) return
        if (shouldClearResume(positionMs, lengthMs)) {
            if (lastResumeWriteMs != -2L) resumeClear(index)
            lastResumeWriteMs = -2L
        } else if (lastResumeWriteMs < 0 || kotlin.math.abs(positionMs - lastResumeWriteMs) >= 2_000L) {
            resumePut(index, positionMs)
            lastResumeWriteMs = positionMs
        }
    }

    fun release() {
        if (released) return
        savePosition()
        released = true
        player.setEventListener(null)
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        runCatching { vlc.release() }
        File(ctx.cacheDir, "ext-subs").deleteRecursively()
        diagnostics.record("VLC session released")
    }

    private fun extensionOf(ctx: Context, uri: Uri): String {
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in setOf("srt", "ass", "ssa", "vtt", "sub", "smi") } ?: "srt"
    }
}
