package com.hopplay.player

import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Dedicated audio-only foreground session. Video continues using the existing
 * libVLC player; this service is only started when user explicitly selects
 * background audio from the file browser.
 */
class HopPlayAudioService : MediaSessionService() {
    private var audioSession: MediaSession? = null
    private var audioPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val diagnostics = PlaybackDiagnostics()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DataSource.Factory { RoutingDataSource(this, diagnostics) })
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        audioPlayer = player
        audioSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = audioSession

    override fun onDestroy() {
        audioSession?.release()
        audioSession = null
        audioPlayer?.release()
        audioPlayer = null
        super.onDestroy()
    }
}
