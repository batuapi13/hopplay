package com.hopplay.player

import com.hopplay.fs.FsEntry

/** Pure filename-only matching; never opens a second remote directory listing. */
data class SubtitleCandidate(val entry: FsEntry, val language: String?, val score: Int)

private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt")
private val languageCodes = mapOf(
    "en" to "en", "eng" to "en", "english" to "en",
    "ms" to "ms", "may" to "ms", "msa" to "ms", "malay" to "ms",
    "ja" to "ja", "jp" to "ja", "jpn" to "ja", "japanese" to "ja",
)
private val suffixTokens = setOf("forced", "sdh", "hi", "cc", "default")
private val bracketText = Regex("""\[[^]]*]|\([^)]*\)""")
private val whitespace = Regex("""[\s._-]+""")

private fun stem(name: String): String = name.substringBeforeLast('.', name)
private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

private fun languageIn(name: String): String? {
    val tokens = stem(name).lowercase()
        .replace(Regex("""[\[\]()_.-]"""), " ")
        .split(Regex("""\s+"""))
    return tokens.asReversed().firstNotNullOfOrNull { languageCodes[it] }
}

private fun stripped(name: String): String {
    var value = stem(name).lowercase()
    value = bracketText.replace(value, " ")
    val tokens = whitespace.split(value).filter { it.isNotBlank() }
    return tokens.dropLastWhile { it in suffixTokens || it in languageCodes.keys }
        .joinToString(" ")
        .trim()
}

private fun exactPrefix(videoStem: String, subtitleStem: String): Boolean {
    if (subtitleStem.equals(videoStem, ignoreCase = true)) return true
    if (!subtitleStem.startsWith(videoStem, ignoreCase = true)) return false
    val suffix = subtitleStem.substring(videoStem.length)
    // Delimiter matters: episode 01 MUST NOT match episode 010.
    return suffix.firstOrNull() in setOf('.', ' ', '-', '_', '[', '(')
}

/** Strong exact-stem matches first, then conservative anime release-tag variants. */
fun discoverSubtitles(video: FsEntry, directory: List<FsEntry>): List<SubtitleCandidate> {
    val base = stem(video.name)
    val normalizedVideo = stripped(video.name)
    if (normalizedVideo.isEmpty()) return emptyList()
    return directory.asSequence()
        .filter { !it.isDir && it.path != video.path && extension(it.name) in subtitleExtensions }
        .mapNotNull { candidate ->
            val subBase = stem(candidate.name)
            val normalizedSub = stripped(candidate.name)
            val score = when {
                exactPrefix(base, subBase) -> 100
                normalizedSub == normalizedVideo -> 70
                else -> 0
            }
            if (score == 0) null else SubtitleCandidate(candidate, languageIn(candidate.name), score)
        }
        .sortedWith(compareByDescending<SubtitleCandidate> { it.score }.thenBy { it.entry.name.lowercase() })
        .toList()
}

/** Select only an unambiguous preferred-language external candidate. */
fun bestSubtitle(candidates: List<SubtitleCandidate>, preferred: String): SubtitleCandidate? {
    val matches = candidates.filter { it.language == preferred }
    val top = matches.maxOfOrNull { it.score } ?: return null
    return matches.singleOrNull { it.score == top }
}
