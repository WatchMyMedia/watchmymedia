package media.watchmy.client.core.cast

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastService
import media.watchmy.client.model.CastState
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LiveCastRemote
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.ServerRepository
import androidx.media3.cast.Cast as Media3Cast
import com.google.android.gms.cast.framework.CastState as SdkCastState

/**
 * Casting from this phone over the Google Cast SDK (PLAN-CAST.md §5.2).
 * Build it once, on the main thread, and only where [isSupported]: it is
 * [CastState.Unavailable] until the Cast SDK is up and the server in use
 * says it issues cast tokens. Media notification and lock screen controls
 * while casting come from the Cast SDK ([options]).
 *
 * The SDK starts once the main thread is first idle, after the app's first
 * frame, and builds its CastContext on a background thread, which on the
 * main thread held up a cold start's first frame. Until it is up the Cast
 * button stays hidden, as it always does without the SDK.
 */
@OptIn(UnstableApi::class)
class RealCastService(
    context: Context,
    private val gateway: CastGateway,
    servers: ServerRepository,
    auth: AuthRepository,
    preferences: PreferencesRepository,
    library: LibraryRepository,
    client: () -> ClientInfo,
) : CastService {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<CastState>(CastState.Unavailable)
    override val state: StateFlow<CastState> = _state.asStateFlow()

    private val controller = CastPlaybackController(gateway, preferences, client, { library.imageUrl(it, ImageSize.W1280) })
    override val remote: PlaybackController = controller
    override val nowPlaying: StateFlow<PlayRequest?> = controller.nowPlaying

    private val liveController = LiveCastController(gateway, client)
    override val live: LiveCastRemote = liveController

    private var castContext: CastContext? = null
    private var sdkState = SdkCastState.NO_DEVICES_AVAILABLE
    private var serverCasts = false
    private var session: CastSession? = null
    private var connecting: String? = null

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            connecting = session.castDevice?.friendlyName
            publish()
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            connecting = session.castDevice?.friendlyName
            publish()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) = disconnected()

        override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected()

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) = disconnected()

        override fun onSessionSuspended(session: CastSession, reason: Int) = disconnected()
    }

    private val castStates = CastStateListener {
        sdkState = it
        publish()
    }

    init {
        Looper.myQueue().addIdleHandler {
            startSdk()
            false
        }
        // Whether the server in use issues cast tokens, checked whenever the server or the sign-in changes.
        scope.launch {
            combine(servers.current, auth.user) { server, user -> server?.server?.id?.takeIf { user != null } }
                .distinctUntilChanged()
                .collect { id ->
                    serverCasts = id != null && withContext(Dispatchers.IO) { gateway.serverCasts() }
                    publish()
                }
        }
    }

    private fun startSdk() {
        // Media3's Cast button needs its own handle on the Cast SDK.
        runCatching { Media3Cast.getSingletonInstance(app).initialize() }
            .onFailure { Log.w(TAG, "Media3 cast unavailable", it) }
        // Both build the one CastContext on a background executor; its listeners run here, on the main thread.
        CastContext.getSharedInstance(app, Dispatchers.IO.asExecutor())
            .addOnSuccessListener(ContextCompat.getMainExecutor(app), ::setUp)
            .addOnFailureListener(ContextCompat.getMainExecutor(app)) { Log.w(TAG, "Cast unavailable", it) }
    }

    override fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private fun setUp(context: CastContext) {
        castContext = context
        sdkState = context.castState
        context.addCastStateListener(castStates)
        context.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        context.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::connected)
        publish()
    }

    private fun connected(s: CastSession) {
        session = s
        connecting = null
        // The file remote and the live remote share the session's messages.
        val shared = SharedLink(SdkReceiverLink(s))
        controller.attach(shared.view())
        liveController.attach(shared.view())
        publish()
    }

    private fun disconnected() {
        session = null
        connecting = null
        controller.detach()
        liveController.detach()
        publish()
    }

    private fun publish() {
        val s = session
        _state.value = castState(
            attached = s != null && controller.connected,
            deviceName = s?.castDevice?.friendlyName,
            sdkReady = castContext != null,
            serverCasts = serverCasts,
            sdkState = sdkState,
            connecting = connecting,
        )
    }

    companion object {
        private const val TAG = "WmmCast"

        /** True when this device can cast at all: Google Play services is there. */
        fun isSupported(context: Context): Boolean =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

        /**
         * The Cast SDK's options, for the app's OptionsProvider: our receiver,
         * Cast Connect (an Android TV with the app plays in the app), and the
         * SDK's media notification, which opens [activityClassName].
         *
         * No supported namespaces: they filter discovery down to devices that
         * already declare our namespace, which no device does before our
         * receiver runs, so the chooser found nothing. Messages on
         * [CastProtocol.NAMESPACE] need no declaring ([SdkReceiverLink]).
         *
         * On Android 17 the Cast button opens the system's Output Switcher:
         * an app targeting it finds no devices in its own chooser without
         * local network access, and the switcher needs none. It also needs
         * the manifest's MediaTransferReceiver.
         */
        fun options(appId: String, activityClassName: String): CastOptions = CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setShowSystemOutputSwitcherOnCastIconClick(Build.VERSION.SDK_INT >= 37)
            .setLaunchOptions(LaunchOptions.Builder().setAndroidReceiverCompatible(true).build())
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(NotificationOptions.Builder().setTargetActivityClassName(activityClassName).build())
                    .setMediaSessionEnabled(true)
                    .build(),
            )
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }
}

/**
 * What the Cast button shows. A session with the remote attached is
 * Connected, whatever else is true; otherwise without the Cast SDK
 * ([sdkReady]) or a server that issues cast tokens there is no casting, and
 * then the SDK's [sdkState] (a CastState constant) or a session starting
 * on [connecting] says the rest.
 */
internal fun castState(
    attached: Boolean,
    deviceName: String?,
    sdkReady: Boolean,
    serverCasts: Boolean,
    sdkState: Int,
    connecting: String?,
): CastState = when {
    attached -> CastState.Connected(deviceName?.takeIf { it.isNotBlank() } ?: "TV")
    !sdkReady || !serverCasts -> CastState.Unavailable
    sdkState == SdkCastState.CONNECTING || connecting != null -> CastState.Connecting(connecting)
    sdkState == SdkCastState.NO_DEVICES_AVAILABLE -> CastState.NoDevices
    else -> CastState.Available
}

/** Whether the server in use issues cast tokens; no server, an older one or one that can't be reached can't. */
internal suspend fun CastGateway.serverCasts(): Boolean = try {
    castServer()?.casting == true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}
