package com.hopplay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val connectionId: String,
    val path: String,
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Bounded local-only history. Never stores a hostname, password, or SSH credential. */
class PlaybackHistory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hopplay_history", Context.MODE_PRIVATE)
    private val lock = Any()
    private fun read(): List<HistoryEntry> {
        val arr = runCatching { JSONArray(prefs.getString("recent", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.getString("connection"), o.getString("path"), o.getString("name"),
                    o.optLong("position"), o.optLong("duration"), o.optLong("updated"),
                    o.optBoolean("completed")
                )
            }.getOrNull()
        }
    }
    fun recent(): List<HistoryEntry> = synchronized(lock) { read().sortedByDescending { it.updatedAt } }
    fun update(connection: String, path: String, name: String, position: Long, duration: Long, finished: Boolean = false) =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = read().filterNot { it.connectionId == connection && it.path == path }
            write((listOf(HistoryEntry(connection, path, name, position, duration, now, finished)) + current).take(150))
        }
    fun remove(connection: String, path: String) = synchronized(lock) {
        write(read().filterNot { it.connectionId == connection && it.path == path })
    }
    fun clear() = synchronized(lock) { prefs.edit().remove("recent").apply() }
    private fun write(items: List<HistoryEntry>) {
        val arr = JSONArray()
        for (h in items) {
            arr.put(JSONObject().put("connection", h.connectionId).put("path", h.path)
                .put("name", h.name).put("position", h.positionMs).put("duration", h.durationMs)
                .put("updated", h.updatedAt).put("completed", h.completed))
        }
        prefs.edit().putString("recent", arr.toString()).apply()
    }
}
