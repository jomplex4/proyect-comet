package com.digitalminds.comet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.digitalminds.comet.R
import com.digitalminds.comet.data.Photo
import com.digitalminds.comet.data.PhotoRepo
import com.digitalminds.comet.databinding.ActivityPhotoViewerBinding
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.Thumbs
import java.util.concurrent.Executors

/** Full screen photos: swipe left / right, pinch or double tap to zoom, tap to show the bars. */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
    }

    private lateinit var b: ActivityPhotoViewerBinding
    private lateinit var layoutManager: LinearLayoutManager
    private val snap = PagerSnapHelper()
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private var photos: MutableList<Photo> = ArrayList()
    private var current = 0
    private var barsShown = true
    private var maxSide = 2048

    private val full = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtMost(64 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    private val deleter = Deleter(this) { deleted -> onDeleted(deleted.map { it.toString() }.toSet()) }

    private inner class PageVH(val page: ZoomImageView) : RecyclerView.ViewHolder(page) {
        var boundKey: String? = null
    }

    private val adapter = object : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_page, parent, false) as ZoomImageView
            v.onSingleTap = { toggleBars() }
            return PageVH(v)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val p = photos[position]
            holder.boundKey = p.key
            val cached = full.get(p.key)
            if (cached != null) {
                holder.page.tag = null
                holder.page.setImageBitmap(cached)
                return
            }
            // Quick small version first, sharp one when ready.
            Thumbs.load(holder.page, p.uri, 512, 512)
            io.execute {
                val bmp = decode(p.uri)
                main.post {
                    if (bmp != null) full.put(p.key, bmp)
                    if (holder.boundKey == p.key && bmp != null) {
                        holder.page.tag = null // a late small preview must not replace the sharp one
                        holder.page.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val dm = resources.displayMetrics
        maxSide = (maxOf(dm.widthPixels, dm.heightPixels) * 1.5f).toInt().coerceIn(1280, 3072)

        setupWindow()

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            photos = mutableListOf(externalPhoto(intent.data!!))
            current = 0
        } else {
            photos = PhotoRepo.viewerList.toMutableList()
            current = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        }
        if (photos.isEmpty()) {
            finish()
            return
        }

        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.pager.layoutManager = layoutManager
        b.pager.adapter = adapter
        b.pager.itemAnimator = null
        snap.attachToRecyclerView(b.pager)
        b.pager.scrollToPosition(current)
        b.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val v = snap.findSnapView(layoutManager) ?: return
                val pos = layoutManager.getPosition(v)
                if (pos != current) {
                    current = pos
                    // Leaving a zoomed photo: show it whole next time.
                    for (i in 0 until recyclerView.childCount) {
                        val child = recyclerView.getChildAt(i)
                        if (child !== v && child is ZoomImageView) child.resetZoom()
                    }
                    updateBars()
                }
            }
        })

        b.btnBack.setOnClickListener { finish() }
        b.btnShare.setOnClickListener { share() }
        b.btnDelete.setOnClickListener {
            val p = photos.getOrNull(current) ?: return@setOnClickListener
            if (p.id < 0) {
                Toast.makeText(this, "This photo belongs to another app", Toast.LENGTH_SHORT).show()
            } else {
                deleter.delete(listOf(p.uri), "photo")
            }
        }
        b.btnProperties.setOnClickListener { showProperties() }
        updateBars()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        b.pager.post { b.pager.scrollToPosition(current) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !barsShown) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---------------------------------------------------------------- window / bars

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val d = resources.displayMetrics.density
            b.topBar.setPadding(
                (4 * d).toInt() + maxOf(bars.left, cut.left), maxOf(bars.top, cut.top),
                (16 * d).toInt() + maxOf(bars.right, cut.right), (18 * d).toInt()
            )
            b.bottomBar.setPadding(
                maxOf(bars.left, cut.left), (26 * d).toInt(),
                maxOf(bars.right, cut.right), (14 * d).toInt() + maxOf(bars.bottom, cut.bottom)
            )
            insets
        }
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, b.root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, b.root).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleBars() {
        barsShown = !barsShown
        if (barsShown) {
            showSystemBars()
            b.topBar.visibility = View.VISIBLE
            b.bottomBar.visibility = View.VISIBLE
            b.topBar.animate().alpha(1f).setDuration(150).start()
            b.bottomBar.animate().alpha(1f).setDuration(150).start()
        } else {
            hideSystemBars()
            b.topBar.animate().alpha(0f).setDuration(150).withEndAction { if (!barsShown) b.topBar.visibility = View.GONE }.start()
            b.bottomBar.animate().alpha(0f).setDuration(150).withEndAction { if (!barsShown) b.bottomBar.visibility = View.GONE }.start()
        }
    }

    private fun updateBars() {
        val p = photos.getOrNull(current) ?: return
        b.title.text = p.name
        b.counter.text = "${current + 1} / ${photos.size}"
    }

    // ---------------------------------------------------------------- decoding

    /** Full picture, rotated the right way (camera EXIF), limited to about 1.5x the screen. */
    private fun decode(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = maxOf(w, h).toFloat() / maxSide
                    if (scale > 1f) decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = Thumbs.sample(bounds.outWidth, bounds.outHeight, maxSide / 2, maxSide / 2)
                }
                val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
                val degrees = contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
                if (degrees == 0f) raw else {
                    val m = Matrix().apply { postRotate(degrees) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- actions

    private fun externalPhoto(uri: Uri): Photo {
        var name = uri.lastPathSegment ?: "Photo"
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iName >= 0) c.getString(iName)?.let { name = it }
                    if (iSize >= 0) size = c.getLong(iSize)
                }
            }
        } catch (e: Exception) {
            // The name from the link is enough.
        }
        return Photo(-1, uri, name, "", "", size, 0, 0, 0, 0, 0, uri.toString())
    }

    private fun share() {
        val p = photos.getOrNull(current) ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, p.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to share", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleted(keys: Set<String>) {
        val before = current
        photos.removeAll { keys.contains(it.key) }
        if (photos.isEmpty()) {
            finish()
            return
        }
        current = before.coerceAtMost(photos.size - 1)
        adapter.notifyDataSetChanged()
        b.pager.scrollToPosition(current)
        updateBars()
    }

    private fun showProperties() {
        val p = photos.getOrNull(current) ?: return
        val rows = ArrayList<Pair<String, String>>()
        rows.add("Name" to p.name)
        rows.add("Location" to p.path.ifEmpty { p.uri.toString() })
        if (p.size > 0) rows.add("Size" to Format.size(p.size))
        var w = p.width
        var h = p.height
        var camera = ""
        var taken = ""
        try {
            contentResolver.openInputStream(p.uri)?.use {
                val exif = ExifInterface(it)
                if (w <= 0) w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                if (h <= 0) h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                camera = if (model.startsWith(make, true)) model else "$make $model".trim()
                taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ""
            }
        } catch (e: Exception) {
            // Not every picture has EXIF data.
        }
        if (w > 0 && h > 0) rows.add("Resolution" to "$w x $h")
        when {
            p.dateTaken > 0 -> rows.add("Date taken" to Format.fullDate(p.dateTaken / 1000))
            taken.isNotEmpty() -> rows.add("Date taken" to taken)
            p.dateModified > 0 -> rows.add("Date" to Format.fullDate(p.dateModified))
        }
        if (camera.isNotEmpty()) rows.add("Camera" to camera)
        Dialogs.properties(this, rows) { if (!barsShown) hideSystemBars() }
    }
}
