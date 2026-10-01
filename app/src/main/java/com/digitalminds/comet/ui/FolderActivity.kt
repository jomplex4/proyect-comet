package com.digitalminds.comet.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitalminds.comet.R
import com.digitalminds.comet.data.Launch
import com.digitalminds.comet.data.MediaRepo
import com.digitalminds.comet.data.Video
import com.digitalminds.comet.databinding.ActivityFolderBinding
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.PositionStore
import com.digitalminds.comet.util.Prefs
import com.digitalminds.comet.util.Thumbs
import java.util.concurrent.Executors

class FolderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BUCKET = "bucket"
        const val EXTRA_NAME = "name"
    }

    private lateinit var b: ActivityFolderBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: VideoAdapter
    private lateinit var mini: MiniPlayer
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var bucketId = ""
    private var all: List<Video> = emptyList()
    private var query = ""
    private var pendingDelete: List<Video> = emptyList()

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) afterDelete(pendingDelete)
            pendingDelete = emptyList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFolderBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        bucketId = intent.getStringExtra(EXTRA_BUCKET) ?: ""
        b.title.text = intent.getStringExtra(EXTRA_NAME) ?: "Folder"

        mini = MiniPlayer(this, b.mini)
        adapter = VideoAdapter(onClick = { onItemClick(it) }, onLongClick = { onItemLongClick(it) })
        b.list.adapter = adapter
        b.list.itemAnimator = null
        applyViewMode()

        b.btnBack.setOnClickListener { finish() }
        b.btnView.setOnClickListener {
            prefs.gridView = !prefs.gridView
            applyViewMode()
        }
        setupSearch()
        setupSelection()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    adapter.selection != null -> exitSelection()
                    b.searchBar.visibility == View.VISIBLE -> closeSearch()
                    else -> finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onStart() {
        super.onStart()
        mini.start()
    }

    override fun onStop() {
        mini.stop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun load() {
        io.execute {
            val list = MediaRepo.loadVideos(applicationContext).filter { it.bucketId == bucketId }
            main.post {
                if (isDestroyed) return@post
                all = MediaRepo.sortVideos(list, prefs)
                render()
            }
        }
    }

    private fun visible(): List<Video> =
        if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) }

    private fun render() {
        val list = visible()
        adapter.selection?.retainAll(all.map { it.key }.toSet())
        adapter.query = query
        adapter.submit(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val total = all.sumOf { it.size }
        val count = if (all.size == 1) "1 VIDEO" else "${all.size} VIDEOS"
        b.subtitle.text = "$count   ${Format.size(total)}"
        if (all.isEmpty() && query.isEmpty()) b.empty.text = "This folder is empty"
        updateSelectionBar()
    }

    private fun applyViewMode() {
        adapter.grid = prefs.gridView
        b.list.layoutManager = if (prefs.gridView) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        if (prefs.gridView) b.list.setPadding(10, 0, 10, b.list.paddingBottom) else b.list.setPadding(0, 0, 0, b.list.paddingBottom)
        // The icon shows the view you switch TO.
        b.btnView.setImageResource(if (prefs.gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- search

    private fun setupSearch() {
        b.btnSearch.setOnClickListener {
            b.header.visibility = View.GONE
            b.searchBar.visibility = View.VISIBLE
            b.searchInput.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(b.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
        b.btnSearchBack.setOnClickListener { closeSearch() }
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                render()
            }
        })
    }

    private fun closeSearch() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        b.searchInput.setText("")
        query = ""
        b.searchBar.visibility = View.GONE
        b.header.visibility = View.VISIBLE
        render()
    }

    // ---------------------------------------------------------------- play / select

    private fun onItemClick(position: Int) {
        val list = visible()
        val v = list.getOrNull(position) ?: return
        val sel = adapter.selection
        if (sel != null) {
            if (!sel.add(v.key)) sel.remove(v.key)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyItemChanged(position)
                updateSelectionBar()
            }
            return
        }
        Launch.pending = list.map { MediaRepo.toMediaItem(it) } to position
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun onItemLongClick(position: Int) {
        val v = visible().getOrNull(position) ?: return
        b.list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (adapter.selection == null) adapter.selection = LinkedHashSet()
        adapter.selection?.add(v.key)
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun setupSelection() {
        b.btnSelClose.setOnClickListener { exitSelection() }
        b.btnSelAll.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            val keys = visible().map { it.key }
            if (sel.containsAll(keys)) sel.clear() else sel.addAll(keys)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyDataSetChanged()
                updateSelectionBar()
            }
        }
        b.btnSelDelete.setOnClickListener { deleteSelected() }
        b.btnSelShare.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            SelectionTools.share(this, all.filter { sel.contains(it.key) }.map { it.uri }, "video/*")
        }
        b.btnSelInfo.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            val chosen = all.filter { sel.contains(it.key) }
            SelectionTools.summary(
                this, "video", chosen.map { it.name }, chosen.size,
                chosen.sumOf { it.size }, chosen.sumOf { it.durationMs }, false
            )
        }
    }

    private fun exitSelection() {
        adapter.selection = null
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        val sel = adapter.selection
        b.selectionBar.visibility = if (sel == null) View.GONE else View.VISIBLE
        if (sel != null) b.selCount.text = "${sel.size} selected"
    }

    // ---------------------------------------------------------------- delete

    private fun deleteSelected() {
        val sel = adapter.selection ?: return
        val targets = all.filter { sel.contains(it.key) }
        if (targets.isEmpty()) return
        val uris: List<Uri> = targets.map { it.uri }
        if (Build.VERSION.SDK_INT >= 30) {
            // Android shows its own confirmation dialog here.
            try {
                pendingDelete = targets
                val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                pendingDelete = emptyList()
                Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show()
            }
        } else {
            val label = if (targets.size == 1) "this video" else "${targets.size} videos"
            Dialogs.confirm(this, "Delete", "Delete $label permanently?", "Delete") {
                io.execute {
                    val done = targets.filter {
                        try { contentResolver.delete(it.uri, null, null) > 0 } catch (e: Exception) { false }
                    }
                    main.post { afterDelete(done) }
                }
            }
        }
    }

    private fun afterDelete(deleted: List<Video>) {
        deleted.forEach {
            PositionStore.forget(it.key)
            Thumbs.evict(it.key)
        }
        if (deleted.isNotEmpty()) {
            val n = deleted.size
            Toast.makeText(this, if (n == 1) "1 video deleted" else "$n videos deleted", Toast.LENGTH_SHORT).show()
        }
        exitSelection()
        load()
    }
}
