package com.hopplay.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Local folders through the Storage Access Framework (a persisted tree URI). */
class LocalFs(private val ctx: Context, private val tree: Uri) : RemoteFs {
    override val rootPath: String = DocumentsContract
        .buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()

    override fun list(path: String): List<FsEntry> {
        val docId = DocumentsContract.getDocumentId(Uri.parse(path))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<FsEntry>()
        ctx.contentResolver.query(children, COLS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += FsEntry(
                    name = c.getString(1) ?: continue,
                    path = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(),
                    isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = c.getLong(3),
                )
            }
        }
        return out
    }

    override fun stat(path: String): FsEntry? =
        ctx.contentResolver.query(Uri.parse(path), COLS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else FsEntry(c.getString(1) ?: "", path, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3))
        }

    override fun open(path: String): RangeFile {
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(path), "r")
            ?: throw java.io.IOException("Cannot open $path")
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return object : RangeFile {
            override val size = pfd.statSize
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) =
                channel.read(ByteBuffer.wrap(buf, off, len), pos)
            override fun close() { channel.close(); pfd.close() }
        }
    }

    // Content URIs are played directly by the platform data source.
    override fun playbackUri(path: String): Uri = Uri.parse(path)

    private companion object {
        val COLS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
