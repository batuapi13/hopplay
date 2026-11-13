package com.hopplay

import android.app.Application
import android.content.Context
import com.hopplay.data.Connection
import com.hopplay.data.ConnectionStore
import com.hopplay.data.Protocol
import com.hopplay.data.ResumeStore
import com.hopplay.fs.LocalFs
import com.hopplay.fs.RemoteFs
import com.hopplay.fs.SftpFs
import com.hopplay.fs.SmbFs
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.ConcurrentHashMap

class FsRegistry(private val ctx: Context, private val store: ConnectionStore) {
    private val map = ConcurrentHashMap<String, RemoteFs>()

    fun get(id: String): RemoteFs = map.getOrPut(id) {
        val c = store.get(id) ?: error("Unknown connection $id")
        create(c)
    }

    fun invalidate(id: String) { map.remove(id) }

    private fun create(c: Connection): RemoteFs = when (c.protocol) {
        Protocol.LOCAL -> LocalFs(ctx, android.net.Uri.parse(c.host))
        Protocol.SFTP -> SftpFs(c, ctx)
        Protocol.SMB -> SmbFs(c)
    }
}

class HopPlayApp : Application() {
    lateinit var store: ConnectionStore
    lateinit var registry: FsRegistry
    lateinit var resume: ResumeStore

    override fun onCreate() {
        super.onCreate()
        instance = this
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        store = ConnectionStore(this)
        registry = FsRegistry(this, store)
        resume = ResumeStore(this)
    }

    companion object {
        lateinit var instance: HopPlayApp
            private set
    }
}
