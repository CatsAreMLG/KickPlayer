package app.kickplayer

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline

// Within this distance of the live point counts as "at live".
const val AT_LIVE_THRESHOLD_MS = 2_000L

// How much already-watched video the player keeps in memory for rewinding.
// Roughly 1 MB per second at 1080p60, so 3 minutes is about 180 MB.
const val BACK_BUFFER_MS = 180_000L

// Oldest part of Kick's live playlist worth seeking into. The very first
// segment can drop off the list before it finishes downloading.
private const val PLAYLIST_MARGIN_MS = 3_000L

// Which stretch of live video the player still holds in memory, measured in
// "period" time: a fixed timeline starting when the stream was opened, which
// doesn't slide when the playlist refreshes. The service writes it (it sees
// every seek, including ones from the watch or earbuds); the screen reads it
// to size the progress bar. Both run in the same process on the main thread.
private object RetainedBuffer {
    var startPeriodMs = C.TIME_UNSET
    var discardedBeforePeriodMs = Long.MIN_VALUE
    var bufferedToPeriodMs = C.TIME_UNSET

    fun reset() {
        startPeriodMs = C.TIME_UNSET
        discardedBeforePeriodMs = Long.MIN_VALUE
        bufferedToPeriodMs = C.TIME_UNSET
    }

    val earliestPeriodMs: Long?
        get() = if (startPeriodMs == C.TIME_UNSET) null
        else maxOf(startPeriodMs, discardedBeforePeriodMs)
}

// Kick's live playlist only covers the last ~30 seconds, and positions are
// measured inside that sliding window, which shifts every time the playlist
// refreshes. This tracks the live point (where the Live button jumps to), how
// far it has advanced in real time since the last refresh, and how far back
// you can rewind. ExoPlayer's built-in live offset can't do this for Kick
// because it needs wall-clock timestamps the streams don't include.
class LiveEdge(
    private val player: Player,
    private val tracksBuffer: Boolean,
) : Player.Listener {
    private var livePointMs = C.TIME_UNSET
    private var refreshedAt = 0L

    init {
        player.addListener(this)
        refresh()
    }

    fun release() = player.removeListener(this)

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (tracksBuffer) RetainedBuffer.reset()
    }

    // Errors and stops drop everything the player had buffered.
    override fun onPlaybackStateChanged(state: Int) {
        if (tracksBuffer && state == Player.STATE_IDLE) RetainedBuffer.reset()
    }

    // A seek outside what's held in memory makes the player throw its buffer
    // away and download fresh from the seek point.
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (!tracksBuffer || reason != Player.DISCONTINUITY_REASON_SEEK || !isActive) return
        val offset = periodOffsetMs() ?: return
        val earliest = RetainedBuffer.earliestPeriodMs ?: return
        val target = newPosition.positionMs - offset
        val bufferedTo = RetainedBuffer.bufferedToPeriodMs
        if (target < earliest || (bufferedTo != C.TIME_UNSET && target > bufferedTo)) {
            RetainedBuffer.startPeriodMs = target
            RetainedBuffer.discardedBeforePeriodMs = Long.MIN_VALUE
        }
    }

    private fun refresh() {
        val timeline = player.currentTimeline
        livePointMs = if (!player.isCurrentMediaItemLive || timeline.isEmpty) C.TIME_UNSET
        else timeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).defaultPositionMs
        refreshedAt = SystemClock.elapsedRealtime()
    }

    // Window position = period position + this (zero or negative).
    private fun periodOffsetMs(): Long? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        return timeline.getPeriod(player.currentPeriodIndex, Timeline.Period()).positionInWindowMs
    }

    // Called by the service twice a second to keep RetainedBuffer current.
    fun observe() {
        if (!tracksBuffer || !isActive) return
        val offset = periodOffsetMs() ?: return
        val position = player.currentPosition - offset
        if (RetainedBuffer.startPeriodMs == C.TIME_UNSET) {
            if (player.playbackState != Player.STATE_READY) return
            RetainedBuffer.startPeriodMs = position
        }
        // ExoPlayer discards anything older than BACK_BUFFER_MS behind the playhead.
        RetainedBuffer.discardedBeforePeriodMs =
            maxOf(RetainedBuffer.discardedBeforePeriodMs, position - BACK_BUFFER_MS)
        RetainedBuffer.bufferedToPeriodMs = player.bufferedPosition - offset
    }

    val isActive: Boolean
        get() = livePointMs != C.TIME_UNSET && player.isCurrentMediaItemLive

    // Live point as of the last playlist refresh, in window coordinates.
    val refreshedLivePointMs: Long
        get() = livePointMs

    // Real time since that refresh. Capped so a stalled or ended stream
    // doesn't report an ever-growing gap.
    fun driftMs(): Long =
        if (!isActive) 0L else (SystemClock.elapsedRealtime() - refreshedAt).coerceIn(0L, 60_000L)

    // Null for VODs.
    fun behindMs(): Long? {
        if (!isActive) return null
        return (livePointMs + driftMs() - player.currentPosition).coerceAtLeast(0L)
    }

    // Oldest seekable spot, in window coordinates (negative = older than
    // Kick's playlist, held in memory). Whichever reaches further back wins:
    // the playlist or what the player kept. Null for VODs.
    fun earliestWindowMs(): Long? {
        if (!isActive) return null
        val offset = periodOffsetMs() ?: return null
        // 500 ms of slack so a seek never lands just past the oldest kept frame.
        val retained = RetainedBuffer.earliestPeriodMs?.let { it + offset + 500 }
        return if (retained == null) PLAYLIST_MARGIN_MS else minOf(PLAYLIST_MARGIN_MS, retained)
    }
}

// What the on-screen controls see. During live streams the progress bar runs
// from the oldest point you can rewind to (left) to the live point (right), so
// being live puts the scrubber at the far right. VODs pass through unchanged.
@OptIn(UnstableApi::class)
class LiveBarPlayer(player: Player) : ForwardingPlayer(player) {
    val edge = LiveEdge(player, tracksBuffer = false)

    // Stops tracking without touching the player. Named detach() because
    // ForwardingPlayer.release() would release the underlying player.
    fun detach() = edge.release()

    private fun origin(): Long = edge.earliestWindowMs() ?: 0L

    private fun barLength(): Long = (edge.refreshedLivePointMs - origin()).coerceAtLeast(0L)

    private fun toBar(realMs: Long): Long =
        (realMs - edge.driftMs() - origin()).coerceIn(0L, barLength())

    private fun toReal(barMs: Long): Long =
        barMs.coerceIn(0L, barLength()) + origin() + edge.driftMs()

    override fun getCurrentTimeline(): Timeline {
        val timeline = super.getCurrentTimeline()
        return if (timeline.isEmpty || !edge.isActive) timeline
        else LivePointTimeline(timeline, origin() * 1000)
    }

    override fun getDuration(): Long =
        if (edge.isActive) barLength() else super.getDuration()

    override fun getContentDuration(): Long =
        if (edge.isActive) barLength() else super.getContentDuration()

    override fun getCurrentPosition(): Long =
        if (edge.isActive) toBar(super.getCurrentPosition()) else super.getCurrentPosition()

    override fun getContentPosition(): Long =
        if (edge.isActive) toBar(super.getContentPosition()) else super.getContentPosition()

    override fun getBufferedPosition(): Long =
        if (edge.isActive) toBar(super.getBufferedPosition()) else super.getBufferedPosition()

    override fun getContentBufferedPosition(): Long =
        if (edge.isActive) toBar(super.getContentBufferedPosition()) else super.getContentBufferedPosition()

    // Dragging the scrubber maps back from bar position to real position.
    override fun seekTo(positionMs: Long) =
        super.seekTo(if (edge.isActive) toReal(positionMs) else positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) =
        super.seekTo(mediaItemIndex, if (edge.isActive) toReal(positionMs) else positionMs)

    private class LivePointTimeline(timeline: Timeline, private val originUs: Long) :
        ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            if (window.isLive() && window.defaultPositionUs != C.TIME_UNSET) {
                window.durationUs = (window.defaultPositionUs - originUs).coerceAtLeast(0L)
            }
            return window
        }
    }
}
