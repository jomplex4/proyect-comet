package com.digitalminds.comet.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.view.animation.DecelerateInterpolator
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.digitalminds.comet.R
import com.digitalminds.comet.data.Launch
import com.digitalminds.comet.data.MediaRepo
import com.digitalminds.comet.databinding.ActivityPlayerBinding
import com.digitalminds.comet.service.PlaybackService
import com.digitalminds.comet.util.Format
import com.digitalminds.comet.util.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.Format as MFormat

class PlayerActivity : AppCompatActivity(), PlaybackService.Host {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private lateinit var gestures: GestureDetector

    // Picture state. The frame always lays out as "Fit" (whole video, nothing cut). "Fill" is
    // the same frame scaled up until it covers the screen, so there are only two real states
    // and a smooth animation between them. Two fingers pan the picture when it is filled.
    private var picScale = 1f
    private var picTx = 0f
    private var picTy = 0f
    private var twoFinger = false
    private var ignoreUntilUp = false
    private var gStartSpan = 1f
    private var gStartScale = 1f
    private var gContentX = 0f
    private var gContentY = 0f
    private var gStartScaleForSnap = 1f
    private var picAnimator: ValueAnimator? = null

    private var service: PlaybackService? = null
    private var player: ExoPlayer? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private var controlsShown = true
    private var locked = false
    private var dragging = false
    private var closing = false

    // Swipe gestures: 1 = brightness (left half), 2 = volume (right half), 3 = seek (sideways).
    private var swipeMode = 0
    private var swipeStart = 0f
    private var seekDragStart = 0L
    private var seekDragTarget = 0L

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    private val modeNames = QueueSheet.modeNames

    private val hideControls = Runnable { setControls(false) }
    private val hideHud = Runnable { b.hud.visibility = View.GONE }
    private val hideLockOnly = Runnable { if (locked) b.lockOnly.visibility = View.GONE }

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
            svc.addHost(this@PlayerActivity)
            val request = Launch.pending
            Launch.pending = null
            if (request != null) {
                svc.playQueue(request.first, request.second)
                attach(svc.player)
            } else if (svc.player.mediaItemCount == 0) {
                // Android closed the app while it was paused: put everything back where it was.
                svc.restoreLast { ok ->
                    if (!ok || isDestroyed) finish() else proceed(svc)
                }
            } else {
                proceed(svc)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detach()
            service = null
        }
    }

    /** The service has something loaded: show it here, or hand it to the music player. */
    private fun proceed(svc: PlaybackService) {
        if (svc.isAudioNow()) {
            startActivity(Intent(this, MusicPlayerActivity::class.java))
            finish()
            return
        }
        attach(svc.player)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            b.root.keepScreenOn = isPlaying
            if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControls)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayButton()
            if (playbackState == Player.STATE_ENDED) setControls(true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (service?.isAudioNow() == true) {
                // Something else started a song: the music player takes over.
                startActivity(Intent(this@PlayerActivity, MusicPlayerActivity::class.java))
                finish()
                return
            }
            updateTitle()
            updateNavButtons()
            resetPicture()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyVideoSize(videoSize)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            b.btnSpeed.text = Format.speed(playbackParameters.speed)
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, "Cannot play this file", Toast.LENGTH_SHORT).show()
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
        prefs = Prefs(this)
        // Decide the screen direction BEFORE anything is drawn, from what we already know about
        // the video, so it opens straight in the right direction (no portrait flash, no black blink).
        preselectOrientation()
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupWindow()
        handleExternalIntent(intent)
        setupControls()
        setupGestures()
        setupPanel()
        applyResizeMode()
        applySavedBrightness()
        updateChips()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.settingsPanel.visibility == View.VISIBLE -> closePanel()
                    b.speedPanel.visibility == View.VISIBLE -> b.speedPanel.visibility = View.GONE
                    locked -> flashLock()
                    else -> closePlayer()
                }
            }
        })

        val bindIntent = Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_LOCAL_BIND)
        bound = bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
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
        player?.let {
            setVideoEnabled(it, true)
            it.setVideoSurfaceView(b.surface)
            // Something may have changed while we were away (paused from the notification):
            // show the real state right now.
            updatePlayButton()
            updateTitle()
            updateProgress()
            b.root.keepScreenOn = it.isPlaying
        }
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /** Home or recents pressed: the app is still in front, the best moment to go audio only. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = player ?: return
        if (!closing && prefs.backgroundPlay && p.playWhenReady) setVideoEnabled(p, false)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        if (closing || isFinishing || isChangingConfigurations) return
        val p = player ?: return
        // Home button, screen off, another app on top.
        if (prefs.backgroundPlay && p.playWhenReady) {
            setVideoEnabled(p, false) // keep going as audio only, saves battery
        } else {
            p.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        service?.removeHost(this)
        detach()
        if (bound) {
            try { unbindService(connection) } catch (e: Exception) { }
            bound = false
        }
        super.onDestroy()
    }

    override fun onPlayerReplaced(newPlayer: ExoPlayer) {
        attach(newPlayer)
    }

    override fun onServiceGone() {
        // The service ended (for example, a long pause was released): nothing left to control.
        detach()
        service = null
        if (!isFinishing) finish()
    }

    // ================================================================ player wiring

    private fun attach(p: ExoPlayer) {
        if (player !== p) {
            player?.removeListener(listener)
            player?.clearVideoSurfaceView(b.surface)
        }
        player = p
        p.removeListener(listener)
        p.addListener(listener)
        setVideoEnabled(p, true)
        p.setVideoSurfaceView(b.surface)
        b.btnSpeed.text = Format.speed(p.playbackParameters.speed)
        b.root.keepScreenOn = p.isPlaying
        updateTitle()
        updateNavButtons()
        updatePlayButton()
        updateChips()
        applyVideoSize(p.videoSize)
        updateProgress()
        updateTimer()
        setControls(true)
    }

    private fun detach() {
        player?.removeListener(listener)
        player?.clearVideoSurfaceView(b.surface)
        player = null
    }

    private fun setVideoEnabled(p: Player, enabled: Boolean) {
        val current = p.trackSelectionParameters
        if (current.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == !enabled) return
        p.trackSelectionParameters = current.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** Opened from WhatsApp, Files, a browser download... */
    private fun handleExternalIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val uri = i.data ?: return
        var name = uri.lastPathSegment ?: "Video"
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
            // Some apps do not answer; the name from the link is enough.
        }
        Launch.pending = listOf(MediaRepo.externalItem(uri, name, size)) to 0
        i.action = null // do not replay it after a rotation or a return from background
    }

    /** Leaving with Back: keep sounding only when Background Play is on. */
    private fun closePlayer() {
        closing = true
        val svc = service
        val p = player
        if (svc != null && p != null) {
            if (prefs.backgroundPlay && p.playWhenReady) {
                setVideoEnabled(p, false)
                svc.savePosition()
            } else {
                svc.stopAndClear()
            }
        }
        finish()
    }

    // ================================================================ window

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Controls stay clear of the notch; the video itself fills everything.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val d = resources.displayMetrics.density
            // Buttons move away from the notch; the dark gradients stay edge to edge
            // (and grow a bit) so the camera area is shaded like the rest.
            b.topArea.setPadding(cut.left, cut.top, cut.right, 0)
            b.bottomArea.setPadding((8 * d).toInt() + cut.left, 0, (8 * d).toInt() + cut.right, (6 * d).toInt() + cut.bottom)
            b.gradTop.layoutParams = b.gradTop.layoutParams.apply { height = (120 * d).toInt() + cut.top }
            b.gradBottom.layoutParams = b.gradBottom.layoutParams.apply { height = (150 * d).toInt() + cut.bottom }
            val pad = (22 * resources.displayMetrics.density).toInt()
            b.panelContent.setPadding(pad, pad + cut.top, pad + cut.right, pad + cut.bottom)
            val lp = b.lockOnly.layoutParams as android.widget.FrameLayout.LayoutParams
            val m = (20 * resources.displayMetrics.density).toInt()
            lp.marginStart = m + cut.left
            lp.bottomMargin = m + cut.bottom
            b.lockOnly.layoutParams = lp
            insets
        }
    }

    private fun hideSystemUi() {
        val c = WindowInsetsControllerCompat(window, b.root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ================================================================ controls

    private fun setupControls() {
        b.btnBack.setOnClickListener { closePlayer() }
        b.btnSettings.setOnClickListener { openPanel() }

        b.btnPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            togglePlay(p)
            scheduleHide()
        }
        b.btnRewind.setOnClickListener { seekBy(-10_000) }
        b.btnForward.setOnClickListener { seekBy(10_000) }
        b.btnPrev.setOnClickListener { service?.previous(); scheduleHide() }
        b.btnNext.setOnClickListener { service?.next(); scheduleHide() }

        b.btnLock.setOnClickListener {
            locked = true
            setControls(false)
            b.speedPanel.visibility = View.GONE
            flashLock()
        }
        b.lockOnly.setOnClickListener {
            locked = false
            b.lockOnly.visibility = View.GONE
            setControls(true)
        }

        b.btnResize.setOnClickListener {
            togglePicture()
            scheduleHide()
        }

        b.btnOrientation.setOnClickListener {
            prefs.orientation = (prefs.orientation + 1) % 3
            applyOrientationMode(player?.videoSize)
            updateChips()
            showHud(
                when (prefs.orientation) {
                    Prefs.ORIENT_LANDSCAPE -> "Landscape"
                    Prefs.ORIENT_PORTRAIT -> "Portrait"
                    else -> "Auto"
                }
            )
            scheduleHide()
        }

        b.btnMute.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val muted = svc.toggleMute()
            updateChips()
            showHud(if (muted) "Muted" else "Sound on")
            scheduleHide()
        }

        b.btnBackground.setOnClickListener {
            if (prefs.backgroundPlay) {
                prefs.backgroundPlay = false
                updateChips()
                showHud("Background Play off")
                scheduleHide()
            } else {
                // Turn it on and step aside: the video keeps sounding like music.
                prefs.backgroundPlay = true
                updateChips()
                Toast.makeText(this, "Background Play on", Toast.LENGTH_SHORT).show()
                player?.play()
                closePlayer()
            }
        }

        b.btnSpeed.setOnClickListener {
            b.speedPanel.visibility = if (b.speedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (b.speedPanel.visibility == View.VISIBLE) buildSpeedRow()
            scheduleHide()
        }

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = player?.duration ?: 0L
                    b.timePos.text = Format.duration(progress.toLong())
                    b.timeLeft.text = "-" + Format.duration((dur - progress).coerceAtLeast(0))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dragging = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                dragging = false
                player?.seekTo(b.seek.progress.toLong())
                scheduleHide()
            }
        })
    }

    private fun togglePlay(@Suppress("UNUSED_PARAMETER") p: Player) {
        val svc = service
        if (svc != null) {
            svc.togglePlay()
        } else {
            val pl = player ?: return
            if (PlaybackService.isActive(pl)) pl.pause() else pl.play()
        }
    }

    // Rapid taps add up: 3 taps show 30s, 51 taps show 510s.
    private var seekAccumMs = 0L
    private var seekAccumAt = 0L

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, dur))
        val now = SystemClock.elapsedRealtime()
        val sameRun = now - seekAccumAt < 1200 && seekAccumMs != 0L && (seekAccumMs < 0) == (deltaMs < 0)
        seekAccumMs = if (sameRun) seekAccumMs + deltaMs else deltaMs
        seekAccumAt = now
        val secs = Math.abs(seekAccumMs) / 1000
        showHud((if (seekAccumMs < 0) "-" else "+") + secs + "s")
        scheduleHide()
    }

    private fun buildSpeedRow() {
        val row = b.speedRow
        row.removeAllViews()
        val dp = resources.displayMetrics.density
        val current = player?.playbackParameters?.speed ?: 1f
        val medium = ResourcesCompat.getFont(this, R.font.space_grotesk_medium)
        speeds.forEach { s ->
            val pill = TextView(this).apply {
                text = Format.speed(s)
                textSize = 13f
                typeface = medium
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.white))
                setBackgroundResource(if (abs(s - current) < 0.01f) R.drawable.bg_pill_on else R.drawable.bg_pill_dark)
                val lp = LinearLayout.LayoutParams((58 * dp).toInt(), (36 * dp).toInt())
                lp.marginEnd = (8 * dp).toInt()
                layoutParams = lp
                setOnClickListener {
                    player?.setPlaybackSpeed(s)
                    b.btnSpeed.text = Format.speed(s)
                    b.speedPanel.visibility = View.GONE
                    showHud("Speed " + Format.speed(s))
                    scheduleHide()
                }
            }
            row.addView(pill)
        }
    }

    private fun updateChips() {
        b.btnOrientation.setImageResource(
            when (prefs.orientation) {
                Prefs.ORIENT_LANDSCAPE -> R.drawable.ic_orient_landscape
                Prefs.ORIENT_PORTRAIT -> R.drawable.ic_orient_portrait
                else -> R.drawable.ic_orient_auto
            }
        )
        val muted = service?.isMuted() == true
        b.btnMute.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
        b.btnMute.setBackgroundResource(if (muted) R.drawable.bg_chip_on else R.drawable.bg_chip)
        b.btnBackground.setBackgroundResource(if (prefs.backgroundPlay) R.drawable.bg_chip_on else R.drawable.bg_chip)
    }

    private fun updateTitle() {
        val md = player?.mediaMetadata
        b.title.text = md?.title ?: player?.currentMediaItem?.mediaMetadata?.title ?: ""
    }

    private fun updateNavButtons() {
        val p = player ?: return
        b.btnPrev.alpha = if (p.hasPreviousMediaItem()) 1f else 0.35f
        b.btnNext.alpha = if (p.hasNextMediaItem()) 1f else 0.35f
    }

    private fun updatePlayButton() {
        val p = player ?: return
        b.btnPlay.setImageResource(if (PlaybackService.isActive(p)) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        val p = player ?: return
        if (!controlsShown || dragging) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0)
        b.seek.max = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        b.seek.progress = pos.coerceAtMost(dur).toInt()
        b.seek.secondaryProgress = p.bufferedPosition.coerceAtMost(dur).toInt()
        b.timePos.text = Format.duration(pos)
        b.timeLeft.text = "-" + Format.duration((dur - pos).coerceAtLeast(0))
    }

    private fun setControls(show: Boolean) {
        handler.removeCallbacks(hideControls)
        if (locked && show) return
        controlsShown = show
        b.controls.animate().cancel()
        if (show) {
            b.controls.visibility = View.VISIBLE
            b.controls.animate().alpha(1f).setDuration(150).start()
            updateProgress()
            scheduleHide()
        } else {
            b.speedPanel.visibility = View.GONE
            b.controls.animate().alpha(0f).setDuration(200).withEndAction {
                if (!controlsShown) b.controls.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        val p = player ?: return
        if (p.isPlaying && !dragging && b.settingsPanel.visibility != View.VISIBLE) {
            handler.postDelayed(hideControls, 3500)
        }
    }

    private fun flashLock() {
        b.lockOnly.visibility = View.VISIBLE
        handler.removeCallbacks(hideLockOnly)
        handler.postDelayed(hideLockOnly, 2500)
    }

    private fun showHud(text: String) {
        b.hud.text = text
        b.hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 900)
    }

    // ================================================================ picture

    private fun applyResizeMode() {
        // Always the whole picture first; Fill is a scale on top (see togglePicture).
        b.videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        updateResizeIcon(picScale)
    }

    private fun applyVideoSize(vs: VideoSize?) {
        if (vs == null || vs.width == 0 || vs.height == 0) return
        val ratio = vs.width * vs.pixelWidthHeightRatio / vs.height
        if (kotlin.math.abs(ratio - lastRatio) > 0.001f) {
            lastRatio = ratio
            resetPicture()
        }
        b.videoFrame.setAspectRatio(ratio)
        applyOrientationMode(vs)
    }

    private var lastRatio = 0f

    private fun preselectOrientation() {
        val first = Launch.pending?.let { it.first.getOrNull(it.second) }
        val extras = first?.mediaMetadata?.extras
        val w = extras?.getInt("shownW") ?: 0
        val h = extras?.getInt("shownH") ?: 0
        requestedOrientation = when (prefs.orientation) {
            Prefs.ORIENT_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Prefs.ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> when {
                w <= 0 || h <= 0 -> return
                w >= h -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    private fun applyOrientationMode(vs: VideoSize?) {
        requestedOrientation = when (prefs.orientation) {
            Prefs.ORIENT_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Prefs.ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> {
                // Auto: turn to match the video (wide video = landscape, vertical video = portrait).
                if (vs == null || vs.width == 0 || vs.height == 0) return
                val wide = vs.width * vs.pixelWidthHeightRatio >= vs.height
                if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    // ================================================================ gestures

    private fun setupGestures() {
        val dp = resources.displayMetrics.density
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    b.settingsPanel.visibility == View.VISIBLE -> closePanel()
                    locked -> flashLock()
                    else -> setControls(!controlsShown)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val p = player ?: return true
                val w = b.gestureLayer.width
                when {
                    e.x < w * 0.35f -> seekBy(-10_000)
                    e.x > w * 0.65f -> seekBy(10_000)
                    else -> {
                        togglePlay(p)
                        showHud(if (PlaybackService.isActive(p)) "Play" else "Pause")
                    }
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (locked || e1 == null) return false
                val h = b.gestureLayer.height.toFloat().coerceAtLeast(1f)
                val w = b.gestureLayer.width.toFloat().coerceAtLeast(1f)
                if (swipeMode == 0) {
                    val dx = abs(e2.x - e1.x)
                    val dy = abs(e2.y - e1.y)
                    if (dx > 16 * dp && dx > dy * 1.2f) {
                        // Pulling sideways anywhere on the screen: go back or forward in the video.
                        val p = player ?: return false
                        swipeMode = 3
                        seekDragStart = p.currentPosition
                        seekDragTarget = seekDragStart
                    } else {
                        // Up and down: brightness (left half) and volume (right half). The very top
                        // and bottom edges belong to the system gestures.
                        if (e1.y < 48 * dp || e1.y > h - 48 * dp) return false
                        if (dy < 12 * dp || dy < dx) return false
                        swipeMode = if (e1.x < w / 2f) 1 else 2
                        swipeStart = if (swipeMode == 1) currentBrightness()
                        else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    }
                }
                if (swipeMode == 3) {
                    val p = player ?: return true
                    val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return true
                    // A full screen width moves a quarter of the video, between 30 s and 10 min.
                    val span = (dur / 4).coerceIn(30_000L, 600_000L).toFloat()
                    val delta = ((e2.x - e1.x) / w * span).toLong()
                    seekDragTarget = (seekDragStart + delta).coerceIn(0, dur)
                    val diff = (seekDragTarget - seekDragStart) / 1000
                    showHud((if (diff < 0) "-" else "+") + abs(diff) + "s   " + Format.duration(seekDragTarget))
                    return true
                }
                val delta = (e1.y - e2.y) / (h * 0.75f)
                if (swipeMode == 1) {
                    val v = (swipeStart + delta).coerceIn(0.01f, 1f)
                    setBrightness(v)
                    showHud("Brightness " + (v * 100).roundToInt() + "%")
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (swipeStart + delta * max).roundToInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    showHud("Volume $v")
                }
                return true
            }
        })
        b.gestureLayer.setOnTouchListener { v, e ->
            handleTouch(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !ignoreUntilUp && !twoFinger) v.performClick()
            true
        }
    }

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreUntilUp = false
                twoFinger = false
                gestures.onTouchEvent(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (locked || e.pointerCount != 2) return
                // Second finger: cancel any tap or swipe in progress and start the picture gesture
                // right now, with no threshold to cross first.
                val cancel = MotionEvent.obtain(e)
                cancel.action = MotionEvent.ACTION_CANCEL
                gestures.onTouchEvent(cancel)
                cancel.recycle()
                swipeMode = 0
                beginTwoFinger(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) moveTwoFinger(e)
                else if (!ignoreUntilUp && e.pointerCount == 1) gestures.onTouchEvent(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFinger) endTwoFinger()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swipeMode == 3 && e.actionMasked == MotionEvent.ACTION_UP) {
                    player?.seekTo(seekDragTarget)
                    scheduleHide()
                }
                if (twoFinger) endTwoFinger()
                if (!ignoreUntilUp) gestures.onTouchEvent(e)
                swipeMode = 0
                twoFinger = false
                ignoreUntilUp = false
            }
            else -> {}
        }
    }

    /** Scale that makes the fitted frame cover the whole screen (1 when it already does). */
    private fun fillScale(): Float {
        val f = b.videoFrame
        val fw = f.width.toFloat()
        val fh = f.height.toFloat()
        if (fw <= 0f || fh <= 0f) return 1f
        return maxOf(b.root.width / fw, b.root.height / fh).coerceAtLeast(1f)
    }

    private fun focusOf(e: MotionEvent): Pair<Float, Float> =
        Pair((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f)

    private fun spanOf(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    }

    private fun beginTwoFinger(e: MotionEvent) {
        picAnimator?.cancel()
        twoFinger = true
        ignoreUntilUp = true
        gStartSpan = spanOf(e)
        gStartScale = picScale
        gStartScaleForSnap = picScale
        val (fx, fy) = focusOf(e)
        // The point of the picture under the fingers must stay under the fingers.
        gContentX = (fx - b.root.width / 2f - picTx) / picScale
        gContentY = (fy - b.root.height / 2f - picTy) / picScale
    }

    private fun moveTwoFinger(e: MotionEvent) {
        val fill = fillScale()
        val canScale = fill > 1.02f
        val scale = if (canScale) {
            (gStartScale * spanOf(e) / gStartSpan).coerceIn(0.75f, fill * 1.2f)
        } else picScale
        val (fx, fy) = focusOf(e)
        picScale = scale
        picTx = fx - b.root.width / 2f - scale * gContentX
        picTy = fy - b.root.height / 2f - scale * gContentY
        clampPicture(false)
        applyPicture()
    }

    private fun endTwoFinger() {
        twoFinger = false
        val fill = fillScale()
        val ratio = picScale / gStartScaleForSnap
        val target = when {
            fill <= 1.02f -> 1f
            ratio > 1.04f -> fill
            ratio < 0.96f -> 1f
            else -> if (gStartScaleForSnap > 1.02f) fill else 1f
        }
        animatePicture(target)
        showHud(if (target > 1.02f) "Fill" else "Fit")
        updateResizeIcon(target)
    }

    /** Keeps the picture covering what it should: centered when small, edge to edge when large. */
    private fun clampPicture(forScale: Boolean) {
        val f = b.videoFrame
        val maxX = ((f.width * picScale - b.root.width) / 2f).coerceAtLeast(0f)
        val maxY = ((f.height * picScale - b.root.height) / 2f).coerceAtLeast(0f)
        picTx = picTx.coerceIn(-maxX, maxX)
        picTy = picTy.coerceIn(-maxY, maxY)
        if (forScale && picScale <= 1.02f) {
            picTx = 0f
            picTy = 0f
        }
    }

    private fun applyPicture() {
        val f = b.videoFrame
        f.scaleX = picScale
        f.scaleY = picScale
        f.translationX = picTx
        f.translationY = picTy
    }

    private fun animatePicture(target: Float) {
        picAnimator?.cancel()
        val s0 = picScale
        val x0 = picTx
        val y0 = picTy
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 200
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            val t = it.animatedValue as Float
            picScale = s0 + (target - s0) * t
            // Fit goes back to the center; Fill keeps where you moved it (inside the limits).
            val k = if (target <= 1.02f) 1f - t else 1f
            picTx = x0 * k
            picTy = y0 * k
            clampPicture(false)
            applyPicture()
        }
        anim.start()
        picAnimator = anim
    }

    /** Fit and Fill button: flips between the two states. */
    private fun togglePicture() {
        val fill = fillScale()
        val target = if (picScale > 1.02f || fill <= 1.02f) 1f else fill
        animatePicture(target)
        showHud(if (target > 1.02f) "Fill" else "Fit")
        updateResizeIcon(target)
    }

    private fun updateResizeIcon(scale: Float) {
        b.btnResize.setImageResource(if (scale > 1.02f) R.drawable.ic_fit else R.drawable.ic_fill)
    }

    private fun resetPicture() {
        picAnimator?.cancel()
        picScale = 1f
        picTx = 0f
        picTy = 0f
        applyPicture()
        updateResizeIcon(1f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turning the phone: go back to the whole picture (Fit), not a stretched crop.
        resetPicture()
    }

    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0f) return w
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v }
        prefs.brightness = v
    }

    private fun applySavedBrightness() {
        val saved = prefs.brightness
        if (saved >= 0f) window.attributes = window.attributes.apply { screenBrightness = saved }
    }

    // ================================================================ settings panel

    private fun modeButtons() = listOf(b.modeOrder, b.modeRepeatOne, b.modeShuffle, b.modeRepeatAll, b.modeOnce)

    private fun setupPanel() {
        b.panelScrim.setOnClickListener { closePanel() }
        modeButtons().forEachIndexed { i, btn ->
            btn.setOnClickListener {
                service?.setRepeatMode(i)
                paintModes(i)
                showHud(modeNames[i])
            }
        }
        b.brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                b.brightnessValue.text = progress.toString()
                if (fromUser) setBrightness((progress / 100f).coerceAtLeast(0.01f))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        b.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                b.volumeValue.text = progress.toString()
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        b.timerRow.setOnClickListener { showTimer() }
        b.btnHw.setOnClickListener { switchDecoder(false) }
        b.btnSw.setOnClickListener { switchDecoder(true) }
        b.btnShare.setOnClickListener { share() }
        b.btnProperties.setOnClickListener { showProperties() }
    }

    private fun openPanel() {
        val svc = service
        paintModes(svc?.repeatMode() ?: prefs.repeatMode)
        paintDecoder(svc?.isSoftwareDecoder() ?: prefs.softwareDecoder)
        b.brightnessSeek.progress = (currentBrightness() * 100).roundToInt()
        b.volumeSeek.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        b.volumeSeek.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        b.volumeValue.text = b.volumeSeek.progress.toString()
        b.brightnessValue.text = b.brightnessSeek.progress.toString()
        setControls(false)
        b.panelScrim.visibility = View.VISIBLE
        b.settingsPanel.visibility = View.VISIBLE
        b.settingsPanel.translationX = 80 * resources.displayMetrics.density
        b.settingsPanel.alpha = 0f
        b.settingsPanel.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private fun closePanel() {
        b.settingsPanel.visibility = View.GONE
        b.panelScrim.visibility = View.GONE
        setControls(true)
    }

    private fun paintModes(mode: Int) {
        modeButtons().forEachIndexed { i, btn ->
            btn.setBackgroundResource(if (i == mode) R.drawable.bg_pill_on else android.R.color.transparent)
        }
        b.repeatLabel.text = modeNames.getOrElse(mode) { modeNames[0] }
    }

    private fun showTimer() {
        val svc = service ?: return
        val remaining = svc.sleepRemainingMs()
        val text = when {
            remaining < 0 -> null
            svc.isSleepEndOfItem() -> "Active: stops after this video"
            else -> "Active: stops in " + Format.duration(remaining)
        }
        Dialogs.sleepTimer(this, text, "End of video", { hideSystemUi() }) { v ->
            when {
                v == 0 -> svc.cancelSleep()
                v < 0 -> svc.setSleepAtEndOfItem()
                else -> svc.setSleepTimer(v)
            }
            showHud(
                when {
                    v == 0 -> "Sleep timer off"
                    v < 0 -> "Stops after this video"
                    else -> "Stops in $v min"
                }
            )
            updateTimer()
        }
    }

    /** Red countdown on the top bar and in the panel while a sleep timer runs. */
    private fun updateTimer() {
        val svc = service ?: return
        val remaining = svc.sleepRemainingMs()
        if (remaining < 0) {
            b.timerBadge.visibility = View.GONE
            b.timerValue.text = "Off"
            b.timerValue.setTextColor(getColor(R.color.muted))
        } else {
            val label = if (svc.isSleepEndOfItem()) "End of video" else Format.duration(remaining)
            b.timerBadge.visibility = View.VISIBLE
            b.timerBadge.text = label
            b.timerValue.text = label
            b.timerValue.setTextColor(getColor(R.color.red_hot))
        }
    }

    private fun paintDecoder(software: Boolean) {
        b.btnHw.setBackgroundResource(if (!software) R.drawable.bg_pill_on else R.drawable.bg_pill_dark)
        b.btnSw.setBackgroundResource(if (software) R.drawable.bg_pill_on else R.drawable.bg_pill_dark)
    }

    private fun switchDecoder(software: Boolean) {
        val svc = service ?: return
        if (svc.isSoftwareDecoder() == software) return
        paintDecoder(software)
        svc.setSoftwareDecoder(software)
        showHud(if (software) "SW Decoder" else "HW Decoder")
    }

    private fun share() {
        val uri: Uri = player?.currentMediaItem?.localConfiguration?.uri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/*")
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

        rows.add("Name" to (md.displayTitle ?: md.title ?: "").toString())
        val path = extras?.getString("path").takeUnless { it.isNullOrEmpty() }
            ?: item.localConfiguration?.uri?.toString() ?: ""
        rows.add("Location" to path)
        val size = extras?.getLong("size") ?: 0L
        if (size > 0) rows.add("Size" to Format.size(size))
        val modified = extras?.getLong("modified") ?: 0L
        if (modified > 0) rows.add("Date" to Format.fullDate(modified))

        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        rows.add("Duration" to Format.duration(dur))

        val vf = p.videoFormat
        if (vf != null) {
            if (vf.width > 0 && vf.height > 0) rows.add("Resolution" to "${vf.width} x ${vf.height}")
            if (vf.frameRate > 0f) rows.add("Frame rate" to String.format(java.util.Locale.US, "%.2f fps", vf.frameRate))
            rows.add("Video codec" to codecName(vf.sampleMimeType))
        }
        val decoder = service?.videoDecoderName
        if (decoder != null) {
            val sw = decoder.startsWith("c2.android", true) || decoder.startsWith("OMX.google", true)
            rows.add("Decoder" to "${if (sw) "SW" else "HW"}  ($decoder)")
        }
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
        if (size > 0 && dur > 0) {
            val kbps = size * 8 / dur // bits per millisecond = kbps
            rows.add("Bitrate" to "$kbps kbps")
        }
        Dialogs.properties(this, rows) { hideSystemUi() }
    }

    private fun codecName(mime: String?): String = when (mime) {
        null -> "Unknown"
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/vnd.dts" -> "DTS"
        "audio/raw" -> "PCM"
        "audio/3gpp" -> "AMR"
        else -> mime
    }
}
