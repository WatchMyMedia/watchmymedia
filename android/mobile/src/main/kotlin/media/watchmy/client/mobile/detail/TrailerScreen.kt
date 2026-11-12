package media.watchmy.client.mobile.detail

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
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.player.HideSystemBars
import media.watchmy.client.mobile.player.KeepScreenOn
import media.watchmy.client.mobile.player.LandscapeLock
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.presentation.detail.TrailerViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.rememberTabletLayout

/**
 * A movie's YouTube trailers on phones and tablets (TrailerPlayer.dc.html,
 * TabTrailerPlayer.dc.html; PLAN-TRAILERS.md §6.2): full screen and in
 * landscape like the player, our bar (back, the trailer and the movie, and
 * YouTube's terms) above YouTube's own player at 16:9, with nothing of ours
 * drawn over it. [trailers] are tried in turn while their owners keep them
 * on YouTube ([TrailerViewModel]), then the screen says so and offers Open
 * in YouTube. Back leaves YouTube's full screen first, then the screen.
 */
@Composable
fun TrailerScreen(movieTitle: String, trailers: List<Trailer.YouTube>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = viewModel { TrailerViewModel(createSavedStateHandle(), movieTitle, trailers) }
    val state by vm.state.collectAsStateWithLifecycle()
    val tablet = rememberTabletLayout().isTablet
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var message by remember { mutableStateOf<String?>(null) }
    val open = { url: String ->
        if (runCatching { uri.openUri(url) }.isFailure) message = TrailerText.COULD_NOT_OPEN
    }
    val back by rememberUpdatedState(onBack)

    var fullscreen by remember { mutableStateOf<FullscreenVideo?>(null) }
    val player = remember {
        TrailerWebView(
            context,
            onEvent = { kind, key, value ->
                when (kind) {
                    TrailerEvent.ERROR -> vm.playerError(key, value)
                    TrailerEvent.UNREACHABLE -> vm.playerUnreachable(key)
                }
            },
            onLeave = { url -> open(url) },
            onFullscreen = { fullscreen = it },
            onGone = { back() },
        )
    }
    DisposableEffect(player) {
        onDispose {
            fullscreen?.callback?.onCustomViewHidden()
            player.release()
        }
    }

    // The page for the trailer playing now; a blank one while a message shows, so nothing plays behind it.
    LaunchedEffect(state.playing?.key, state.attempt) {
        val playing = state.playing
        if (playing != null) player.load(playing.key) else player.clear()
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

    val activity = LocalActivity.current
    if (activity != null) {
        LandscapeLock(activity, pinned = false)
        HideSystemBars(activity.window)
    }
    KeepScreenOn(state.playing != null)
    BackHandler {
        val f = fullscreen
        if (f != null) {
            f.callback.onCustomViewHidden()
            fullscreen = null
        } else {
            onBack()
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            TrailerBar(
                title = state.trailer?.name ?: TrailerText.BUTTON,
                movieTitle = vm.movieTitle,
                tablet = tablet,
                onBack = onBack,
                onTerms = { open(TrailerText.YOUTUBE_TERMS_URL) },
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(16f / 9f)) {
                    val shown = state.trailer
                    when {
                        state.playing != null -> AndroidView(factory = { player.attach() }, Modifier.fillMaxSize())
                        state.unreachable -> TrailerMessage(
                            title = TrailerText.UNREACHABLE_TITLE,
                            body = TrailerText.UNREACHABLE_BODY,
                            action = "Try again",
                            external = false,
                            onAction = vm::retry,
                        )
                        else -> TrailerMessage(
                            title = TrailerText.REFUSED_TITLE,
                            body = TrailerText.REFUSED_BODY,
                            action = TrailerText.OPEN_IN_YOUTUBE.takeIf { shown != null },
                            external = true,
                            onAction = { shown?.let { open(it.watchUrl) } },
                        )
                    }
                }
            }
        }
        // YouTube's own full-screen button: its video over everything, ours included.
        fullscreen?.let { f ->
            key(f) {
                AndroidView(
                    factory = { c -> FrameLayout(c).apply { setBackgroundColor(android.graphics.Color.BLACK); (f.view.parent as? ViewGroup)?.removeView(f.view); addView(f.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        MessageHost(message, { message = null }, Modifier.align(Alignment.BottomCenter))
    }
}

/** The bar above the player: back, the trailer's name over the movie's, and "Plays from YouTube · YouTube Terms of Service". */
@Composable
private fun TrailerBar(title: String, movieTitle: String, tablet: Boolean, onBack: () -> Unit, onTerms: () -> Unit) {
    Column(Modifier.background(WmmColors.Ground)) {
        Row(
            Modifier.fillMaxWidth().height(if (tablet) 72.dp else 64.dp).padding(start = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarIconButton(WmmIcons.Back, "Back to the movie", onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = if (tablet) 18.sp else 16.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(movieTitle, style = TextStyle(fontFamily = Inter, fontSize = if (tablet) 14.sp else 13.sp, color = WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                buildAnnotatedString {
                    append("${TrailerText.PLAYS_FROM_YOUTUBE} · ")
                    withLink(
                        LinkAnnotation.Clickable(
                            "terms",
                            TextLinkStyles(SpanStyle(color = WmmColors.TextSoft, textDecoration = TextDecoration.Underline)),
                        ) { onTerms() },
                    ) { append(TrailerText.YOUTUBE_TERMS) }
                },
                style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted),
                maxLines = 1,
            )
        }
        HorizontalDivider(color = WmmColors.Line)
    }
}

/** In the player's place: why no trailer plays, and what to do (refused: Open in YouTube; unreachable: Try again). */
@Composable
private fun TrailerMessage(title: String, body: String, action: String?, external: Boolean, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(WmmColors.Surface).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(WmmIcons.Question, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(32.dp))
        Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = WmmColors.Text, textAlign = TextAlign.Center), modifier = Modifier.padding(top = 4.dp))
        Text(body, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft, textAlign = TextAlign.Center))
        if (action != null) TonalButton(action, onAction, Modifier.padding(top = 8.dp), icon = if (external) WmmIcons.External else null)
    }
}

/** YouTube's video in its own full screen, until [callback] says it is over. */
private class FullscreenVideo(val view: View, val callback: WebChromeClient.CustomViewCallback)

/**
 * The WebView that holds YouTube's player, locked down: JavaScript for the
 * player's page only, no file or content access, no other windows, no
 * zoom, and every link that would take the page somewhere else opened
 * outside the app instead ([onLeave]: the browser, or the YouTube app for
 * YouTube's links). The page's events reach [onEvent] on the main thread.
 * [onGone] is called when the WebView's renderer died, and the WebView is
 * then of no more use.
 */
@SuppressLint("SetJavaScriptEnabled")
private class TrailerWebView(
    context: Context,
    private val onEvent: (kind: String, key: String, value: Int) -> Unit,
    private val onLeave: (String) -> Unit,
    private val onFullscreen: (FullscreenVideo?) -> Unit,
    private val onGone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val baseUrl = trailerBaseUrl(context.packageName)
    private val origin = baseUrl.removeSuffix("/")
    private var released = false

    private val view: WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            // YouTube's player keeps its settings (volume, quality) in local storage.
            domStorageEnabled = true
            // Trailer was the tap that asked for it.
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
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = onFullscreen(FullscreenVideo(view, callback))

            override fun onHideCustomView() = onFullscreen(null)

            // Without a poster, the WebView draws its own grey play button before YouTube's player appears.
            override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
        }
    }

    /** The WebView, out of whatever held it before (the player comes and goes with the messages). */
    fun attach(): WebView = view.also { (it.parent as? ViewGroup)?.removeView(it) }

    fun load(key: String) {
        if (released) return
        view.loadDataWithBaseURL(baseUrl, trailerPage(key, origin), "text/html", "utf-8", null)
    }

    fun clear() {
        if (!released) view.loadUrl(BLANK)
    }

    fun pause() {
        if (released) return
        view.evaluateJavascript("pauseTrailer()", null)
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

    private companion object {
        const val BLANK = "about:blank"

        fun Uri.isWeb() = scheme == "https" || scheme == "http"
    }
}
