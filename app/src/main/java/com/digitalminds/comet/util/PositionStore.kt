package com.digitalminds.comet.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val uri: String,
    val title: String,
    val bucketId: String,
    val durationMs: Long,
    val isVideo: Boolean
)

/**
 * Remembers where each file was left (resume + the red progress bar under thumbnails) and the
 * last 10 things played (the history row on the VIDEOS screen).
 */
object PositionStore {
    private const val MAX_HISTORY = 10
    private lateinit var pos: SharedPreferences
    private lateinit var meta: SharedPreferences

    fun init(ctx: Context) {
        if (::pos.isInitialized) return
        pos = ctx.applicationContext.getSharedPreferences("comet_positions", Context.MODE_PRIVATE)
        meta = ctx.applicationContext.getSharedPreferences("comet_history", Context.MODE_PRIVATE)
    }

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        pos.edit().putString(key, "${positionMs.coerceIn(0, durationMs)},$durationMs").apply()
    }

    private fun read(key: String): Pair<Long, Long>? {
        val raw = pos.getString(key, null) ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val p = parts[0].toLongOrNull() ?: return null
        val d = parts[1].toLongOrNull() ?: return null
        return p to d
    }

    /** Where to start this file: the saved spot, or 0 if new or already finished. */
    fun resumeFor(key: String, minDurationMs: Long = 0): Long {
        val (p, d) = read(key) ?: return 0
        if (d < minDurationMs) return 0
        return if (p > 3000 && p < d - 5000) p else 0
    }

    /** 0..1 of how much was watched, or -1 if never played. */
    fun progress(key: String): Float {
        val (p, d) = read(key) ?: return -1f
        if (d <= 0) return -1f
        return (p.toFloat() / d).coerceIn(0f, 1f)
    }

    fun forget(key: String) {
        pos.edit().remove(key).apply()
        val list = history().filter { it.uri != key }
        writeHistory(list)
    }

    fun history(): List<HistoryEntry> {
        val raw = meta.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistoryEntry(
                    o.getString("u"), o.optString("t"), o.optString("b"),
                    o.optLong("d"), o.optBoolean("v", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(e: HistoryEntry) {
        val list = mutableListOf(e)
        list.addAll(history().filter { it.uri != e.uri })
        writeHistory(list.take(MAX_HISTORY))
    }

    private fun writeHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("u", it.uri).put("t", it.title).put("b", it.bucketId)
                    .put("d", it.durationMs).put("v", it.isVideo)
            )
        }
        meta.edit().putString("items", arr.toString()).apply()
    }
}
