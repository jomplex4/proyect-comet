package com.digitalminds.comet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitalminds.comet.R
import com.digitalminds.comet.data.AudioRepo
import com.digitalminds.comet.data.Folder
import com.digitalminds.comet.data.Launch
import com.digitalminds.comet.data.MediaRepo
import com.digitalminds.comet.data.MusicFolder
import com.digitalminds.comet.data.Photo
import com.digitalminds.comet.data.PhotoFolder
import com.digitalminds.comet.data.PhotoRepo
import com.digitalminds.comet.data.Playlist
import com.digitalminds.comet.data.Playlists
import com.digitalminds.comet.data.Song
import com.digitalminds.comet.data.Video
import com.digitalminds.comet.databinding.ActivityMainBinding
import com.digitalminds.comet.databinding.ViewNavItemBinding
import com.digitalminds.comet.util.HistoryEntry
import com.digitalminds.comet.util.PositionStore
import com.digitalminds.comet.util.Prefs
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), MainActions {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: MainAdapter
    private lateinit var mini: MiniPlayer
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var videos: List<Video> = emptyList()
    private var songs: List<Song> = emptyList()
    private var photos: List<Photo> = emptyList()

    /** The songs currently listed (all songs sorted, or search results): what a tap plays. */
    private var shownSongs: List<Song> = emptyList()

    private var currentTab = TAB_VIDEOS
    private var query = ""
    private var loadedOnce = false
    private var askedCount = 0

    private val deleter = Deleter(this) {
        exitSelection()
        reload()
    }

    companion object {
        private const val TAB_VIDEOS = 0
        private const val TAB_MUSIC = 1
        private const val TAB_PHOTOS = 2
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        if (savedInstanceState == null) playSplash()

        adapter = MainAdapter(this)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        mini = MiniPlayer(this, b.mini) { adapter.notifyDataSetChanged() }
        setupTabs()
        setupSearch()
        setupSelection()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    adapter.selection != null -> exitSelection()
                    b.searchBar.visibility == View.VISIBLE -> closeSearch()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        if (!hasAnyPermission()) requestPermissions() else reload()
    }

    override fun onStart() {
        super.onStart()
        mini.start()
    }

    override fun onResume() {
        super.onResume()
        // Back from a folder or a player: refresh progress bars, history and new files.
        if (loadedOnce && hasAnyPermission()) reload()
    }

    override fun onStop() {
        mini.stop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---------------------------------------------------------------- splash

    private fun playSplash() {
        b.splash.visibility = View.VISIBLE
        b.splashIcon.scaleX = 0.86f
        b.splashIcon.scaleY = 0.86f
        b.splashIcon.alpha = 0f
        b.splashBrand.alpha = 0f
        b.splashIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).start()
        b.splashBrand.animate().alpha(1f).setStartDelay(160).setDuration(380).start()
        main.postDelayed({
            b.splash.animate().alpha(0f).setDuration(260).withEndAction {
                b.splash.visibility = View.GONE
            }.start()
        }, 1100)
    }

    // ---------------------------------------------------------------- bottom menu

    private fun tabs(): List<Triple<ViewNavItemBinding, String, Int>> = listOf(
        Triple(b.navVideos, "Videos", R.drawable.ic_nav_video),
        Triple(b.navMusic, "Music", R.drawable.ic_music_note),
        Triple(b.navPhotos, "Photos", R.drawable.ic_nav_photo)
    )

    private fun setupTabs() {
        tabs().forEachIndexed { i, (tab, label, icon) ->
            tab.navLabel.text = label
            tab.navIcon.setImageResource(icon)
            tab.root.setOnClickListener { selectTab(i) }
        }
        selectTab(TAB_VIDEOS)
    }

    private fun selectTab(i: Int) {
        if (adapter.selection != null) exitSelection()
        val changed = currentTab != i
        currentTab = i
        tabs().forEachIndexed { index, (tab, _, _) -> tab.root.isSelected = index == i }
        if (changed && b.searchBar.visibility == View.VISIBLE) closeSearch()
        b.btnSearch.visibility = if (i == TAB_PHOTOS) View.INVISIBLE else View.VISIBLE
        b.searchInput.hint = if (i == TAB_MUSIC) "Search songs" else "Search videos"
        render()
        if (changed) b.list.scrollToPosition(0)
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
                adapter.query = query
                render()
            }
        })
    }

    private fun closeSearch() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        b.searchInput.setText("")
        query = ""
        adapter.query = ""
        b.searchBar.visibility = View.GONE
        b.header.visibility = View.VISIBLE
        render()
    }

    // ---------------------------------------------------------------- permissions

    private fun permFor(tab: Int): String = when {
        Build.VERSION.SDK_INT < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
        tab == TAB_MUSIC -> Manifest.permission.READ_MEDIA_AUDIO
        tab == TAB_PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
        else -> Manifest.permission.READ_MEDIA_VIDEO
    }

    private fun has(perm: String) = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyPermission() = listOf(TAB_VIDEOS, TAB_MUSIC, TAB_PHOTOS).any { has(permFor(it)) }

    private fun requestPermissions() {
        askedCount++
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 29) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings > Apps > COMET > Permissions", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        io.execute {
            val v = if (has(permFor(TAB_VIDEOS))) MediaRepo.loadVideos(applicationContext) else emptyList()
            val s = if (has(permFor(TAB_MUSIC))) AudioRepo.loadSongs(applicationContext) else emptyList()
            val p = if (has(permFor(TAB_PHOTOS))) PhotoRepo.loadPhotos(applicationContext) else emptyList()
            main.post {
                if (isDestroyed) return@post
                videos = v
                songs = s
                photos = p
                loadedOnce = true
                render()
            }
        }
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        if (!has(permFor(currentTab))) {
            val what = when (currentTab) {
                TAB_MUSIC -> "your music"
                TAB_PHOTOS -> "your photos"
                else -> "your videos"
            }
            showMessage("COMET needs access to $what to list them.", "Allow access") {
                if (askedCount == 0 || shouldShowRequestPermissionRationale(permFor(currentTab))) requestPermissions()
                else openAppSettings()
            }
            return
        }
        val rows = when (currentTab) {
            TAB_MUSIC -> musicRows()
            TAB_PHOTOS -> photoRows()
            else -> videoRows()
        } ?: return
        b.messageBox.visibility = View.GONE
        b.list.visibility = View.VISIBLE
        adapter.submit(rows)
    }

    private fun videoRows(): List<MainRow>? {
        val rows = ArrayList<MainRow>()
        if (query.isNotEmpty()) {
            val found = MediaRepo.sortVideos(videos.filter { it.name.contains(query, ignoreCase = true) }, prefs)
            if (found.isEmpty()) {
                showMessage("No videos match \"$query\"", null, null)
                return null
            }
            found.forEach { rows.add(MainRow.VideoRow(it)) }
            return rows
        }
        val known = videos.map { it.key }.toHashSet()
        val history = PositionStore.history().filter { it.isVideo && known.contains(it.uri) }
        if (history.isNotEmpty()) rows.add(MainRow.History(history))
        val folders = MediaRepo.folders(videos, prefs)
        if (folders.isEmpty() && loadedOnce) {
            showMessage("No videos found on this phone yet.", null, null)
            return null
        }
        rows.add(MainRow.Header(if (folders.size == 1) "1 FOLDER" else "${folders.size} FOLDERS"))
        folders.forEach { rows.add(MainRow.FolderRow(it)) }
        return rows
    }

    private fun musicRows(): List<MainRow> {
        val rows = ArrayList<MainRow>()
        if (query.isNotEmpty()) {
            shownSongs = AudioRepo.sortSongs(
                songs.filter {
                    it.title.contains(query, true) || it.artist.contains(query, true) || it.fileName.contains(query, true)
                }, prefs
            )
            rows.add(MainRow.MusicHeader(MainRow.HEADER_SONGS, "Play All", shownSongs.size.toString()))
            shownSongs.forEach { rows.add(MainRow.SongRow(it)) }
            return rows
        }
        // Recently played audio: only long ones (podcasts, talks), like the rule you set.
        val known = songs.map { it.key }.toHashSet()
        val history = PositionStore.history().filter { !it.isVideo && known.contains(it.uri) }
        if (history.isNotEmpty()) rows.add(MainRow.History(history))

        val section = prefs.musicSection
        rows.add(MainRow.Segment(section))
        when (section) {
            1 -> {
                val folders = AudioRepo.folders(songs, prefs)
                rows.add(MainRow.MusicHeader(MainRow.HEADER_FOLDERS, if (folders.size == 1) "1 Folder" else "${folders.size} Folders", ""))
                folders.forEach { rows.add(MainRow.MusicFolderRow(it)) }
            }
            2 -> {
                val lists = Playlists.all()
                val byKey = songs.associateBy { it.key }
                rows.add(MainRow.MusicHeader(MainRow.HEADER_PLAYLISTS, if (lists.size == 1) "1 Playlist" else "${lists.size} Playlists", ""))
                lists.forEach { p ->
                    rows.add(MainRow.PlaylistRow(p, p.keys.firstNotNullOfOrNull { byKey[it] }))
                }
            }
            else -> {
                shownSongs = AudioRepo.sortSongs(songs, prefs)
                rows.add(MainRow.MusicHeader(MainRow.HEADER_SONGS, "Play All", shownSongs.size.toString()))
                shownSongs.forEach { rows.add(MainRow.SongRow(it)) }
            }
        }
        return rows
    }

    private fun photoRows(): List<MainRow>? {
        val folders = PhotoRepo.folders(photos, prefs)
        if (folders.isEmpty() && loadedOnce) {
            showMessage("No photos found on this phone yet.", null, null)
            return null
        }
        val rows = ArrayList<MainRow>()
        rows.add(MainRow.Header(if (folders.size == 1) "1 ALBUM" else "${folders.size} ALBUMS"))
        folders.forEach { rows.add(MainRow.PhotoFolderRow(it)) }
        return rows
    }

    private fun showMessage(text: String, action: String?, onAction: (() -> Unit)?) {
        b.list.visibility = View.GONE
        b.messageBox.visibility = View.VISIBLE
        b.messageText.text = text
        if (action != null && onAction != null) {
            b.messageAction.visibility = View.VISIBLE
            b.messageAction.text = action
            b.messageAction.setOnClickListener { onAction() }
        } else {
            b.messageAction.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- selection (songs)

    /** What the current selection holds: songs, or folders of videos, music or photos. */
    private fun selectionKind(): Char? = adapter.selection?.firstOrNull()?.let { if (it.length > 1 && it[1] == ':') it[0] else 's' }

    private fun setupSelection() {
        b.btnSelClose.setOnClickListener { exitSelection() }
        b.btnSelAll.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            val kind = selectionKind() ?: return@setOnClickListener
            val keys = allKeys(kind)
            if (sel.containsAll(keys)) sel.clear() else sel.addAll(keys)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyDataSetChanged()
                updateSelectionBar()
            }
        }
        b.btnSelDelete.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            when (selectionKind()) {
                'v' -> deleter.delete(videos.filter { sel.contains("v:" + it.bucketId) }.map { it.uri }, "video")
                'm' -> deleter.delete(songs.filter { sel.contains("m:" + it.bucketId) }.map { it.uri }, "song")
                'p' -> deleter.delete(photos.filter { sel.contains("p:" + it.bucketId) }.map { it.uri }, "photo")
                else -> deleter.delete(songs.filter { sel.contains(it.key) }.map { it.uri }, "song")
            }
        }
        b.btnSelShare.setOnClickListener {
            val sel = adapter.selection ?: return@setOnClickListener
            SelectionTools.share(this, songs.filter { sel.contains(it.key) }.map { it.uri }, "audio/*")
        }
        b.btnSelInfo.setOnClickListener { showSelectionProperties() }
    }

    private fun allKeys(kind: Char): List<String> = when (kind) {
        'v' -> adapter.rows.mapNotNull { (it as? MainRow.FolderRow)?.let { r -> "v:" + r.folder.id } }
        'm' -> adapter.rows.mapNotNull { (it as? MainRow.MusicFolderRow)?.let { r -> "m:" + r.folder.id } }
        'p' -> adapter.rows.mapNotNull { (it as? MainRow.PhotoFolderRow)?.let { r -> "p:" + r.folder.id } }
        else -> shownSongs.map { it.key }
    }

    /** One Properties table for everything selected together. */
    private fun showSelectionProperties() {
        val sel = adapter.selection ?: return
        when (selectionKind()) {
            'v' -> {
                val chosen = MediaRepo.folders(videos, prefs).filter { sel.contains("v:" + it.id) }
                SelectionTools.summary(
                    this, "video", chosen.map { it.name }, chosen.sumOf { it.count },
                    chosen.sumOf { it.totalSize }, chosen.sumOf { f -> f.videos.sumOf { it.durationMs } }, true
                )
            }
            'm' -> {
                val chosen = AudioRepo.folders(songs, prefs).filter { sel.contains("m:" + it.id) }
                SelectionTools.summary(
                    this, "song", chosen.map { it.name }, chosen.sumOf { it.count },
                    chosen.sumOf { f -> f.songs.sumOf { it.size } }, chosen.sumOf { f -> f.songs.sumOf { it.durationMs } }, true
                )
            }
            'p' -> {
                val chosen = PhotoRepo.folders(photos, prefs).filter { sel.contains("p:" + it.id) }
                SelectionTools.summary(
                    this, "photo", chosen.map { it.name }, chosen.sumOf { it.count },
                    chosen.sumOf { f -> f.photos.sumOf { it.size } }, null, true
                )
            }
            else -> {
                val chosen = songs.filter { sel.contains(it.key) }
                SelectionTools.summary(
                    this, "song", chosen.map { it.title }, chosen.size,
                    chosen.sumOf { it.size }, chosen.sumOf { it.durationMs }, false
                )
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
        b.nav.visibility = if (sel == null) View.VISIBLE else View.GONE
        if (sel != null) {
            val folders = selectionKind().let { it == 'v' || it == 'm' || it == 'p' }
            b.selCount.text = if (folders) "${sel.size} selected" else "${sel.size} selected"
            b.btnSelShare.visibility = if (folders) View.GONE else View.VISIBLE
        }
    }

    private fun songAt(position: Int): Song? = (adapter.rows.getOrNull(position) as? MainRow.SongRow)?.song

    private fun startSelection(key: String) {
        b.list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (adapter.selection == null) adapter.selection = LinkedHashSet()
        adapter.selection?.add(key)
        adapter.notifyDataSetChanged()
        updateSelectionBar()
    }

    /** Tap on a folder while selecting: tick it or untick it. Returns true when it was a selection tap. */
    private fun toggleFolder(key: String): Boolean {
        val sel = adapter.selection ?: return false
        if (!sel.add(key)) sel.remove(key)
        if (sel.isEmpty()) exitSelection() else {
            adapter.notifyDataSetChanged()
            updateSelectionBar()
        }
        return true
    }

    // ---------------------------------------------------------------- MainActions

    override fun onHistory(e: HistoryEntry) {
        if (e.isVideo) {
            val queue = MediaRepo.queueFor(applicationContext, e.uri, e.bucketId, prefs)
            if (queue == null) return gone(e)
            Launch.pending = queue
            startActivity(Intent(this, PlayerActivity::class.java))
        } else {
            val queue = AudioRepo.queueFor(applicationContext, e.uri, e.bucketId, prefs)
            if (queue == null) return gone(e)
            // Resume right where it was, in the little bar and the notification only:
            // the full player does not open.
            mini.play(queue.first, queue.second)
        }
    }

    private fun gone(e: HistoryEntry) {
        Toast.makeText(this, "This file is no longer available", Toast.LENGTH_SHORT).show()
        PositionStore.forget(e.uri)
        render()
    }

    override fun onSort(anchor: View) {
        val spec = if (currentTab == TAB_PHOTOS) prefs.photoSort() else prefs.videoSort()
        Dialogs.sort(this, spec) { render() }
    }

    override fun onVideoFolder(f: Folder) {
        if (toggleFolder("v:" + f.id)) return
        startActivity(
            Intent(this, FolderActivity::class.java)
                .putExtra(FolderActivity.EXTRA_BUCKET, f.id)
                .putExtra(FolderActivity.EXTRA_NAME, f.name)
        )
    }

    override fun onVideo(v: Video) {
        val folder = MediaRepo.sortVideos(videos.filter { it.bucketId == v.bucketId }, prefs)
        val idx = folder.indexOfFirst { it.key == v.key }.coerceAtLeast(0)
        Launch.pending = folder.map { MediaRepo.toMediaItem(it) } to idx
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onSegment(i: Int) {
        if (adapter.selection != null) exitSelection()
        prefs.musicSection = i
        render()
    }

    override fun onMusicHeader(kind: Int) {
        if (kind != MainRow.HEADER_SONGS || shownSongs.isEmpty()) return
        playSongs(shownSongs, 0)
    }

    override fun onMusicHeaderAction(kind: Int, anchor: View) {
        if (kind == MainRow.HEADER_PLAYLISTS) {
            Dialogs.input(this, "New playlist", "", "Create") { name ->
                Playlists.create(name)
                render()
            }
        } else {
            Dialogs.sort(this, prefs.musicSort()) { render() }
        }
    }

    override fun onSong(position: Int) {
        val song = songAt(position) ?: return
        val sel = adapter.selection
        if (sel != null) {
            if (!sel.add(song.key)) sel.remove(song.key)
            if (sel.isEmpty()) exitSelection() else {
                adapter.notifyItemChanged(position)
                updateSelectionBar()
            }
            return
        }
        val idx = shownSongs.indexOfFirst { it.key == song.key }.coerceAtLeast(0)
        playSongs(shownSongs, idx)
    }

    override fun onSongLong(position: Int) {
        val song = songAt(position) ?: return
        if (adapter.selection != null && selectionKind() != 's') return
        startSelection(song.key)
    }

    override fun onFolderLong(key: String) {
        val kind = selectionKind()
        // Do not mix folders with songs, or two kinds of folders, in the same selection.
        if (adapter.selection != null && kind != key[0]) return
        startSelection(key)
    }

    private fun playSongs(list: List<Song>, index: Int) {
        Launch.pending = list.map { AudioRepo.toMediaItem(it) } to index
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    override fun onMusicFolder(f: MusicFolder) {
        if (toggleFolder("m:" + f.id)) return
        startActivity(
            Intent(this, SongListActivity::class.java)
                .putExtra(SongListActivity.EXTRA_FOLDER, f.id)
                .putExtra(SongListActivity.EXTRA_TITLE, f.name)
        )
    }

    override fun onPlaylist(p: Playlist) {
        startActivity(
            Intent(this, SongListActivity::class.java)
                .putExtra(SongListActivity.EXTRA_PLAYLIST, p.id)
                .putExtra(SongListActivity.EXTRA_TITLE, p.name)
        )
    }

    override fun onPlaylistMore(p: Playlist, anchor: View) {
        Dialogs.menu(
            anchor, listOf(
                MenuEntry(R.drawable.ic_pencil, "Rename") {
                    Dialogs.input(this, "Rename playlist", p.name, "Save") { name ->
                        Playlists.rename(p.id, name)
                        render()
                    }
                },
                MenuEntry(R.drawable.ic_trash, "Delete") {
                    Dialogs.confirm(this, "Delete playlist", "Delete \"${p.name}\"? The songs stay on your phone.", "Delete") {
                        Playlists.delete(p.id)
                        render()
                    }
                }
            )
        )
    }

    override fun onPhotoFolder(f: PhotoFolder) {
        if (toggleFolder("p:" + f.id)) return
        startActivity(
            Intent(this, PhotoFolderActivity::class.java)
                .putExtra(PhotoFolderActivity.EXTRA_BUCKET, f.id)
                .putExtra(PhotoFolderActivity.EXTRA_NAME, f.name)
        )
    }
}
