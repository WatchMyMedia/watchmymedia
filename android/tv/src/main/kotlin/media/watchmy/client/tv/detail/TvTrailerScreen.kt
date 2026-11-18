package media.watchmy.client.tv.detail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.presentation.detail.TrailerViewModel
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.tv.player.KeepScreenOn
import media.watchmy.client.tv.player.Spinner
import media.watchmy.client.ui.TRAILER_BRIDGE
import media.watchmy.client.ui.TrailerEvent
import media.watchmy.client.ui.TrailerState
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.onBack
import media.watchmy.client.ui.parseTrailerTime
import media.watchmy.client.ui.trailerBaseUrl
import media.watchmy.client.ui.trailerPage
import android.view.KeyEvent as AndroidKeyEvent

/**
 * A movie's YouTube trailers on Android TV (TvTrailerPlayer.dc.html,
 * PLAN-TV-TRAILERS.md §4): YouTube's IFrame player in a WebView, as on the
 * phone, but filling the screen with nothing of ours drawn on it or beside
 * it while it is shown.
 *
 * - While the player loads, our own screen (the trailer, the movie and
 *   "Plays from YouTube" under a spinner) shows, and the WebView is there
 *   but not shown. The page doesn't play by itself: once the player says it
 *   is ready, the screen shows it, then asks it to play, so nothing plays
 *   unseen. A player that isn't ready within [READY_TIMEOUT_MS] counts as
 *   unreachable.
 * - [trailers] are tried in turn while their owners keep them on YouTube
 *   ([TrailerViewModel]); after the last, the player is taken off the screen
 *   and ours says so, with Open in YouTube where an app takes the link. When
 *   YouTube can't be reached, ours offers Try again.
 * - While the trailer is paused, the player leaves the screen again and our
 *   pause screen takes its place (the user's decision after TV0): the
 *   trailer, the movie and "Paused · 0:13 / 7:45" under a pause sign.
 *   YouTube's own player shows nothing when paused through its API, so a
 *   paused trailer looked frozen. Select or Play shows the player again,
 *   then asks it to play; the jumps move the time on our screen and leave
 *   the trailer paused.
 * - A trailer that played and ends closes the screen, as Back does. It
 *   pauses when the screen is hidden, and keeps the TV awake while it plays.
 * - The remote follows §4.3 ([trailerKey]) through YouTube's IFrame API
 *   ([trailerScript]); the screen holds the focus and the WebView never
 *   takes it. Back is always ours.
 *
 * When no WebView can be made after all (the screen restored after the
 * TV's WebView went away), the trailer goes to the YouTube app instead.
 */
@Composable
fun TvTrailerScreen(
    movieTitle: String,
    trailers: List<Trailer.YouTube>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { TrailerViewModel(createSavedStateHandle(), movieTitle, trailers) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val back by rememberUpdatedState(onBack)
    var left by remember { mutableStateOf(false) }
    val leave = {
        if (!left) {
            left = true
            back()
        }
    }
    val status = remember { TrailerStatus() }
    var notice by remember { mutableStateOf<String?>(null) }

    val player = remember {
        TvTrailerWebView.create(
            context,
            onEvent = { kind, key, value ->
                // Word from a trailer the screen has moved past is left alone.
                if (key == vm.state.value.playing?.key) {
                    when (kind) {
                        TrailerEvent.READY -> status.ready(key, vm.state.value.attempt)
                        TrailerEvent.STATE -> {
                            status.playerState = value
                            when (value) {
                                TrailerState.PLAYING -> {
                                    status.played = true
                                    // Playing is never left out of sight, however it started.
                                    status.paused = false
                                }
                                TrailerState.PAUSED -> status.paused = true
                                TrailerState.ENDED -> if (status.played) leave()
                            }
                        }
                        TrailerEvent.ERROR -> vm.playerError(key, value)
                        TrailerEvent.UNREACHABLE -> vm.playerUnreachable(key)
                    }
                }
            },
            onLeave = { url -> if (!context.openYouTube(url)) notice = TrailerText.COULD_NOT_OPEN },
            onGone = { leave() },
        )
    }
    if (player == null) {
        // No WebView after all: the YouTube app, as before trailers played here.
        LaunchedEffect(Unit) {
            trailers.firstOrNull()?.let { context.openYouTube(it.watchUrl) }
            leave()
        }
        Box(modifier.fillMaxSize().background(PlayerGround))
        return
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // The page for the trailer playing now, or a blank one while a message shows, so nothing plays behind it.
    LaunchedEffect(state.playing?.key, state.attempt) {
        status.reset()
        val playing = state.playing
        if (playing == null) {
            player.clear()
            return@LaunchedEffect
        }
        player.load(playing.key)
        delay(READY_TIMEOUT_MS)
        // A WebView too old for YouTube's player never says it is ready.
        if (!status.isReady(playing.key, state.attempt)) vm.playerUnreachable(playing.key)
    }

    // Ready: the player is ours to show, or to replace with the pause screen while it is paused.
    val ready = state.playing?.let { status.isReady(it.key, state.attempt) } == true
    val paused = ready && status.paused
    val shown = ready && !status.paused
    val root = remember { FocusRequester() }
    // Shown first, then asked to play, at the start and after a pause: YouTube's rule that nothing plays unseen.
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        withFrameNanos { }
        player.play()
    }
    // The screen holds the focus for the remote's keys (§4.3); the WebView never takes it.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        withFrameNanos { }
        root.tryFocus()
    }
    // The pause screen's time, from the page.
    LaunchedEffect(paused) { if (paused) player.time(status::time) }
    LaunchedEffect(shown) { if (shown) notice = null }
    val onKey = { event: AndroidKeyEvent ->
        val key = trailerKey(event.keyCode)
        val jump = key == TrailerKey.Back10 || key == TrailerKey.Ahead10
        when {
            key == TrailerKey.Pass || key == TrailerKey.Leave -> false
            event.action != AndroidKeyEvent.ACTION_DOWN || key == TrailerKey.Swallow -> true
            // A held play or pause key acts once; held jumps repeat.
            event.repeatCount > 0 && !jump -> true
            status.paused -> {
                when {
                    // Shown again by the pause screen leaving; then asked to play.
                    key == TrailerKey.Toggle || key == TrailerKey.Play -> status.paused = false
                    // Jumps stay paused, and the pause screen shows where they went.
                    jump -> trailerScript(key)?.let { player.call(it, status::time) }
                }
                true
            }
            else -> {
                trailerScript(key)?.let { player.call(it) }
                true
            }
        }
    }

    // YouTube's rules: nothing plays while the screen can't be seen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    KeepScreenOn(shown && status.playing)

    Box(
        modifier
            .fillMaxSize()
            .background(PlayerGround)
            .onBack(onBack = leave)
            .onPreviewKeyEvent { ready && onKey(it.nativeKeyEvent) }
            .focusRequester(root)
            .focusable(enabled = ready),
    ) {
        AndroidView(
            factory = { player.attach() },
            modifier = Modifier.fillMaxSize(),
            update = { player.show(shown) },
        )
        if (shown) return@Box
        val trailer = state.trailer
        val name = trailer?.name ?: TrailerText.BUTTON
        when {
            paused -> TrailerPaused(name, vm.movieTitle, status.positionMs, status.durationMs)
            state.unreachable -> TrailerProblem(
                trailer = name,
                movie = vm.movieTitle,
                icon = WmmIcons.Offline,
                title = TrailerText.UNREACHABLE_TITLE,
                body = TrailerText.UNREACHABLE_BODY,
                action = "Try again",
                external = false,
                onAction = vm::retry,
                onBack = leave,
            )
            state.refused -> {
                val app = remember(context) { context.canOpenYouTube() }
                TrailerProblem(
                    trailer = name,
                    movie = vm.movieTitle,
                    icon = WmmIcons.Question,
                    title = TrailerText.REFUSED_TITLE,
                    body = TrailerText.REFUSED_BODY,
                    action = TrailerText.OPEN_IN_YOUTUBE.takeIf { app && trailer != null },
                    external = true,
                    onAction = { trailer?.let { if (!context.openYouTube(it.watchUrl)) notice = TrailerText.COULD_NOT_OPEN } },
                    onBack = leave,
                )
            }
            else -> TrailerLoading(name, vm.movieTitle)
        }
        Notice(notice, { notice = null })
    }
}

/** How long the player has to say it is ready before the screen says YouTube couldn't be reached (§4.4). */
internal const val READY_TIMEOUT_MS = 20_000L

/** The TV player's near-black ground, under the trailer screen's own states (TvTrailerPlayer.dc.html). */
private val PlayerGround = Color(0xFF070708)

/** The problem screens' explanation (#BDBDBD in the board). */
private val BodyText = Color(0xFFBDBDBD)

/** What the page has said about the trailer it shows now, from its load on. */
@Stable
private class TrailerStatus {
    /** The trailer and load ([TrailerViewModel.State.attempt]) whose player said it is ready, so the screen shows it. */
    private var readyFor by mutableStateOf<Pair<String, Int>?>(null)

    /** YouTube's last player state (`TrailerState`), -1 before any. */
    var playerState by mutableIntStateOf(-1)

    /** It played at some point, so its end closes the screen. */
    var played = false

    /** YouTube's player said it is paused, and nobody has asked it to play since: the pause screen shows. */
    var paused by mutableStateOf(false)

    /** Where the paused trailer is, and how long it is (0 while YouTube doesn't know), for the pause screen. */
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    fun time(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        if (durationMs > 0) this.durationMs = durationMs
    }

    val playing: Boolean get() = playerState == TrailerState.PLAYING || playerState == TrailerState.BUFFERING

    fun ready(key: String, attempt: Int) {
        readyFor = key to attempt
    }

    /** Ready for this load, not one before it: a new trailer or Try again is out of sight until its own player is ready. */
    fun isReady(key: String, attempt: Int): Boolean = readyFor == key to attempt

    fun reset() {
        readyFor = null
        playerState = -1
        played = false
        paused = false
        positionMs = 0
        durationMs = 0
    }
}

/** While YouTube's player loads, out of sight: a spinner, the trailer, the movie and "Plays from YouTube". Nothing takes focus. */
@Composable
private fun TrailerLoading(trailer: String, movie: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(PlayerGround),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spinner()
        Text(trailer, style = inter(48.sp, FontWeight.Bold, tracking = -0.01f), color = WmmColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 48.dp))
        Text(movie, style = inter(26.sp), color = WmmColors.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        Text(TrailerText.PLAYS_FROM_YOUTUBE, style = inter(20.sp, FontWeight.Medium), color = WmmColors.TextMuted, modifier = Modifier.padding(top = 36.dp))
    }
}

/**
 * While the trailer is paused, in the player's place: laid out as the
 * loading screen, with a pause sign for the spinner, and where the trailer
 * is ("Paused · 0:13 / 7:45") for "Plays from YouTube". Nothing on it takes
 * focus; the screen has the keys.
 */
@Composable
private fun TrailerPaused(trailer: String, movie: String, positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(PlayerGround),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(WmmIcons.Pause, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(72.dp))
        Text(trailer, style = inter(48.sp, FontWeight.Bold, tracking = -0.01f), color = WmmColors.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 48.dp))
        Text(movie, style = inter(26.sp), color = WmmColors.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        Text(
            TrailerText.paused(positionMs, durationMs),
            style = inter(20.sp, FontWeight.Medium),
            color = WmmColors.TextMuted,
            modifier = Modifier.padding(top = 36.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Why no trailer plays, in the player's place: the trailer and the movie at
 * the top left (as the Live TV problems place their channel), then the
 * icon, the title, the words and the buttons in the middle. The first
 * button has the focus: [action] (primary) when there is one, else Back to
 * the movie, which is then the primary one.
 */
@Composable
private fun TrailerProblem(
    trailer: String,
    movie: String,
    icon: ImageVector,
    title: String,
    body: String,
    action: String?,
    external: Boolean,
    onAction: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Box(modifier.fillMaxSize().background(PlayerGround)) {
        Row(Modifier.padding(start = 58.dp, top = 52.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(trailer, style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.alignByBaseline())
            Text(movie, style = inter(22.sp), color = WmmColors.TextMuted, modifier = Modifier.alignByBaseline())
        }
        Column(
            Modifier.align(Alignment.Center).width(1200.dp).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(104.dp).background(WmmColors.Surface2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(48.dp))
            }
            Text(title, style = inter(48.sp, FontWeight.Bold, tracking = -0.01f), color = WmmColors.Text, textAlign = TextAlign.Center)
            Text(
                body,
                style = inter(24.sp, lineHeight = 36.sp),
                color = BodyText,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 960.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (action != null) {
                    TvButton(action, onAction, Modifier.focusRequester(focus), style = TvButtonStyle.Primary, icon = if (external) WmmIcons.External else null)
                    TvButton(TrailerText.BACK_TO_MOVIE, onBack)
                } else {
                    TvButton(TrailerText.BACK_TO_MOVIE, onBack, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
                }
            }
        }
    }
    LaunchedEffect(title, action) {
        withFrameNanos { }
        focus.tryFocus()
    }
}

/**
 * The WebView that holds YouTube's player on the TV, locked down as the
 * phone's is (TrailerScreen): JavaScript for the player's page only, no
 * file or content access, no other windows, no zoom, and any link that
 * would take the page elsewhere opened outside the app ([onLeave]). The
 * page's events reach [onEvent] on the main thread; [onGone] is called when
 * its renderer died.
 *
 * It is never shown until the player is ready ([show]) and never takes
 * the focus: the screen calls YouTube's IFrame API in the page ([call]).
 */
@SuppressLint("SetJavaScriptEnabled")
internal class TvTrailerWebView private constructor(
    context: Context,
    private val view: WebView,
    private val onEvent: (kind: String, key: String, value: Int) -> Unit,
    private val onLeave: (String) -> Unit,
    private val onGone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val baseUrl = trailerBaseUrl(context.packageName)
    private val origin = baseUrl.removeSuffix("/")
    private var released = false

    init {
        view.apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            // MATCH_PARENT, not the WRAP_CONTENT AndroidView would give it: a WebView whose height wraps
            // its content lays the page out 0 px high, so the page's 100% heights collapse and YouTube's
            // player frame plays its sound with no picture (seen on a Google TV Streamer, TV0).
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.INVISIBLE
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                // YouTube's player keeps its settings (volume, quality) in local storage.
                domStorageEnabled = true
                // The screen asks it to play once it is shown; Trailer was the press that asked for that.
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setSupportZoom(false)
                builtInZoomControls = false
                setGeolocationEnabled(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(Bridge(), TRAILER_BRIDGE)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.toString() == BLANK) return false
                    // A frame inside the page (YouTube's player) loads freely; this is only asked for its other schemes.
                    if (!request.isForMainFrame) return !url.isWeb()
                    if (url.isWeb()) onLeave(url.toString())
                    return true
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    release()
                    onGone()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Without a poster, the WebView draws its own grey play button before YouTube's player appears.
                override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
            }
        }
    }

    /** The WebView, out of whatever held it before. */
    fun attach(): WebView = view.also { (it.parent as? ViewGroup)?.removeView(it) }

    fun load(key: String) {
        if (released) return
        view.loadDataWithBaseURL(baseUrl, trailerPage(key, origin, autoplay = false), "text/html", "utf-8", null)
    }

    fun clear() {
        if (!released) view.loadUrl(BLANK)
    }

    /** Shows the player, or keeps it out of sight: while it loads, while it is paused, and behind our messages. */
    fun show(shown: Boolean) {
        if (!released) view.visibility = if (shown) View.VISIBLE else View.INVISIBLE
    }

    /** Asks the player to play, once it is shown. */
    fun play() = call("playTrailer()")

    /** Reads where the player is and how long the video is ([parseTrailerTime]), into [onTime]. */
    fun time(onTime: (positionMs: Long, durationMs: Long) -> Unit) = call("trailerTime()", onTime)

    /**
     * Calls the page ([trailerScript], `trailerPage`). [onTime] gets where
     * the player is after it, when the call returns that (`trailerTime()`
     * and the jumps do).
     */
    fun call(script: String, onTime: ((positionMs: Long, durationMs: Long) -> Unit)? = null) {
        if (released) return
        if (onTime == null) {
            view.evaluateJavascript(script, null)
        } else {
            view.evaluateJavascript(script) { result ->
                if (!released) parseTrailerTime(result)?.let { (position, duration) -> onTime(position, duration) }
            }
        }
    }

    fun pause() {
        if (released) return
        call("pauseTrailer()")
        view.onPause()
    }

    fun resume() {
        if (!released) view.onResume()
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        view.stopLoading()
        view.removeJavascriptInterface(TRAILER_BRIDGE)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    /**
     * What the page calls. Android gives it to every frame in the WebView,
     * YouTube's player too, so it takes only what is harmless to be told
     * wrongly, and checks it on the main thread.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun post(kind: String, key: String, value: Int) {
            main.post { if (!released) onEvent(kind, key, value) }
        }
    }

    companion object {
        private const val BLANK = "about:blank"

        private fun Uri.isWeb() = scheme == "https" || scheme == "http"

        /** The player's WebView, or null when this TV can't make one ([TrailerWebViews]). */
        fun create(
            context: Context,
            onEvent: (kind: String, key: String, value: Int) -> Unit,
            onLeave: (String) -> Unit,
            onGone: () -> Unit,
        ): TvTrailerWebView? = TrailerWebViews.create(context)?.let { TvTrailerWebView(context, it, onEvent, onLeave, onGone) }
    }
}
