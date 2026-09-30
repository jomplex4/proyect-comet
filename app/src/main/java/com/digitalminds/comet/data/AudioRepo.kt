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

data class Song(
    val id: Long,
    val uri: Uri,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String,
    val bucketId: String,
    val bucketName: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val path: String
) {
    val key: String get() = uri.toString()

    /** Second line of a row: artist when known, otherwise the folder. */
    val subtitle: String get() = if (artist.isNotBlank() && artist != "<unknown>") artist else bucketName
}

data class MusicFolder(val id: String, val name: String, val songs: List<Song>) {
    val count: Int get() = songs.size
    val latestAdded: Long get() = songs.maxOfOrNull { it.dateAdded } ?: 0L
    val latestModified: Long get() = songs.maxOfOrNull { it.dateModified } ?: 0L
}

/** Every audio file in the Android media index, minus ringtones, alarms and voice notes. */
object AudioRepo {

    @Volatile
    var cache: List<Song> = emptyList()
        private set

    private const val MIN_DURATION_MS = 20_000L

    private fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /** Blocking; run off the main thread. */
    fun loadSongs(ctx: Context): List<Song> {
        val out = ArrayList<Song>()
        val base = collection()
        val cols = ArrayList(
            listOf(
                "_id", "_display_name", "title", "artist", "album", "duration",
                "_size", "date_added", "date_modified", "_data"
            )
        )
        if (Build.VERSION.SDK_INT >= 29) {
            cols.add("bucket_id")
            cols.add("bucket_display_name")
        }
        val selection = "_size > 0 AND is_ringtone = 0 AND is_notification = 0 AND is_alarm = 0"
        try {
            ctx.contentResolver.query(base, cols.toTypedArray(), selection, null, null)?.use { c ->
                val iId = c.getColumnIndex("_id")
                val iName = c.getColumnIndex("_display_name")
                val iTitle = c.getColumnIndex("title")
                val iArtist = c.getColumnIndex("artist")
                val iAlbum = c.getColumnIndex("album")
                val iDur = c.getColumnIndex("duration")
                val iSize = c.getColumnIndex("_size")
                val iAdded = c.getColumnIndex("date_added")
                val iMod = c.getColumnIndex("date_modified")
                val iData = c.getColumnIndex("_data")
                val iBucket = c.getColumnIndex("bucket_id")
                val iBucketName = c.getColumnIndex("bucket_display_name")
                while (c.moveToNext()) {
                    val duration = if (iDur >= 0) c.getLong(iDur) else 0L
                    if (duration in 1 until MIN_DURATION_MS) continue
                    val path = (if (iData >= 0) c.getString(iData) else null) ?: ""
                    val folderPath = path.substringBeforeLast("/", "")
                    val fromPath = folderPath.substringAfterLast("/")
                    val bucketName = (if (iBucketName >= 0) c.getString(iBucketName) else null)
                        ?: fromPath.ifEmpty { "Internal" }
                    // WhatsApp and recorder voice notes are not music.
                    if (bucketName.contains("Voice Notes", true) || path.contains("/WhatsApp Voice Notes/", true)) continue
                    val bucketId = (if (iBucket >= 0) c.getString(iBucket) else null)
                        ?: folderPath.ifEmpty { bucketName }
                    val id = c.getLong(iId)
                    val fileName = (if (iName >= 0) c.getString(iName) else null) ?: path.substringAfterLast("/")
                    val title = (if (iTitle >= 0) c.getString(iTitle) else null)
                        ?.takeIf { it.isNotBlank() } ?: Format.stripExtension(fileName)
                    out.add(
                        Song(
                            id = id,
                            uri = ContentUris.withAppendedId(base, id),
                            fileName = fileName,
                            title = title,
                            artist = (if (iArtist >= 0) c.getString(iArtist) else null) ?: "",
                            album = (if (iAlbum >= 0) c.getString(iAlbum) else null) ?: "",
                            bucketId = bucketId,
                            bucketName = bucketName,
                            durationMs = duration,
                            size = if (iSize >= 0) c.getLong(iSize) else 0L,
                            dateAdded = if (iAdded >= 0) c.getLong(iAdded) else 0L,
                            dateModified = if (iMod >= 0) c.getLong(iMod) else 0L,
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

    fun sortSongs(list: List<Song>, prefs: Prefs): List<Song> {
        val sorted = when (prefs.musicSortKey) {
            Prefs.SORT_MODIFIED -> list.sortedBy { it.dateModified }
            Prefs.SORT_NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            else -> list.sortedBy { it.dateAdded }
        }
        return if (prefs.musicSortAscending) sorted else sorted.reversed()
    }

    fun folders(list: List<Song>, prefs: Prefs): List<MusicFolder> {
        val groups = list.groupBy { it.bucketId }.map { (id, songs) ->
            MusicFolder(id, songs.first().bucketName, sortSongs(songs, prefs))
        }
        val sorted = when (prefs.musicSortKey) {
            Prefs.SORT_MODIFIED -> groups.sortedBy { it.latestModified }
            Prefs.SORT_NAME -> groups.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            else -> groups.sortedBy { it.latestAdded }
        }
        return if (prefs.musicSortAscending) sorted else sorted.reversed()
    }

    fun toMediaItem(s: Song): MediaItem {
        val extras = Bundle().apply {
            putString("bucket", s.bucketId)
            putString("path", s.path)
            putLong("size", s.size)
            putLong("modified", s.dateModified)
            putBoolean("audio", true)
        }
        return MediaItem.Builder()
            .setUri(s.uri)
            .setMediaId(s.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setDisplayTitle(s.fileName)
                    .setArtist(s.subtitle)
                    .setAlbumTitle(s.album)
                    .setArtworkUri(s.uri)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** An audio file handed over by another app (WhatsApp, Files, etc.). */
    fun externalItem(uri: Uri, displayName: String, size: Long): MediaItem {
        val extras = Bundle().apply {
            putString("path", uri.toString())
            putLong("size", size)
            putBoolean("audio", true)
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

    fun byKeys(keys: List<String>): List<Song> {
        val map = cache.associateBy { it.key }
        return keys.mapNotNull { map[it] }
    }

    /** Queue for an audio history entry: its folder when it still exists. */
    fun queueFor(ctx: Context, uri: String, bucketId: String, prefs: Prefs): Pair<List<MediaItem>, Int>? {
        val all = if (cache.isEmpty()) loadSongs(ctx) else cache
        if (bucketId.isNotEmpty()) {
            val folder = sortSongs(all.filter { it.bucketId == bucketId }, prefs)
            val idx = folder.indexOfFirst { it.key == uri }
            if (idx >= 0) return folder.map { toMediaItem(it) } to idx
        }
        val single = all.firstOrNull { it.key == uri } ?: return null
        return listOf(toMediaItem(single)) to 0
    }
}

/** True when a queue item is audio (song, podcast) and should open the music player. */
fun MediaItem.isAudioItem(): Boolean {
    if (mediaMetadata.extras?.getBoolean("audio") == true) return true
    val mime = localConfiguration?.mimeType
    return mime != null && mime.startsWith("audio/")
}
