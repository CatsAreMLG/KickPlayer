package app.kickplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Owns the player and the media session. The session is what gives you the
// notification, lock screen, Pixel Watch, and earbud controls.
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var liveEdge: LiveEdge
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshAttempted = false
    private val handler = Handler(Looper.getMainLooper())

    // Saves VOD progress every 5 seconds, so at most 5 seconds are lost if
    // the app gets killed or you switch videos.
    private val saveProgress = object : Runnable {
        override fun run() {
            if (exo.isPlaying) saveVodPosition()
            handler.postDelayed(this, 5_000)
        }
    }

    // Above 1x you eventually catch up to the live edge, where there is nothing
    // left to play. Checks twice a second and drops back to 1x on arrival.
    private val catchUpCheck = object : Runnable {
        override fun run() {
            liveEdge.observe()
            if (exo.playbackParameters.speed > 1f) {
                val behind = liveEdge.behindMs()
                if (behind != null && behind < AT_LIVE_THRESHOLD_MS) exo.setPlaybackSpeed(1f)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Keeps downloading up to 2 minutes ahead while paused, so short pauses
        // resume exactly where you stopped, and keeps the last 3 minutes of
        // watched video so rewinding works past Kick's 30 second live playlist.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(BACK_BUFFER_MS.toInt(), /* retainBackBufferFromKeyframe= */ true)
            .build()

        exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true) // pauses when earbuds disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK) // keeps Wi-Fi and CPU awake with the screen off
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        liveEdge = LiveEdge(exo, tracksBuffer = true)
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .withQuality(Prefs.qualityHeight(this))
            .withCaptions(Prefs.captionsOn(this))
        handler.post(catchUpCheck)
        handler.post(saveProgress)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) refreshAttempted = false
                if (state == Player.STATE_ENDED) {
                    KickApi.vodUuidOf(exo.currentMediaItem?.mediaId)?.let {
                        VodPositions.clear(this@PlaybackService, it)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveVodPosition()
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    // Paused longer than the stream's rewind window: go to live.
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    // Stream URLs can expire; fetch a fresh one once.
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                        !refreshAttempted -> refreshStream()
                }
            }
        })

        // Every command (app, notification, watch, earbuds) passes through here.
        val player = object : ForwardingPlayer(exo) {
            // On live, back 10s reaches as far as the player still holds video,
            // which can be older than Kick's playlist. At the oldest point it
            // does nothing, and it never jumps you forward.
            override fun seekBack() {
                val earliest = liveEdge.earliestWindowMs() ?: return super.seekBack()
                val position = exo.currentPosition
                if (position <= earliest) return
                super.seekTo((position - exo.seekBackIncrement).coerceAtLeast(earliest))
            }

            // On live, forward 10s never goes past live: at live it does nothing,
            // and within 10s of live it lands exactly on live. Applies to the
            // on-screen button, PiP, and anything else that sends "skip forward".
            override fun seekForward() {
                val behind = liveEdge.behindMs() ?: return super.seekForward()
                when {
                    behind < AT_LIVE_THRESHOLD_MS -> Unit
                    behind <= exo.seekForwardIncrement -> exo.seekToDefaultPosition()
                    else -> super.seekForward()
                }
            }
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> {
                    val items = mediaItems.map { item ->
                        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                        item.buildUpon()
                            .setUri(uri)
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .setLiveConfiguration(LIVE_CONFIG)
                            .build()
                    }
                    return Futures.immediateFuture(items)
                }

                // Pressing play after Android closed the app (earbuds, watch, lock
                // screen) reloads whatever you watched last: a VOD at its saved
                // spot, or a channel at live.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val last = Recents.all(this@PlaybackService).firstOrNull()
                        ?: return Futures.immediateFailedFuture(IllegalStateException("Nothing to resume"))
                    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    scope.launch {
                        runCatching {
                            if (last.type == RecentItem.VOD) {
                                val stream = KickApi.resolveVod(this@PlaybackService, last.id)
                                val saved = VodPositions.get(this@PlaybackService, last.id)
                                MediaSession.MediaItemsWithStartPosition(
                                    listOf(stream.toMediaItem()), 0,
                                    if (saved > 0) saved else C.TIME_UNSET,
                                )
                            } else {
                                val stream = KickApi.resolve(this@PlaybackService, last.id)
                                MediaSession.MediaItemsWithStartPosition(
                                    listOf(stream.toMediaItem()), 0, C.TIME_UNSET,
                                )
                            }
                        }.onSuccess { future.set(it) }.onFailure { future.setException(it) }
                    }
                    return future
                }
            })
            .build()

        // Stay in the foreground (so Android won't close the app and drop the
        // loaded video) for 10 minutes after pausing. 10 minutes is the most
        // Media3 allows; past that, pressing play resumes via onPlaybackResumption.
        setForegroundServiceTimeoutMs(MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS)
    }

    private fun saveVodPosition() {
        val uuid = KickApi.vodUuidOf(exo.currentMediaItem?.mediaId) ?: return
        val position = exo.currentPosition
        val duration = exo.duration
        when {
            // Within the last minute counts as finished, so it starts fresh next time.
            duration != C.TIME_UNSET && duration - position < 60_000 ->
                VodPositions.clear(this, uuid)
            // Skips the first 10 seconds, which also avoids saving 0 while a video loads.
            position > 10_000 -> VodPositions.save(this, uuid, position)
        }
    }

    private fun refreshStream() {
        val id = exo.currentMediaItem?.mediaId ?: return
        refreshAttempted = true
        val keepPlaying = exo.playWhenReady
        val wasLive = exo.isCurrentMediaItemLive
        val position = exo.currentPosition
        scope.launch {
            runCatching { KickApi.resolveById(this@PlaybackService, id) }
                .onSuccess { stream ->
                    // A VOD picks up at the same spot; live goes back to the live edge.
                    if (wasLive) exo.setMediaItem(stream.toMediaItem())
                    else exo.setMediaItem(stream.toMediaItem(), position)
                    exo.prepare()
                    exo.playWhenReady = keepPlaying
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away from recent apps (or dismissing the picture-in-picture
    // window) closes it completely: playback stops, the notification goes away,
    // and the VOD position is saved for next time.
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveVodPosition()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        saveVodPosition()
        liveEdge.release()
        handler.removeCallbacks(catchUpCheck)
        handler.removeCallbacks(saveProgress)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
