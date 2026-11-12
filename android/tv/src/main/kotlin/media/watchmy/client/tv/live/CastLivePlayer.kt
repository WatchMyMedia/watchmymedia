package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.CastedLive
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * A channel cast to this TV (Cast Connect, the LiveCastConnect board): the
 * TV's own live player, with the caster's channels and pictures, and none of
 * what the phone does (On now, Quality, Full guide). When the phone stops
 * casting the TV says so for [STOPPED_SHOWN_MS], then goes back.
 */
@Composable
fun CastLivePlayer(live: CastedLive, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    // Logos and pictures from the caster's server, with their cast token.
    val caster = remember(services, live) { CasterServices(services, live.live) }
    val channels by live.channels.collectAsStateWithLifecycle()
    val sender by live.sender.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAppServices provides caster) {
        if (sender.stopped) {
            val channel = live.controller.state.value.target?.channel ?: live.target.channel
            CastStoppedScreen(sender.name, "${channel.number} ${channel.name}", onExit, modifier)
        } else {
            LivePlayerScreen(live.target, channels, onExit = onExit, onChannels = onExit, modifier = modifier, controller = live.controller, listings = live.listings, cast = sender)
        }
    }
}

private class CasterServices(services: AppServices, override val live: LiveRepository) : AppServices by services

/** How long "Pixel 9 stopped casting" shows before the TV goes back. */
internal const val STOPPED_SHOWN_MS = 5_000L

/** The Cast section beside the channel banner: who cast, and where the rest is. */
@Composable
internal fun CastFromBox(sender: String, modifier: Modifier = Modifier) {
    Row(
        modifier.width(460.dp).background(Color(0xEE101012), RoundedCornerShape(20.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(20.dp)).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(56.dp).background(WmmColors.Accent.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(WmmIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(30.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cast from $sender", style = inter(24.sp, FontWeight.Bold), color = WmmColors.Text)
            Text("Quality and the channel list are on the phone.", style = inter(18.sp), color = WmmColors.TextMuted)
        }
    }
}

/** The pill above a cast's problem screens: "Cast from Pixel 9", or "Casting ended". */
@Composable
internal fun CastPill(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xCC101012), RoundedCornerShape(999.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(999.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WmmIcons.Cast, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(22.dp))
        Text(label, style = inter(18.sp, FontWeight.SemiBold), color = WmmColors.Text)
    }
}

/**
 * A cast's problem, full screen: the pill (and the channel asked for) at the
 * top, then the icon, title, words and actions in the middle, the first
 * action focused.
 */
@Composable
internal fun CastProblem(
    pill: String,
    title: String,
    body: String,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    channel: String? = null,
    small: String? = null,
    actions: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color(0xFF070708))) {
        Row(Modifier.padding(start = 58.dp, top = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            channel?.let { Text(it, style = inter(24.sp, FontWeight.Bold), color = WmmColors.Text) }
            CastPill(pill)
        }
        Column(Modifier.align(Alignment.Center).width(980.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(76.dp).background(WmmColors.Surface2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(WmmIcons.Cast, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(38.dp))
            }
            Text(title, style = inter(44.sp, FontWeight.Bold), color = WmmColors.Text)
            Text(body, style = inter(24.sp, lineHeight = 34.sp), color = WmmColors.TextMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            small?.let { Text(it, style = inter(19.sp), color = WmmColors.TextFaint) }
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { actions() }
        }
    }
    LaunchedEffect(focus) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

/** "Pixel 9 stopped casting": the channel has stopped and its tuner is free; the TV goes back by itself. */
@Composable
internal fun CastStoppedScreen(sender: String, channel: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val close by rememberUpdatedState(onClose)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(STOPPED_SHOWN_MS)
        close()
    }
    CastProblem(
        pill = "Casting ended",
        title = "$sender stopped casting",
        body = "$channel stopped on this TV, and its tuner is free again.",
        small = "This TV goes back to what it showed before in ${STOPPED_SHOWN_MS / 1000} s.",
        focus = focus,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_BACK && event.type == KeyEventType.KeyUp) close()
            event.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_BACK
        },
    ) {
        TvButton("Close", { close() }, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
    }
}

/** The pause note while a cast channel is paused: from the phone, or from this TV's remote. */
internal fun castPausedNote(sender: LiveCastSender): String =
    if (sender.pausedThere) "Paused on ${sender.name}. After 5 minutes the tuner is given back." else "Paused. After 5 minutes the tuner is given back."

/**
 * A cast channel's prompts, in the LiveCastConnect board's words: Still
 * watching (which counts down the minute before the tuner goes back), all
 * tuners busy, and any other stop, each with Stop casting.
 */
@Composable
internal fun CastPrompt(
    state: LivePlayerState,
    channel: LiveChannel,
    sender: String,
    focus: FocusRequester,
    onKeepWatching: () -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.askStillWatching -> {
            var left by remember { mutableIntStateOf(STILL_WATCHING_GRACE_S) }
            LaunchedEffect(Unit) {
                while (left > 0) {
                    delay(1_000)
                    left--
                }
            }
            Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = .75f)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(980.dp).background(WmmColors.Surface, RoundedCornerShape(28.dp)).padding(48.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("${channel.number} ${channel.name} · on for 4 hours · cast from $sender", style = inter(19.sp), color = WmmColors.TextMuted)
                    Text("Still watching?", style = inter(40.sp, FontWeight.Bold), color = WmmColors.Text)
                    Text(
                        "Nothing has been pressed here or on $sender for 4 hours. Live TV stops in 0:${left.toString().padStart(2, '0')} so the tuner is free for someone else. Any press here or on the phone counts as an answer.",
                        style = inter(24.sp, lineHeight = 34.sp), color = WmmColors.TextMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        TvButton("Keep watching", onKeepWatching, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
                        TvButton("Stop casting", onStop)
                    }
                }
            }
        }
        state.reason == "tuners_busy" -> CastProblem(
            pill = "Cast from $sender",
            channel = "${channel.number}  ${channel.name}",
            title = "All tuners are in use",
            body = "$sender asked for ${channel.number}, but the HDHomeRun’s tuners are all busy. Try again when someone stops watching, or pick another channel on the phone.",
            focus = focus,
            modifier = modifier,
        ) {
            TvButton("Try again", onRetry, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
            TvButton("Stop casting", onStop)
        }
        else -> LivePlayerPrompt(
            title = state.error?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "Live TV stopped",
            message = state.error ?: "The tuner was released. Tune again to watch live.",
            action = "Tune again",
            onAction = onRetry,
            onLeave = onStop,
            focus = focus,
            modifier = modifier,
            leaveLabel = "Stop casting",
        )
    }
}

/** The minute Still watching gives before the tuner goes back (the live controller's grace). */
private const val STILL_WATCHING_GRACE_S = 59
