package com.digitalminds.comet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.digitalminds.comet.R
import kotlin.math.sin

/** Three little red bars that bounce while the row's song is playing, still when paused. */
class EqBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.red_hot) }
    private val rect = RectF()

    var playing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.16f
        val barW = (w - gap * 2) / 3f
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until 3) {
            val level = if (playing) {
                (0.35 + 0.65 * (0.5 + 0.5 * sin(t * (5.2 + i * 1.7) + i * 1.3))).toFloat()
            } else {
                floatArrayOf(0.45f, 0.8f, 0.6f)[i]
            }
            val left = i * (barW + gap)
            rect.set(left, h * (1f - level), left + barW, h)
            canvas.drawRoundRect(rect, barW / 3f, barW / 3f, paint)
        }
        if (playing && isShown) postInvalidateOnAnimation()
    }
}
