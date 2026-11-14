package com.hopplay.player

import android.content.Context
import com.hopplay.fs.FsEntry
import com.hopplay.fs.RemoteFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val MAX_SUBTITLE_BYTES = 4L * 1024L * 1024L

/**
 * Materialize only selected sidecar subtitle candidates, never video data.
 * SFTP reads run on Dispatchers.IO, have a strict size cap, and use app-private cache.
 * External files are removed by VlcSession.release().
 */
suspend fun cacheSftpSubtitle(ctx: Context, fs: RemoteFs, entry: FsEntry): File =
    withContext(Dispatchers.IO) {
        if (entry.size > MAX_SUBTITLE_BYTES || entry.size <= 0) throw IOException("Subtitle too large or empty")
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("srt", "ass", "ssa", "vtt")) throw IOException("Unsupported subtitle")
        val directory = File(ctx.cacheDir, "ext-subs").apply { mkdirs() }
        val target = File.createTempFile("sftp-sidecar-", ".$extension", directory)
        try {
            fs.open(entry.path).use { remote ->
                if (remote.size > MAX_SUBTITLE_BYTES || remote.size <= 0) throw IOException("Invalid subtitle size")
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var position = 0L
                    while (position < remote.size) {
                        coroutineContext.ensureActive()
                        val count = minOf(buffer.size.toLong(), remote.size - position).toInt()
                        val read = remote.read(position, buffer, 0, count)
                        if (read <= 0) throw IOException("Incomplete subtitle read")
                        output.write(buffer, 0, read)
                        position += read
                    }
                }
            }
            target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
