package com.digitalminds.comet.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
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
 * (a video playing as audio today, music in the next build): prev / play / next, stop,
 * and a tap opens the full player again.
 *
 * It never starts the service by itself: it only attaches if something is already playing.
 */
class MiniPlayer(
    private val activity: Activity,
    private val b: ViewMiniPlayerBinding,
    /** Called when the loaded file or play/pause changes (lists repaint the red row). */
    private val onNowPlayingChanged: () -> Unit = {}
) : PlaybackService.Host {

    private var service: PlaybackService? = null
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
        b.miniOpen.setOnClickListener {
            val audio = player?.currentMediaItem?.isAudioItem() == true
            val target = if (audio) MusicPlayerActivity::class.java else PlayerActivity::class.java
            activity.startActivity(Intent(activity, target))
        }
        b.miniQueue.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            QueueSheet.show(activity, svc)
        }
        b.miniPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(p.currentMediaItemIndex)
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            }
        }
        b.miniNext.setOnClickListener { service?.next() }
        b.miniClose.setOnClickListener {
            service?.stopAndClear()
            detach()
            render()
        }
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
        b.miniPlay.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
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
