package com.digitalminds.comet.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {
    fun duration(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    /** Compact date like the one in the lists: "9-29", or "2025-9-29" for other years. */
    fun shortDate(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val c = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        val now = Calendar.getInstance()
        val md = "${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}"
        return if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) md else "${c.get(Calendar.YEAR)}-$md"
    }

    fun fullDate(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "Unknown"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochSeconds * 1000))
    }

    fun stripExtension(name: String): String {
        val i = name.lastIndexOf(".")
        return if (i > 0) name.substring(0, i) else name
    }

    fun speed(s: Float): String {
        val t = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')
        return "${t}X"
    }
}
