package com.digitalminds.comet.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Lightweight thumbnail loader. For gallery files it asks Android for the thumbnail it already
 * keeps (no decoding of full frames in our process); album art comes from the song itself.
 */
object Thumbs {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtMost(32 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Remembers which files have no artwork so we do not try again on every scroll. */
    private val missing = HashSet<String>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /**
     * Loads a thumbnail into [view]. [fallback] is shown while loading and when the file has
     * no picture (a music note for songs without cover, for example).
     */
    fun load(view: ImageView, uri: Uri, w: Int = 320, h: Int = 180, fallback: Int = 0) {
        val key = "$uri@$w"
        view.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        if (fallback != 0) view.setImageResource(fallback) else view.setImageDrawable(null)
        synchronized(missing) { if (missing.contains(key)) return }
        exec.execute {
            if (view.tag != key) return@execute
            val bmp = fetch(app, uri, w, h)
            if (bmp == null) synchronized(missing) { missing.add(key) }
            main.post {
                if (bmp != null) cache.put(key, bmp)
                if (view.tag == key && bmp != null) view.setImageBitmap(bmp)
            }
        }
    }

    fun evict(uri: String) {
        val prefix = "$uri@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    private fun isAudio(uri: Uri) = uri.toString().contains("/audio/")
    private fun isImage(uri: Uri) = uri.toString().contains("/images/")

    /** Blocking: call off the main thread. */
    fun fetch(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        if (uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29) {
            try {
                return ctx.contentResolver.loadThumbnail(uri, Size(w, h), null)
            } catch (e: Throwable) {
                // No stored thumbnail: fall through to reading the file.
            }
        }
        return try {
            when {
                isImage(uri) -> decodeImage(ctx, uri, w, h)
                isAudio(uri) -> embeddedArt(ctx, uri, w, h)
                uri.authority == MediaStore.AUTHORITY -> {
                    val id = uri.lastPathSegment?.toLongOrNull()
                    @Suppress("DEPRECATION")
                    val t = if (id != null) MediaStore.Video.Thumbnails.getThumbnail(
                        ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    ) else null
                    t ?: frameOf(ctx, uri, w, h)
                }
                else -> frameOf(ctx, uri, w, h) ?: embeddedArt(ctx, uri, w, h)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** Big album art for the music player (not kept in the list cache). */
    fun artwork(ctx: Context, uri: Uri, size: Int): Bitmap? {
        return try {
            embeddedArt(ctx, uri, size, size) ?: fetch(ctx, uri, size, size)
        } catch (e: Throwable) {
            null
        }
    }

    private fun embeddedArt(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val bytes = r.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, w, h) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Throwable) {
            null
        } finally {
            try { r.release() } catch (e: Throwable) { }
        }
    }

    private fun decodeImage(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, w, h) }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun frameOf(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = minOf(w.toFloat() / frame.width, h.toFloat() / frame.height, 1f)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true
                )
                if (scaled != frame) frame.recycle()
                scaled
            } else frame
        } catch (e: Throwable) {
            null
        } finally {
            try { r.release() } catch (e: Throwable) { }
        }
    }

    fun sample(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        if (srcW <= 0 || srcH <= 0) return 1
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2
        return s
    }

    /**
     * Cheap, good-looking blur: shrink the picture a lot and let the view stretch it back with
     * filtering. No RenderScript, works on every Android version.
     */
    fun blurred(src: Bitmap): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val small = Bitmap.createScaledBitmap(soft, 18, 18, true)
        return Bitmap.createScaledBitmap(small, 180, 180, true)
    }
}
