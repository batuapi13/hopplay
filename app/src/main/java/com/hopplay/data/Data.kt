package com.hopplay.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Protocol { LOCAL, SFTP, SMB }

/** For LOCAL, [host] holds the persisted SAF tree URI. */
data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
    val share: String = "",
    val domain: String = "",
    val path: String = "/",
)

/** Connections (including credentials) live in Keystore-backed EncryptedSharedPreferences. */
class ConnectionStore(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "hopplay_connections",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun all(): List<Connection> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    fun get(id: String): Connection? = all().firstOrNull { it.id == id }

    fun save(c: Connection) {
        val list = all()
        val idx = list.indexOfFirst { it.id == c.id }
        write(if (idx >= 0) list.toMutableList().also { it[idx] = c } else list + c)
    }

    fun delete(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<Connection>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(c: Connection) = JSONObject()
        .put("id", c.id).put("name", c.name).put("protocol", c.protocol.name)
        .put("host", c.host).put("port", c.port).put("user", c.user)
        .put("password", c.password).put("share", c.share).put("domain", c.domain)
        .put("path", c.path)

    private fun fromJson(o: JSONObject) = Connection(
        id = o.getString("id"), name = o.getString("name"),
        protocol = Protocol.valueOf(o.getString("protocol")),
        host = o.optString("host"), port = o.optInt("port"), user = o.optString("user"),
        password = o.optString("password"), share = o.optString("share"),
        domain = o.optString("domain"), path = o.optString("path", "/"),
    )

    private companion object { const val KEY = "list" }
}

/** Remembers the last playback position per media id. */
class ResumeStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hopplay_resume", Context.MODE_PRIVATE)
    fun get(id: String): Long? = if (prefs.contains(id)) prefs.getLong(id, 0L) else null
    fun put(id: String, posMs: Long) = prefs.edit().putLong(id, posMs).apply()
    fun clear(id: String) = prefs.edit().remove(id).apply()
}
