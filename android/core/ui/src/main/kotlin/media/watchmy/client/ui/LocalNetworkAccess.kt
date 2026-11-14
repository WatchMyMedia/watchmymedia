package media.watchmy.client.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Android 17's local network access, for the server picker's row
 * (PLAN-ANDROID-REVIEW.md §3.4.2, LocalNetwork.dc.html and
 * TvLocalNetwork.dc.html). Without it the app can't find servers on this
 * network or reach them at their local address; "Not now" at the app's
 * first question was the only chance to allow it before.
 */
@Stable
class LocalNetworkAccess internal constructor(
    needed: Boolean,
    stopped: Boolean,
    private val onAsk: () -> Unit,
) {
    /** The permission is missing on a system that asks for it. */
    var needed by mutableStateOf(needed)
        internal set

    /** The system has stopped asking: only its settings can allow it now. */
    var stopped by mutableStateOf(stopped)
        internal set

    /** Allow (the system's question) or Open settings (once it has stopped asking). */
    fun ask() = onAsk()
}

/**
 * The picker's view of local network access. [onGranted] runs when it is
 * allowed, here or in the settings. The system's question is asked with
 * the platform's own requestPermissions, and its answer read when the
 * screen resumes after it, so core:ui needs no activity library for it.
 */
@Composable
fun rememberLocalNetworkAccess(onGranted: () -> Unit = {}): LocalNetworkAccess {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    var stopped by rememberSaveable { mutableStateOf(false) }
    // The question or the settings page is open; the next resume reads its answer.
    var asking by remember { mutableStateOf(false) }
    lateinit var access: LocalNetworkAccess
    access = remember {
        LocalNetworkAccess(needsLocalNetwork(context), stopped) {
            asking = true
            val activity = context.findActivity()
            if (access.stopped || activity == null) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                activity.requestPermissions(arrayOf(PERMISSION), REQUEST_CODE)
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        val needed = needsLocalNetwork(context)
        if (access.needed && !needed) granted()
        access.needed = needed
        if (asking && needed && context.findActivity()?.shouldShowRequestPermissionRationale(PERMISSION) != true) {
            // Refused with no reason to show again: the system won't ask any more.
            stopped = true
            access.stopped = true
        }
        asking = false
        onPauseOrDispose { }
    }
    return access
}

/** The permission Android 17 asks local network access with. */
private const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** The picker's request; the answer is read on resume, not from this code. */
private const val REQUEST_CODE = 0x4C4E

private fun needsLocalNetwork(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
