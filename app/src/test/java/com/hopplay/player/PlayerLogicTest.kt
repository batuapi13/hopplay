package com.hopplay.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {
    @Test fun formatsTime() {
        assertEquals("0:05", formatTime(5_000))
        assertEquals("1:02:03", formatTime(3_723_000))
        assertEquals("0:00", formatTime(-10))
    }

    @Test fun formatsSpeed() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("0.25x", formatSpeed(0.25f))
        assertEquals("1.5x", formatSpeed(1.5f))
    }

    @Test fun resumeClearedNearEdges() {
        assertTrue(shouldClearResume(1_000, 100_000))
        assertTrue(shouldClearResume(97_000, 100_000))
        assertFalse(shouldClearResume(50_000, 100_000))
    }

    @Test fun dragSeekIsClamped() {
        assertEquals(0L, dragSeekTarget(10_000, -1000f, 1000f, 600_000))
        assertEquals(60_000L, dragSeekTarget(0, 500f, 1000f, 600_000))
        assertEquals(100_000L, dragSeekTarget(90_000, 1000f, 1000f, 100_000))
    }

    @Test fun dragLevelClamped() {
        assertEquals(1f, dragLevel(0.9f, -500f, 1000f), 0.001f)
        assertEquals(0.02f, dragLevel(0.1f, 900f, 1000f, min = 0.02f), 0.001f)
    }

    @Test fun axisLocking() {
        assertEquals(DragAxis.NONE, resolveDragAxis(3f, 2f, true, 20f))
        assertEquals(DragAxis.SEEK, resolveDragAxis(40f, 5f, true, 20f))
        assertEquals(DragAxis.BRIGHTNESS, resolveDragAxis(5f, 40f, true, 20f))
        assertEquals(DragAxis.VOLUME, resolveDragAxis(5f, -40f, false, 20f))
    }

    @Test fun orientationCycles() {
        assertEquals(OrientationMode.LANDSCAPE, OrientationMode.AUTO.next())
        assertEquals(OrientationMode.PORTRAIT, OrientationMode.LANDSCAPE.next())
        assertEquals(OrientationMode.AUTO, OrientationMode.PORTRAIT.next())
    }

    @Test fun queueNavigation() {
        assertEquals(1, nextIndex(0, 3))
        assertNull(nextIndex(2, 3))
        assertEquals(2, previousIndex(3, 1_000))
        assertEquals(3, previousIndex(3, 10_000))
        assertEquals(0, previousIndex(0, 1_000))
    }

    @Test fun subtitleDelayClamped() {
        assertEquals(60_000L, clampSubtitleDelayMs(999_999))
        assertEquals(-60_000L, clampSubtitleDelayMs(-999_999))
    }

    @Test fun trackNameFallback() {
        assertEquals("Track 2", cleanTrackName(null, 2))
        assertEquals("Track 1", cleanTrackName("  ", 1))
        assertEquals("English [en]", cleanTrackName("English [en]", 1))
    }
}
