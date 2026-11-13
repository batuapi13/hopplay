package com.hopplay.player

import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory, bounded playback diagnostics. Never record SFTP credentials,
 * connection IDs, server URLs or full media paths.
 *
 * Export is user-initiated via Android's system document picker.
 */
class PlaybackDiagnostics {
    private val events = ArrayDeque<String>()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    @Synchronized
    fun record(event: String) {
        if (events.size >= 120) events.removeFirst()
        events.addLast("${timestamp.format(Date())}  ${safe(event)}")
    }

    fun trackSummary(tracks: Tracks): String {
        val lines = mutableListOf<String>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                lines.add("type=${group.type}, selected=${group.isTrackSelected(i)}, supported=${group.isTrackSupported(i)}, ${formatSummary(format)}")
            }
        }
        return if (lines.isEmpty()) "No tracks reported" else lines.joinToString(" | ").take(3000)
    }

    fun formatSummary(f: Format): String =
        "mime=${f.sampleMimeType ?: "unknown"}, codecs=${f.codecs ?: "unknown"}, " +
            "video=${f.width}x${f.height}, fps=${f.frameRate}, " +
            "audio=${f.sampleRate}Hz/${f.channelCount}ch, bitrate=${f.bitrate}"

    fun failure(error: PlaybackException) {
        record("PLAYBACK FAILURE code=${error.errorCode} name=${error.errorCodeName}")
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 8) {
            record("cause[$depth] ${cause.javaClass.name}: ${cause.message.orEmpty()}")
            // Include class/method/line only, not file paths or stack arguments.
            cause.stackTrace.take(15).forEach {
                record("  at ${it.className}.${it.methodName}:${it.lineNumber}")
            }
            cause = cause.cause
            depth++
        }
    }

    @Synchronized
    fun exportText(): String = buildString {
        appendLine("HopPlay playback diagnostic")
        appendLine("Generated: ${timestamp.format(Date())}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
        appendLine("No passwords, hostnames, connection identifiers or complete media paths are collected.")
        appendLine()
        events.forEach { appendLine(it) }
    }

    private fun safe(text: String): String {
        // Message text from remote filesystem errors may contain private host/path details.
        return text
            .replace(Regex("""Format\(\d+,\s*[^,]*,"""), "Format([redacted-title],")
            .replace(Regex("(?i)(?:sftp|ssh|hopplay|smb|https?)://[^\\s,;)]+"), "[redacted-uri]")
            .replace(Regex("(?<![A-Za-z0-9])/(?:[^\\s/:]+/)+[^\\s,;)]*"), "[redacted-path]")
            .take(1400)
    }
}
