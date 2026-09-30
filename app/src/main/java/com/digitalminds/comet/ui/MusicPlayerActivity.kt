package com.digitalminds.comet.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.digitalminds.comet.R
import com.digitalminds.comet.data.AudioRepo
import com.digitalminds.comet.data.Launch
import com.digitalminds.comet.data.Playlists
import com.digitalminds.comet.data.isAudioItem
import com.digitalminds.comet.databinding.ActivityMusicPlayerBinding
import com.digitalminds.comet.service.PlaybackService
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.Thumbs
import java.util.concurrent.Executors
import kotlin.math.abs
import androidx.media3.common.Format as MFormat

class MusicPlayerActivity : AppCompatActivity(), PlaybackService.Host {

    private lateinit var b: ActivityMusicPlayerBinding
    private var service: PlaybackService? = null
    private var player: ExoPlayer? = null
    private var bound = false
    private var dragging = false
    private var artKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    private val deleter = Deleter(this) { deleted -> removeFromQueue(deleted.map { it.toString() }.toSet()) }

    private fun removeFromQueue(keys: Set<String>) {
        val p = player
        if (p != null) {
            for (i in p.mediaItemCount - 1 downTo 0) {
                if (keys.contains(p.getMediaItemAt(i).mediaId)) p.removeMediaItem(i)
            }
            if (p.mediaItemCount == 0) {
                service?.stopAndClear()
                finish()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            updateTimer()
            handler.postDelayed(this, 300)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service() ?: return
            service = svc
            svc.addHost(this@MusicPlayerActivity)
            val request = Launch.pending
            Launch.pending = null
            if (request != null) {
                svc.playQueue(request.first, request.second)
            } else if (svc.player.mediaItemCount == 0) {
                finish()
                return
            } else if (!svc.isAudioNow()) {
                // A video is loaded: show it in the video player instead.
                startActivity(Intent(this@MusicPlayerActivity, PlayerActivity::class.java))
                finish()
                return
            }
            attach(svc.player)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detach()
            service = null
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
            refresh()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            b.btnSpeed.text = Format.speed(playbackParameters.speed)
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@MusicPlayerActivity, "Cannot play this file", Toast.LENGTH_SHORT).show()
            val p = player ?: return
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
        }
    }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.artFrame.makeCircle()
        b.title.isSelected = true // lets long titles scroll

        handleExternalIntent(intent)
        setupButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.speedPanel.visibility == View.VISIBLE) b.speedPanel.visibility = View.GONE else finish()
            }
        })

        val i = Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL_BIND)
        bound = bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
        val svc = service ?: return
        val request = Launch.pending ?: return
        Launch.pending = null
        svc.playQueue(request.first, request.second)
        attach(svc.player)
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        service?.removeHost(this)
        detach()
        if (bound) {
            try { unbindService(connection) } catch (e: Exception) { }
            bound = false
        }
        io.shutdown()
        super.onDestroy()
    }

    override fun onPlayerReplaced(newPlayer: ExoPlayer) {
        attach(newPlayer)
    }

    private fun attach(p: ExoPlayer) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
        b.btnSpeed.text = Format.speed(p.playbackParameters.speed)
        artKey = null
        refresh()
    }

    private fun detach() {
        player?.removeListener(listener)
        player = null
    }

    /** "Open with COMET" for audio from WhatsApp, Files, a download... */
    private fun handleExternalIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val uri = i.data ?: return
        var name = uri.lastPathSegment ?: "Audio"
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                        if (iName >= 0) c.getString(iName)?.let { name = it }
                        if (iSize >= 0) size = c.getLong(iSize)
                    }
                }
        } catch (e: Exception) {
            // The name from the link is enough.
        }
        Launch.pending = listOf(AudioRepo.externalItem(uri, name, size)) to 0
        i.action = null
    }

    // ================================================================ UI

    private fun refresh() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        if (!item.isAudioItem() && p.mediaItemCount > 0) {
            startActivity(Intent(this, PlayerActivity::class.java))
            finish()
            return
        }
        val md = p.mediaMetadata
        val title = (item.mediaMetadata.title ?: md.title ?: "").toString()
        if (b.title.text.toString() != title) b.title.text = title
        b.artist.text = item.mediaMetadata.artist ?: md.artist ?: ""
        b.btnPlay.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPrev.alpha = if (p.hasPreviousMediaItem()) 1f else 0.35f
        b.btnNext.alpha = if (p.hasNextMediaItem()) 1f else 0.35f
        val fav = Playlists.isFavorite(item.mediaId)
        b.btnFavorite.setImageResource(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        if (fav) b.btnFavorite.setColorFilter(getColor(R.color.red_hot)) else b.btnFavorite.clearColorFilter()
        val mode = service?.repeatMode() ?: 0
        b.btnRepeat.setImageResource(QueueSheet.modeIcons.getOrElse(mode) { QueueSheet.modeIcons[0] })
        if (mode == PlaybackService.MODE_ORDER) b.btnRepeat.clearColorFilter()
        else b.btnRepeat.setColorFilter(getColor(R.color.red_hot))
        loadArt(item)
        updateProgress()
        updateTimer()
    }

    private fun loadArt(item: MediaItem) {
        if (artKey == item.mediaId) return
        artKey = item.mediaId
        val uri = item.localConfiguration?.uri ?: return
        val key = item.mediaId
        io.execute {
            val art = Thumbs.artwork(applicationContext, uri, 720)
            val blur = art?.let { Thumbs.blurred(it) }
            handler.post {
                if (isDestroyed || artKey != key) return@post
                b.art.setImageBitmap(art)
                b.bgArt.setImageBitmap(blur)
                b.art.alpha = 0f
                b.art.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        if (dragging) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0)
        b.seek.max = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        b.seek.progress = pos.coerceAtMost(dur).toInt()
        b.timePos.text = Format.duration(pos)
        b.timeTotal.text = Format.duration(dur)
    }

    private fun updateTimer() {
        val svc = service ?: return
        val remaining = svc.sleepRemainingMs()
        if (remaining < 0) {
            b.btnTimer.clearColorFilter()
            b.timerLabel.visibility = View.GONE
        } else {
            b.btnTimer.setColorFilter(getColor(R.color.red_hot))
            b.timerLabel.visibility = View.VISIBLE
            b.timerLabel.text = if (svc.isSleepEndOfItem()) "Stops after this song" else "Stops in " + Format.duration(remaining)
        }
    }

    // ================================================================ buttons

    private fun setupButtons() {
        b.btnBack.setOnClickListener { finish() }
        b.btnPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(p.currentMediaItemIndex)
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            }
        }
        b.btnPrev.setOnClickListener { service?.previous() }
        b.btnNext.setOnClickListener { service?.next() }
        b.btnRewind.setOnClickListener { seekBy(-10_000) }
        b.btnForward.setOnClickListener { seekBy(10_000) }
        b.btnQueue.setOnClickListener { service?.let { QueueSheet.show(this, it) { refresh() } } }
        b.btnRepeat.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val next = (svc.repeatMode() + 1) % QueueSheet.modeNames.size
            svc.setRepeatMode(next)
            Toast.makeText(this, QueueSheet.modeNames[next], Toast.LENGTH_SHORT).show()
            refresh()
        }
        b.btnFavorite.setOnClickListener {
            val key = player?.currentMediaItem?.mediaId ?: return@setOnClickListener
            val now = Playlists.toggleFavorite(key)
            Toast.makeText(this, if (now) "Added to My Favorites" else "Removed from My Favorites", Toast.LENGTH_SHORT).show()
            refresh()
        }
        b.btnTimer.setOnClickListener { showTimer() }
        b.btnSpeed.setOnClickListener {
            b.speedPanel.visibility = if (b.speedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (b.speedPanel.visibility == View.VISIBLE) buildSpeedRow()
        }
        b.btnEq.setOnClickListener { openEqualizer() }
        b.btnAddPlaylist.setOnClickListener {
            val key = player?.currentMediaItem?.mediaId ?: return@setOnClickListener
            Dialogs.addToPlaylist(this, key) { name ->
                Toast.makeText(this, "Added to $name", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        b.btnShare.setOnClickListener { share() }
        b.btnDelete.setOnClickListener {
            val uri = player?.currentMediaItem?.localConfiguration?.uri ?: return@setOnClickListener
            deleter.delete(listOf(uri), "song")
        }
        b.btnProperties.setOnClickListener { showProperties() }

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) b.timePos.text = Format.duration(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                player?.seekTo(b.seek.progress.toLong())
            }
        })
    }

    private fun seekBy(delta: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + delta).coerceIn(0, dur))
        updateProgress()
    }

    private fun buildSpeedRow() {
        val row = b.speedRow
        row.removeAllViews()
        val dp = resources.displayMetrics.density
        val current = player?.playbackParameters?.speed ?: 1f
        val medium = ResourcesCompat.getFont(this, R.font.space_grotesk_medium)
        speeds.forEach { s ->
            row.addView(TextView(this).apply {
                text = Format.speed(s)
                textSize = 13f
                typeface = medium
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.white))
                setBackgroundResource(if (abs(s - current) < 0.01f) R.drawable.bg_pill_on else R.drawable.bg_pill_dark)
                layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (34 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() }
                setOnClickListener {
                    player?.setPlaybackSpeed(s)
                    b.btnSpeed.text = Format.speed(s)
                    b.speedPanel.visibility = View.GONE
                }
            })
        }
    }

    private fun showTimer() {
        val svc = service ?: return
        val remaining = svc.sleepRemainingMs()
        val text = when {
            remaining < 0 -> null
            svc.isSleepEndOfItem() -> "Active: stops after this song"
            else -> "Active: stops in " + Format.duration(remaining)
        }
        Dialogs.sleepTimer(this, text, "End of song", {}) { v ->
            when {
                v == 0 -> svc.cancelSleep()
                v < 0 -> svc.setSleepAtEndOfItem()
                else -> svc.setSleepTimer(v)
            }
            val msg = when {
                v == 0 -> "Sleep timer off"
                v < 0 -> "Stops after this song"
                else -> "Stops in $v min"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            updateTimer()
        }
    }

    /** Opens the phone equalizer (Samsung SoundAlive, etc.) for COMET: 0 KB added to the app. */
    private fun openEqualizer() {
        val session = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        val i = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        if (session != C.AUDIO_SESSION_ID_UNSET) i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 7)
        } catch (e: Exception) {
            Toast.makeText(this, "No equalizer found on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        val uri: Uri = player?.currentMediaItem?.localConfiguration?.uri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to share", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProperties() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val md = item.mediaMetadata
        val extras = md.extras
        val rows = ArrayList<Pair<String, String>>()
        rows.add("File" to (md.displayTitle ?: md.title ?: "").toString())
        md.title?.let { rows.add("Title" to it.toString()) }
        md.artist?.let { if (it.isNotBlank()) rows.add("Artist" to it.toString()) }
        md.albumTitle?.let { if (it.isNotBlank()) rows.add("Album" to it.toString()) }
        val path = extras?.getString("path").takeUnless { it.isNullOrEmpty() }
            ?: item.localConfiguration?.uri?.toString() ?: ""
        rows.add("Location" to path)
        val size = extras?.getLong("size") ?: 0L
        if (size > 0) rows.add("Size" to Format.size(size))
        val modified = extras?.getLong("modified") ?: 0L
        if (modified > 0) rows.add("Date" to Format.fullDate(modified))
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        rows.add("Duration" to Format.duration(dur))
        val af = p.audioFormat
        if (af != null) {
            val ch = when (af.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                MFormat.NO_VALUE -> ""
                else -> "${af.channelCount} ch"
            }
            val rate = if (af.sampleRate != MFormat.NO_VALUE) "${af.sampleRate} Hz" else ""
            rows.add("Audio" to listOf(codecName(af.sampleMimeType), rate, ch).filter { it.isNotEmpty() }.joinToString("  "))
        }
        if (size > 0 && dur > 0) rows.add("Bitrate" to "${size * 8 / dur} kbps")
        Dialogs.properties(this, rows) {}
    }

    private fun codecName(mime: String?): String = when (mime) {
        null -> "Unknown"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/raw" -> "PCM (WAV)"
        "audio/3gpp" -> "AMR"
        "audio/amr-wb" -> "AMR-WB"
        else -> mime
    }
}
