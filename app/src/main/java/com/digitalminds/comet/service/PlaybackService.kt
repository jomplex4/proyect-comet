package com.digitalminds.comet.service

import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.digitalminds.comet.data.AudioRepo
import com.digitalminds.comet.data.MediaRepo
import com.digitalminds.comet.data.isAudioItem
import com.digitalminds.comet.ui.MusicPlayerActivity
import com.digitalminds.comet.ui.PlayerActivity
import com.digitalminds.comet.util.HistoryEntry
import com.digitalminds.comet.util.LastSession
import com.digitalminds.comet.util.PositionStore
import com.digitalminds.comet.util.Prefs
import com.digitalminds.comet.util.Thumbs
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Owns the one and only player. Because it is a MediaSessionService, Android draws the
 * standard media notification (artwork, title, seek bar, prev / play / next) and the lock
 * screen controls, and playback keeps going with the screen off (Background Play).
 */
class PlaybackService : MediaSessionService() {

    interface Host {
        /** Called when the decoder switch rebuilds the player. */
        fun onPlayerReplaced(newPlayer: ExoPlayer)

        /** The service is going away; drop any reference to its player. */
        fun onServiceGone() {}
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    companion object {
        const val ACTION_LOCAL_BIND = "com.digitalminds.comet.action.LOCAL_BIND"

        const val MODE_ORDER = 0
        const val MODE_REPEAT_ONE = 1
        const val MODE_SHUFFLE = 2
        const val MODE_REPEAT_ALL = 3
        const val MODE_ONCE = 4

        const val TEN_MINUTES = 10 * 60 * 1000L
        private const val FADE_MS = 5000L

        /** A paused player keeps its notification and its place for this long. */
        private const val HOLD_MS = 60 * 60 * 1000L

        /** What the play / pause button should show: playing, or about to (buffering). */
        fun isActive(p: Player): Boolean =
            p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE

        /** What is loaded right now, for the "now playing" red row in lists. */
        @Volatile
        var nowPlayingKey: String? = null
            private set
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var session: MediaSession? = null
    private val hosts = LinkedHashSet<Host>()

    lateinit var player: ExoPlayer
        private set

    /** Name of the video decoder actually in use (shown in Properties). */
    var videoDecoderName: String? = null
        private set

    private var lastKey: String? = null
    private var lastDuration: Long = 0
    private var historyLoggedFor: String? = null
    private var sessionIsAudio: Boolean? = null

    // Sleep timer: absolute end time, or "end of the current file". Volume fades out at the end.
    private var sleepAt = 0L
    private var sleepEndOfItem = false

    // Volume = mute switch x start ramp x sleep fade. Every change goes through applyVolume(),
    // so a fade-in, a fade-out and the mute button never fight each other.
    // Paused but not forgotten: the service stays in the foreground so Android does not close it.
    private var pausedSince = 0L
    private val io = Executors.newSingleThreadExecutor()

    private val holdTick = object : Runnable {
        override fun run() {
            if (player.mediaItemCount == 0 || player.playWhenReady || !wantsNotification()) {
                pausedSince = 0L
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (pausedSince == 0L) pausedSince = now
            if (now - pausedSince > HOLD_MS) {
                // Long forgotten. If you are looking at the app we leave it; otherwise let go.
                if (hosts.isEmpty()) stopAndClear()
                return
            }
            promoteForeground()
            handler.postDelayed(this, 30_000)
        }
    }

    /** Re-attaches the paused notification to the foreground service (Media3 lets go when you pause). */
    private fun promoteForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val posted = nm.activeNotifications.firstOrNull {
                it.id == DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
            } ?: return
            ServiceCompat.startForeground(
                this, posted.id, posted.notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            // The system may refuse while the app is in the background; the next try will do it.
        }
    }

    /** Called whenever the player pauses or its notification changes while paused. */
    private fun holdWhilePaused() {
        handler.removeCallbacks(holdTick)
        if (player.mediaItemCount == 0 || player.playWhenReady) {
            pausedSince = 0L
            return
        }
        if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
        // Media3 updates the notification a moment after the pause (and again when the cover
        // loads), so we re-attach a few times, then keep checking every 30 seconds.
        handler.postDelayed({ if (!player.playWhenReady) promoteForeground() }, 400)
        handler.postDelayed({ if (!player.playWhenReady) promoteForeground() }, 1500)
        handler.postDelayed({ if (!player.playWhenReady) promoteForeground() }, 4000)
        handler.postDelayed(holdTick, 30_000)
    }

    private var userMuted = false
    private var rampFactor = 1f
    private var rampFrom = 1f
    private var rampStart = 0L
    private var rampMs = 1L
    private var sleepFactor = 1f
    private var awaitingFirstFrame = false

    private fun applyVolume() {
        player.volume = if (userMuted) 0f else (rampFactor * sleepFactor).coerceIn(0f, 1f)
    }

    private val rampTick = object : Runnable {
        override fun run() {
            val t = ((SystemClock.elapsedRealtime() - rampStart).toFloat() / rampMs).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t) // smooth start and end, no click
            rampFactor = rampFrom + (1f - rampFrom) * eased
            applyVolume()
            if (t < 1f) handler.postDelayed(this, 16)
        }
    }

    /** Raises the volume from where it is to full, gently. */
    private fun startFadeIn(ms: Long) {
        handler.removeCallbacks(rampTick)
        rampFrom = rampFactor
        rampStart = SystemClock.elapsedRealtime()
        rampMs = ms.coerceAtLeast(1)
        handler.post(rampTick)
    }

    private val firstFrameFallback = Runnable {
        if (awaitingFirstFrame) {
            awaitingFirstFrame = false
            startFadeIn(300)
        }
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            val remaining = sleepRemainingMs()
            if (remaining < 0) return
            if (sleepAt > 0 && remaining <= 0) {
                finishSleep()
                return
            }
            if (remaining <= FADE_MS && player.isPlaying) {
                sleepFactor = (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f)
                applyVolume()
            } else {
                restoreFade()
            }
            handler.postDelayed(this, 100)
        }
    }

    private val saver = object : Runnable {
        override fun run() {
            if (player.isPlaying) savePosition()
            handler.postDelayed(this, 2000)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prev = lastKey
            if (prev != null && mediaItem != null && prev != mediaItem.mediaId &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastDuration > 0
            ) {
                PositionStore.save(prev, lastDuration, lastDuration)
            }
            historyLoggedFor = null
            if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                val resume = PositionStore.resumeFor(mediaItem.mediaId, minResumeDuration(mediaItem))
                if (resume > 0) player.seekTo(resume)
            }
            lastKey = mediaItem?.mediaId
            lastDuration = 0
            nowPlayingKey = mediaItem?.mediaId
            updateSessionActivity(mediaItem)
            if (mediaItem != null) LastSession.saveIndex(player.currentMediaItemIndex)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfItem) {
                finishSleep()
            }
            if (!playWhenReady) holdWhilePaused() else pausedSince = 0L
            // Coming back from pause: bring the sound up softly instead of cutting in.
            if (playWhenReady && !awaitingFirstFrame && sleepFactor == 1f) {
                rampFactor = 0f
                applyVolume()
                startFadeIn(160)
            }
        }

        override fun onRenderedFirstFrame() {
            if (awaitingFirstFrame) {
                awaitingFirstFrame = false
                handler.removeCallbacks(firstFrameFallback)
                startFadeIn(300)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (player.mediaItemCount == 0) return
            LastSession.saveQueue(
                List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                player.currentMediaItem?.isAudioItem() == true
            )
            LastSession.saveIndex(player.currentMediaItemIndex)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePosition()
            if (!isPlaying && !player.playWhenReady) holdWhilePaused()
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            // Video turned off (Background Play) or back on: the notification may need to appear or go.
            refreshNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> logHistory()
                Player.STATE_ENDED -> savePosition()
                else -> {}
            }
        }

    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        PositionStore.init(this)
        Thumbs.init(this)
        player = buildPlayer(prefs.softwareDecoder)

        val s = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent(false))
            .setBitmapLoader(CacheBitmapLoader(ThumbBitmapLoader(this)))
            .build()
        session = s
        addSession(s)
        handler.post(saver)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // ---------------------------------------------------------------- notification

    /**
     * The notification is for music, and for a video that keeps going as audio (Background
     * Play). A video you are watching on screen needs none.
     */
    private fun wantsNotification(): Boolean {
        val item = player.currentMediaItem ?: return true
        if (item.isAudioItem()) return true
        return player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (wantsNotification()) {
            super.onUpdateNotification(session, startInForegroundRequired)
            if (!player.playWhenReady && player.mediaItemCount > 0) holdWhilePaused()
        } else {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }
    }

    private fun refreshNotification() {
        val s = session ?: return
        val state = player.playbackState
        val running = player.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        try {
            onUpdateNotification(s, running)
        } catch (e: Exception) {
            // The system may refuse to start a foreground service right now; playback is not affected.
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == ACTION_LOCAL_BIND) return binder
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (player.mediaItemCount == 0) {
            stopSelf()
        } else if (!player.playWhenReady) {
            // Swiped away while paused: the notification stays for a while, like a music app.
            holdWhilePaused()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(holdTick)
        hosts.toList().forEach { it.onServiceGone() }
        hosts.clear()
        io.shutdown()
        nowPlayingKey = null
        handler.removeCallbacksAndMessages(null)
        savePosition()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    /** Tapping the notification opens the right screen: video player or music player. */
    private fun activityIntent(audio: Boolean): PendingIntent {
        val target = if (audio) MusicPlayerActivity::class.java else PlayerActivity::class.java
        val open = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, if (audio) 1 else 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateSessionActivity(item: MediaItem?) {
        val audio = item?.isAudioItem() == true
        if (sessionIsAudio == audio) return
        sessionIsAudio = audio
        session?.setSessionActivity(activityIntent(audio))
    }

    /** Songs always start from the top; long audios (podcasts, talks) and videos resume. */
    private fun minResumeDuration(item: MediaItem): Long = if (item.isAudioItem()) TEN_MINUTES else 0L

    fun isAudioNow(): Boolean = player.currentMediaItem?.isAudioItem() == true

    // ---------------------------------------------------------------- sleep timer

    fun setSleepTimer(minutes: Int) {
        cancelSleep()
        if (minutes <= 0) return
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.post(sleepTick)
    }

    fun setSleepAtEndOfItem() {
        cancelSleep()
        sleepEndOfItem = true
        player.pauseAtEndOfMediaItems = true
        handler.post(sleepTick)
    }

    fun cancelSleep() {
        handler.removeCallbacks(sleepTick)
        restoreFade()
        if (sleepEndOfItem) {
            sleepEndOfItem = false
            applyMode(player, prefs.repeatMode)
        }
        sleepAt = 0L
    }

    fun isSleepEndOfItem(): Boolean = sleepEndOfItem

    /** Mute / unmute. Returns the new state. */
    fun toggleMute(): Boolean {
        userMuted = !userMuted
        applyVolume()
        return userMuted
    }

    fun isMuted(): Boolean = userMuted

    /** Milliseconds until the timer stops playback, or -1 when no timer is set. */
    fun sleepRemainingMs(): Long {
        if (sleepAt > 0) return (sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        if (sleepEndOfItem) {
            val dur = player.duration
            if (dur == C.TIME_UNSET || dur <= 0) return Long.MAX_VALUE
            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
            return ((dur - player.currentPosition) / speed).toLong().coerceAtLeast(0)
        }
        return -1
    }

    private fun restoreFade() {
        if (sleepFactor != 1f) {
            sleepFactor = 1f
            applyVolume()
        }
    }

    private fun finishSleep() {
        handler.removeCallbacks(sleepTick)
        player.pause()
        restoreFade()
        val wasEndOfItem = sleepEndOfItem
        sleepAt = 0L
        sleepEndOfItem = false
        if (wasEndOfItem) applyMode(player, prefs.repeatMode)
        savePosition()
    }

    fun addHost(h: Host) { hosts.add(h) }
    fun removeHost(h: Host) { hosts.remove(h) }

    // ---------------------------------------------------------------- player

    private fun buildPlayer(software: Boolean): ExoPlayer {
        // HW: phone chip decoders first. SW: Android software decoders first.
        // Either way, if the preferred decoder fails the next one is tried automatically.
        val selector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            if (!MimeTypes.isVideo(mimeType)) {
                infos
            } else {
                infos.sortedBy { info ->
                    if (software) (if (info.softwareOnly) 0 else 1)
                    else (if (info.hardwareAccelerated) 0 else 1)
                }
            }
        }
        val renderers = DefaultRenderersFactory(this)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(true) // 24 / 32 bit files keep their depth instead of being cut to 16 bit
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(playerListener)
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                videoDecoderName = decoderName
            }
        })
        applyMode(p, prefs.repeatMode)
        return p
    }

    private fun applyMode(p: ExoPlayer, mode: Int) {
        when (mode) {
            MODE_REPEAT_ONE -> {
                p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_ONE; p.pauseAtEndOfMediaItems = false
            }
            MODE_SHUFFLE -> {
                p.shuffleModeEnabled = true; p.repeatMode = Player.REPEAT_MODE_ALL; p.pauseAtEndOfMediaItems = false
            }
            MODE_REPEAT_ALL -> {
                p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_ALL; p.pauseAtEndOfMediaItems = false
            }
            MODE_ONCE -> {
                p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_OFF; p.pauseAtEndOfMediaItems = true
            }
            else -> {
                p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_OFF; p.pauseAtEndOfMediaItems = false
            }
        }
    }

    fun setRepeatMode(mode: Int) {
        prefs.repeatMode = mode
        applyMode(player, mode)
        if (sleepEndOfItem) player.pauseAtEndOfMediaItems = true
    }

    fun repeatMode(): Int = prefs.repeatMode

    fun isSoftwareDecoder(): Boolean = prefs.softwareDecoder

    /** Rebuilds the player with the other decoder family, keeping position and queue. */
    fun setSoftwareDecoder(software: Boolean) {
        if (software == prefs.softwareDecoder) return
        prefs.softwareDecoder = software
        restoreFade()
        val old = player
        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        val params = old.playbackParameters
        val tracks = old.trackSelectionParameters
        savePosition()
        old.removeListener(playerListener)
        old.release()
        videoDecoderName = null

        val p = buildPlayer(software)
        p.playbackParameters = params
        p.trackSelectionParameters = tracks
        if (sleepEndOfItem) p.pauseAtEndOfMediaItems = true
        player = p
        applyVolume()
        if (items.isNotEmpty()) {
            lastKey = items.getOrNull(index)?.mediaId
            p.setMediaItems(items, index, position)
            p.prepare()
            p.playWhenReady = playWhenReady
        }
        session?.setPlayer(p)
        hosts.toList().forEach { it.onPlayerReplaced(p) }
    }

    private fun setAttributesFor(audio: Boolean) {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(if (audio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
    }

    /**
     * Android closed the app while it was paused and you came back: put the queue and the exact
     * position back, paused, so the play button works. [onDone] gets false when there is nothing
     * to restore.
     */
    fun restoreLast(onDone: (Boolean) -> Unit) {
        val snap = LastSession.load()
        if (snap == null) {
            onDone(false)
            return
        }
        io.execute {
            val items = try {
                if (snap.audio) {
                    AudioRepo.loadSongs(applicationContext)
                    AudioRepo.byKeys(snap.keys).map { AudioRepo.toMediaItem(it) }
                } else {
                    MediaRepo.loadVideos(applicationContext)
                    MediaRepo.byKeys(snap.keys).map { MediaRepo.toMediaItem(it) }
                }
            } catch (e: Exception) {
                emptyList()
            }
            handler.post {
                if (items.isEmpty()) {
                    onDone(false)
                    return@post
                }
                if (player.mediaItemCount == 0) {
                    val wanted = snap.keys.getOrNull(snap.index)
                    val idx = items.indexOfFirst { it.mediaId == wanted }.coerceAtLeast(0)
                    setAttributesFor(snap.audio)
                    lastKey = items[idx].mediaId
                    nowPlayingKey = items[idx].mediaId
                    updateSessionActivity(items[idx])
                    val pos = if (items[idx].mediaId == wanted) snap.positionMs else 0L
                    player.setMediaItems(items, idx, pos)
                    LastSession.savePosition(pos)
                    player.prepare()
                    player.playWhenReady = false
                }
                onDone(true)
            }
        }
    }

    /** One tap on play / pause, correct whatever state the player is in. */
    fun togglePlay() {
        val p = player
        if (isActive(p)) {
            p.pause()
            return
        }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(p.currentMediaItemIndex)
        p.play()
    }

    /** Starts a queue (a whole folder) at [index], resuming where that file was left. */
    fun playQueue(items: List<MediaItem>, index: Int) {
        if (items.isEmpty()) return
        savePosition()
        val i = index.coerceIn(0, items.size - 1)
        val start = PositionStore.resumeFor(items[i].mediaId, minResumeDuration(items[i]))
        lastKey = items[i].mediaId
        nowPlayingKey = items[i].mediaId
        updateSessionActivity(items[i])
        lastDuration = 0
        val audio = items[i].isAudioItem()
        setAttributesFor(audio)
        // Start silent and rise gently: songs right away, videos when the first picture is on screen.
        handler.removeCallbacks(rampTick)
        handler.removeCallbacks(firstFrameFallback)
        rampFactor = 0f
        applyVolume()
        awaitingFirstFrame = !audio
        player.setMediaItems(items, i, start)
        player.prepare()
        player.play()
        if (audio) startFadeIn(260) else handler.postDelayed(firstFrameFallback, 1500)
    }

    fun next() {
        savePosition()
        player.seekToNextMediaItem()
    }

    fun previous() {
        savePosition()
        player.seekToPreviousMediaItem()
    }

    /** Leaves nothing playing (used when the player closes without Background Play). */
    fun stopAndClear() {
        savePosition()
        player.stop()
        player.clearMediaItems()
        cancelSleep()
        LastSession.clear()
        lastKey = null
        nowPlayingKey = null
        pausedSince = 0L
        handler.removeCallbacks(holdTick)
        stopSelf()
    }

    fun savePosition() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        PositionStore.save(item.mediaId, position, duration)
        LastSession.savePosition(player.currentPosition)
        lastKey = item.mediaId
        lastDuration = duration
    }

    private fun logHistory() {
        val item = player.currentMediaItem ?: return
        if (historyLoggedFor == item.mediaId) return
        historyLoggedFor = item.mediaId
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        lastDuration = duration
        val isVideo = player.currentTracks.containsType(C.TRACK_TYPE_VIDEO)
        // History rule: every video; audio only when it is long (podcasts, talks).
        if (!isVideo && duration < TEN_MINUTES) return
        val md = item.mediaMetadata
        PositionStore.addHistory(
            HistoryEntry(
                uri = item.mediaId,
                title = (md.title ?: "").toString(),
                bucketId = md.extras?.getString("bucket") ?: "",
                durationMs = duration,
                isVideo = isVideo
            )
        )
    }

    /** Notification artwork: the video thumbnail Android already keeps for the file. */
    private class ThumbBitmapLoader(
        private val ctx: Context,
        private val base: BitmapLoader = DataSourceBitmapLoader(ctx)
    ) : BitmapLoader by base {

        private val exec = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            if (uri.scheme == "content" || uri.scheme == "file") {
                return exec.submit(Callable {
                    Thumbs.fetch(ctx, uri, 512, 288) ?: throw IOException("No thumbnail")
                })
            }
            return base.loadBitmap(uri)
        }

        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
            metadata.artworkData?.let { return base.decodeBitmap(it) }
            metadata.artworkUri?.let { return loadBitmap(it) }
            return null
        }
    }
}
