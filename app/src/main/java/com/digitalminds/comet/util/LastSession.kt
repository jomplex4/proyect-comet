package com.digitalminds.comet.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers what was loaded in the player (queue, current file, position), so that if Android
 * closes the app while it is paused, opening it again puts everything back exactly where it was
 * instead of showing a dead screen.
 */
object LastSession {

    class Snapshot(val keys: List<String>, val index: Int, val positionMs: Long, val audio: Boolean)

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("comet_session", Context.MODE_PRIVATE)
    }

    fun saveQueue(keys: List<String>, audio: Boolean) {
        if (!::sp.isInitialized) return
        sp.edit().putString("keys", keys.joinToString("\n")).putBoolean("audio", audio).apply()
    }

    fun saveIndex(index: Int) {
        if (!::sp.isInitialized) return
        sp.edit().putInt("index", index).putLong("pos", 0L).apply()
    }

    fun savePosition(positionMs: Long) {
        if (!::sp.isInitialized) return
        sp.edit().putLong("pos", positionMs.coerceAtLeast(0L)).apply()
    }

    fun clear() {
        if (!::sp.isInitialized) return
        sp.edit().clear().apply()
    }

    fun load(): Snapshot? {
        if (!::sp.isInitialized) return null
        val raw = sp.getString("keys", null) ?: return null
        val keys = raw.split("\n").filter { it.isNotEmpty() }
        if (keys.isEmpty()) return null
        return Snapshot(keys, sp.getInt("index", 0), sp.getLong("pos", 0L), sp.getBoolean("audio", false))
    }
}
