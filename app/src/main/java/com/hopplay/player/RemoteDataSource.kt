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
class RoutingDataSource(ctx: Context) : DataSource {
    private val default = DefaultDataSource.Factory(ctx).createDataSource()
    private val remote = RemoteDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(l: TransferListener) = default.addTransferListener(l)

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == REMOTE_SCHEME) remote else default
        active = ds
        return ds.open(dataSpec)
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
class RemoteDataSource : DataSource {
    private var file: RangeFile? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private val buf = ByteArray(BUFFER)
    private var bufStart = 0L
    private var bufLen = 0

    override fun addTransferListener(l: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val fs = HopPlayApp.instance.registry.get(dataSpec.uri.authority ?: throw IOException("No connection"))
        val f = fs.open(dataSpec.uri.path ?: "/")
        file = f
        pos = dataSpec.position
        if (pos > f.size) throw DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else f.size - pos
        bufLen = 0
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val f = file ?: return C.RESULT_END_OF_INPUT
        if (pos < bufStart || pos >= bufStart + bufLen || bufLen == 0) {
            var filled = 0
            while (filled < buf.size) {
                val n = f.read(pos + filled, buf, filled, buf.size - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled == 0) return C.RESULT_END_OF_INPUT
            bufStart = pos
            bufLen = filled
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
    }

    private companion object { const val BUFFER = 256 * 1024 }
}
