package com.digitalminds.comet.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.Prefs

data class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucketId: String,
    val bucketName: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val path: String
) {
    val key: String get() = uri.toString()
}

data class Folder(val id: String, val name: String, val videos: List<Video>) {
    val count: Int get() = videos.size
    val totalSize: Long get() = videos.sumOf { it.size }
    val latestAdded: Long get() = videos.maxOfOrNull { it.dateAdded } ?: 0L
    val latestModified: Long get() = videos.maxOfOrNull { it.dateModified } ?: 0L
}

/** Reads every video in the Android media index, grouped by folder. */
object MediaRepo {

    @Volatile
    var cache: List<Video> = emptyList()
        private set

    private fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** Blocking; run off the main thread. */
    fun loadVideos(ctx: Context): List<Video> {
        val out = ArrayList<Video>()
        val cols = arrayOf(
            "_id", "_display_name", "bucket_id", "bucket_display_name", "duration",
            "_size", "date_added", "date_modified", "width", "height", "_data"
        )
        val base = collection()
        try {
            ctx.contentResolver.query(base, cols, "_size > 0", null, null)?.use { c ->
                val iId = c.getColumnIndex("_id")
                val iName = c.getColumnIndex("_display_name")
                val iBucket = c.getColumnIndex("bucket_id")
                val iBucketName = c.getColumnIndex("bucket_display_name")
                val iDur = c.getColumnIndex("duration")
                val iSize = c.getColumnIndex("_size")
                val iAdded = c.getColumnIndex("date_added")
                val iMod = c.getColumnIndex("date_modified")
                val iW = c.getColumnIndex("width")
                val iH = c.getColumnIndex("height")
                val iData = c.getColumnIndex("_data")
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = (if (iName >= 0) c.getString(iName) else null) ?: continue
                    val path = (if (iData >= 0) c.getString(iData) else null) ?: ""
                    val fromPath = path.substringBeforeLast("/", "").substringAfterLast("/")
                    val bucketName = (if (iBucketName >= 0) c.getString(iBucketName) else null)
                        ?: fromPath.ifEmpty { "Internal" }
                    val bucketId = (if (iBucket >= 0) c.getString(iBucket) else null) ?: bucketName
                    out.add(
                        Video(
                            id = id,
                            uri = ContentUris.withAppendedId(base, id),
                            name = name,
                            bucketId = bucketId,
                            bucketName = bucketName,
                            durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                            size = if (iSize >= 0) c.getLong(iSize) else 0L,
                            dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                            dateModified = if (iMod >= 0) c.getLong(iMod) else 0L,
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

    fun sortVideos(list: List<Video>, prefs: Prefs): List<Video> {
        val sorted = when (prefs.sortKey) {
            Prefs.SORT_MODIFIED -> list.sortedBy { it.dateModified }
            Prefs.SORT_NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> list.sortedBy { it.dateAdded }
        }
        return if (prefs.sortAscending) sorted else sorted.reversed()
    }

    fun folders(list: List<Video>, prefs: Prefs): List<Folder> {
        val groups = list.groupBy { it.bucketId }.map { (id, vids) ->
            Folder(id, vids.first().bucketName, sortVideos(vids, prefs))
        }
        val sorted = when (prefs.sortKey) {
            Prefs.SORT_MODIFIED -> groups.sortedBy { it.latestModified }
            Prefs.SORT_NAME -> groups.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> groups.sortedBy { it.latestAdded }
        }
        return if (prefs.sortAscending) sorted else sorted.reversed()
    }

    fun toMediaItem(v: Video): MediaItem {
        val extras = Bundle().apply {
            putString("bucket", v.bucketId)
            putString("path", v.path)
            putLong("size", v.size)
            putLong("modified", v.dateModified)
        }
        return MediaItem.Builder()
            .setUri(v.uri)
            .setMediaId(v.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Format.stripExtension(v.name))
                    .setDisplayTitle(v.name)
                    .setArtist(v.bucketName)
                    .setArtworkUri(v.uri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** A file handed over by another app (WhatsApp, Files, etc.). */
    fun externalItem(uri: Uri, displayName: String, size: Long): MediaItem {
        val extras = Bundle().apply {
            putString("path", uri.toString())
            putLong("size", size)
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Format.stripExtension(displayName))
                    .setDisplayTitle(displayName)
                    .setArtist("COMET")
                    .setArtworkUri(uri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** Builds the play list for a history entry: its whole folder when it still exists. */
    fun queueFor(ctx: Context, uri: String, bucketId: String, prefs: Prefs): Pair<List<MediaItem>, Int>? {
        val all = if (cache.isEmpty()) loadVideos(ctx) else cache
        if (bucketId.isNotEmpty()) {
            val folder = sortVideos(all.filter { it.bucketId == bucketId }, prefs)
            val idx = folder.indexOfFirst { it.key == uri }
            if (idx >= 0) return folder.map { toMediaItem(it) } to idx
        }
        val single = all.firstOrNull { it.key == uri } ?: return null
        return listOf(toMediaItem(single)) to 0
    }
}

/** Hand-off from a list screen to the player (same process, no huge Intent extras). */
object Launch {
    var pending: Pair<List<MediaItem>, Int>? = null
}
