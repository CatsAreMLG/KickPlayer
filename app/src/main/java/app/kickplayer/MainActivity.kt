package app.kickplayer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var controls: View
    private lateinit var channelInput: EditText
    private lateinit var status: TextView
    private lateinit var playerFrame: View
    private lateinit var playerView: PlayerView
    private lateinit var infoSection: View
    private lateinit var infoTitle: TextView
    private lateinit var infoAvatar: ImageView
    private lateinit var infoStreamer: TextView
    private lateinit var infoDetail: TextView
    private lateinit var fullscreenTitle: TextView
    private var controlsVisible = false
    private var openMenus = 0
    private lateinit var homeScroll: View
    private lateinit var homeList: LinearLayout
    private var liveLabel: TextView? = null
    private lateinit var seekBackHint: TextView
    private lateinit var seekForwardHint: TextView
    private lateinit var zoomHint: TextView
    // Fullscreen zoom: 1 = whole picture. pan is the video's offset from center.
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var multiTouch = false
    private var barPlayer: LiveBarPlayer? = null

    private var isFullscreen = false
    // In fullscreen, turning the phone upright leaves fullscreen, but only once
    // the phone has actually been sideways (so tapping the button while holding
    // the phone upright doesn't exit immediately).
    private var exitWhenUpright = false
    // After leaving fullscreen, the screen holds portrait until the phone is
    // upright again, then normal auto-rotate resumes.
    private var waitingForUpright = false

    private val channelStatuses = mutableMapOf<String, ChannelStatus>()
    private var statusJob: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }
            ?.let { runCatching { it.get() }.getOrNull() }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val liveLabelUpdater = object : Runnable {
        override fun run() {
            updateLiveLabel()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val upright = degrees <= 20 || degrees >= 340
                val sideways = degrees in 60..120 || degrees in 240..300
                when {
                    isFullscreen && sideways -> exitWhenUpright = true
                    isFullscreen && upright && exitWhenUpright -> toggleFullscreenButton()
                    waitingForUpright && upright -> {
                        waitingForUpright = false
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = toggleFullscreenButton()
    }

    // Handles the buttons inside the picture-in-picture window.
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = controller ?: return
            when (intent.action) {
                ACTION_TOGGLE -> if (c.isPlaying) c.pause() else c.play()
                ACTION_BACK -> c.seekBack()
                ACTION_FORWARD -> c.seekForward()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            // Screen stays on while video plays; normal sleep timeout while paused.
            playerView.keepScreenOn = isPlaying
        }
        override fun onPlayerError(error: PlaybackException) {
            status.text = "Playback error: ${error.errorCodeName}"
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateInfo()
        override fun onEvents(player: Player, events: Player.Events) = applyLayoutMode()
        // Runs after PlayerView's own caption update and replaces it with a trimmed version.
        override fun onCues(cueGroup: CueGroup) {
            playerView.subtitleView?.setCues(trimCues(cueGroup.cues))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controls = findViewById(R.id.controls)
        channelInput = findViewById(R.id.channelInput)
        status = findViewById(R.id.status)
        playerFrame = findViewById(R.id.playerFrame)
        playerView = findViewById(R.id.playerView)
        infoSection = findViewById(R.id.infoSection)
        infoTitle = findViewById(R.id.infoTitle)
        infoAvatar = findViewById(R.id.infoAvatar)
        infoStreamer = findViewById(R.id.infoStreamer)
        infoDetail = findViewById(R.id.infoDetail)
        fullscreenTitle = findViewById(R.id.fullscreenTitle)
        homeScroll = findViewById(R.id.homeScroll)
        homeList = findViewById(R.id.homeList)
        seekBackHint = findViewById(R.id.seekBackHint)
        seekForwardHint = findViewById(R.id.seekForwardHint)
        zoomHint = findViewById(R.id.zoomHint)

        // The rewind/forward buttons use the 10 second increments set in PlaybackService.
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        // Setting a listener is what makes PlayerView show its fullscreen button.
        playerView.setFullscreenButtonClickListener { full -> onFullscreenButton(full) }
        setupCaptionsButton()
        setupSettingsButton()
        setupGestures()
        setupLiveLabel()

        channelInput.setText(Prefs.lastChannel(this))
        channelInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { watch(); true } else false
        }
        findViewById<Button>(R.id.goButton).setOnClickListener { watch() }
        findViewById<Button>(R.id.vodsButton).setOnClickListener { showVodList() }

        // The status line (loading, errors) hides itself when there's nothing to say.
        status.doAfterTextChanged { applyLayoutMode() }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controlsVisible = visibility == View.VISIBLE
                updateFullscreenTitle()
            }
        )

        onBackPressedDispatcher.addCallback(this, backCallback)

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE)
            addAction(ACTION_BACK)
            addAction(ACTION_FORWARD)
        }
        ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        updatePipParams()
        renderHome()
        applyLayoutMode()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = controller ?: return@addListener
            val bar = LiveBarPlayer(c)
            barPlayer = bar
            playerView.player = bar
            c.addListener(playerListener)
            updatePipParams()
            updateInfo()
            applyLayoutMode()
            playerView.keepScreenOn = c.isPlaying
        }, ContextCompat.getMainExecutor(this))
        uiHandler.post(liveLabelUpdater)
        orientationListener.enable()
        renderHome() // picks up VOD progress saved while in the background
        refreshChannelStatuses()
    }

    // The service keeps playing after this; the activity just disconnects from it.
    override fun onStop() {
        uiHandler.removeCallbacks(liveLabelUpdater)
        orientationListener.disable()
        statusJob?.cancel()
        playerView.player = null
        barPlayer?.detach()
        barPlayer = null
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }

    // ---------- Layout: normal, fullscreen, picture-in-picture ----------

    private fun hasMedia() = (controller?.mediaItemCount ?: 0) > 0

    private fun applyLayoutMode() {
        val pip = isInPictureInPictureMode
        val immersive = isFullscreen || pip
        val media = hasMedia()

        setVisible(controls, !immersive)
        setVisible(homeScroll, !immersive)
        setVisible(infoSection, !immersive && media)
        setVisible(status, !immersive && status.text.isNotEmpty())
        setVisible(playerFrame, immersive || media)
        playerView.useController = !pip
        updateFullscreenTitle()
        updateCaptionMargins()

        // Normal mode: 16:9 player above the home list. Fullscreen/PiP: player fills the screen.
        val lp = playerFrame.layoutParams as LinearLayout.LayoutParams
        val height = if (immersive) 0 else resources.displayMetrics.widthPixels * 9 / 16
        val weight = if (immersive) 1f else 0f
        if (lp.height != height || lp.weight != weight) {
            lp.height = height
            lp.weight = weight
            playerFrame.layoutParams = lp
        }
    }

    private fun setVisible(view: View, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != v) view.visibility = v
    }

    // Fullscreen works the same however you enter it: the screen turns
    // landscape (either way up), and turning the phone upright leaves it.
    private fun onFullscreenButton(full: Boolean) {
        isFullscreen = full
        backCallback.isEnabled = full
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (full) {
            waitingForUpright = false
            exitWhenUpright = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            exitWhenUpright = false
            resetZoom()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            waitingForUpright = true
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        applyLayoutMode()
    }

    // Media3 keeps the fullscreen icon's state private, so leaving fullscreen
    // by back gesture or rotation presses the button itself to keep the icon in sync.
    private fun toggleFullscreenButton() {
        val button = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        if (button != null) button.performClick() else onFullscreenButton(!isFullscreen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Turning the phone sideways while something plays goes fullscreen.
        if (landscape && !isFullscreen && hasMedia() && !isInPictureInPictureMode && !waitingForUpright) {
            toggleFullscreenButton()
        }
        applyLayoutMode()
    }

    // ---------- Now-playing info, fullscreen title, captions ----------

    // Per-item note shown under the streamer ("Resumed at 1:23:45"). Kept in a
    // companion so it survives the screen being recreated while audio plays.
    private fun updateInfo() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        infoTitle.text = title
        fullscreenTitle.text = title
        infoStreamer.text = meta.artist?.toString().orEmpty()
        loadAvatar(
            meta.artworkUri?.toString(),
            meta.extras?.getString(EXTRA_CHANNEL_SLUG),
        )
        val id = c.currentMediaItem?.mediaId
        infoDetail.text = when {
            c.isCurrentMediaItemLive -> "Live stream"
            id != null -> playNotes[id] ?: "VOD"
            else -> ""
        }
    }

    private var avatarJob: Job? = null

    // Uses the picture that came with the stream; failing that, a picture
    // already fetched for that channel; failing that, fetches it once.
    private fun loadAvatar(url: String?, channelSlug: String?) {
        avatarJob?.cancel()
        val known = url ?: channelSlug?.let { avatarCache[it] ?: channelStatuses[it]?.avatarUrl }
        showAvatar(known)
        if (known != null || channelSlug == null) return
        avatarJob = lifecycleScope.launch {
            runCatching { KickApi.channelStatus(this@MainActivity, channelSlug) }
                .getOrNull()?.avatarUrl?.let { fetched ->
                    avatarCache[channelSlug] = fetched
                    showAvatar(fetched)
                }
        }
    }

    private fun showAvatar(url: String?) {
        infoAvatar.load(url) {
            placeholder(R.drawable.avatar_placeholder)
            error(R.drawable.avatar_placeholder)
            fallback(R.drawable.avatar_placeholder)
            transformations(CircleCropTransformation())
        }
    }

    private fun updateFullscreenTitle() {
        setVisible(
            fullscreenTitle,
            isFullscreen && controlsVisible && fullscreenTitle.text.isNotEmpty()
        )
    }

    // Keeps captions off the screen edges and bottom, like YouTube.
    private fun updateCaptionMargins() {
        val subtitles = playerView.subtitleView ?: return
        val w = playerView.width
        val h = playerView.height
        if (w == 0 || h == 0) return
        val side = (w * if (isFullscreen) 0.14f else 0.06f).toInt()
        val bottom = (h * if (isFullscreen) 0.09f else 0.07f).toInt()
        if (subtitles.paddingLeft != side || subtitles.paddingBottom != bottom) {
            subtitles.setPadding(side, 0, side, bottom)
        }
    }

    // Higher = lower on screen. Kick's captions number rows from the top for the
    // upper half and from the bottom (negative) for the lower half.
    private fun bottomness(cue: Cue): Float = when {
        cue.line == Cue.DIMEN_UNSET -> Float.MAX_VALUE
        cue.lineType == Cue.LINE_TYPE_NUMBER && cue.line < 0 -> 10_000f + cue.line
        cue.lineType == Cue.LINE_TYPE_FRACTION -> cue.line * 100f
        else -> cue.line
    }

    // Shows at most the bottom two caption lines, so fewer words sit on
    // screen at once and older lines clear sooner.
    private fun trimCues(cues: List<Cue>): List<Cue> {
        if (cues.isEmpty()) return cues
        val bottomFirst = cues.sortedByDescending { bottomness(it) }
        val kept = mutableListOf<Cue>()
        var lines = 0
        for (cue in bottomFirst) {
            if (lines >= MAX_CAPTION_LINES) break
            val text = cue.text
            if (text == null) { kept += cue; continue }
            val cueLines = text.split('\n')
            val room = MAX_CAPTION_LINES - lines
            if (cueLines.size <= room) {
                kept += cue
                lines += cueLines.size
            } else {
                // Keep only the last lines of a multi-line caption block.
                val start = text.length - cueLines.takeLast(room).joinToString("\n").length
                kept += cue.buildUpon().setText(text.subSequence(start, text.length)).build()
                lines += room
            }
        }
        return kept
    }

    // ---------- Home screen ----------

    private fun renderHome() {
        homeList.removeAllViews()
        val recents = Recents.all(this)
        homeList.addView(TextView(this).apply {
            text = if (recents.isEmpty()) {
                "Nothing watched yet. Type a channel or paste a VOD link above."
            } else {
                "Recently watched"
            }
            setTextColor(if (recents.isEmpty()) Color.parseColor("#888888") else Color.WHITE)
            textSize = if (recents.isEmpty()) 14f else 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(4))
        })
        recents.forEach { homeList.addView(recentRow(it)) }
    }

    private fun recentRow(item: RecentItem): View {
        val row = layoutInflater.inflate(R.layout.item_media, homeList, false)
        val watched = DateUtils.getRelativeTimeSpanString(item.watchedAt)

        if (item.type == RecentItem.LIVE) {
            val st = channelStatuses[item.id]
            val live = st?.isLive == true
            bindRow(
                row,
                thumbUrl = if (live) st?.thumbnailUrl ?: st?.avatarUrl else st?.avatarUrl ?: item.avatarUrl,
                fitThumb = !live,
                title = st?.title?.takeIf { live && it.isNotBlank() } ?: item.title,
                subtitle = st?.streamer ?: item.streamer,
                detail = "Channel · watched $watched",
                badge = when (st?.isLive) { true -> "LIVE"; false -> "OFFLINE"; null -> null },
                badgeColor = if (live) Color.parseColor("#E53935") else Color.parseColor("#616161"),
            )
            row.setOnClickListener {
                channelInput.setText(item.id)
                Prefs.setLastChannel(this, item.id)
                play("Loading ${item.id}…") { KickApi.resolve(this, item.id) }
            }
        } else {
            val position = VodPositions.get(this, item.id)
            val progress = if (item.durationMs > 0 && position > 0) {
                position.toFloat() / item.durationMs
            } else null
            bindRow(
                row,
                thumbUrl = item.thumbnailUrl ?: item.avatarUrl,
                fitThumb = item.thumbnailUrl == null,
                title = item.title,
                subtitle = item.streamer,
                detail = (if (position > 0) "Watched to ${formatTimestamp(position)}" else "VOD") +
                    " · $watched",
                durationText = item.durationMs.takeIf { it > 0 }?.let { formatTimestamp(it) },
                progress = progress,
            )
            row.setOnClickListener {
                play("Loading VOD…", item.thumbnailUrl, item.durationMs) {
                    KickApi.resolveVod(this, item.id)
                }
            }
        }

        row.setOnLongClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("Remove from recents")
                setOnMenuItemClickListener {
                    Recents.remove(this@MainActivity, item)
                    renderHome()
                    true
                }
                show()
            }
            true
        }
        return row
    }

    private fun bindRow(
        row: View,
        thumbUrl: String?,
        fitThumb: Boolean,
        title: String,
        subtitle: String,
        detail: String,
        badge: String? = null,
        badgeColor: Int = Color.TRANSPARENT,
        durationText: String? = null,
        progress: Float? = null,
    ) {
        row.findViewById<ImageView>(R.id.thumb).apply {
            // Avatars are square, so they're shown whole instead of cropped to 16:9.
            scaleType = if (fitThumb) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            load(thumbUrl)
        }
        row.findViewById<TextView>(R.id.title).text = title.ifBlank { "Untitled" }
        row.findViewById<TextView>(R.id.subtitle).text = subtitle
        row.findViewById<TextView>(R.id.detail).text = detail
        row.findViewById<TextView>(R.id.badge).apply {
            visibility = if (badge != null) View.VISIBLE else View.GONE
            text = badge
            setBackgroundColor(badgeColor)
        }
        row.findViewById<TextView>(R.id.duration).apply {
            visibility = if (durationText != null) View.VISIBLE else View.GONE
            text = durationText
        }
        row.findViewById<ProgressBar>(R.id.progress).apply {
            visibility = if (progress != null) View.VISIBLE else View.GONE
            this.progress = ((progress ?: 0f) * 1000).toInt().coerceIn(0, 1000)
        }
    }

    // Checks which recent channels are live right now, one at a time.
    private fun refreshChannelStatuses() {
        statusJob?.cancel()
        val slugs = Recents.all(this).filter { it.type == RecentItem.LIVE }.map { it.id }.distinct()
        if (slugs.isEmpty()) return
        statusJob = lifecycleScope.launch {
            for (slug in slugs) {
                runCatching { KickApi.channelStatus(this@MainActivity, slug) }
                    .onSuccess {
                        channelStatuses[slug] = it
                        renderHome()
                    }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ---------- Loading and playing ----------

    // Channel name, kick.com/<channel>, or any Kick VOD link.
    private fun normalizedSlug(): String = channelInput.text.toString().trim()
        .removePrefix("https://").removePrefix("www.").removePrefix("kick.com/")
        .substringBefore('/').lowercase()

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(channelInput.windowToken, 0)
    }

    private fun watch() {
        val input = channelInput.text.toString()
        val vodUuid = extractVodUuid(input)
        if (vodUuid != null) {
            play("Loading VOD…") { KickApi.resolveVod(this, vodUuid) }
            return
        }
        val slug = normalizedSlug()
        if (slug.isBlank()) return
        Prefs.setLastChannel(this, slug)
        play("Loading $slug…") { KickApi.resolve(this, slug) }
    }

    // fallbackThumb/fallbackDuration come from the VOD list, in case the single-VOD
    // lookup lacks them.
    private fun play(
        loadingText: String,
        fallbackThumb: String? = null,
        fallbackDuration: Long = 0L,
        load: suspend () -> KickStream,
    ) {
        hideKeyboard()
        status.text = loadingText
        lifecycleScope.launch {
            try {
                val stream = load()
                val c = controller ?: run { status.text = "Player not ready, try again."; return@launch }
                val vodUuid = KickApi.vodUuidOf(stream.mediaId)
                val resumeAt = vodUuid?.let { VodPositions.get(this@MainActivity, it) } ?: 0L
                c.setPlaybackSpeed(1f)
                if (resumeAt > 0) c.setMediaItem(stream.toMediaItem(), resumeAt)
                else c.setMediaItem(stream.toMediaItem())
                c.prepare()
                c.play()
                if (!stream.isLive) {
                    playNotes[stream.mediaId] =
                        if (resumeAt > 0) "Resumed at ${formatTimestamp(resumeAt)}" else "Started from the beginning"
                }
                status.text = ""
                updateInfo()

                Recents.add(
                    this@MainActivity,
                    RecentItem(
                        type = if (stream.isLive) RecentItem.LIVE else RecentItem.VOD,
                        id = vodUuid ?: stream.mediaId,
                        title = stream.title,
                        streamer = stream.streamer,
                        thumbnailUrl = stream.thumbnailUrl ?: fallbackThumb,
                        avatarUrl = stream.avatarUrl,
                        durationMs = stream.durationMs.takeIf { it > 0 } ?: fallbackDuration,
                        watchedAt = System.currentTimeMillis(),
                    )
                )
                if (stream.isLive) {
                    channelStatuses[stream.mediaId] = ChannelStatus(
                        stream.mediaId, stream.streamer, true, stream.title,
                        stream.thumbnailUrl, stream.avatarUrl,
                    )
                }
                renderHome()
                applyLayoutMode()
            } catch (e: KickException) {
                status.text = e.message
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
        }
    }

    private fun showVodList() {
        // If a VOD link is in the box, fall back to the last channel watched.
        val slug = if (extractVodUuid(channelInput.text.toString()) != null) Prefs.lastChannel(this)
        else normalizedSlug()
        if (slug.isBlank()) return
        hideKeyboard()
        status.text = "Loading $slug VODs…"
        lifecycleScope.launch {
            try {
                val vods = KickApi.listVods(this@MainActivity, slug)
                if (vods.isEmpty()) { status.text = "$slug has no VODs."; return@launch }
                status.text = ""
                val adapter = object : BaseAdapter() {
                    override fun getCount() = vods.size
                    override fun getItem(i: Int) = vods[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
                        val row = convertView
                            ?: layoutInflater.inflate(R.layout.item_media, parent, false)
                        val v = vods[i]
                        val watched = VodPositions.get(this@MainActivity, v.uuid)
                        bindRow(
                            row,
                            thumbUrl = v.thumbnailUrl,
                            fitThumb = false,
                            title = v.title,
                            subtitle = v.startTime.take(10),
                            detail = if (watched > 0) "Watched to ${formatTimestamp(watched)}" else "",
                            durationText = v.durationMs.takeIf { it > 0 }?.let { formatTimestamp(it) },
                            progress = if (watched > 0 && v.durationMs > 0) {
                                watched.toFloat() / v.durationMs
                            } else null,
                        )
                        return row
                    }
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("$slug VODs")
                    .setAdapter(adapter) { _, i ->
                        val vod = vods[i]
                        play("Loading VOD…", vod.thumbnailUrl, vod.durationMs) {
                            KickApi.resolveVod(this@MainActivity, vod.uuid)
                        }
                    }
                    .show()
            } catch (e: KickException) {
                status.text = e.message
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
        }
    }

    // ---------- Player controls ----------

    private fun goLive() {
        val c = controller ?: return
        c.setPlaybackSpeed(1f)
        c.seekToDefaultPosition() // for a live stream, the default position is the live edge
        c.play()
    }

    // During live streams, the player's position/duration readout only measures
    // Kick's ~30 second playlist window. This swaps it for a behind-live readout
    // in the same spot; VODs keep the normal timestamps.
    private fun setupLiveLabel() {
        val timeRow = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_time) ?: return
        val label = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
        }
        timeRow.addView(label)
        liveLabel = label
    }

    private fun updateLiveLabel() {
        val label = liveLabel ?: return
        val timeRow = label.parent as? ViewGroup ?: return
        val live = controller?.isCurrentMediaItemLive == true
        for (i in 0 until timeRow.childCount) {
            val child = timeRow.getChildAt(i)
            child.visibility = if ((child === label) == live) View.VISIBLE else View.GONE
        }
        if (!live) return
        val behind = barPlayer?.edge?.behindMs()
        if (behind == null || behind < AT_LIVE_THRESHOLD_MS) {
            label.text = "● LIVE"
            label.setTextColor(Color.parseColor("#FF4D4D"))
        } else {
            label.text = "${formatTimestamp(behind)} behind live"
            label.setTextColor(Color.WHITE)
        }
    }

    // The CC button only appears when the stream or VOD has captions. Its
    // default menu is replaced with a one-tap on/off that's remembered for
    // everything you watch. The icon updates itself to show on or off.
    private fun setupCaptionsButton() {
        playerView.setShowSubtitleButton(true)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener {
            val on = !Prefs.captionsOn(this)
            Prefs.setCaptionsOn(this, on)
            controller?.let { c ->
                c.trackSelectionParameters = c.trackSelectionParameters.withCaptions(on)
            }
        }
    }

    // ---------- Speed and quality (bottom bar and the gear menu) ----------

    private fun applySpeed(speed: Float) {
        val c = controller ?: return
        // Already at live, so there's nothing ahead to speed through.
        val behind = barPlayer?.edge?.behindMs()
        if (speed > 1f && behind != null && behind < AT_LIVE_THRESHOLD_MS) {
            Toast.makeText(
                this, "Already at live. Speed up works after pausing or falling behind.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            c.setPlaybackSpeed(speed)
        }
    }

    // Every resolution Kick offers for this stream, highest first.
    private fun availableHeights(): List<Int> {
        val c = controller ?: return emptyList()
        return c.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
    }

    // 0 = Auto
    private fun applyQuality(height: Int) {
        val c = controller ?: return
        Prefs.setQualityHeight(this, height)
        c.trackSelectionParameters = c.trackSelectionParameters.withQuality(height)
    }

    // The player's gear button opens Speed and Quality, so both work in fullscreen.
    private fun setupSettingsButton() {
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
            ?.setOnClickListener { anchor -> showSettingsMenu(anchor) }
    }

    // The gear menu, then a second menu for the choice. The controls stay up
    // the whole time, until you pick something or tap away.
    private fun showSettingsMenu(anchor: View) {
        val c = controller ?: return
        val main = PopupMenu(this, anchor)
        main.menu.add("Speed: ${speedLabel(c.playbackParameters.speed)}").setOnMenuItemClickListener {
            val speeds = PopupMenu(this, anchor)
            SPEEDS.forEach { speed ->
                speeds.menu.add(speedLabel(speed)).setOnMenuItemClickListener { applySpeed(speed); true }
            }
            showKeepingControls(speeds)
            true
        }
        main.menu.add("Quality: ${qualityLabel()}").setOnMenuItemClickListener {
            val qualities = PopupMenu(this, anchor)
            qualities.menu.add("Auto").setOnMenuItemClickListener { applyQuality(0); true }
            availableHeights().forEach { h ->
                qualities.menu.add("${h}p").setOnMenuItemClickListener { applyQuality(h); true }
            }
            showKeepingControls(qualities)
            true
        }
        if (c.isCurrentMediaItemLive) {
            main.menu.add("Go to live").setOnMenuItemClickListener { goLive(); true }
        }
        showKeepingControls(main)
    }

    private fun showKeepingControls(menu: PopupMenu) {
        openMenus++
        playerView.controllerShowTimeoutMs = 0 // 0 = never auto-hide
        playerView.showController()
        menu.setOnDismissListener {
            openMenus--
            if (openMenus == 0) {
                playerView.controllerShowTimeoutMs = CONTROLS_TIMEOUT_MS
                playerView.showController() // restarts the normal hide timer
            }
        }
        menu.show()
    }

    // ---------- Touch gestures on the video ----------

    // Double-tap the left or right half to skip back or forward 10 seconds.
    // In fullscreen, pinch to zoom from the whole picture up to 8x, centered
    // on your fingers, and drag with one finger to look around while zoomed.
    // It snaps to "zoomed to fill" when you pass near that level.
    // A single tap still shows/hides the controls.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector) = isFullscreen

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomAround(detector.scaleFactor, detector.focusX, detector.focusY)
                    flash(zoomHint, "%.1fx".format(zoomScale))
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    val fill = fillScale()
                    when {
                        zoomScale <= 1.02f -> {
                            resetZoom()
                            flash(zoomHint, "Original")
                        }
                        fill > 1.01f && kotlin.math.abs(zoomScale - fill) / fill < 0.07f -> {
                            zoomScale = fill
                            applyZoom()
                            flash(zoomHint, "Zoomed to fill")
                        }
                    }
                }
            })

        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.performClick() // PlayerView's click shows or hides the controls
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return false
                if (e.x < playerView.width / 2f) {
                    c.seekBack()
                    flash(seekBackHint, "« 10s")
                } else {
                    val behind = barPlayer?.edge?.behindMs()
                    c.seekForward()
                    flash(
                        seekForwardHint,
                        if (behind != null && behind <= c.seekForwardIncrement) "Live" else "10s »"
                    )
                }
                return true
            }

            // One-finger drag pans while zoomed in.
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
            ): Boolean {
                if (zoomScale <= 1.01f || multiTouch) return false
                panX -= distanceX
                panY -= distanceY
                applyZoom()
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> multiTouch = false
                // A second finger means a pinch, so cancel any tap in progress.
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    tapDetector.onTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            if (!multiTouch) tapDetector.onTouchEvent(event)
            true
        }

        // Rotation or resizing changes the limits on how far you can pan.
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyZoom()
            updateCaptionMargins()
        }
    }

    // The layer holding the video picture, sized to fit the screen at 1x and
    // centered in the player. Zoom scales and moves this layer. The player uses
    // SurfaceView (the default, and the most power-efficient), which follows
    // view scaling correctly on Android 7.0 and newer.
    private val videoFrame: View?
        get() = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    // The zoom level at which the video covers the whole screen.
    private fun fillScale(): Float {
        val frame = videoFrame ?: return 1f
        if (frame.width == 0 || frame.height == 0) return 1f
        return maxOf(
            playerView.width.toFloat() / frame.width,
            playerView.height.toFloat() / frame.height,
        )
    }

    // Zooms by factor while keeping the point under your fingers in place.
    private fun zoomAround(factor: Float, focusX: Float, focusY: Float) {
        val newScale = (zoomScale * factor).coerceIn(1f, MAX_ZOOM)
        val f = newScale / zoomScale
        val dx = focusX - playerView.width / 2f
        val dy = focusY - playerView.height / 2f
        panX = dx - f * (dx - panX)
        panY = dy - f * (dy - panY)
        zoomScale = newScale
        applyZoom()
    }

    // Applies zoom and pan, keeping the video's edges from pulling inside the
    // screen (no black gaps from panning past the picture).
    private fun applyZoom() {
        val frame = videoFrame ?: return
        val maxX = maxOf(0f, (frame.width * zoomScale - playerView.width) / 2f)
        val maxY = maxOf(0f, (frame.height * zoomScale - playerView.height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        frame.pivotX = frame.width / 2f
        frame.pivotY = frame.height / 2f
        frame.scaleX = zoomScale
        frame.scaleY = zoomScale
        frame.translationX = panX
        frame.translationY = panY
    }

    private fun resetZoom() {
        zoomScale = 1f
        panX = 0f
        panY = 0f
        applyZoom()
    }

    private fun flash(hint: TextView, text: String) {
        hint.animate().cancel()
        hint.text = text
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
        hint.animate()
            .alpha(0f)
            .setStartDelay(500)
            .setDuration(300)
            .withEndAction { hint.visibility = View.GONE }
            .start()
    }

    // "Auto (720p)" shows what Auto picked; a pinned choice shows just "720p".
    private fun qualityLabel(): String {
        val pinned = Prefs.qualityHeight(this)
        val current = controller?.videoSize?.height ?: 0
        return when {
            pinned > 0 -> "${pinned}p"
            current > 0 -> "Auto (${current}p)"
            else -> "Auto"
        }
    }

    private fun speedLabel(speed: Float) =
        if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

    // ---------- Picture-in-picture ----------

    private fun pipAction(action: String, iconRes: Int, label: String, requestCode: Int) = RemoteAction(
        Icon.createWithResource(this, iconRes), label, label,
        PendingIntent.getBroadcast(
            this, requestCode, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    // PiP window buttons: back 10s, play/pause, forward 10s.
    private fun updatePipParams() {
        val playing = controller?.isPlaying == true
        val actions = listOf(
            pipAction(ACTION_BACK, android.R.drawable.ic_media_rew, "Back 10 seconds", 1),
            pipAction(
                ACTION_TOGGLE,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                0
            ),
            pipAction(ACTION_FORWARD, android.R.drawable.ic_media_ff, "Forward 10 seconds", 2),
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(playing)
        setPictureInPictureParams(builder.build())
    }

    // Android 8 to 11 lack auto-enter, so enter PiP manually on Home.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && controller?.isPlaying == true) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        applyLayoutMode()
    }

    companion object {
        private const val ACTION_TOGGLE = "app.kickplayer.PIP_TOGGLE"
        private const val ACTION_BACK = "app.kickplayer.PIP_BACK"
        private const val ACTION_FORWARD = "app.kickplayer.PIP_FORWARD"
        private val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        private const val MAX_ZOOM = 8f
        private const val MAX_CAPTION_LINES = 2
        private const val CONTROLS_TIMEOUT_MS = 5_000
        private val playNotes = mutableMapOf<String, String>()
        private val avatarCache = mutableMapOf<String, String>()
    }
}
