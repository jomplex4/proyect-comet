package com.digitalminds.comet.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.digitalminds.comet.R
import com.digitalminds.comet.data.isAudioItem
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.databinding.ViewMiniPlayerBinding
import com.digitalminds.comet.service.PlaybackService
import com.digitalminds.comet.util.Thumbs

/**
 * The small bar above the bottom menu that shows what keeps playing in the background
 * (music, or a video playing as audio): previous / play / next.
 *
 * Tap: open the full player. Swipe left or right: stop and close it. Swipe up: the queue.
 * It only attaches if something is already playing, or when [play] starts something.
 */
class MiniPlayer(
    private val activity: Activity,
    private val b: ViewMiniPlayerBinding,
    /** Called when the loaded file or play/pause changes (lists repaint the red row). */
    private val onNowPlayingChanged: () -> Unit = {}
) : PlaybackService.Host {

    private var service: PlaybackService? = null
    private var pendingQueue: Pair<List<MediaItem>, Int>? = null
    private var player: ExoPlayer? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service() ?: return
            service = svc
            svc.addHost(this@MiniPlayer)
            val queue = pendingQueue
            pendingQueue = null
            if (queue != null) svc.playQueue(queue.first, queue.second)
            attach(svc.player)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detach()
            service = null
            render()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            render()
        }
    }

    init {
        b.root.clipToOutline = true
        b.miniThumbBox.clipToOutline = true
        b.miniOpen.setOnClickListener { openFull() }
        setupSwipe()
        b.miniPrev.setOnClickListener { service?.previous() }
        b.miniPlay.setOnClickListener {
            val svc = service
            if (svc != null) svc.togglePlay() else player?.let { if (PlaybackService.isActive(it)) it.pause() else it.play() }
        }
        b.miniNext.setOnClickListener { service?.next() }
    }

    private fun openFull() {
        val audio = player?.currentMediaItem?.isAudioItem() == true
        val target = if (audio) MusicPlayerActivity::class.java else PlayerActivity::class.java
        activity.startActivity(Intent(activity, target))
    }

    private fun closeForever() {
        service?.stopAndClear()
        detach()
        render()
    }

    /** Swipe sideways to close, up for the queue; a plain tap still opens the player. */
    private fun setupSwipe() {
        val d = activity.resources.displayMetrics.density
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var mode = 0 // 0 undecided, 1 sideways, 2 up
        b.miniOpen.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    mode = 0
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (mode == 0) {
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) mode = 1
                        else if (dy < -slop && Math.abs(dy) > Math.abs(dx)) mode = 2
                        if (mode != 0) {
                            v.isPressed = false
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    when (mode) {
                        1 -> {
                            b.root.translationX = dx
                            b.root.alpha = (1f - Math.abs(dx) / (b.root.width * 0.9f)).coerceIn(0.2f, 1f)
                        }
                        2 -> b.root.translationY = (dy / 3f).coerceIn(-18f * d, 0f)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    v.isPressed = false
                    when {
                        mode == 1 && Math.abs(dx) > Math.min(b.root.width * 0.35f, 140 * d) -> {
                            val out = if (dx > 0) b.root.width.toFloat() else -b.root.width.toFloat()
                            b.root.animate().translationX(out).alpha(0f).setDuration(160).withEndAction {
                                closeForever()
                                b.root.translationX = 0f
                                b.root.alpha = 1f
                            }.start()
                        }
                        mode == 2 && dy < -40 * d -> {
                            resetPosition()
                            service?.let { QueueSheet.show(activity, it) }
                        }
                        mode == 0 -> v.performClick()
                        else -> resetPosition()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    resetPosition()
                }
            }
            true
        }
    }

    private fun resetPosition() {
        b.root.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(140).start()
    }

    /**
     * Starts a queue from a screen that has no player of its own (a recent audio in the list).
     * Only the little bar and the notification show up; the full player is not opened.
     */
    fun play(items: List<MediaItem>, index: Int) {
        val svc = service
        if (svc != null) {
            svc.playQueue(items, index)
            return
        }
        pendingQueue = items to index
        if (bound) {
            try { activity.unbindService(connection) } catch (e: Exception) { }
            bound = false
        }
        val i = Intent(activity, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL_BIND)
        bound = activity.bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    /** Call from onStart. */
    fun start() {
        if (!bound) {
            val i = Intent(activity, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL_BIND)
            // Flag 0: do not create the service; connect only when it is (or becomes) alive.
            bound = activity.bindService(i, connection, 0)
        }
        handler.post(ticker)
    }

    /** Call from onStop. */
    fun stop() {
        handler.removeCallbacks(ticker)
        service?.removeHost(this)
        detach()
        if (bound) {
            try { activity.unbindService(connection) } catch (e: Exception) { }
            bound = false
        }
        service = null
    }

    override fun onPlayerReplaced(newPlayer: ExoPlayer) {
        attach(newPlayer)
    }

    override fun onServiceGone() {
        detach()
        service = null
        render()
    }

    private fun attach(p: ExoPlayer) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
        render()
    }

    private fun detach() {
        player?.removeListener(listener)
        player = null
    }

    private var shownKey: String? = null

    private var lastKey: String? = null
    private var lastPlaying = false

    private fun notifyNowPlaying(key: String?, playing: Boolean) {
        NowPlaying.key = key
        NowPlaying.playing = playing
        if (key != lastKey || playing != lastPlaying) {
            lastKey = key
            lastPlaying = playing
            onNowPlayingChanged()
        }
    }

    private fun render() {
        val p = player
        val item: MediaItem? = p?.currentMediaItem
        if (p == null || item == null || p.mediaItemCount == 0) {
            b.root.visibility = View.GONE
            shownKey = null
            notifyNowPlaying(null, false)
            return
        }
        notifyNowPlaying(item.mediaId, p.isPlaying)
        b.root.visibility = View.VISIBLE
        val md = item.mediaMetadata
        b.miniTitle.text = md.title ?: ""
        b.miniPlay.setImageResource(if (PlaybackService.isActive(p)) R.drawable.ic_pause else R.drawable.ic_play)
        b.miniNext.alpha = if (p.hasNextMediaItem()) 1f else 0.35f
        if (shownKey != item.mediaId) {
            shownKey = item.mediaId
            val audio = item.isAudioItem()
            // Songs get a square cover, videos a 16:9 frame.
            val d = activity.resources.displayMetrics.density
            b.miniThumbBox.layoutParams = b.miniThumbBox.layoutParams.apply {
                width = ((if (audio) 42 else 72) * d).toInt()
            }
            val uri = item.localConfiguration?.uri
            if (uri != null) {
                if (audio) Thumbs.load(b.miniThumb, uri, 144, 144, R.drawable.ic_music_note_small)
                else Thumbs.load(b.miniThumb, uri)
            } else {
                b.miniThumb.setImageDrawable(null)
            }
        }
        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        if (b.root.visibility != View.VISIBLE) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0)
        b.miniSubtitle.text = if (dur > 0) "${Format.duration(pos)} / ${Format.duration(dur)}" else ""
        if (dur > 0) b.miniProgress.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
    }
}
