package com.digitalminds.comet.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri

/**
 * Reads a window of a big picture at its real resolution, without loading the whole thing in
 * memory. The photo viewer uses it when you zoom in, so details stay sharp instead of being a
 * blown-up small copy.
 */
class RegionLoader(private val ctx: Context, private val uri: Uri) {

    private var decoder: BitmapRegionDecoder? = null
    private var degrees = 0
    private var rawW = 0
    private var rawH = 0

    /** Picture size as it is shown (after the camera rotation). */
    var width = 0
        private set
    var height = 0
        private set

    /** Blocking; run off the main thread. Returns false when this format cannot be read in parts. */
    fun open(): Boolean {
        return try {
            degrees = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (e: Exception) {
                0
            }
            val stream = ctx.contentResolver.openInputStream(uri) ?: return false
            @Suppress("DEPRECATION")
            val d = stream.use { BitmapRegionDecoder.newInstance(it, false) } ?: return false
            decoder = d
            rawW = d.width
            rawH = d.height
            width = if (degrees == 90 || degrees == 270) rawH else rawW
            height = if (degrees == 90 || degrees == 270) rawW else rawH
            width > 0 && height > 0
        } catch (e: Throwable) {
            false
        }
    }

    /** [shown] is in displayed coordinates. [sample] is 1, 2, 4... (higher = smaller bitmap). */
    fun decode(shown: Rect, sample: Int): Bitmap? {
        val d = decoder ?: return null
        val raw = when (degrees) {
            90 -> Rect(shown.top, rawH - shown.right, shown.bottom, rawH - shown.left)
            180 -> Rect(rawW - shown.right, rawH - shown.bottom, rawW - shown.left, rawH - shown.top)
            270 -> Rect(rawW - shown.bottom, shown.left, rawW - shown.top, shown.right)
            else -> Rect(shown)
        }
        raw.left = raw.left.coerceIn(0, rawW - 1)
        raw.top = raw.top.coerceIn(0, rawH - 1)
        raw.right = raw.right.coerceIn(raw.left + 1, rawW)
        raw.bottom = raw.bottom.coerceIn(raw.top + 1, rawH)
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val bmp = synchronized(d) { d.decodeRegion(raw, opts) } ?: return null
            if (degrees == 0) bmp else {
                val m = Matrix().apply { postRotate(degrees.toFloat()) }
                val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (turned !== bmp) bmp.recycle()
                turned
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        val d = decoder ?: return
        decoder = null
        try {
            synchronized(d) { d.recycle() }
        } catch (e: Throwable) {
            // Already gone.
        }
    }
}
