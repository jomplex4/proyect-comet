package com.digitalminds.comet.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.digitalminds.comet.util.Prefs

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucketId: String,
    val bucketName: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val path: String
) {
    val key: String get() = uri.toString()
}

data class PhotoFolder(val id: String, val name: String, val photos: List<Photo>) {
    val count: Int get() = photos.size
    val latestAdded: Long get() = photos.maxOfOrNull { it.dateAdded } ?: 0L
    val latestModified: Long get() = photos.maxOfOrNull { it.dateModified } ?: 0L
}

/** Every picture in the Android media index, grouped by album (folder). */
object PhotoRepo {

    @Volatile
    var cache: List<Photo> = emptyList()
        private set

    /** Filled by the album screen right before opening the viewer (same process hand-off). */
    var viewerList: List<Photo> = emptyList()

    private fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Blocking; run off the main thread. */
    fun loadPhotos(ctx: Context): List<Photo> {
        val out = ArrayList<Photo>()
        val base = collection()
        val cols = arrayOf(
            "_id", "_display_name", "bucket_id", "bucket_display_name", "_size",
            "date_added", "date_modified", "datetaken", "width", "height", "_data"
        )
        try {
            ctx.contentResolver.query(base, cols, "_size > 0", null, null)?.use { c ->
                val iId = c.getColumnIndex("_id")
                val iName = c.getColumnIndex("_display_name")
                val iBucket = c.getColumnIndex("bucket_id")
                val iBucketName = c.getColumnIndex("bucket_display_name")
                val iSize = c.getColumnIndex("_size")
                val iAdded = c.getColumnIndex("date_added")
                val iMod = c.getColumnIndex("date_modified")
                val iTaken = c.getColumnIndex("datetaken")
                val iW = c.getColumnIndex("width")
                val iH = c.getColumnIndex("height")
                val iData = c.getColumnIndex("_data")
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val path = (if (iData >= 0) c.getString(iData) else null) ?: ""
                    val fromPath = path.substringBeforeLast("/", "").substringAfterLast("/")
                    val bucketName = (if (iBucketName >= 0) c.getString(iBucketName) else null)
                        ?: fromPath.ifEmpty { "Internal" }
                    out.add(
                        Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(base, id),
                            name = (if (iName >= 0) c.getString(iName) else null) ?: path.substringAfterLast("/"),
                            bucketId = (if (iBucket >= 0) c.getString(iBucket) else null) ?: bucketName,
                            bucketName = bucketName,
                            size = if (iSize >= 0) c.getLong(iSize) else 0L,
                            dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                            dateModified = if (iMod >= 0) c.getLong(iMod) else 0L,
                            dateTaken = if (iTaken >= 0) c.getLong(iTaken) else 0L,
                            width = if (iW >= 0) c.getInt(iW) else 0,
                            height = if (iH >= 0) c.getInt(iH) else 0,
                            path = path
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Permission revoked or media index busy: show what we have.
        }
        cache = out
        return out
    }

    fun sortPhotos(list: List<Photo>, prefs: Prefs): List<Photo> {
        val sorted = when (prefs.photoSortKey) {
            Prefs.SORT_MODIFIED -> list.sortedBy { it.dateModified }
            Prefs.SORT_NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> list.sortedBy { if (it.dateTaken > 0) it.dateTaken / 1000 else it.dateAdded }
        }
        return if (prefs.photoSortAscending) sorted else sorted.reversed()
    }

    fun folders(list: List<Photo>, prefs: Prefs): List<PhotoFolder> {
        val groups = list.groupBy { it.bucketId }.map { (id, photos) ->
            PhotoFolder(id, photos.first().bucketName, sortPhotos(photos, prefs))
        }
        val sorted = when (prefs.photoSortKey) {
            Prefs.SORT_MODIFIED -> groups.sortedBy { it.latestModified }
            Prefs.SORT_NAME -> groups.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> groups.sortedBy { it.latestAdded }
        }
        return if (prefs.photoSortAscending) sorted else sorted.reversed()
    }
}
