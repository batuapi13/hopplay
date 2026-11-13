package com.hopplay.fs

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hopplay.data.Connection
import java.util.EnumSet

class SmbFs(c: Connection) : NetworkFs(c) {
    private val client = SMBClient()
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        val conn = client.connect(c.host, if (c.port > 0) c.port else 445)
        val auth = if (c.user.isEmpty()) AuthenticationContext.guest()
        else AuthenticationContext(c.user, c.password.toCharArray(), c.domain)
        val session = conn.authenticate(auth)
        return (session.connectShare(c.share) as DiskShare).also { share = it }
    }

    private fun smbPath(p: String) = p.trim('/').replace('/', '\\')

    override fun list(path: String): List<FsEntry> =
        share().list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val dir = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                FsEntry(it.fileName, join(path, it.fileName), dir, it.endOfFile)
            }

    override fun stat(path: String): FsEntry? = runCatching {
        val s = share()
        val p = smbPath(path)
        if (s.folderExists(p)) FsEntry(path.substringAfterLast('/'), path, true, 0)
        else FsEntry(path.substringAfterLast('/'), path, false, s.getFileInformation(p).standardInformation.endOfFile)
    }.getOrNull()

    override fun open(path: String): RangeFile {
        val f = share().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return object : RangeFile {
            override val size = f.fileInformation.standardInformation.endOfFile
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) = f.read(buf, pos, off, len)
            override fun close() = f.close()
        }
    }
}
