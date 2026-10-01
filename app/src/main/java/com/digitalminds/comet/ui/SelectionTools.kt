package com.digitalminds.comet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.digitalminds.comet.util.Format

/** Things the selection bars share: sending several files, and one combined Properties. */
object SelectionTools {

    fun share(ctx: Context, uris: List<Uri>, mime: String) {
        if (uris.isEmpty()) return
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No app available to share", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * One table for everything selected together: how many folders and files, the total size and,
     * for videos and music, the total time. [names] are folders (or files when no folder is chosen).
     */
    fun summary(
        ctx: Context,
        what: String,
        names: List<String>,
        files: Int,
        size: Long,
        durationMs: Long?,
        folderMode: Boolean,
        onDismiss: () -> Unit = {}
    ) {
        val rows = ArrayList<Pair<String, String>>()
        if (folderMode) rows.add("Folders" to names.size.toString())
        rows.add((what.replaceFirstChar { it.uppercase() } + "s") to files.toString())
        rows.add("Total size" to Format.size(size))
        if (durationMs != null) rows.add("Total time" to Format.durationLong(durationMs))
        val shown = names.take(8).joinToString("\n")
        val more = if (names.size > 8) "\n+ ${names.size - 8} more" else ""
        rows.add((if (folderMode) "Folder names" else "Names") to shown + more)
        Dialogs.properties(ctx, rows, onDismiss)
    }
}
