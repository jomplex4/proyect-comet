package com.digitalminds.comet.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.digitalminds.comet.data.Photo
import com.digitalminds.comet.data.PhotoRepo
import com.digitalminds.comet.databinding.ActivityPhotoFolderBinding
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.Prefs
import java.util.concurrent.Executors

class PhotoFolderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BUCKET = "bucket"
        const val EXTRA_NAME = "name"
    }

    private lateinit var b: ActivityPhotoFolderBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: PhotoAdapter
    private lateinit var mini: MiniPlayer
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var bucketId = ""
    private var all: List<Photo> = emptyList()

    private val deleter = Deleter(this) {
        exitSelection()
        load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhotoFolderBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        bucketId = intent.getStringExtra(EXTRA_BUCKET) ?: ""
        b.title.text = intent.getStringExtra(EXTRA_NAME) ?: "Photos"

        adapter = PhotoAdapter(onClick = { onItemClick(it) }, onLongClick = { onItemLongClick(it) })
        b.list.layoutManager = GridLayoutManager(this, spanCount())
        b.list.adapter = adapter
        b.list.itemAnimator = null
        mini = MiniPlayer(this, b.mini)

        b.btnBack.setOnClickListener { finish() }
        b.btnSort.setOnClickListener { Dialogs.sort(this, prefs.photoSort()) { load() } }
        setupSelection()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selection != null) exitSelection() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        mini.start()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onStop() {
        mini.stop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        b.list.layoutManager = GridLayoutManager(this, spanCount())
    }

    private fun spanCount(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return (widthDp / 118f).toInt().coerceIn(3, 7)
    }

    private fun load() {
        io.execute {
            val list = PhotoRepo.loadPhotos(applicationContext).filter { it.bucketId == bucketId }
            main.post {
                if (isDestroyed) return@post
                all = PhotoRepo.sortPhotos(list, prefs)
                render()
            }
        }
    }

    private fun render() {
        adapter.selection?.retainAll(all.map { it.key }.toSet())
        adapter.submit(all)
        b.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        b.empty.text = "This album is empty"
        val n = all.size
        b.subtitle.text = (if (n == 1) "1 PHOTO" else "$n PHOTOS") + "   " + Format.size(all.sumOf { it.size })
        updateSelectionBar()
    }

    private fun onItemClick(position: Int) {
        val p = all.getOrNull(position) ?: return
        val sel = adapter.selection
        if (sel != null) {
            if (!sel.add(p.key)) sel.remove(p.key)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyItemChanged(position)
                updateSelectionBar()
            }
            return
        }
        PhotoRepo.viewerList = all
        startActivity(Intent(this, PhotoViewerActivity::class.java).putExtra(PhotoViewerActivity.EXTRA_INDEX, position))
    }

    private fun onItemLongClick(position: Int) {
        val p = all.getOrNull(position) ?: return
        b.list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (adapter.selection == null) adapter.selection = LinkedHashSet()
        adapter.selection?.add(p.key)
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun setupSelection() {
        b.btnSelClose.setOnClickListener { exitSelection() }
        b.btnSelAll.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            val keys = all.map { it.key }
            if (sel.containsAll(keys)) sel.clear() else sel.addAll(keys)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyDataSetChanged()
                updateSelectionBar()
            }
        }
        b.btnSelDelete.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            deleter.delete(all.filter { sel.contains(it.key) }.map { it.uri }, "photo")
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
}
