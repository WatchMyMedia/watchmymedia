package media.watchmy.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.ui.components.BrandMark
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import media.watchmy.client.ui.WmmColors

/**
 * Android 17 blocks traffic to the local network for apps that target it
 * unless the person allows "local network access": finding servers with mDNS,
 * `.local` names, and HTTP to a server's LAN address all fail without it. A
 * media server is usually on the LAN, so on Android 17 and later this asks
 * once, with a line on why, before the app's own screens. "Not now" is
 * remembered; servers reached over the internet work either way, and the
 * permission can be granted later under Nearby devices in the app's settings.
 * A system dialog dismissed without an answer is not a "Not now": the app
 * goes on, and asks again at its next start. [question] is answered when
 * this is, for a cast that waits on it.
 */
@Composable
fun LocalNetworkGate(isTv: Boolean, question: LocalNetworkQuestion, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val asking by question.open.collectAsState()
    if (!asking) {
        content()
        return
    }

    val activity = LocalActivity.current as? ComponentActivity
    // True once the system has stopped showing its dialog for this app (denied twice).
    var blocked by remember { mutableStateOf(false) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationaleAfter = activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK) == true
        when (localNetworkAnswer(granted, rationaleBefore, rationaleAfter)) {
            LocalNetworkAnswer.Granted, LocalNetworkAnswer.Dismissed -> question.answer()
            LocalNetworkAnswer.NotNow -> {
                decline(context)
                question.answer()
            }
            LocalNetworkAnswer.Blocked -> blocked = true
        }
    }

    WatchMyMediaPhoneTheme {
        LocalNetworkPrompt(
            isTv = isTv,
            blocked = blocked,
            onAllow = {
                if (blocked) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    decline(context)
                    question.answer()
                } else {
                    rationaleBefore = activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK) == true
                    launcher.launch(LOCAL_NETWORK)
                }
            },
            onNotNow = {
                decline(context)
                question.answer()
            },
        )
    }
}

/**
 * Whether the local network question is still open, for the whole app: from
 * start until the gate is answered. A cast that arrives meanwhile (Cast
 * Connect opens the app with it, before anyone has answered) waits for the
 * answer before it looks for the caster's server, which this TV can't reach
 * on the local network until then.
 */
class LocalNetworkQuestion(open: Boolean) {
    private val state = MutableStateFlow(open)
    val open: StateFlow<Boolean> = state.asStateFlow()

    fun answer() {
        state.value = false
    }

    /** Returns once the question is answered, or after [timeoutMs], when no one is at the prompt. */
    suspend fun awaitAnswer(timeoutMs: Long = ANSWER_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) { state.first { !it } }
    }

    companion object {
        /** Long enough to read the prompt and answer it; then a cast tries anyway. */
        const val ANSWER_TIMEOUT_MS = 2 * 60_000L

        /** Open when this device and app still need to ask. */
        fun of(context: Context) = LocalNetworkQuestion(shouldAsk(context))
    }
}

/** What asking for local network access came back as. */
internal enum class LocalNetworkAnswer { Granted, NotNow, Dismissed, Blocked }

/**
 * Reads the system dialog's result. Android's rationale flag
 * ([rationaleBefore] the request, [rationaleAfter] it) turns true at the
 * first "Don't allow" and false again at the second, after which the system
 * stops asking. Dismissing the dialog (Back, a tap outside) changes neither,
 * so a refusal with the flag false both times was a dismissal, not an
 * answer.
 */
internal fun localNetworkAnswer(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): LocalNetworkAnswer = when {
    granted -> LocalNetworkAnswer.Granted
    rationaleAfter -> LocalNetworkAnswer.NotNow
    rationaleBefore -> LocalNetworkAnswer.Blocked
    else -> LocalNetworkAnswer.Dismissed
}

@Composable
private fun LocalNetworkPrompt(isTv: Boolean, blocked: Boolean, onAllow: () -> Unit, onNotNow: () -> Unit) {
    val allowFocus = remember { FocusRequester() }
    // A TV has no touch: put the D-pad on the main button.
    LaunchedEffect(isTv) { if (isTv) allowFocus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WmmColors.Ground)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.Start) {
            BrandMark(size = 48.dp)
            Spacer(Modifier.height(24.dp))
            Text("Find your server on this network", style = MaterialTheme.typography.headlineSmall, color = WmmColors.Text)
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (blocked) {
                    "Local network access is turned off for WatchMyMedia. To use a server on this network, " +
                        "allow Nearby devices in the app's settings."
                } else {
                    "WatchMyMedia finds the server on your home network and streams straight from it. " +
                        "Android asks you to allow local network access for that."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = WmmColors.TextMuted,
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAllow, modifier = Modifier.focusRequester(allowFocus)) {
                    Text(if (blocked) "Open settings" else "Continue")
                }
                OutlinedButton(onClick = onNotNow) { Text("Not now") }
            }
        }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val PREFS = "local_network"
private const val DECLINED = "declined"

private fun shouldAsk(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 37 &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED &&
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DECLINED, false)

private fun decline(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(DECLINED, true) }
}
