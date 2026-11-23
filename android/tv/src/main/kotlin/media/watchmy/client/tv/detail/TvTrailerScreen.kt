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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.presentation.detail.TrailerViewModel
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.tv.player.KeepScreenOn
import media.watchmy.client.ui.TRAILER_BRIDGE
import media.watchmy.client.ui.TrailerEvent
import media.watchmy.client.ui.TrailerState
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.onBack
import media.watchmy.client.ui.parseTrailerTime
import media.watchmy.client.ui.trailerBaseUrl
import media.watchmy.client.ui.trailerPage
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import media.watchmy.client.model.formatClock
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.MediaCard
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.WideCardHeight
import media.watchmy.client.tv.components.WideCardWidth
import media.watchmy.client.tv.player.KeyHint
import android.view.KeyEvent as AndroidKeyEvent

/**
 * A movie's YouTube trailers on Android TV:
 * YouTube's IFrame player in a WebView, as on the
 * phone, but filling the screen with nothing of ours drawn on it or beside
 * it while it is shown.
 *
 * - While the player loads, our own screen (the movie, the trailer,
 *   "Getting it from YouTube…" and a focused Back to the movie) shows, and
 *   the WebView is there but not shown. The page doesn't play by itself: once the player says it
 *   is ready, the screen shows it, then asks it to play, so nothing plays
 *   unseen. A player that isn't ready within [READY_TIMEOUT_MS] counts as
 *   unreachable.
 * - [trailers] are tried in turn while their owners keep them on YouTube
 *   ([TrailerViewModel]); after the last, the player is taken off the screen
 *   and ours says so, with Open in YouTube where an app takes the link. When
 *   YouTube can't be reached, ours offers Try again.
 * - While the trailer is paused, the player leaves the screen again and our
 *   pause screen takes its place: the trailer, "Paused at 0:13 · 7:31
 *   left", a focused Play and the movie's other trailers under it.
 *   YouTube's own player shows nothing when paused through its API, so a
 *   paused trailer looked frozen. Select or Play shows the player again,
 *   then asks it to play; the jumps move the time on our screen and leave
 *   the trailer paused. ▼ goes to the other trailers, and OK on one plays it.
 * - A trailer that played and ends closes the screen, as Back does. It
 *   pauses when the screen is hidden, and keeps the TV awake while it plays.
 * - The remote's keys ([trailerKey]) go through YouTube's IFrame API
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
    // The trailer chosen from More trailers, and those after it, tried in turn as the first one was.
    var chosen by rememberSaveable { mutableStateOf(trailers.firstOrNull()?.key) }
    val ordered = remember(trailers, chosen) { trailers.dropWhile { it.key != chosen }.ifEmpty { trailers } }
    val vm = viewModel(key = "trailer:$chosen") { TrailerViewModel(createSavedStateHandle(), movieTitle, ordered) }
    // The page's callbacks are made once, with the WebView: they read the trailer model in use now.
    val currentVm by rememberUpdatedState(vm)
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
                val vm = currentVm
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
    // The screen holds the focus for the remote's keys while YouTube's player shows; the WebView never takes it.
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        withFrameNanos { }
        root.tryFocus()
    }
    // Focus on one of More trailers: ◀ ▶ move along the row and OK plays that one, rather than the player taking them.
    var inMore by remember { mutableStateOf(false) }
    // Paused, our screen's Play has the focus, so OK plays as it always did.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(paused) {
        inMore = false
        if (!paused) return@LaunchedEffect
        withFrameNanos { }
        playFocus.tryFocus() || root.tryFocus()
    }
    // The pause screen's time, from the page.
    LaunchedEffect(paused) { if (paused) player.time(status::time) }
    LaunchedEffect(shown) { if (shown) notice = null }
    val onKey = { event: AndroidKeyEvent ->
        val key = trailerKey(event.keyCode)
        val jump = key == TrailerKey.Back10 || key == TrailerKey.Ahead10
        val select = event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == AndroidKeyEvent.KEYCODE_ENTER || event.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            key == TrailerKey.Pass || key == TrailerKey.Leave -> false
            // Paused, ▲ ▼ move between Play and More trailers.
            status.paused && key == TrailerKey.Swallow -> false
            status.paused && inMore && (jump && event.keyCode != AndroidKeyEvent.KEYCODE_MEDIA_REWIND && event.keyCode != AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD || select) -> false
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
        // The movie's other trailers, which the paused screen offers.
        val others = trailers.filter { it.key != trailer?.key }
        when {
            paused -> TrailerPaused(
                trailer = name,
                movie = vm.movieTitle,
                positionMs = status.positionMs,
                durationMs = status.durationMs,
                others = others,
                playFocus = playFocus,
                onPlay = { status.paused = false },
                onChoose = { chosen = it.key },
                onMoreFocused = { inMore = it },
            )
            state.unreachable -> TrailerProblem(
                movie = vm.movieTitle,
                title = TrailerText.UNREACHABLE_TITLE,
                body = UNREACHABLE_BODY,
                action = "Try again",
                external = false,
                onAction = vm::retry,
                onBack = leave,
            )
            state.refused -> {
                val app = remember(context) { context.canOpenYouTube() }
                TrailerProblem(
                    movie = vm.movieTitle,
                    title = TrailerText.REFUSED_TITLE,
                    body = REFUSED_BODY,
                    action = TrailerText.OPEN_IN_YOUTUBE.takeIf { app && trailer != null },
                    external = true,
                    onAction = { trailer?.let { if (!context.openYouTube(it.watchUrl)) notice = TrailerText.COULD_NOT_OPEN } },
                    onBack = leave,
                )
            }
            else -> TrailerLoading(name, vm.movieTitle, onBack = leave)
        }
        Notice(notice, { notice = null })
    }
}

/** How long the player has to say it is ready before the screen says YouTube couldn't be reached. */
internal const val READY_TIMEOUT_MS = 20_000L

/** The TV player's near-black ground, under the trailer screen's own states. */
private val PlayerGround = Color(0xFF070708)

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

/** The problem screens' words, as a viewer would say them. */
private const val REFUSED_BODY = "Whoever posted it doesn’t let it play inside other apps."
private const val UNREACHABLE_BODY = "This TV may be off the internet. Your library still works."

/** "Trailer for Dead Reckoning Bay · plays from YouTube", over every state of ours. */
private fun trailerKicker(movie: String) = "Trailer for $movie · plays from YouTube"

/** "Paused at 0:13 · 2:32 left", or the time alone while YouTube doesn't know how long it is. */
internal fun pausedAt(positionMs: Long, durationMs: Long): String =
    "Paused at ${formatClock(positionMs)}" + if (durationMs > 0) " · ${formatClock((durationMs - positionMs).coerceAtLeast(0))} left" else ""

/**
 * Our screen in the player's place: the kicker, a title, a line about it
 * and the buttons, at the safe edge, with the clock. [top] is where it
 * starts: higher for the pause screen, which has a row under it.
 */
@Composable
private fun TrailerOwnScreen(
    movie: String,
    title: String,
    modifier: Modifier = Modifier,
    top: Dp = StateTop,
    body: String? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(PlayerGround)) {
        TvClock()
        Column(Modifier.padding(start = SafeX, end = SafeX, top = top).widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Kicker(trailerKicker(movie))
            Text(title, style = TvType.Title, color = WmmColors.Text, maxLines = 2)
            if (body != null) Text(body, style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 820.dp).semantics { liveRegion = LiveRegionMode.Polite })
            content()
        }
    }
}

private val StateTop = 330.dp

/** While YouTube's player loads, out of sight: the trailer, "Getting it from YouTube…", and Back to the movie, focused. */
@Composable
private fun TrailerLoading(trailer: String, movie: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    TrailerOwnScreen(movie, trailer, modifier, body = "Getting it from YouTube…") {
        TvButton(TrailerText.BACK_TO_MOVIE, onBack, Modifier.padding(top = 14.dp).focusRequester(focus), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.tryFocus()
    }
}

/**
 * While the trailer is paused, in the player's place: the trailer, how far
 * it has played and how long is left, Play (focused, so OK plays as it
 * always did), and the movie's other trailers ([others]) in a row under it:
 * ▼ goes there, ◀ ▶ move along it and OK plays one ([onChoose]). The keys
 * along the foot say what Play doesn't show.
 */
@Composable
private fun TrailerPaused(
    trailer: String,
    movie: String,
    positionMs: Long,
    durationMs: Long,
    others: List<Trailer.YouTube>,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onChoose: (Trailer.YouTube) -> Unit,
    onMoreFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        TrailerOwnScreen(movie, trailer, top = PausedTop) {
            if (durationMs > 0) {
                Box(Modifier.padding(top = 6.dp).width(560.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f)).height(8.dp).background(WmmColors.Accent))
                }
            }
            Text(
                pausedAt(positionMs, durationMs),
                style = TvType.Body.copy(fontFeatureSettings = "tnum"),
                color = WmmColors.Text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TvButton("Play", onPlay, Modifier.padding(top = 14.dp).focusRequester(playFocus), style = TvButtonStyle.Primary, icon = WmmIcons.Play, growFrom = TransformOrigin(0f, 0.5f))
        }
        if (others.isNotEmpty()) {
            Column(
                Modifier
                    .padding(start = SafeX, top = MoreTop)
                    .onFocusChanged { onMoreFocused(it.hasFocus) },
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Text("More trailers for $movie", style = TvType.CardTitle, color = WmmColors.Text)
                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    for (t in others) {
                        MediaCard(
                            title = t.name,
                            subtitle = null,
                            imageUrl = null,
                            artWidth = WideCardWidth,
                            artHeight = WideCardHeight,
                            onClick = { onChoose(t) },
                            seed = t.key,
                        )
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = SafeX, bottom = SafeY), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            KeyHint("OK", "Play")
            KeyHint("◀ ▶", "10 seconds")
            KeyHint("Back", "The movie")
        }
    }
}

private val PausedTop = 150.dp
private val MoreTop = 640.dp

/**
 * Why no trailer plays, in the player's place: the title, the words and
 * the buttons, at the safe edge. The first button has the focus: [action]
 * (primary) when there is one, else Back to the movie, which is then the
 * primary one.
 */
@Composable
private fun TrailerProblem(
    movie: String,
    title: String,
    body: String,
    action: String?,
    external: Boolean,
    onAction: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    TrailerOwnScreen(movie, title, modifier, body = body) {
        Row(Modifier.padding(top = 14.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (action != null) {
                TvButton(action, onAction, Modifier.focusRequester(focus), style = TvButtonStyle.Primary, icon = if (external) WmmIcons.External else null, growFrom = TransformOrigin(0f, 0.5f))
                TvButton(TrailerText.BACK_TO_MOVIE, onBack)
            } else {
                TvButton(TrailerText.BACK_TO_MOVIE, onBack, Modifier.focusRequester(focus), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
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
            // player frame plays its sound with no picture (seen on a Google TV Streamer).
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
