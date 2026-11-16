package media.watchmy.client.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.live.rememberLiveDevice
import media.watchmy.client.model.FOLLOWS_EMPTY
import media.watchmy.client.model.FOLLOWS_FAILED_TITLE
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.NEW_EPISODES_CHANNEL
import media.watchmy.client.model.NEW_EPISODES_ROW_LINE
import media.watchmy.client.model.NEW_EPISODES_SWITCH
import media.watchmy.client.model.NEW_EPISODES_SWITCH_LINE
import media.watchmy.client.model.NOTIFICATIONS_LEDE
import media.watchmy.client.model.NotificationsBlocked
import media.watchmy.client.model.OTHER_DEVICES_STILL
import media.watchmy.client.model.REMINDERS_ARE_IN
import media.watchmy.client.model.REMINDERS_ARE_IN_LINK
import media.watchmy.client.model.SHOWS_YOU_FOLLOW
import media.watchmy.client.model.STOP_NOTIFYING
import media.watchmy.client.model.TURN_ON_NOTIFICATIONS
import media.watchmy.client.model.line
import media.watchmy.client.model.newEpisodesBlocked
import media.watchmy.client.model.newEpisodesLimits
import media.watchmy.client.model.onThisDevice
import media.watchmy.client.model.spoken
import media.watchmy.client.model.stopNotifyingSpoken
import media.watchmy.client.model.stopRefused
import media.watchmy.client.model.text
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.NotificationsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/** Settings › Notifications' model, from the Settings route's own models, which the overview's line shares. */
@Composable
internal fun notificationsModel(): NotificationsViewModel {
    val services = LocalAppServices.current
    return viewModel(key = "settings-notifications") { NotificationsViewModel(services.follows) }
}

/**
 * Settings › Notifications (PLAN-NEW-EPISODES.md §7.2; the boards'
 * `settings` and `off`): the card when Android won't show new episodes
 * (while the switch is on), the lede, On this phone (the switch, the
 * Notifications row, the limits and, where Playback shows them, where Live
 * TV reminders are), then Shows you follow, newest first, each with Stop
 * notifying. A show still in the library opens its page ([onOpenShow]).
 * [remindersShown] says Playback has Live TV reminders; [onOpenPlayback]
 * opens it.
 */
@Composable
internal fun NotificationsPage(
    tablet: Boolean,
    online: Boolean,
    remindersShown: Boolean,
    onOpenShow: (Long) -> Unit,
    onOpenPlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = notificationsModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val device = deviceWord()
    val context = LocalContext.current
    val allowed = rememberChannelsAllowed()
    val showing = allowed.app && allowed.newEpisodes
    // Back online, a list that didn't load comes back by itself.
    LaunchedEffect(online) { if (online && state.list is Load.Failed) vm.retry() }

    Column(modifier) {
        if (state.notifyHere && !showing) {
            val blocked = NotificationsBlocked(app = !allowed.app, reminders = false, newEpisodes = true)
            StatusCard(
                newEpisodesBlocked(device),
                blocked.text() + " " + OTHER_DEVICES_STILL,
                NoteTone.Warn,
                Modifier.cardInset(tablet),
                action = TURN_ON_NOTIFICATIONS,
                onAction = { openNotifications(context, NEW_EPISODES_CHANNEL) },
            )
        }
        Text(NOTIFICATIONS_LEDE, style = LedeStyle, modifier = Modifier.padding(horizontal = if (tablet) 4.dp else 16.dp).padding(top = 4.dp, bottom = 2.dp))
        PageHeading(onThisDevice(device), tablet)
        SettingsRows(tablet) {
            ListRow(
                NEW_EPISODES_SWITCH,
                subtitle = NEW_EPISODES_SWITCH_LINE,
                modifier = Modifier.toggleable(value = state.notifyHere, role = Role.Switch, onValueChange = vm::setNotifyHere),
                trailing = { Switch(checked = state.notifyHere, onCheckedChange = null, colors = SettingSwitchColors) },
            )
            ListRow(
                "Notifications",
                subtitle = NEW_EPISODES_ROW_LINE,
                modifier = Modifier.clickable { openNotifications(context, NEW_EPISODES_CHANNEL) },
                trailing = { RowValue(if (showing) "Allowed" else "Off") },
            )
        }
        FootNote(newEpisodesLimits(rememberLiveDevice()), tablet = tablet)
        if (remindersShown) RemindersPointer(tablet, onOpenPlayback)
        PageHeading(SHOWS_YOU_FOLLOW, tablet)
        when (val list = state.list) {
            Load.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
                CircularProgressIndicator(Modifier.size(20.dp), color = WmmColors.TextMuted, strokeWidth = 2.dp)
            }
            is Load.Failed -> ListFailed(list.message, tablet, onRetry = vm::retry)
            is Load.Ready -> if (list.data.isEmpty()) {
                Text(FOLLOWS_EMPTY, style = LedeStyle, modifier = Modifier.padding(horizontal = if (tablet) 4.dp else 16.dp).padding(top = 4.dp))
            } else {
                SettingsRows(tablet) {
                    list.data.forEach { show ->
                        FollowRow(show, onOpen = onOpenShow, onStop = { vm.stop(show) })
                        if (show.tmdbId in state.refused) FailureLine(stopRefused(show.title), onRetry = { vm.stop(show) })
                    }
                }
            }
        }
    }
}

/** "Live TV reminders are in Playback.", with Playback a link to its page. */
@Composable
private fun RemindersPointer(tablet: Boolean, onOpenPlayback: () -> Unit) {
    val text = remember(onOpenPlayback) {
        buildAnnotatedString {
            append(REMINDERS_ARE_IN)
            withLink(LinkAnnotation.Clickable("playback", TextLinkStyles(SpanStyle(color = WmmColors.Accent, textDecoration = TextDecoration.Underline))) { onOpenPlayback() }) {
                append(REMINDERS_ARE_IN_LINK)
            }
            append(".")
        }
    }
    Text(text, style = FootStyle, modifier = Modifier.padding(horizontal = if (tablet) 4.dp else 16.dp).padding(top = if (tablet) 6.dp else 4.dp))
}

/**
 * A show followed: its poster, its latest new episode and when, and Stop
 * notifying. One no longer in the library has no poster and no link, and
 * says so in the warning colour.
 */
@Composable
private fun FollowRow(show: FollowedShow, onOpen: (Long) -> Unit, onStop: () -> Unit) {
    val library = LocalAppServices.current.library
    val line = show.line()
    val showId = show.showId
    ListRow(
        show.title,
        subtitle = line,
        subtitleColor = if (show.gone) WmmColors.Warn else WmmColors.TextMuted,
        minHeight = 76.dp,
        modifier = (if (showId != null) Modifier.clickable { onOpen(showId) } else Modifier).semantics { contentDescription = show.spoken(line) },
        leading = {
            if (show.gone) {
                Box(Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(6.dp)).background(WmmColors.Surface2), contentAlignment = Alignment.Center) {
                    Icon(WmmIcons.Tv, contentDescription = null, tint = WmmColors.TextFaint, modifier = Modifier.size(18.dp))
                }
            } else {
                Artwork(library.imageUrl(show.posterPath, ImageSize.W185), seed = show.title, contentDescription = null, modifier = Modifier.size(40.dp, 60.dp), shape = RoundedCornerShape(6.dp))
            }
        },
        trailing = { GoldTextButton(STOP_NOTIFYING, onStop, Modifier.semantics { contentDescription = stopNotifyingSpoken(show.title) }) },
    )
}

/** The list didn't load: its title, the line and Try again, in its place. */
@Composable
private fun ListFailed(line: String, tablet: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (tablet) 4.dp else 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(FOLLOWS_FAILED_TITLE, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text))
            Text(line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, color = WmmColors.TextMuted))
        }
        GoldTextButton("Try again", onRetry)
    }
}
