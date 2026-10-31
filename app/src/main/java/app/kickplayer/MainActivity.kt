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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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

    // Where the video is: not shown, at the top of the player screen, or
    // floating over the home screen. Fullscreen and system PiP sit on top of these.
    private enum class PlayerMode { HIDDEN, EXPANDED, MINI }
    private var playerMode = PlayerMode.HIDDEN

    private lateinit var root: View
    private lateinit var homeScreen: View
    private lateinit var playerScreen: View
    private lateinit var playerSlot: View
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var channelInput: EditText
    private lateinit var status: TextView
    private lateinit var pinnedRow: LinearLayout
    private lateinit var pinnedHint: TextView
    private lateinit var recentHeader: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var homeBottomSpace: View
    private lateinit var infoTitle: TextView
    private lateinit var infoAvatar: ImageView
    private lateinit var infoStreamer: TextView
    private lateinit var infoDetail: TextView
    private lateinit var pinButton: TextView
    private lateinit var moreHeader: TextView
    private lateinit var moreList: LinearLayout
    private lateinit var fullscreenTitle: TextView
    private lateinit var miniOverlay: View
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniClose: ImageButton
    private lateinit var seekBackHint: TextView
    private lateinit var seekForwardHint: TextView
    private lateinit var zoomHint: TextView
    private var liveLabel: TextView? = null
    private var controlsVisible = false
    private var openMenus = 0

    // Fullscreen zoom: 1 = whole picture. pan is the video's offset from center.
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var multiTouch = false
    private var barPlayer: LiveBarPlayer? = null

    // Where the mini player was last dragged to (offset from bottom-right).
    private var miniTx = 0f
    private var miniTy = 0f

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
    private var moreJob: Job? = null
    private var moreLoadedFor: String? = null

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

    // Back: leaves fullscreen first, then shrinks the player into the mini player.
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isFullscreen) toggleFullscreenButton() else minimize()
        }
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
            miniPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        override fun onPlayerError(error: PlaybackException) {
            showError("Playback error: ${error.errorCodeName}")
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

        root = findViewById(R.id.root)
        homeScreen = findViewById(R.id.homeScreen)
        playerScreen = findViewById(R.id.playerScreen)
        playerSlot = findViewById(R.id.playerSlot)
        playerFrame = findViewById(R.id.playerFrame)
        playerView = findViewById(R.id.playerView)
        channelInput = findViewById(R.id.channelInput)
        status = findViewById(R.id.status)
        pinnedRow = findViewById(R.id.pinnedRow)
        pinnedHint = findViewById(R.id.pinnedHint)
        recentHeader = findViewById(R.id.recentHeader)
        recentList = findViewById(R.id.recentList)
        homeBottomSpace = findViewById(R.id.homeBottomSpace)
        infoTitle = findViewById(R.id.infoTitle)
        infoAvatar = findViewById(R.id.infoAvatar)
        infoStreamer = findViewById(R.id.infoStreamer)
        infoDetail = findViewById(R.id.infoDetail)
        pinButton = findViewById(R.id.pinButton)
        moreHeader = findViewById(R.id.moreHeader)
        moreList = findViewById(R.id.moreList)
        fullscreenTitle = findViewById(R.id.fullscreenTitle)
        miniOverlay = findViewById(R.id.miniOverlay)
        miniPlayPause = findViewById(R.id.miniPlayPause)
        miniClose = findViewById(R.id.miniClose)
        seekBackHint = findViewById(R.id.seekBackHint)
        seekForwardHint = findViewById(R.id.seekForwardHint)
        zoomHint = findViewById(R.id.zoomHint)
        setupWindowInsets()

        // The rewind/forward buttons use the 10 second increments set in PlaybackService.
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        // Setting a listener is what makes PlayerView show its fullscreen button.
        playerView.setFullscreenButtonClickListener { full -> onFullscreenButton(full) }
        setupCaptionsButton()
        setupSettingsButton()
        setupGestures()
        setupLiveLabel()
        setupMiniPlayer()

        channelInput.setText(Prefs.lastChannel(this))
        channelInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { watch(); true } else false
        }
        findViewById<View>(R.id.goButton).setOnClickListener { watch() }
        findViewById<View>(R.id.vodsButton).setOnClickListener { showVodList() }
        findViewById<View>(R.id.navHome).setOnClickListener { minimize() }
        pinButton.setOnClickListener { togglePinForCurrent() }

        // The status line (loading, errors) hides itself when there's nothing to say.
        status.doAfterTextChanged { setVisible(status, status.text.isNotEmpty()) }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controlsVisible = visibility == View.VISIBLE
                updateOverlayButtons()
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
            miniPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
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

    // ---------- Layout: home, player, mini player, fullscreen, PiP ----------

    private fun hasMedia() = (controller?.mediaItemCount ?: 0) > 0

    private fun applyLayoutMode() {
        val c = controller
        val media = hasMedia()
        if (c != null) {
            // Something started playing (or kept playing in the background) with
            // no video showing: open the player. Playback cleared: hide it.
            if (media && playerMode == PlayerMode.HIDDEN) playerMode = PlayerMode.EXPANDED
            if (!media && playerMode != PlayerMode.HIDDEN) playerMode = PlayerMode.HIDDEN
        }
        val pip = isInPictureInPictureMode
        val full = isFullscreen || pip
        val mini = playerMode == PlayerMode.MINI && !full

        setVisible(homeScreen, !full && playerMode != PlayerMode.EXPANDED)
        setVisible(playerScreen, !full && playerMode == PlayerMode.EXPANDED)
        setVisible(playerFrame, full || playerMode != PlayerMode.HIDDEN)
        setVisible(miniOverlay, mini)
        playerView.useController = !pip && !mini
        playerView.subtitleView?.visibility = if (mini) View.GONE else View.VISIBLE
        backCallback.isEnabled = isFullscreen || playerMode == PlayerMode.EXPANDED

        // Size and place the video for the current mode.
        val contentW = (root.width - root.paddingLeft - root.paddingRight)
            .takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val expandedH = contentW * 9 / 16
        val lp = playerFrame.layoutParams as FrameLayout.LayoutParams
        val (w, h, gravity, margin) = when {
            full -> listOf(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.TOP or android.view.Gravity.START, 0,
            )
            mini -> {
                val miniW = (contentW * 0.52f).toInt()
                listOf(miniW, miniW * 9 / 16, android.view.Gravity.BOTTOM or android.view.Gravity.END, dp(12))
            }
            else -> listOf(FrameLayout.LayoutParams.MATCH_PARENT, expandedH, android.view.Gravity.TOP, 0)
        }
        // Expanded: just below the navbar. Mini: inset from the corner. Fullscreen: no margins.
        val top = if (!full && !mini) dp(NAVBAR_HEIGHT_DP) else margin
        if (lp.width != w || lp.height != h || lp.gravity != gravity ||
            lp.leftMargin != margin || lp.topMargin != top
        ) {
            lp.width = w
            lp.height = h
            lp.gravity = gravity
            lp.setMargins(margin, top, margin, margin)
            playerFrame.layoutParams = lp
        }
        playerFrame.translationX = if (mini) miniTx else 0f
        playerFrame.translationY = if (mini) miniTy else 0f
        playerFrame.elevation = if (mini) dp(14).toFloat() else 0f
        playerFrame.setBackgroundResource(if (mini) R.drawable.bg_mini else android.R.color.black)
        playerFrame.clipToOutline = mini

        val slotLp = playerSlot.layoutParams
        if (slotLp.height != expandedH) {
            slotLp.height = expandedH
            playerSlot.layoutParams = slotLp
        }
        val bottomSpace = if (playerMode == PlayerMode.MINI) dp(150) else dp(24)
        val spaceLp = homeBottomSpace.layoutParams
        if (spaceLp.height != bottomSpace) {
            spaceLp.height = bottomSpace
            homeBottomSpace.layoutParams = spaceLp
        }

        updateOverlayButtons()
        updateCaptionMargins()
    }

    private fun setVisible(view: View, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != v) view.visibility = v
    }

    // Shrinks the player into the floating mini player and shows the home screen.
    private fun minimize() {
        if (playerMode != PlayerMode.EXPANDED || isFullscreen) return
        playerMode = PlayerMode.MINI
        playerView.hideController()
        renderHome()
        applyLayoutMode()
    }

    private fun expand() {
        if (playerMode != PlayerMode.MINI) return
        playerMode = PlayerMode.EXPANDED
        applyLayoutMode()
    }

    // Stops playback entirely (the mini player's X).
    private fun closePlayer() {
        val c = controller ?: return
        c.pause()
        c.stop()
        c.clearMediaItems()
        playerMode = PlayerMode.HIDDEN
        renderHome()
        applyLayoutMode()
    }

    // Title across the top in fullscreen, while the controls are up.
    private fun updateOverlayButtons() {
        setVisible(
            fullscreenTitle,
            isFullscreen && controlsVisible && fullscreenTitle.text.isNotEmpty()
        )
    }

    // The app draws behind the status bar, navigation bar, and camera cutout,
    // then pads itself to stay clear of them. In fullscreen the padding drops
    // to zero so the video can use every pixel, like YouTube.
    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val pad = if (isFullscreen) {
                androidx.core.graphics.Insets.NONE
            } else {
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime() // keeps the search box above the keyboard
                )
            }
            view.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            view.post { applyLayoutMode() }
            insets
        }
    }

    // Lets fullscreen video extend into the camera cutout's side of the screen.
    private fun setCutoutMode(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT < 28) return
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (fullscreen) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    // Fullscreen works the same however you enter it: the screen turns
    // landscape (either way up), and turning the phone upright leaves it.
    private fun onFullscreenButton(full: Boolean) {
        isFullscreen = full
        setCutoutMode(full)
        ViewCompat.requestApplyInsets(root)
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
        // Turning the phone sideways while the player is open goes fullscreen.
        if (landscape && !isFullscreen && playerMode == PlayerMode.EXPANDED &&
            !isInPictureInPictureMode && !waitingForUpright
        ) {
            toggleFullscreenButton()
        }
        applyLayoutMode()
    }

    // ---------- Mini player ----------

    // Drag the mini player anywhere; it snaps to the nearer side when let go.
    // Tap it to open the full player.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMiniPlayer() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        miniOverlay.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    playerFrame.animate().cancel()
                    downX = e.rawX
                    downY = e.rawY
                    startX = playerFrame.translationX
                    startY = playerFrame.translationY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val (minX, minY) = miniBounds()
                        playerFrame.translationX = (startX + dx).coerceIn(minX, 0f)
                        playerFrame.translationY = (startY + dy).coerceIn(minY, 0f)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        expand()
                    } else {
                        val (minX, _) = miniBounds()
                        miniTx = if (playerFrame.translationX < minX / 2f) minX else 0f
                        miniTy = playerFrame.translationY
                        playerFrame.animate().translationX(miniTx).setDuration(180).start()
                    }
                }
            }
            true
        }
        miniPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        miniClose.setOnClickListener { closePlayer() }
    }

    // How far left and up the mini player can move (as negative offsets).
    private fun miniBounds(): Pair<Float, Float> {
        val m = dp(12)
        val parentW = root.width - root.paddingLeft - root.paddingRight
        val parentH = root.height - root.paddingTop - root.paddingBottom
        val maxLeft = (parentW - playerFrame.width - 2 * m).coerceAtLeast(0)
        val maxUp = (parentH - playerFrame.height - 2 * m).coerceAtLeast(0)
        return Pair(-maxLeft.toFloat(), -maxUp.toFloat())
    }

    // ---------- Now-playing info, pinning, more from this streamer ----------

    private fun currentChannelSlug(): String? =
        controller?.mediaMetadata?.extras?.getString(EXTRA_CHANNEL_SLUG)

    // Per-item note shown under the streamer ("Resumed at 1:23:45"). Kept in a
    // companion so it survives the screen being recreated while audio plays.
    private fun updateInfo() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        val title = meta.title?.toString().orEmpty()
        infoTitle.text = title
        fullscreenTitle.text = title
        infoStreamer.text = meta.artist?.toString().orEmpty()
        val slug = currentChannelSlug()
        loadAvatar(meta.artworkUri?.toString(), slug)
        val id = c.currentMediaItem?.mediaId
        infoDetail.text = when {
            c.isCurrentMediaItemLive -> "Live stream"
            id != null -> playNotes[id] ?: "VOD"
            else -> ""
        }
        updatePinButton()
        loadMoreFrom(slug, meta.artist?.toString(), KickApi.vodUuidOf(id))
    }

    private fun updatePinButton() {
        val slug = currentChannelSlug()
        setVisible(pinButton, slug != null)
        if (slug == null) return
        val pinned = Pins.isPinned(this, slug)
        pinButton.text = if (pinned) "Pinned" else "Pin"
        pinButton.setBackgroundResource(if (pinned) R.drawable.bg_pill_pinned else R.drawable.bg_pill_glass)
        pinButton.setTextColor(if (pinned) ContextCompat.getColor(this, R.color.kick_green) else Color.WHITE)
    }

    private fun togglePinForCurrent() {
        val slug = currentChannelSlug() ?: return
        val meta = controller?.mediaMetadata
        if (Pins.isPinned(this, slug)) {
            Pins.remove(this, slug)
        } else {
            Pins.add(
                this,
                PinnedChannel(
                    slug = slug,
                    name = meta?.artist?.toString() ?: slug,
                    avatarUrl = meta?.artworkUri?.toString() ?: avatarCache[slug],
                )
            )
        }
        updatePinButton()
        renderHome()
    }

    // Recent VODs from the streamer you're watching, under the player.
    private fun loadMoreFrom(slug: String?, streamer: String?, currentVod: String?) {
        if (slug == null) {
            moreLoadedFor = null
            moreList.removeAllViews()
            setVisible(moreHeader, false)
            return
        }
        val key = "$slug|$currentVod"
        if (key == moreLoadedFor) return
        moreLoadedFor = key
        moreJob?.cancel()
        moreJob = lifecycleScope.launch {
            val vods = runCatching { KickApi.listVods(this@MainActivity, slug) }.getOrNull().orEmpty()
                .filter { it.uuid != currentVod }
                .take(8)
            moreList.removeAllViews()
            moreHeader.text = "More from ${streamer ?: slug}"
            setVisible(moreHeader, vods.isNotEmpty())
            vods.forEach { vod ->
                val row = layoutInflater.inflate(R.layout.item_media, moreList, false)
                bindVodRow(row, vod)
                row.setOnClickListener { playVod(vod) }
                moreList.addView(row)
            }
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

    // ---------- Captions ----------

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
        // Pinned channels
        pinnedRow.removeAllViews()
        val pins = Pins.all(this)
        pins.forEach { pinnedRow.addView(pinnedChip(it)) }
        setVisible(pinnedHint, pins.isEmpty())

        // Recently watched
        recentList.removeAllViews()
        val recents = Recents.all(this)
        recentHeader.text = if (recents.isEmpty()) "Nothing watched yet" else "Recently watched"
        recents.forEach { recentList.addView(recentRow(it)) }
    }

    private fun pinnedChip(pin: PinnedChannel): View {
        val chip = layoutInflater.inflate(R.layout.item_pinned, pinnedRow, false)
        val st = channelStatuses[pin.slug]
        val live = st?.isLive == true
        chip.findViewById<View>(R.id.pinRing)
            .setBackgroundResource(if (live) R.drawable.bg_ring_live else R.drawable.bg_ring)
        chip.findViewById<ImageView>(R.id.pinAvatar).load(st?.avatarUrl ?: pin.avatarUrl) {
            placeholder(R.drawable.avatar_placeholder)
            error(R.drawable.avatar_placeholder)
            fallback(R.drawable.avatar_placeholder)
            transformations(CircleCropTransformation())
        }
        setVisible(chip.findViewById(R.id.pinLive), live)
        chip.findViewById<TextView>(R.id.pinName).text = st?.streamer ?: pin.name
        chip.setOnClickListener { openChannel(pin.slug) }
        chip.setOnLongClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("Show VODs").setOnMenuItemClickListener { showVodList(pin.slug); true }
                menu.add("Unpin").setOnMenuItemClickListener {
                    Pins.remove(this@MainActivity, pin.slug)
                    renderHome()
                    updatePinButton()
                    true
                }
                show()
            }
            true
        }
        return chip
    }

    private fun recentRow(item: RecentItem): View {
        val row = layoutInflater.inflate(R.layout.item_media, recentList, false)
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
                badgeLive = live,
            )
            row.setOnClickListener { openChannel(item.id) }
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
                val slug = item.channelSlug
                if (slug != null) {
                    if (Pins.isPinned(this@MainActivity, slug)) {
                        menu.add("Unpin ${item.streamer}").setOnMenuItemClickListener {
                            Pins.remove(this@MainActivity, slug)
                            renderHome()
                            updatePinButton()
                            true
                        }
                    } else {
                        menu.add("Pin ${item.streamer}").setOnMenuItemClickListener {
                            Pins.add(
                                this@MainActivity,
                                PinnedChannel(slug, item.streamer, channelStatuses[slug]?.avatarUrl ?: item.avatarUrl),
                            )
                            renderHome()
                            updatePinButton()
                            true
                        }
                    }
                }
                menu.add("Remove from recents").setOnMenuItemClickListener {
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

    private fun bindVodRow(row: View, vod: KickVod) {
        val watched = VodPositions.get(this, vod.uuid)
        bindRow(
            row,
            thumbUrl = vod.thumbnailUrl,
            fitThumb = false,
            title = vod.title,
            subtitle = vod.startTime.take(10),
            detail = if (watched > 0) "Watched to ${formatTimestamp(watched)}" else "",
            durationText = vod.durationMs.takeIf { it > 0 }?.let { formatTimestamp(it) },
            progress = if (watched > 0 && vod.durationMs > 0) watched.toFloat() / vod.durationMs else null,
        )
    }

    private fun bindRow(
        row: View,
        thumbUrl: String?,
        fitThumb: Boolean,
        title: String,
        subtitle: String,
        detail: String,
        badge: String? = null,
        badgeLive: Boolean = false,
        durationText: String? = null,
        progress: Float? = null,
    ) {
        // Rounds the thumbnail's corners to match its background shape.
        row.findViewById<View>(R.id.thumbFrame).clipToOutline = true
        row.findViewById<ImageView>(R.id.thumb).apply {
            // Avatars are square, so they're shown whole instead of cropped to 16:9.
            scaleType = if (fitThumb) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            load(thumbUrl)
        }
        row.findViewById<TextView>(R.id.title).text = title.ifBlank { "Untitled" }
        row.findViewById<TextView>(R.id.subtitle).text = subtitle
        row.findViewById<TextView>(R.id.detail).apply {
            text = detail
            visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<TextView>(R.id.badge).apply {
            visibility = if (badge != null) View.VISIBLE else View.GONE
            text = badge
            setBackgroundResource(if (badgeLive) R.drawable.bg_badge_live else R.drawable.bg_badge_dark)
            setTextColor(if (badgeLive) Color.BLACK else Color.WHITE)
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

    // Checks which pinned and recent channels are live right now, one at a time.
    private fun refreshChannelStatuses() {
        statusJob?.cancel()
        val slugs = (Pins.all(this).map { it.slug } +
            Recents.all(this).filter { it.type == RecentItem.LIVE }.map { it.id }).distinct()
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

    // Errors show under the search box on the home screen, or as a toast
    // when the player screen is covering it.
    private fun showError(message: String?) {
        status.text = message.orEmpty()
        if (playerMode == PlayerMode.EXPANDED && !message.isNullOrEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
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

    // Live channels play; channels known to be offline open their VODs instead.
    private fun openChannel(slug: String) {
        channelInput.setText(slug)
        Prefs.setLastChannel(this, slug)
        if (channelStatuses[slug]?.isLive == false) {
            showVodList(slug)
        } else {
            play("Loading $slug…") { KickApi.resolve(this, slug) }
        }
    }

    private fun playVod(vod: KickVod) {
        play("Loading VOD…", vod.thumbnailUrl, vod.durationMs) {
            KickApi.resolveVod(this@MainActivity, vod.uuid)
        }
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
                val c = controller ?: run { showError("Player not ready, try again."); return@launch }
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
                playerMode = PlayerMode.EXPANDED
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
                        channelSlug = stream.channelSlug,
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
                showError(e.message)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    private fun showVodList(slugOverride: String? = null) {
        // If a VOD link is in the box, fall back to the last channel watched.
        val slug = slugOverride
            ?: if (extractVodUuid(channelInput.text.toString()) != null) Prefs.lastChannel(this)
            else normalizedSlug()
        if (slug.isBlank()) return
        hideKeyboard()
        status.text = "Loading $slug VODs…"
        lifecycleScope.launch {
            try {
                val vods = KickApi.listVods(this@MainActivity, slug)
                if (vods.isEmpty()) { showError("$slug has no VODs."); return@launch }
                status.text = ""
                val adapter = object : BaseAdapter() {
                    override fun getCount() = vods.size
                    override fun getItem(i: Int) = vods[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
                        val row = convertView
                            ?: layoutInflater.inflate(R.layout.item_media, parent, false)
                        bindVodRow(row, vods[i])
                        return row
                    }
                }
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("$slug VODs")
                    .setAdapter(adapter) { _, i -> playVod(vods[i]) }
                    .create()
                dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
                dialog.show()
                // The list exists only once the dialog is showing.
                dialog.listView?.apply {
                    // Lists ignore row margins, so the gap comes from an invisible divider.
                    divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    dividerHeight = dp(10)
                    setPadding(dp(12), 0, dp(12), dp(12))
                    clipToPadding = false
                }
            } catch (e: KickException) {
                showError(e.message)
            } catch (e: Exception) {
                showError("Error: ${e.message}")
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
    // Swipe down to shrink into the mini player (or to leave fullscreen).
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

            // A quick downward swipe shrinks the player, like YouTube.
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
            ): Boolean {
                if (multiTouch || zoomScale > 1.01f) return false
                if (velocityY < 1200 || kotlin.math.abs(velocityY) < kotlin.math.abs(velocityX) * 1.5f) {
                    return false
                }
                if (isFullscreen) toggleFullscreenButton() else minimize()
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
        private const val NAVBAR_HEIGHT_DP = 52
        private const val MAX_CAPTION_LINES = 2
        private const val CONTROLS_TIMEOUT_MS = 5_000
        private val playNotes = mutableMapOf<String, String>()
        private val avatarCache = mutableMapOf<String, String>()
    }
}
