package com.digitalminds.comet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
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

    private val maxZoom = 5f

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
