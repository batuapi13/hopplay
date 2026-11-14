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
    private fun resetTransport() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    @Synchronized
    private fun client(): SFTPClient {
        val current = sftp
        if (current != null && ssh?.isConnected == true) return current
        resetTransport()
        val next = SSHClient()
        // Reconnection must re-verify the pinned host key; NEVER disable TOFU.
        next.addHostKeyVerifier(verifier)
        next.connectTimeout = 10_000
        try {
            next.connect(c.host, if (c.port > 0) c.port else 22)
            next.authPassword(c.user, c.password)
            val active = next.newSFTPClient()
            ssh = next
            sftp = active
            return active
        } catch (t: Throwable) {
            runCatching { next.disconnect() }
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

    private class Handle(val remote: RemoteFile, val channel: SFTPClient, val length: Long) {
        fun close() {
            try { remote.close() } finally { channel.close() }
        }
    }

    private fun openHandle(path: String): Handle {
        val channel = synchronized(this) {
            client()
            requireNotNull(ssh).newSFTPClient()
        }
        try {
            val remote = channel.open(path)
            try {
                return Handle(remote, channel, remote.length())
            } catch (t: Throwable) {
                runCatching { remote.close() }
                throw t
            }
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
    }

    override fun open(path: String): RangeFile {
        // A dedicated channel per file prevents reads from racing with browse/other videos.
        var handle = openHandle(path)
        val initialSize = handle.length
        var retriesLeft = 1
        var closed = false
        return object : RangeFile {
            override val size: Long = initialSize

            @Synchronized
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                check(!closed) { "SFTP file already closed" }
                try {
                    return handle.remote.read(pos, buf, off, len)
                } catch (original: java.io.IOException) {
                    if (retriesLeft-- <= 0) throw original
                    // Bounded, once-per-file recovery for transport read failures.
                    // Authentication failure or changed host key aborts immediately.
                    runCatching { handle.close() }
                    synchronized(this@SftpFs) { resetTransport() }
                    Thread.sleep(350)
                    handle = openHandle(path)
                    if (handle.length != initialSize)
                        throw java.io.IOException("Remote file changed during SFTP reconnect")
                    return handle.remote.read(pos, buf, off, len)
                }
            }

            @Synchronized
            override fun close() {
                if (closed) return
                closed = true
                handle.close()
            }
        }
    }
}
