package com.digitalminds.comet.util

import android.content.Context

/** Small persisted settings. Everything here survives app restarts. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("comet_prefs", Context.MODE_PRIVATE)

    var sortKey: Int
        get() = sp.getInt("sort_key", SORT_ADDED)
        set(v) = sp.edit().putInt("sort_key", v).apply()

    var sortAscending: Boolean
        get() = sp.getBoolean("sort_asc", false)
        set(v) = sp.edit().putBoolean("sort_asc", v).apply()

    var musicSortKey: Int
        get() = sp.getInt("music_sort_key", SORT_ADDED)
        set(v) = sp.edit().putInt("music_sort_key", v).apply()

    var musicSortAscending: Boolean
        get() = sp.getBoolean("music_sort_asc", false)
        set(v) = sp.edit().putBoolean("music_sort_asc", v).apply()

    var photoSortKey: Int
        get() = sp.getInt("photo_sort_key", SORT_ADDED)
        set(v) = sp.edit().putInt("photo_sort_key", v).apply()

    var photoSortAscending: Boolean
        get() = sp.getBoolean("photo_sort_asc", false)
        set(v) = sp.edit().putBoolean("photo_sort_asc", v).apply()

    /** Sub-section inside MUSIC: 0 Songs, 1 Folders, 2 Playlists. */
    var musicSection: Int
        get() = sp.getInt("music_section", 0)
        set(v) = sp.edit().putInt("music_section", v).apply()

    var gridView: Boolean
        get() = sp.getBoolean("grid_view", false)
        set(v) = sp.edit().putBoolean("grid_view", v).apply()

    var backgroundPlay: Boolean
        get() = sp.getBoolean("bg_play", false)
        set(v) = sp.edit().putBoolean("bg_play", v).apply()

    var softwareDecoder: Boolean
        get() = sp.getBoolean("sw_decoder", false)
        set(v) = sp.edit().putBoolean("sw_decoder", v).apply()

    var repeatMode: Int
        get() = sp.getInt("repeat_mode", 0)
        set(v) = sp.edit().putInt("repeat_mode", v).apply()

    /** 0 = Fill (expand without distortion), 1 = Fit. */
    var resizeMode: Int
        get() = sp.getInt("resize_mode", RESIZE_FILL)
        set(v) = sp.edit().putInt("resize_mode", v).apply()

    var orientation: Int
        get() = sp.getInt("orientation", ORIENT_AUTO)
        set(v) = sp.edit().putInt("orientation", v).apply()

    /** -1 means follow the system brightness. */
    var brightness: Float
        get() = sp.getFloat("brightness", -1f)
        set(v) = sp.edit().putFloat("brightness", v).apply()

    /** One sort setting (videos, music or photos) as seen by the shared sort dialog. */
    class SortSpec(
        val key: () -> Int,
        val setKey: (Int) -> Unit,
        val ascending: () -> Boolean,
        val setAscending: (Boolean) -> Unit
    )

    fun videoSort() = SortSpec({ sortKey }, { sortKey = it }, { sortAscending }, { sortAscending = it })
    fun musicSort() = SortSpec({ musicSortKey }, { musicSortKey = it }, { musicSortAscending }, { musicSortAscending = it })
    fun photoSort() = SortSpec({ photoSortKey }, { photoSortKey = it }, { photoSortAscending }, { photoSortAscending = it })

    companion object {
        const val SORT_ADDED = 0
        const val SORT_MODIFIED = 1
        const val SORT_NAME = 2

        const val RESIZE_FILL = 0
        const val RESIZE_FIT = 1

        const val ORIENT_AUTO = 0
        const val ORIENT_LANDSCAPE = 1
        const val ORIENT_PORTRAIT = 2
    }
}
