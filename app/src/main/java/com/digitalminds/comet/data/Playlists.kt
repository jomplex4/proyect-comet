package com.digitalminds.comet.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(val id: String, val name: String, val keys: List<String>) {
    val isFavorites: Boolean get() = id == Playlists.FAVORITES
}

/**
 * Playlists live inside the app (a small JSON list of song addresses), so they never touch
 * the files themselves. "My Favorites" always exists and cannot be renamed or deleted.
 */
object Playlists {
    const val FAVORITES = "favorites"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("comet_playlists", Context.MODE_PRIVATE)
    }

    fun all(): List<Playlist> {
        val list = read()
        val fav = list.firstOrNull { it.isFavorites } ?: Playlist(FAVORITES, "My Favorites", emptyList())
        return listOf(fav) + list.filter { !it.isFavorites }
    }

    fun get(id: String): Playlist? = all().firstOrNull { it.id == id }

    fun create(name: String): Playlist {
        val p = Playlist("pl_" + System.currentTimeMillis(), name.trim().ifEmpty { "New playlist" }, emptyList())
        write(all() + p)
        return p
    }

    fun rename(id: String, name: String) {
        if (id == FAVORITES) return
        write(all().map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    fun delete(id: String) {
        if (id == FAVORITES) return
        write(all().filter { it.id != id })
    }

    fun contains(id: String, key: String): Boolean = get(id)?.keys?.contains(key) == true

    fun add(id: String, key: String) {
        write(all().map { if (it.id == id && !it.keys.contains(key)) it.copy(keys = it.keys + key) else it })
    }

    fun remove(id: String, keys: Collection<String>) {
        write(all().map { if (it.id == id) it.copy(keys = it.keys.filter { k -> !keys.contains(k) }) else it })
    }

    /** Drops a deleted file from every playlist. */
    fun forget(key: String) {
        write(all().map { it.copy(keys = it.keys.filter { k -> k != key }) })
    }

    fun isFavorite(key: String): Boolean = contains(FAVORITES, key)

    /** Returns the new state. */
    fun toggleFavorite(key: String): Boolean {
        return if (isFavorite(key)) {
            remove(FAVORITES, listOf(key)); false
        } else {
            add(FAVORITES, key); true
        }
    }

    private fun read(): List<Playlist> {
        val raw = sp.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val k = o.getJSONArray("k")
                Playlist(o.getString("id"), o.getString("n"), (0 until k.length()).map { k.getString(it) })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            val k = JSONArray()
            p.keys.forEach { k.put(it) }
            arr.put(JSONObject().put("id", p.id).put("n", p.name).put("k", k))
        }
        sp.edit().putString("items", arr.toString()).apply()
    }
}
