package media.watchmy.client.mobile.detail

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.downloads.rememberNotificationAsk
import media.watchmy.client.model.FOLLOW_ASK_TITLE
import media.watchmy.client.model.LiveDevice
import media.watchmy.client.model.followAskBody
import media.watchmy.client.model.followAskNotNow
import media.watchmy.client.model.followLabel
import media.watchmy.client.model.followSpoken
import media.watchmy.client.model.newEpisodesLimits
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Notify me (PLAN-NEW-EPISODES.md §7.1; the boards' `bell`): the first of
 * About the show's actions. Off, the outline bell and "Notify me"; on, the
 * filled bell and "Notifications on", both gold. A button whose name carries
 * its state, not a switch.
 */
@Composable
internal fun FollowButton(following: Boolean, show: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TonalButton(
        followLabel(following),
        onClick,
        modifier.semantics { contentDescription = followSpoken(following, show) },
        icon = if (following) WmmIcons.BellFilled else WmmIcons.Bell,
        contentColor = if (following) WmmColors.Accent else WmmColors.Text,
    )
}

/**
 * The explainer before Android asks (the boards' `explainer`): after the
 * first Notify me, on Android 13 and later while the app may not post
 * notifications yet, once a process. The show is already followed.
 */
@Stable
internal class FollowAsk {
    /** The show the explainer is about, while it is open. */
    var pending by mutableStateOf<String?>(null)

    /** After a Notify me that turned it on: explains, then asks, when this device may not post notifications yet. */
    fun after(context: Context, show: String) {
        if (explained || Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        explained = true
        pending = show
    }

    private companion object {
        /** Explained once a process at most; Android itself stops asking after two refusals. */
        var explained = false
    }
}

@Composable
internal fun rememberFollowAsk(): FollowAsk = remember { FollowAsk() }

/**
 * The explainer, as Remind me's is: the gold bell, the title, the body, the
 * limits and what Not now does, Not now as a text button and Continue
 * filled, which hands over to Android's question. Not now, Back and a tap
 * outside keep the follow.
 */
@Composable
internal fun FollowAskDialog(ask: FollowAsk, device: LiveDevice) {
    // Registered while the screen is, so Android's answer has somewhere to land after this closes.
    val notify = rememberNotificationAsk()
    val show = ask.pending ?: return
    AlertDialog(
        onDismissRequest = { ask.pending = null },
        icon = { Icon(WmmIcons.Bell, contentDescription = null, tint = WmmColors.Accent) },
        title = { Text(FOLLOW_ASK_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(followAskBody(show, device), color = WmmColors.TextSoft, fontSize = 14.sp, lineHeight = 20.sp)
                Text(newEpisodesLimits(device), color = WmmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
                Text(followAskNotNow(show), color = WmmColors.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        },
        dismissButton = { GoldTextButton("Not now", { ask.pending = null }) },
        confirmButton = {
            FilledButton("Continue", {
                ask.pending = null
                notify()
            })
        },
        containerColor = WmmColors.Surface2,
    )
}
