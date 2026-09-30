package com.digitalminds.comet.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.digitalminds.comet.data.Playlists
import com.digitalminds.comet.util.PositionStore
import com.digitalminds.comet.util.Thumbs
import java.util.concurrent.Executors

/**
 * Deletes media files. On Android 11+ the system shows its own confirmation; on older
 * versions we ask first. Create it as a property of the activity (it registers a result
 * launcher, which Android only allows before the screen starts).
 */
class Deleter(private val activity: ComponentActivity, private val onDeleted: (List<Uri>) -> Unit) {

    private var pending: List<Uri> = emptyList()
    private val main = Handler(Looper.getMainLooper())

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val done = pending
        pending = emptyList()
        if (result.resultCode == Activity.RESULT_OK && done.isNotEmpty()) finish(done)
    }

    /** [what] is how the files are named in messages: "video", "song" or "photo". */
    fun delete(uris: List<Uri>, what: String) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                pending = uris
                val pi = MediaStore.createDeleteRequest(activity.contentResolver, uris)
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                pending = emptyList()
                Toast.makeText(activity, "Could not delete", Toast.LENGTH_SHORT).show()
            }
        } else {
            val label = if (uris.size == 1) "this $what" else "${uris.size} ${what}s"
            Dialogs.confirm(activity, "Delete", "Delete $label permanently?", "Delete") {
                Executors.newSingleThreadExecutor().execute {
                    val done = uris.filter {
                        try { activity.contentResolver.delete(it, null, null) > 0 } catch (e: Exception) { false }
                    }
                    main.post { finish(done) }
                }
            }
        }
    }

    private fun finish(done: List<Uri>) {
        done.forEach {
            val key = it.toString()
            PositionStore.forget(key)
            Playlists.forget(key)
            Thumbs.evict(key)
        }
        if (done.isNotEmpty()) {
            Toast.makeText(activity, if (done.size == 1) "Deleted" else "${done.size} files deleted", Toast.LENGTH_SHORT).show()
        }
        onDeleted(done)
    }
}
