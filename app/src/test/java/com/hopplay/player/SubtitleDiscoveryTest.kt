package com.hopplay.player

import com.hopplay.fs.FsEntry
import org.junit.Assert.*
import org.junit.Test

class SubtitleDiscoveryTest {
    private fun file(name: String) = FsEntry(name, "/anime/$name", false, 320L)

    @Test fun matchesLanguageSuffixAndRejectsDifferentEpisode() {
        val video = file("Macross Zero - 01.mkv")
        val files = listOf(file("Macross Zero - 01.en.ass"), file("Macross Zero - 01.ms.srt"),
            file("Macross Zero - 02.en.ass"), file("Macross Zero - 010.en.ass"))
        val matches = discoverSubtitles(video, files)
        assertEquals(2, matches.size)
        assertEquals("en", bestSubtitle(matches, "en")?.language)
        assertEquals("ms", bestSubtitle(matches, "ms")?.language)
    }

    @Test fun allowsAnimeReleaseTagsButNotDifferentTitles() {
        val video = file("[Commie] Macross Zero - 01 [1080p].mkv")
        val matches = discoverSubtitles(video, listOf(
            file("[Commie] Macross Zero - 01 [English].ass"),
            file("[Other] Macross Zero - 02 [English].ass"),
            file("Vandread - 01.en.srt")))
        assertEquals(1, matches.size)
        assertEquals("en", matches.first().language)
    }

    @Test fun ambiguousEnglishMatchesAreOfferedButNotAutoSelected() {
        val video = file("episode01.mkv")
        val matches = discoverSubtitles(video, listOf(
            file("episode01.en.ass"), file("episode01.eng.srt")))
        assertEquals(2, matches.size)
        assertNull(bestSubtitle(matches, "en"))
    }

    @Test fun noForcedMatchForUnrelatedSubtitle() {
        val video = file("Macross 01.mkv")
        assertTrue(discoverSubtitles(video, listOf(
            file("Macross 02.srt"), file("another.en.srt"), file("Macross 01.txt"))).isEmpty())
    }
}
