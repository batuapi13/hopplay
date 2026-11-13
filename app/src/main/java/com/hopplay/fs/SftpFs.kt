package com.hopplay.fs

import android.net.Uri
import com.hopplay.data.Connection
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

abstract class NetworkFs(protected val c: Connection) : RemoteFs {
    override val rootPath: String = "/" + c.path.trim('/')

    override fun playbackUri(path: String): Uri =
        Uri.parse("hopplay://${c.id}" + Uri.encode(path, "/"))

    protected fun join(parent: String, name: String) =
        if (parent.endsWith("/")) parent + name else "$parent/$name"
}

class SftpFs(c: Connection) : NetworkFs(c) {
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    @Synchronized
    private fun client(): SFTPClient {
        val cur = sftp
        if (cur != null && ssh?.isConnected == true) return cur
        runCatching { ssh?.disconnect() }
        val k = SSHClient()
        // TODO: trust-on-first-use host key store.
        k.addHostKeyVerifier(PromiscuousVerifier())
        k.connectTimeout = 15_000
        k.connect(c.host, if (c.port > 0) c.port else 22)
        k.authPassword(c.user, c.password)
        ssh = k
        return k.newSFTPClient().also { sftp = it }
    }

    override fun list(path: String): List<FsEntry> =
        client().ls(path).filter { it.name != "." && it.name != ".." }
            .map { FsEntry(it.name, join(path, it.name), it.isDirectory, it.attributes.size) }

    override fun stat(path: String): FsEntry? = runCatching {
        val a = client().stat(path)
        FsEntry(path.substringAfterLast('/'), path, a.type == FileMode.Type.DIRECTORY, a.size)
    }.getOrNull()

    override fun open(path: String): RangeFile {
        val rf: RemoteFile = client().open(path)
        return object : RangeFile {
            override val size = rf.length()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) = rf.read(pos, buf, off, len)
            override fun close() = rf.close()
        }
    }
}
