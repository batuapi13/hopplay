package com.hopplay.fs

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.hopplay.data.Connection
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Locale

abstract class NetworkFs(protected val c: Connection) : RemoteFs {
    override val rootPath: String = "/" + c.path.trim('/')

    override fun playbackUri(path: String): Uri =
        Uri.parse("hopplay://${c.id}" + Uri.encode(path, "/"))

    protected fun join(parent: String, name: String) =
        if (parent.endsWith("/")) parent + name else "$parent/$name"
}

/**
 * Trust on first use: pin the first SSH host key seen for host:port, and refuse
 * silent replacement. This pin is independent of saved connection credentials.
 */
class TofuHostKeyVerifier(ctx: Context) : HostKeyVerifier {
    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "hopplay_sftp_known_hosts"

        private fun identity(host: String, port: Int) =
            "${host.lowercase(Locale.ROOT)}:${if (port > 0) port else 22}"

        fun fingerprint(ctx: Context, host: String, port: Int): String? =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(identity(host, port), null)

        /** Require an explicit user confirmation in the UI before calling this. */
        fun forget(ctx: Context, host: String, port: Int): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(identity(host, port)).commit()
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val identity = identity(hostname, port)
        val fingerprint = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded),
            Base64.NO_WRAP,
        )
        synchronized(prefs) {
            val known = prefs.getString(identity, null)
            if (known != null) {
                if (known != fingerprint) {
                    throw SecurityException("SFTP host key changed for $identity. Connection blocked. Verify the server key before resetting trust.")
                }
                return true
            }
            // commit() ensures the key is saved before the connection is trusted.
            check(prefs.edit().putString(identity, fingerprint).commit()) {
                "Unable to save SFTP server host key for $identity"
            }
            return true
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

class SftpFs(c: Connection, ctx: Context) : NetworkFs(c) {
    private val verifier = TofuHostKeyVerifier(ctx)
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    @Synchronized
    private fun client(): SFTPClient {
        val cur = sftp
        if (cur != null && ssh?.isConnected == true) return cur
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
        val k = SSHClient()
        k.addHostKeyVerifier(verifier)
        k.connectTimeout = 15_000
        try {
            k.connect(c.host, if (c.port > 0) c.port else 22)
            k.authPassword(c.user, c.password)
            val active = k.newSFTPClient()
            ssh = k
            sftp = active
            return active
        } catch (t: Throwable) {
            runCatching { k.disconnect() }
            throw t
        }
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
