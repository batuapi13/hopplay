package com.hopplay.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalCompareTest {
    @Test fun episodeNumbersSortNaturally() {
        assertTrue(naturalCompare("ep2.mkv", "ep10.mkv") < 0)
        assertTrue(naturalCompare("ep9.mkv", "ep10.mkv") < 0)
    }

    @Test fun comparisonIsCaseInsensitive() {
        assertEquals(0, naturalCompare("Movie.MKV", "movie.mkv"))
    }

    @Test fun directoriesComeBeforeFiles() {
        val entries = listOf(
            FsEntry("ep2.mkv", "/ep2.mkv", false, 1),
            FsEntry("Season 2", "/Season 2", true, 0),
            FsEntry("ep10.mkv", "/ep10.mkv", false, 1),
        )
        val sorted = entries.sortedWith(entryOrder)
        assertEquals(listOf("Season 2", "ep2.mkv", "ep10.mkv"), sorted.map { it.name })
    }

    @Test fun numericRunsOfDifferentLengths() {
        assertTrue(naturalCompare("part12", "part3") > 0)
    }
}
