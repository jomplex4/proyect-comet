package com.digitalminds.comet.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.digitalminds.comet.R
import com.digitalminds.comet.data.Playlists
import com.digitalminds.comet.util.Prefs

data class MenuEntry(val iconRes: Int, val label: String, val action: () -> Unit)

object Dialogs {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun row(ctx: Context, label: String, iconRes: Int, checked: Boolean?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 14))
            setOnClickListener { onClick() }
        }
        if (iconRes != 0) {
            row.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                alpha = 0.85f
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 14) }
            })
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (checked != null) {
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(ctx.getColor(R.color.red_hot))
                visibility = if (checked) View.VISIBLE else View.INVISIBLE
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20))
            })
        }
        return row
    }

    // ---------------------------------------------------------------- sort

    /** Sort options (videos, music or photos: each keeps its own setting). */
    fun sort(ctx: Context, spec: Prefs.SortSpec, onChanged: () -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 4))
        }
        val medium = ResourcesCompat.getFont(ctx, R.font.space_grotesk_medium)
        val options = listOf(
            Prefs.SORT_ADDED to "Date added",
            Prefs.SORT_MODIFIED to "Date modified",
            Prefs.SORT_NAME to "Name"
        )
        val rows = ArrayList<LinearLayout>()
        fun paintRows() {
            rows.forEachIndexed { i, r ->
                r.getChildAt(r.childCount - 1).visibility = if (options[i].first == spec.key()) View.VISIBLE else View.INVISIBLE
            }
        }
        options.forEach { (key, label) ->
            val r = row(ctx, label, 0, spec.key() == key) {
                spec.setKey(key)
                paintRows()
                onChanged()
            }
            rows.add(r)
            root.addView(r)
        }

        root.addView(TextView(ctx).apply {
            text = "Order"
            setTextColor(ctx.getColor(R.color.muted))
            textSize = 13f
            typeface = medium
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 8))
        })

        val pills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 8))
        }
        fun pill(label: String): TextView = TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = medium
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f)
        }
        val asc = pill("Ascending")
        val desc = pill("Descending").apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(ctx, 10)
        }
        fun paint() {
            asc.setBackgroundResource(if (spec.ascending()) R.drawable.bg_pill_on else R.drawable.bg_pill_dark)
            desc.setBackgroundResource(if (!spec.ascending()) R.drawable.bg_pill_on else R.drawable.bg_pill_dark)
        }
        paint()
        asc.setOnClickListener { spec.setAscending(true); paint(); onChanged() }
        desc.setOnClickListener { spec.setAscending(false); paint(); onChanged() }
        pills.addView(asc)
        pills.addView(desc)
        root.addView(pills)

        AlertDialog.Builder(ctx, R.style.Theme_Comet_Dialog)
            .setTitle("Sort by")
            .setView(root)
            .setPositiveButton("Done", null)
            .show()
    }

    // ---------------------------------------------------------------- sleep timer

    /**
     * [onPick]: minutes (0 = off), or -1 for "end of the current file".
     * [endLabel] is "End of video" or "End of song".
     */
    fun sleepTimer(ctx: Context, remainingText: String?, endLabel: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
        }
        if (remainingText != null) {
            root.addView(TextView(ctx).apply {
                text = remainingText
                setTextColor(ctx.getColor(R.color.red_hot))
                textSize = 13.5f
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 8))
            })
        }
        var dialog: AlertDialog? = null
        val options = listOf(
            0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes",
            60 to "1 hour", 90 to "1 hour 30 minutes", -1 to endLabel
        )
        options.forEach { (value, label) ->
            root.addView(row(ctx, label, 0, null) {
                onPick(value)
                dialog?.dismiss()
            })
        }
        root.addView(TextView(ctx).apply {
            text = "The sound fades out during the last 5 seconds."
            setTextColor(ctx.getColor(R.color.muted))
            textSize = 12f
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 4))
        })
        dialog = AlertDialog.Builder(ctx, R.style.Theme_Comet_Dialog)
            .setTitle("Sleep Timer")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setOnDismissListener { onDismiss() }
            .show()
    }

    // ---------------------------------------------------------------- playlists

    fun addToPlaylist(ctx: Context, key: String, onDone: (String) -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4))
        }
        var dialog: AlertDialog? = null
        root.addView(row(ctx, "New playlist", R.drawable.ic_add, null) {
            dialog?.dismiss()
            input(ctx, "New playlist", "", "Create") { name ->
                val p = Playlists.create(name)
                Playlists.add(p.id, key)
                onDone(p.name)
            }
        })
        Playlists.all().forEach { p ->
            val inside = p.keys.contains(key)
            root.addView(row(ctx, p.name, if (p.isFavorites) R.drawable.ic_heart_filled else R.drawable.ic_playlist, inside) {
                if (!inside) {
                    Playlists.add(p.id, key)
                    onDone(p.name)
                }
                dialog?.dismiss()
            })
        }
        dialog = AlertDialog.Builder(ctx, R.style.Theme_Comet_Dialog)
            .setTitle("Add to playlist")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun input(ctx: Context, title: String, initial: String, action: String, onOk: (String) -> Unit) {
        val field = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            setTextColor(Color.WHITE)
            setHintTextColor(ctx.getColor(R.color.muted))
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        val box = LinearLayout(ctx).apply {
            setPadding(dp(ctx, 22), dp(ctx, 8), dp(ctx, 22), 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(ctx, R.style.Theme_Comet_Dialog)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .show()
        field.requestFocus()
    }

    // ---------------------------------------------------------------- generic

    /** Simple label / value table used by Properties. */
    fun properties(ctx: Context, rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 4), dp(ctx, 24), dp(ctx, 8))
        }
        rows.forEach { (label, value) ->
            list.addView(TextView(ctx).apply {
                text = label
                setTextColor(ctx.getColor(R.color.muted))
                textSize = 12f
                setPadding(0, dp(ctx, 10), 0, 0)
            })
            list.addView(TextView(ctx).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 14.5f
                setTextIsSelectable(true)
            })
        }
        AlertDialog.Builder(ctx, R.style.Theme_Comet_Dialog)
            .setTitle("Properties")
            .setView(ScrollView(ctx).apply { addView(list) })
            .setPositiveButton("Close", null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    fun confirm(ctx: Context, title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx, R.style.Theme_Comet_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ -> onYes() }
            .show()
    }

    /** Small dark dropdown (icon + label rows), used for a playlist's Rename / Delete. */
    fun menu(anchor: View, entries: List<MenuEntry>) {
        val ctx = anchor.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
            elevation = dp(ctx, 14).toFloat()
        }
        val popup = PopupWindow(container, dp(ctx, 200), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        entries.forEach { e ->
            container.addView(row(ctx, e.label, e.iconRes, null) {
                popup.dismiss()
                e.action()
            })
        }
        popup.showAsDropDown(anchor, 0, dp(ctx, 4), Gravity.END)
    }
}
