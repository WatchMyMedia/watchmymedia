package media.watchmy.client.mobile.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

/**
 * Phones play in landscape; tablets follow rotation, or stay
 * [pinned] to the side it is on now; puts back what the activity asked for
 * before on leaving.
 */
@Composable
internal fun LandscapeLock(activity: Activity, pinned: Boolean) {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        onDispose { activity.requestedOrientation = previous }
    }
    DisposableEffect(activity, pinned, tablet) {
        activity.requestedOrientation = when {
            pinned -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            tablet -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {}
    }
}

/**
 * Whether the rotation lock can do anything here. Android 16 ignores
 * orientation requests from apps that target it on displays at least 600 dp
 * across both ways, where the system turns the screen itself, so the lock
 * button is left out there; phones keep it.
 */
@Composable
internal fun rememberRotationLockWorks(activity: Activity?): Boolean {
    val configuration = LocalConfiguration.current
    return remember(activity, configuration) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return@remember true
        val bounds = activity.windowManager.maximumWindowMetrics.bounds
        val density = activity.resources.displayMetrics.density
        rotationLockWorks(Build.VERSION.SDK_INT, (minOf(bounds.width(), bounds.height()) / density).roundToInt())
    }
}

/** [rememberRotationLockWorks] for an API level and the display's smallest width. */
internal fun rotationLockWorks(sdk: Int, displaySmallestWidthDp: Int): Boolean =
    sdk < Build.VERSION_CODES.BAKLAVA || displaySmallestWidthDp < 600

/** Hides the status and navigation bars while shown (a swipe from the edge brings them back for a moment). */
@Composable
internal fun HideSystemBars(window: Window) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(window, lifecycle) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val hide = {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        hide()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hide() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * For content in a dialog or bottom sheet over the player: those are
 * windows of their own, which would bring the system bars back.
 */
@Composable
internal fun HideSystemBarsInDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        if (window != null) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
}

@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** The buttons Android shows on the picture-in-picture window. */
internal enum class PipAction { Replay, PlayPause, Forward }

/** Whether the activity is in picture in picture, and a way in. */
@Stable
internal class PictureInPicture(private val activity: ComponentActivity) {
    val supported: Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    var active by mutableStateOf(activity.isInPictureInPictureMode)
        internal set

    internal var params: PictureInPictureParams? = null

    /** Returns false when the device or the viewer turned picture in picture off. */
    fun enter(): Boolean = supported && runCatching { activity.enterPictureInPictureMode(params ?: PictureInPictureParams.Builder().build()) }.getOrDefault(false)
}

/**
 * Picture in picture for the player: keeps the window's aspect ratio and
 * buttons current, enters it when the viewer leaves the app while
 * [playing] (Android 12 and later do that by themselves with auto-enter),
 * and passes the window's buttons to [onAction]. [aspect] is width to
 * height; [sourceRect] is where the video sits, for the animation.
 */
@Composable
internal fun rememberPictureInPicture(
    activity: ComponentActivity,
    aspect: Pair<Int, Int>,
    sourceRect: android.graphics.Rect?,
    playing: Boolean,
    onAction: (PipAction) -> Unit,
): PictureInPicture {
    val pip = remember(activity) { PictureInPicture(activity) }
    val isPlaying by rememberUpdatedState(playing)
    val handle by rememberUpdatedState(onAction)
    val broadcast = remember(activity) { "${activity.packageName}.player.PIP_ACTION" }
    DisposableEffect(activity) {
        val modeListener = Consumer<PictureInPictureModeChangedInfo> { pip.active = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        val leaveListener = Runnable { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isPlaying) pip.enter() }
        activity.addOnUserLeaveHintListener(leaveListener)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                PipAction.entries.getOrNull(intent.getIntExtra(EXTRA_PIP_ACTION, -1))?.let(handle)
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(broadcast), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(modeListener)
            activity.removeOnUserLeaveHintListener(leaveListener)
            activity.unregisterReceiver(receiver)
            // Other screens must not drop into picture in picture when the viewer leaves.
            if (pip.supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
        }
    }
    DisposableEffect(activity, aspect, sourceRect, playing) {
        val params = pipParams(activity, broadcast, aspect, sourceRect, playing)
        pip.params = params
        if (pip.supported) runCatching { activity.setPictureInPictureParams(params) }
        onDispose {}
    }
    return pip
}

private fun pipParams(activity: Activity, broadcast: String, aspect: Pair<Int, Int>, sourceRect: android.graphics.Rect?, playing: Boolean): PictureInPictureParams {
    fun action(a: PipAction, icon: Int, title: String): RemoteAction {
        val intent = Intent(broadcast).setPackage(activity.packageName).putExtra(EXTRA_PIP_ACTION, a.ordinal)
        val pending = PendingIntent.getBroadcast(activity, a.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource("android", icon), title, title, pending)
    }
    val actions = listOf(
        action(PipAction.Replay, android.R.drawable.ic_media_rew, "Back 10 seconds"),
        if (playing) action(PipAction.PlayPause, android.R.drawable.ic_media_pause, "Pause") else action(PipAction.PlayPause, android.R.drawable.ic_media_play, "Play"),
        action(PipAction.Forward, android.R.drawable.ic_media_ff, "Forward 10 seconds"),
    )
    val b = PictureInPictureParams.Builder().setAspectRatio(Rational(aspect.first, aspect.second)).setActions(actions)
    if (sourceRect != null && !sourceRect.isEmpty) b.setSourceRectHint(sourceRect)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        b.setAutoEnterEnabled(playing)
        b.setSeamlessResizeEnabled(false)
    }
    return b.build()
}

/**
 * The picture-in-picture aspect ratio for a video [width] × [height] (any
 * unit), as a fraction Android accepts: 16:9 when the size is not known yet,
 * and kept within the 1:2.39 to 2.39:1 Android allows.
 */
internal fun pipAspectRatio(width: Float?, height: Float?): Pair<Int, Int> {
    if (width == null || height == null || !(width > 0f) || !(height > 0f) || width.isInfinite() || height.isInfinite()) return 16 to 9
    val ratio = (width / height).coerceIn(1 / 2.39f + 0.001f, 2.39f - 0.001f)
    return (ratio * 1000).roundToInt() to 1000
}

private const val EXTRA_PIP_ACTION = "action"
