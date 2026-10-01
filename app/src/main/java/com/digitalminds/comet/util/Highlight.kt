package com.digitalminds.comet.util

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan

/** Paints the part of a name that matches the search with a soft translucent grey. */
object Highlight {
    private val SOFT_GREY = Color.argb(110, 150, 152, 160)

    fun text(@Suppress("UNUSED_PARAMETER") ctx: Context, text: String, query: String): CharSequence {
        if (query.isEmpty() || text.isEmpty()) return text
        val out = SpannableString(text)
        var from = 0
        while (true) {
            val i = text.indexOf(query, from, ignoreCase = true)
            if (i < 0) break
            out.setSpan(BackgroundColorSpan(SOFT_GREY), i, i + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            from = i + query.length
        }
        return out
    }
}
