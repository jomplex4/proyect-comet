package com.digitalminds.comet.ui

import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitalminds.comet.R
import com.digitalminds.comet.data.Folder
import com.digitalminds.comet.data.MusicFolder
import com.digitalminds.comet.data.Photo
import com.digitalminds.comet.data.PhotoFolder
import com.digitalminds.comet.data.Playlist
import com.digitalminds.comet.data.Song
import com.digitalminds.comet.data.Video
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.Highlight
import com.digitalminds.comet.util.HistoryEntry
import com.digitalminds.comet.util.PositionStore
import com.digitalminds.comet.util.Thumbs

private fun bindProgress(bar: ProgressBar, key: String) {
    val p = PositionStore.progress(key)
    if (p < 0f) {
        bar.visibility = View.INVISIBLE
    } else {
        bar.visibility = View.VISIBLE
        bar.progress = (p * 1000).toInt().coerceAtLeast(20)
    }
}

/** Who is playing right now, so lists can paint that row red with the bouncing bars. */
object NowPlaying {
    var key: String? = null
    var playing: Boolean = false
}

// ------------------------------------------------------------------ history strip

class HistoryAdapter(private val onClick: (HistoryEntry) -> Unit) :
    RecyclerView.Adapter<HistoryAdapter.VH>() {

    var items: List<HistoryEntry> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val icon: ImageView = v.findViewById(R.id.icon)
        val duration: TextView = v.findViewById(R.id.duration)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        v.clipToOutline = true
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.duration.text = Format.duration(e.durationMs)
        bindProgress(h.progress, e.uri)
        h.icon.visibility = if (e.isVideo) View.GONE else View.VISIBLE
        Thumbs.load(h.thumb, Uri.parse(e.uri))
        h.itemView.setOnClickListener { onClick(e) }
    }
}

// ------------------------------------------------------------------ shared row binders

class SongVH(v: View) : RecyclerView.ViewHolder(v) {
    val artBox: View = v.findViewById(R.id.artBox)
    val art: ImageView = v.findViewById(R.id.art)
    val check: ImageView = v.findViewById(R.id.check)
    val title: TextView = v.findViewById(R.id.title)
    val subtitle: TextView = v.findViewById(R.id.subtitle)
    val eq: EqBarsView = v.findViewById(R.id.eq)
    val date: TextView = v.findViewById(R.id.date)

    init {
        artBox.clipToOutline = true
    }

    fun bind(s: Song, selection: Set<String>?, query: String = "") {
        val current = s.key == NowPlaying.key
        title.text = Highlight.text(itemView.context, s.title, query)
        title.setTextColor(itemView.context.getColor(if (current) R.color.red_hot else R.color.white))
        subtitle.text = TextUtils.concat(Highlight.text(itemView.context, s.subtitle, query), "  ·  ${Format.duration(s.durationMs)}")
        date.text = Format.shortDate(s.dateAdded)
        eq.visibility = if (current) View.VISIBLE else View.GONE
        eq.playing = current && NowPlaying.playing
        Thumbs.load(art, s.uri, 144, 144, R.drawable.ic_music_note_small)
        if (selection == null) {
            check.visibility = View.GONE
            itemView.setBackgroundResource(R.drawable.bg_row)
        } else {
            val on = selection.contains(s.key)
            check.visibility = View.VISIBLE
            check.setBackgroundResource(if (on) R.drawable.bg_check_on else R.drawable.bg_check_off)
            check.setImageResource(if (on) R.drawable.ic_check else 0)
            itemView.setBackgroundResource(if (on) R.drawable.bg_selected_row else R.drawable.bg_row)
        }
    }
}

class FolderVH(v: View) : RecyclerView.ViewHolder(v) {
    val name: TextView = v.findViewById(R.id.folderName)
    val count: TextView = v.findViewById(R.id.folderCount)
    val icon: ImageView = v.findViewById(R.id.folderIcon)
    val cover: ImageView = v.findViewById(R.id.folderCover)
    val box: View = v.findViewById(R.id.folderIconBox)
    val check: ImageView = v.findViewById(R.id.folderCheck)

    init {
        box.clipToOutline = true
    }

    /** [selected] is null when not selecting; true / false shows the tick circle. */
    fun bind(name: String, count: Int, coverUri: Uri?, selected: Boolean?, query: String = "") {
        this.name.text = Highlight.text(itemView.context, name, query)
        this.count.text = count.toString()
        if (coverUri != null) {
            box.setBackgroundResource(R.drawable.bg_thumb_small)
            icon.visibility = View.GONE
            cover.visibility = View.VISIBLE
            Thumbs.load(cover, coverUri, 144, 144)
        } else {
            box.background = null
            icon.visibility = View.VISIBLE
            cover.visibility = View.GONE
        }
        if (selected == null) {
            check.visibility = View.GONE
            itemView.setBackgroundResource(R.drawable.bg_row)
        } else {
            check.visibility = View.VISIBLE
            check.setBackgroundResource(if (selected) R.drawable.bg_check_on else R.drawable.bg_check_off)
            check.setImageResource(if (selected) R.drawable.ic_check else 0)
            itemView.setBackgroundResource(if (selected) R.drawable.bg_selected_row else R.drawable.bg_row)
        }
    }
}

class PlaylistVH(v: View) : RecyclerView.ViewHolder(v) {
    val box: View = v.findViewById(R.id.coverBox)
    val cover: ImageView = v.findViewById(R.id.cover)
    val coverIcon: ImageView = v.findViewById(R.id.coverIcon)
    val name: TextView = v.findViewById(R.id.name)
    val count: TextView = v.findViewById(R.id.count)
    val more: ImageButton = v.findViewById(R.id.btnMore)

    init {
        box.clipToOutline = true
    }

    fun bind(p: Playlist, firstSong: Song?) {
        name.text = p.name
        val n = p.keys.size
        count.text = if (n == 1) "1 song" else "$n songs"
        more.visibility = if (p.isFavorites) View.INVISIBLE else View.VISIBLE
        if (p.isFavorites) {
            coverIcon.setImageResource(R.drawable.ic_heart_filled)
            coverIcon.setColorFilter(itemView.context.getColor(R.color.red_hot))
            cover.setImageDrawable(null)
            cover.tag = null
        } else {
            coverIcon.setImageResource(R.drawable.ic_playlist)
            coverIcon.clearColorFilter()
            if (firstSong != null) Thumbs.load(cover, firstSong.uri, 144, 144) else {
                cover.setImageDrawable(null)
                cover.tag = null
            }
        }
    }
}

// ------------------------------------------------------------------ main screen (all tabs)

sealed class MainRow {
    data class History(val items: List<HistoryEntry>) : MainRow()
    data class Header(val text: String) : MainRow()
    data class FolderRow(val folder: Folder) : MainRow()
    data class VideoRow(val video: Video) : MainRow()
    data class Segment(val selected: Int) : MainRow()
    data class MusicHeader(val kind: Int, val title: String, val count: String) : MainRow()
    data class SongRow(val song: Song) : MainRow()
    data class MusicFolderRow(val folder: MusicFolder) : MainRow()
    data class PlaylistRow(val playlist: Playlist, val first: Song?) : MainRow()
    data class PhotoFolderRow(val folder: PhotoFolder) : MainRow()

    companion object {
        const val HEADER_SONGS = 0
        const val HEADER_FOLDERS = 1
        const val HEADER_PLAYLISTS = 2
    }
}

interface MainActions {
    fun onHistory(e: HistoryEntry)
    fun onSort(anchor: View)
    fun onVideoFolder(f: Folder)
    fun onVideo(v: Video)
    fun onSegment(i: Int)
    fun onMusicHeader(kind: Int)
    fun onMusicHeaderAction(kind: Int, anchor: View)
    fun onSong(position: Int)
    fun onSongLong(position: Int)
    fun onMusicFolder(f: MusicFolder)
    fun onPlaylist(p: Playlist)
    fun onPlaylistMore(p: Playlist, anchor: View)
    fun onPhotoFolder(f: PhotoFolder)

    /** Long press on a folder row: [key] is "v:", "m:" or "p:" plus the folder id. */
    fun onFolderLong(key: String)
}

class MainAdapter(private val actions: MainActions) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<MainRow> = emptyList()
        private set

    /** Selected songs or folders (by key) while in selection mode, else null. */
    var selection: MutableSet<String>? = null

    /** Current search text, painted grey inside matching names. */
    var query: String = ""

    fun submit(list: List<MainRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is MainRow.History -> 0
        is MainRow.Header -> 1
        is MainRow.FolderRow -> 2
        is MainRow.VideoRow -> 3
        is MainRow.Segment -> 4
        is MainRow.MusicHeader -> 5
        is MainRow.SongRow -> 6
        is MainRow.MusicFolderRow -> 2
        is MainRow.PlaylistRow -> 7
        is MainRow.PhotoFolderRow -> 2
    }

    private class HistoryVH(v: View, onClick: (HistoryEntry) -> Unit) : RecyclerView.ViewHolder(v) {
        val adapter = HistoryAdapter(onClick)
        init {
            val rv = v as RecyclerView
            rv.layoutManager = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = adapter
            rv.itemAnimator = null
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.headerText)
        val sort: ImageButton = v.findViewById(R.id.btnSort)
    }

    private class SegmentVH(v: View) : RecyclerView.ViewHolder(v) {
        val tabs: List<TextView> = listOf(
            v.findViewById(R.id.segSongs), v.findViewById(R.id.segFolders), v.findViewById(R.id.segPlaylists)
        )
    }

    private class MusicHeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val main: LinearLayout = v.findViewById(R.id.headerMain)
        val play: ImageView = v.findViewById(R.id.headerPlay)
        val title: TextView = v.findViewById(R.id.headerTitle)
        val count: TextView = v.findViewById(R.id.headerCount)
        val action: ImageButton = v.findViewById(R.id.headerAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HistoryVH(inf.inflate(R.layout.item_history_strip, parent, false)) { actions.onHistory(it) }
            1 -> HeaderVH(inf.inflate(R.layout.item_folder_header, parent, false))
            2 -> FolderVH(inf.inflate(R.layout.item_folder, parent, false))
            4 -> SegmentVH(inf.inflate(R.layout.item_segment, parent, false))
            5 -> MusicHeaderVH(inf.inflate(R.layout.item_music_header, parent, false))
            6 -> SongVH(inf.inflate(R.layout.item_song, parent, false))
            7 -> PlaylistVH(inf.inflate(R.layout.item_playlist, parent, false))
            else -> VideoAdapter.VH(inf.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MainRow.History -> {
                val h = holder as HistoryVH
                h.adapter.items = row.items
                h.adapter.notifyDataSetChanged()
            }
            is MainRow.Header -> {
                val h = holder as HeaderVH
                h.text.text = row.text
                h.sort.setOnClickListener { actions.onSort(it) }
            }
            is MainRow.FolderRow -> {
                val h = holder as FolderVH
                val key = "v:" + row.folder.id
                h.bind(row.folder.name, row.folder.count, null, selection?.contains(key))
                h.itemView.setOnClickListener { actions.onVideoFolder(row.folder) }
                h.itemView.setOnLongClickListener { actions.onFolderLong(key); true }
            }
            is MainRow.VideoRow -> {
                val h = holder as VideoAdapter.VH
                VideoAdapter.bind(h, row.video, null, query)
                h.itemView.setOnClickListener { actions.onVideo(row.video) }
                h.itemView.setOnLongClickListener(null)
            }
            is MainRow.Segment -> {
                val h = holder as SegmentVH
                h.tabs.forEachIndexed { i, t ->
                    val on = i == row.selected
                    t.setBackgroundResource(if (on) R.drawable.bg_pill_on else android.R.color.transparent)
                    t.setTextColor(t.context.getColor(if (on) R.color.white else R.color.muted))
                    t.setOnClickListener { actions.onSegment(i) }
                }
            }
            is MainRow.MusicHeader -> {
                val h = holder as MusicHeaderVH
                h.title.text = row.title
                h.count.text = row.count
                h.play.visibility = if (row.kind == MainRow.HEADER_SONGS) View.VISIBLE else View.GONE
                h.main.isClickable = row.kind == MainRow.HEADER_SONGS
                h.main.setOnClickListener { actions.onMusicHeader(row.kind) }
                h.action.setImageResource(if (row.kind == MainRow.HEADER_PLAYLISTS) R.drawable.ic_add else R.drawable.ic_sort)
                h.action.contentDescription = if (row.kind == MainRow.HEADER_PLAYLISTS) "New playlist" else "Sort"
                h.action.setOnClickListener { actions.onMusicHeaderAction(row.kind, it) }
            }
            is MainRow.SongRow -> {
                val h = holder as SongVH
                h.bind(row.song, selection, query)
                h.itemView.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) actions.onSong(p)
                }
                h.itemView.setOnLongClickListener {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) actions.onSongLong(p)
                    true
                }
            }
            is MainRow.MusicFolderRow -> {
                val h = holder as FolderVH
                val key = "m:" + row.folder.id
                h.bind(row.folder.name, row.folder.count, null, selection?.contains(key))
                h.itemView.setOnClickListener { actions.onMusicFolder(row.folder) }
                h.itemView.setOnLongClickListener { actions.onFolderLong(key); true }
            }
            is MainRow.PlaylistRow -> {
                val h = holder as PlaylistVH
                h.bind(row.playlist, row.first)
                h.itemView.setOnClickListener { actions.onPlaylist(row.playlist) }
                h.more.setOnClickListener { actions.onPlaylistMore(row.playlist, it) }
            }
            is MainRow.PhotoFolderRow -> {
                val h = holder as FolderVH
                val key = "p:" + row.folder.id
                h.bind(row.folder.name, row.folder.count, row.folder.photos.firstOrNull()?.uri, selection?.contains(key))
                h.itemView.setOnClickListener { actions.onPhotoFolder(row.folder) }
                h.itemView.setOnLongClickListener { actions.onFolderLong(key); true }
            }
        }
    }
}

// ------------------------------------------------------------------ videos inside a folder

class VideoAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    var items: List<Video> = emptyList()
        private set
    var grid = false

    /** Current search text, painted grey inside matching names. */
    var query: String = ""

    /** Keys of selected videos, or null when not in selection mode. */
    var selection: MutableSet<String>? = null

    fun submit(list: List<Video>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumbBox: View = v.findViewById(R.id.thumbBox)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val check: ImageView = v.findViewById(R.id.check)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val date: TextView = v.findViewById(R.id.date)

        init {
            thumbBox.clipToOutline = true
        }
    }

    companion object {
        fun bind(h: VH, v: Video, selection: Set<String>?, query: String = "") {
            h.title.text = Highlight.text(h.itemView.context, v.name, query)
            h.duration.text = Format.duration(v.durationMs)
            h.meta.text = Format.size(v.size)
            h.date.text = Format.shortDate(v.dateAdded)
            bindProgress(h.progress, v.key)
            Thumbs.load(h.thumb, v.uri)
            if (selection == null) {
                h.check.visibility = View.GONE
                h.itemView.setBackgroundResource(R.drawable.bg_row)
            } else {
                val on = selection.contains(v.key)
                h.check.visibility = View.VISIBLE
                h.check.setBackgroundResource(if (on) R.drawable.bg_check_on else R.drawable.bg_check_off)
                h.check.setImageResource(if (on) R.drawable.ic_check else 0)
                h.itemView.setBackgroundResource(if (on) R.drawable.bg_selected_row else R.drawable.bg_row)
            }
        }
    }

    override fun getItemViewType(position: Int) = if (grid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_video_grid else R.layout.item_video
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        bind(h, items[position], selection, query)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
    }
}

// ------------------------------------------------------------------ songs (folder / playlist screen)

class SongAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<SongVH>() {

    var items: List<Song> = emptyList()
        private set
    var selection: MutableSet<String>? = null
    var query: String = ""

    fun submit(list: List<Song>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongVH =
        SongVH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: SongVH, position: Int) {
        h.bind(items[position], selection, query)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
    }
}

// ------------------------------------------------------------------ photos grid

class PhotoAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    var items: List<Photo> = emptyList()
        private set
    var selection: MutableSet<String>? = null

    fun submit(list: List<Photo>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ImageView = v.findViewById(R.id.photo)
        val dim: View = v.findViewById(R.id.dim)
        val check: ImageView = v.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        Thumbs.load(h.photo, p.uri, 320, 320)
        val sel = selection
        if (sel == null) {
            h.check.visibility = View.GONE
            h.dim.visibility = View.GONE
        } else {
            val on = sel.contains(p.key)
            h.check.visibility = View.VISIBLE
            h.check.setBackgroundResource(if (on) R.drawable.bg_check_on else R.drawable.bg_check_off)
            h.check.setImageResource(if (on) R.drawable.ic_check else 0)
            h.dim.visibility = if (on) View.VISIBLE else View.GONE
        }
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onClick(i)
        }
        h.itemView.setOnLongClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onLongClick(i)
            true
        }
    }
}
