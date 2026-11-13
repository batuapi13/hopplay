package com.hopplay.player

import kotlin.math.abs

/** Pure helpers kept free of Android/libVLC types so they can be unit tested on the JVM. */

const val CONTROLS_AUTO_HIDE_MS = 4_000L
const val BUFFERING_WARNING_MS = 15_000L
const val RESUME_SAVE_INTERVAL_MS = 10_000L
const val RESUME_EDGE_MS = 5_000L

val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${"%.2f".format(speed).trimEnd('0')}x"

fun shouldClearResume(posMs: Long, lengthMs: Long): Boolean =
    posMs < RESUME_EDGE_MS || (lengthMs > 0 && posMs >= lengthMs - RESUME_EDGE_MS)

/** Horizontal drag: full screen width equals [SEEK_SPAN_MS] of media, clamped to the media bounds. */
const val SEEK_SPAN_MS = 120_000L

fun dragSeekTarget(startMs: Long, dragX: Float, widthPx: Float, lengthMs: Long): Long {
    if (widthPx <= 0f) return startMs
    val raw = startMs + (dragX / widthPx * SEEK_SPAN_MS).toLong()
    return if (lengthMs > 0) raw.coerceIn(0, lengthMs) else raw.coerceAtLeast(0)
}

/** Vertical drag: a full-height swipe changes the value by about 120%. */
fun dragLevel(start: Float, dragY: Float, heightPx: Float, min: Float = 0f): Float {
    if (heightPx <= 0f) return start
    return (start - dragY / heightPx * 1.2f).coerceIn(min, 1f)
}

enum class DragAxis { NONE, SEEK, BRIGHTNESS, VOLUME }

/** Locks a drag to one axis once it passes [thresholdPx]; vertical drags pick brightness/volume by start side. */
fun resolveDragAxis(accX: Float, accY: Float, startedOnLeft: Boolean, thresholdPx: Float): DragAxis {
    if (abs(accX) < thresholdPx && abs(accY) < thresholdPx) return DragAxis.NONE
    return when {
        abs(accX) > abs(accY) * 1.2f -> DragAxis.SEEK
        abs(accY) >= abs(accX) -> if (startedOnLeft) DragAxis.BRIGHTNESS else DragAxis.VOLUME
        else -> DragAxis.NONE
    }
}

enum class AspectMode(val label: String) {
    FIT("Fit"), FILL("Fill"), RATIO_16_9("16:9"), RATIO_4_3("4:3"), ORIGINAL("Original"), CROP("Crop")
}

enum class OrientationMode(val label: String) { AUTO("Auto"), LANDSCAPE("Landscape"), PORTRAIT("Portrait") }

fun OrientationMode.next(): OrientationMode = OrientationMode.values()[(ordinal + 1) % OrientationMode.values().size]

/** Next index to play after the current one ends, or null at the end of the queue. */
fun nextIndex(current: Int, size: Int): Int? = if (current + 1 < size) current + 1 else null

fun previousIndex(current: Int, positionMs: Long): Int =
    if (positionMs > 3_000L || current <= 0) current else current - 1

enum class PlayState { LOADING, BUFFERING, PLAYING, PAUSED, ENDED, FAILED }

/** Subtitle delay is stored in ms in the UI and converted to libVLC's microseconds at the call site. */
fun clampSubtitleDelayMs(ms: Long): Long = ms.coerceIn(-60_000L, 60_000L)

/** One displayed track option; [id] is libVLC's track id (-1 means disabled). */
data class TrackOption(val id: Int, val label: String, val selected: Boolean)

/** Turns libVLC's raw "Track 1 - [English]" style names into a readable label with a numbered fallback. */
fun cleanTrackName(raw: String?, ordinal: Int): String {
    val name = raw?.trim().orEmpty()
    if (name.isEmpty()) return "Track $ordinal"
    return name
}
