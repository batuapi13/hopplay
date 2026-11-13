package com.hopplay.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hopplay.HopPlayApp
import com.hopplay.fs.RangeFile
import java.io.IOException

const val REMOTE_SCHEME = "hopplay"

/** Sends hopplay:// URIs to [RemoteDataSource], everything else to the platform default. */
class RoutingDataSource(ctx: Context, private val diagnostics: PlaybackDiagnostics) : DataSource {
    private val default = DefaultDataSource.Factory(ctx).createDataSource()
    private val remote = RemoteDataSource(diagnostics)
    private var active: DataSource? = null

    override fun addTransferListener(l: TransferListener) = default.addTransferListener(l)

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == REMOTE_SCHEME) remote else default
        try {
            val openedLength = ds.open(dataSpec)
            active = ds
            return openedLength
        } catch (t: Throwable) {
            runCatching { ds.close() }
            active = null
            throw t
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        active?.close()
        active = null
    }
}

/** Range-reading data source: ExoPlayer seeks by reopening at a new position; no full download. */
class RemoteDataSource(private val diagnostics: PlaybackDiagnostics) : DataSource {
    private var file: RangeFile? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private val buf = ByteArray(BUFFER)
    private var bufStart = 0L
    private var bufLen = 0

    override fun addTransferListener(l: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        close()
        val requestUri = dataSpec.uri
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val fs = HopPlayApp.instance.registry.get(requestUri.authority ?: throw IOException("No connection"))
        val f = fs.open(requestUri.path ?: "/")
        try {
            val start = dataSpec.position
            if (start > f.size) throw DataSourceException(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            )
            file = f
            uri = requestUri
            pos = start
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong())
                minOf(dataSpec.length, f.size - start) else f.size - start
            bufStart = 0L
            bufLen = 0
            diagnostics.record("SFTP open: position=" + start + " length=" + remaining + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - startedAt))
            return remaining
        } catch (t: Throwable) {
            diagnostics.record("SFTP open failed: " + t.javaClass.simpleName + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - startedAt))
            runCatching { f.close() }
            throw t
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val f = file ?: return C.RESULT_END_OF_INPUT
        if (pos < bufStart || pos >= bufStart + bufLen || bufLen == 0) {
            // One bounded read per call avoids stalling while filling a huge
            // buffer over a high-latency SFTP or SMB connection.
            val requested = minOf(buf.size.toLong(), remaining).toInt()
            val readStarted = android.os.SystemClock.elapsedRealtime()
            val n = f.read(pos, buf, 0, requested)
            val elapsed = android.os.SystemClock.elapsedRealtime() - readStarted
            if (elapsed > 750 || bufLen == 0) diagnostics.record("SFTP read: bytes=" + n + " position=" + pos + " elapsedMs=" + elapsed)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bufStart = pos
            bufLen = n
        }
        val off = (pos - bufStart).toInt()
        val n = minOf(length.toLong(), remaining, (bufLen - off).toLong()).toInt()
        System.arraycopy(buf, off, buffer, offset, n)
        pos += n
        remaining -= n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { file?.close() }
        file = null
        uri = null
        pos = 0L
        remaining = 0L
        bufStart = 0L
        bufLen = 0
    }

    private companion object { const val BUFFER = 256 * 1024 }
}
