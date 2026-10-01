package com.digitalminds.comet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.digitalminds.comet.util.RegionLoader
import java.util.concurrent.Executors
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs

/**
 * Photo view with pinch to zoom, double tap to zoom in / out, and drag while zoomed.
 * When the picture is not zoomed (or you drag past its edge) the swipe goes to the pager,
 * so you move to the next photo.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    var onSingleTap: (() -> Unit)? = null

    private val m = Matrix()
    private val rect = RectF()
    private var zoom = 1f

    private val maxZoom = 8f

    val isZoomed: Boolean get() = zoom > 1.01f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) resetZoom() else zoomBy(2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isZoomed || scaleDetector.isInProgress) return false
            if (abs(distanceX) > abs(distanceY) && !canPan(-distanceX)) {
                // At the edge of the zoomed photo: let the pager take the swipe.
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            m.postTranslate(-distanceX, -distanceY)
            clamp()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    // ---- full quality detail: when zoomed in, the visible part is read again at real resolution
    private var loader: RegionLoader? = null
    private var detail: Bitmap? = null
    private val detailRect = Rect() // where the detail sits, in the picture's own pixels
    private var detailGen = 0
    private val ui = Handler(Looper.getMainLooper())
    private val detailPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val detailTask = Runnable { loadDetail() }

    fun setRegionLoader(l: RegionLoader?) {
        val old = loader
        loader = l
        detail = null
        detailGen++
        ui.removeCallbacks(detailTask)
        if (old != null && old !== l) worker.execute { old.close() }
        invalidate()
        scheduleDetail()
    }

    private fun scheduleDetail() {
        ui.removeCallbacks(detailTask)
        if (loader == null) return
        if (zoom > 1.25f) {
            ui.postDelayed(detailTask, 140)
        } else if (detail != null) {
            detail = null
            invalidate()
        }
    }

    private fun loadDetail() {
        val l = loader ?: return
        val d = drawable ?: return
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        val fw = l.width.toFloat()
        val fh = l.height.toFloat()
        if (bw <= 0f || bh <= 0f || fw <= bw * 1.05f && fh <= bh * 1.05f) return
        val inv = Matrix()
        if (!m.invert(inv)) return
        val vis = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(vis) // now in the small copy's pixels
        val kx = fw / bw
        val ky = fh / bh
        val padX = vis.width() * 0.15f
        val padY = vis.height() * 0.15f
        val region = Rect(
            ((vis.left - padX) * kx).toInt().coerceIn(0, l.width - 1),
            ((vis.top - padY) * ky).toInt().coerceIn(0, l.height - 1),
            ((vis.right + padX) * kx).toInt().coerceIn(1, l.width),
            ((vis.bottom + padY) * ky).toInt().coerceIn(1, l.height)
        )
        if (region.width() < 2 || region.height() < 2) return
        // How many screen pixels each real pixel gets: below 1 we can read every 2nd, 4th... pixel.
        val values = FloatArray(9)
        m.getValues(values)
        val perRealPixel = values[Matrix.MSCALE_X] / kx
        var sample = 1
        while (sample * 2 <= 1f / perRealPixel.coerceAtLeast(0.0001f)) sample *= 2
        val gen = ++detailGen
        worker.execute {
            val bmp = l.decode(region, sample)
            ui.post {
                if (gen == detailGen && bmp != null) {
                    detail = bmp
                    detailRect.set(region)
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = detail ?: return
        val l = loader ?: return
        val d = drawable ?: return
        val kx = d.intrinsicWidth.toFloat() / l.width
        val ky = d.intrinsicHeight.toFloat() / l.height
        rect.set(detailRect.left * kx, detailRect.top * ky, detailRect.right * kx, detailRect.bottom * ky)
        m.mapRect(rect)
        canvas.drawBitmap(bmp, null, rect, detailPaint)
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(detailTask)
        super.onDetachedFromWindow()
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        val base = minOf(vw / dw, vh / dh)
        m.reset()
        m.postScale(base, base)
        m.postTranslate((vw - dw * base) / 2f, (vh - dh * base) / 2f)
        zoom = 1f
        imageMatrix = m
        detail = null
        detailGen++
        ui.removeCallbacks(detailTask)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (zoom * factor).coerceIn(1f, maxZoom)
        val f = target / zoom
        if (f == 1f) return
        m.postScale(f, f, fx, fy)
        zoom = target
        clamp()
    }

    private fun mapped(): RectF? {
        val d = drawable ?: return null
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(rect)
        return rect
    }

    /** Can the photo still move [dx] pixels horizontally without leaving a gap? */
    private fun canPan(dx: Float): Boolean {
        val r = mapped() ?: return false
        return if (dx > 0) r.left < -1f else r.right > width + 1f
    }

    /** Keeps the photo centered when smaller than the screen and edge-to-edge when larger. */
    private fun clamp() {
        val r = mapped() ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dx = when {
            r.width() <= vw -> vw / 2f - r.centerX()
            r.left > 0f -> -r.left
            r.right < vw -> vw - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= vh -> vh / 2f - r.centerY()
            r.top > 0f -> -r.top
            r.bottom < vh -> vh - r.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        imageMatrix = m
        scheduleDetail()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(isZoomed)
        }
        if (event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
