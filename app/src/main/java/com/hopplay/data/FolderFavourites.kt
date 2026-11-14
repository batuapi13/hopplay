package com.hopplay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FolderFavourite(val connectionId: String, val path: String)

/** Device-local folder pins, no stored hostname or credentials. */
class FolderFavourites(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hopplay_favourites", Context.MODE_PRIVATE)
    fun all(): List<FolderFavourite> {
        val array = runCatching { JSONArray(prefs.getString("folders", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            runCatching { array.getJSONObject(i).let { FolderFavourite(it.getString("connection"), it.getString("path")) } }.getOrNull()
        }
    }
    fun contains(id: String, path: String) = all().any { it.connectionId == id && it.path == path }
    fun toggle(id: String, path: String) {
        val old = all()
        val updated = if (contains(id, path)) old.filterNot { it.connectionId == id && it.path == path }
            else (old + FolderFavourite(id, path)).takeLast(100)
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("connection", it.connectionId).put("path", it.path)) }
        prefs.edit().putString("folders", array.toString()).apply()
    }
    fun removeConnection(id: String) {
        val arr = JSONArray()
        all().filterNot { it.connectionId == id }
            .forEach { arr.put(JSONObject().put("connection", it.connectionId).put("path", it.path)) }
        prefs.edit().putString("folders", arr.toString()).apply()
    }
}
