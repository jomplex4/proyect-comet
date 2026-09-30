package com.digitalminds.comet.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitalminds.comet.R
import com.digitalminds.comet.data.AudioRepo
import com.digitalminds.comet.data.Launch
import com.digitalminds.comet.data.Playlists
import com.digitalminds.comet.data.Song
import com.digitalminds.comet.databinding.ActivitySongListBinding
import com.digitalminds.comet.util.Prefs
import java.util.concurrent.Executors

/**
 * Songs of one music folder, or of one playlist. In a playlist, the selection bar takes songs
 * out of the list (files stay); in a folder it deletes the files.
 */
class SongListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var b: ActivitySongListBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: SongAdapter
    private lateinit var mini: MiniPlayer
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var folderId: String? = null
    private var playlistId: String? = null
    private var all: List<Song> = emptyList()
    private var query = ""

    private val deleter = Deleter(this) {
        exitSelection()
        load()
    }

    private val isPlaylist get() = playlistId != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySongListBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        folderId = intent.getStringExtra(EXTRA_FOLDER)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST)
        b.title.text = intent.getStringExtra(EXTRA_TITLE) ?: "Music"

        adapter = SongAdapter(onClick = { onItemClick(it) }, onLongClick = { onItemLongClick(it) })
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null
        mini = MiniPlayer(this, b.mini) { adapter.notifyDataSetChanged() }

        b.btnBack.setOnClickListener { finish() }
        // Playlists keep the order you built; folders can be sorted.
        b.btnSort.visibility = if (isPlaylist) View.GONE else View.VISIBLE
        b.btnSort.setOnClickListener { Dialogs.sort(this, prefs.musicSort()) { load() } }
        b.subtitle.setOnClickListener { playFrom(0) }
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

    private fun load() {
        io.execute {
            val songs = AudioRepo.loadSongs(applicationContext)
            val list = if (isPlaylist) {
                AudioRepo.byKeys(Playlists.get(playlistId ?: "")?.keys ?: emptyList())
            } else {
                AudioRepo.sortSongs(songs.filter { it.bucketId == folderId }, prefs)
            }
            main.post {
                if (isDestroyed) return@post
                all = list
                render()
            }
        }
    }

    private fun visible(): List<Song> =
        if (query.isEmpty()) all
        else all.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.fileName.contains(query, true) }

    private fun render() {
        val list = visible()
        adapter.selection?.retainAll(all.map { it.key }.toSet())
        adapter.submit(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.empty.text = when {
            query.isNotEmpty() -> "No songs match \"$query\""
            isPlaylist -> "This playlist is empty.\nAdd songs from the player with the + icon."
            else -> "This folder is empty"
        }
        val n = all.size
        b.subtitle.text = if (n == 0) "" else "PLAY ALL   " + (if (n == 1) "1 SONG" else "$n SONGS")
        b.subtitle.setTextColor(getColor(if (n == 0) R.color.muted else R.color.red_hot))
        updateSelectionBar()
    }

    private fun playFrom(index: Int) {
        val list = visible()
        if (list.isEmpty()) return
        Launch.pending = list.map { AudioRepo.toMediaItem(it) } to index.coerceIn(0, list.size - 1)
        startActivity(Intent(this, MusicPlayerActivity::class.java))
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

    // ---------------------------------------------------------------- tap / select

    private fun onItemClick(position: Int) {
        val s = visible().getOrNull(position) ?: return
        val sel = adapter.selection
        if (sel != null) {
            if (!sel.add(s.key)) sel.remove(s.key)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyItemChanged(position)
                updateSelectionBar()
            }
            return
        }
        playFrom(position)
    }

    private fun onItemLongClick(position: Int) {
        val s = visible().getOrNull(position) ?: return
        b.list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (adapter.selection == null) adapter.selection = LinkedHashSet()
        adapter.selection?.add(s.key)
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    private fun setupSelection() {
        // In a playlist the action removes from the list; the files are not touched.
        if (isPlaylist) {
            b.btnSelDelete.setImageResource(R.drawable.ic_close)
            b.btnSelDelete.contentDescription = "Remove from playlist"
        }
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
        b.btnSelDelete.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            val id = playlistId
            if (id != null) {
                Playlists.remove(id, sel.toList())
                exitSelection()
                load()
            } else {
                deleter.delete(all.filter { sel.contains(it.key) }.map { it.uri }, "song")
            }
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
