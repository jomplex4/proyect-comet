package com.digitalminds.comet.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.digitalminds.comet.data.isAudioItem
import com.digitalminds.comet.ui.MusicPlayerActivity
import com.digitalminds.comet.ui.PlayerActivity
import com.digitalminds.comet.util.HistoryEntry
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
    private var fadeBase = -1f

    private val sleepTick = object : Runnable {
        override fun run() {
            val remaining = sleepRemainingMs()
            if (remaining < 0) return
            if (sleepAt > 0 && remaining <= 0) {
                finishSleep()
                return
            }
            if (remaining <= FADE_MS && player.isPlaying) {
                if (fadeBase < 0f) fadeBase = player.volume
                player.volume = fadeBase * (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f)
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
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfItem) {
                finishSleep()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> logHistory()
                Player.STATE_ENDED -> savePosition()
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePosition()
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

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == ACTION_LOCAL_BIND) return binder
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            savePosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        hosts.toList().forEach { it.onServiceGone() }
        hosts.clear()
        nowPlayingKey = null
        handler.removeCallbacksAndMessages(null)
        savePosition()
        session?.release()
        session = null
        effectSession(player.audioSessionId, false)
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

    /** Mute / unmute that also plays well with a sleep-timer fade in progress. Returns the new state. */
    fun toggleMute(): Boolean {
        val mutedNow = if (fadeBase >= 0f) fadeBase == 0f else player.volume == 0f
        val muted = !mutedNow
        if (fadeBase >= 0f) fadeBase = if (muted) 0f else 1f
        player.volume = if (muted) 0f else 1f
        return muted
    }

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
        if (fadeBase >= 0f) {
            player.volume = fadeBase
            fadeBase = -1f
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
        effectSession(p.audioSessionId, true)
        return p
    }

    /** Lets the phone equalizer (Samsung SoundAlive, etc.) attach to COMET's audio. */
    private fun effectSession(sessionId: Int, open: Boolean) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        val action = if (open) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        try {
            sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
        } catch (e: Exception) {
            // No equalizer app on the phone; nothing to tell.
        }
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
        val volume = old.volume
        val tracks = old.trackSelectionParameters
        savePosition()
        old.removeListener(playerListener)
        effectSession(old.audioSessionId, false)
        old.release()
        videoDecoderName = null

        val p = buildPlayer(software)
        p.playbackParameters = params
        p.volume = volume
        p.trackSelectionParameters = tracks
        if (sleepEndOfItem) p.pauseAtEndOfMediaItems = true
        player = p
        if (items.isNotEmpty()) {
            lastKey = items.getOrNull(index)?.mediaId
            p.setMediaItems(items, index, position)
            p.prepare()
            p.playWhenReady = playWhenReady
        }
        session?.setPlayer(p)
        hosts.toList().forEach { it.onPlayerReplaced(p) }
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
        player.setMediaItems(items, i, start)
        player.prepare()
        player.play()
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
        lastKey = null
        nowPlayingKey = null
        stopSelf()
    }

    fun savePosition() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        PositionStore.save(item.mediaId, position, duration)
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
