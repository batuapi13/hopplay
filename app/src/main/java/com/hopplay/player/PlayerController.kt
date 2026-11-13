package com.hopplay.player

import android.app.Application
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.hopplay.data.ResumeStore

/**
 * One ExoPlayer instance per opened folder playlist. Stop and release the
 * previous instance before opening another folder so Qualcomm MediaCodec
 * resources and renderer state cannot leak between playback sessions.
 */
class PlayerController(private val app: Application, private val resume: ResumeStore) {
    var player: ExoPlayer = createPlayer()
        private set

    private var lastId: String? = null

    private fun createPlayer(): ExoPlayer {
        val fresh = ExoPlayer.Builder(
            app,
            DefaultRenderersFactory(app)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true),
            DefaultMediaSourceFactory(
                DataSource.Factory { RoutingDataSource(app) },
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
            ),
        )
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()

        fresh.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastId?.let(resume::clear)
                lastId = mediaItem?.mediaId
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    mediaItem?.mediaId?.let(resume::get)?.let { fresh.seekTo(it) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && fresh === player) savePosition()
            }
        })
        return fresh
    }

    fun play(items: List<MediaItem>, startIndex: Int) {
        if (items.isEmpty()) return
        // A new player is preferable to reusing a potentially failed hardware
        // decoder across unrelated MKV files/folders.
        player.stop()
        player.release()
        lastId = null
        player = createPlayer()

        val startPos = items.getOrNull(startIndex)?.mediaId?.let(resume::get) ?: C.TIME_UNSET
        lastId = items.getOrNull(startIndex)?.mediaId
        player.setMediaItems(items, startIndex, startPos)
        player.prepare()
        player.playWhenReady = true
    }

    fun savePosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        val dur = player.duration
        val pos = player.currentPosition
        if (pos < 5_000 || (dur != C.TIME_UNSET && pos > dur - 5_000)) resume.clear(id) else resume.put(id, pos)
    }

    fun stop() {
        savePosition()
        player.stop()
        player.clearMediaItems()
        lastId = null
    }

    fun release() = player.release()
}
