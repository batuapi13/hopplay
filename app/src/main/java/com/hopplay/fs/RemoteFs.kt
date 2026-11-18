package com.hopplay.fs

import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.Closeable

data class FsEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

/** Random-access reader: lets remote files stream and seek without a full download. */
interface RangeFile : Closeable {
    val size: Long
    /** Reads up to [len] bytes at [pos]; returns -1 at EOF. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

interface RemoteFs {
    val rootPath: String
    fun list(path: String): List<FsEntry>
    fun stat(path: String): FsEntry?
    fun open(path: String): RangeFile
    /** URI handed to the player; remote schemes are resolved by RemoteDataSource. */
    fun playbackUri(path: String): Uri
}

object MediaTypes {
    private val video = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts", "flv",
        "wmv", "3gp", "mpg", "mpeg", "ogv", "vob", "asf",
    )
    private val audio = setOf(
        "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "ape",
        "mka", "amr", "aif", "aiff", "alac", "ac3", "eac3", "dts", "mp2",
    )
    private val subs = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA,
    )

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isPlayable(name: String) = ext(name).let { it in video || it in audio }
    fun subtitleMime(name: String): String? = subs[ext(name)]
}

/** Natural ordering: "ep2" < "ep10", case-insensitive. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

val entryOrder = Comparator<FsEntry> { x, y ->
    if (x.isDir != y.isDir) { if (x.isDir) -1 else 1 } else naturalCompare(x.name, y.name)
}
