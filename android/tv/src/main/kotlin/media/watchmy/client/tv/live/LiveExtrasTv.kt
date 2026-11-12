package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.LIVE_REMINDERS_EMPTY_TV
import media.watchmy.client.model.LIVE_REMINDERS_NOTE_TV
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.LiveSearchHit
import media.watchmy.client.model.bannerLine
import media.watchmy.client.model.bannerSpoken
import media.watchmy.client.model.canRemind
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveMinutesLeft
import media.watchmy.client.model.liveReminderHeadline
import media.watchmy.client.model.liveRemindersCountTv
import media.watchmy.client.model.liveSearchNoneTv
import media.watchmy.client.model.progressAt
import media.watchmy.client.model.spokenName
import media.watchmy.client.model.tvLine
import media.watchmy.client.model.tvRemoveLabel
import media.watchmy.client.model.tvStatus
import media.watchmy.client.model.tvWhen
import media.watchmy.client.model.whenLineTv
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.presentation.live.LiveSearchViewModel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/*
 * Favourites, search and reminders on Android TV (PLAN-LIVE-TV-GUIDE.md §6.5;
 * android-tv/LiveGuideExtras, LiveSearch and LiveReminder): Hold OK menus,
 * the Reminders list, the On Live TV row and the in-app reminder banner.
 * Android TV posts no system notification for a reminder.
 */

/** One row of a Hold OK menu. */
@Immutable
internal class TvMenuItem(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/** A Hold OK menu: what it is for, its rows (the first focused) and a note. Back closes it. */
@Composable
internal fun TvLiveMenu(title: String, subtitle: String, logo: LiveChannel?, items: List<TvMenuItem>, onDismiss: () -> Unit, note: String? = null) {
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(560.dp).background(WmmColors.Surface, RoundedCornerShape(22.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(22.dp)).padding(28.dp).focusGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (logo?.logo != null) ChannelLogo(logo, Modifier.width(56.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = inter(26.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = inter(18.sp), color = WmmColors.TextMuted, maxLines = 1)
                    }
                }
                items.forEachIndexed { index, item ->
                    TvButton(item.label, item.onClick, if (index == 0) Modifier.focusRequester(first) else Modifier, icon = item.icon, size = TvButtonSize.Medium, enabled = item.enabled)
                }
                note?.let { Text(it, style = inter(17.sp), color = WmmColors.TextMuted) }
            }
        }
        InitialFocus(first)
    }
}

/**
 * Live TV › Reminders on the TV: soonest first, each with Remove; a moved or
 * gone one says so. Reminders show on this TV while the app is open.
 */
@Composable
fun LiveRemindersScreenTv(channels: List<LiveChannel>, onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val model = viewModel { LiveRemindersViewModel(services.live, services.servers) }
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val now = rememberLiveNow()
    val first = remember { FocusRequester() }
    TvScaffold(RailSection.LiveTV, onNavigate, modifier) {
        Column(Modifier.fillMaxSize().padding(start = 150.dp, end = 58.dp, top = 48.dp)) {
            Text("Live TV › Reminders", style = inter(19.sp), color = WmmColors.TextMuted)
            Text("Reminders", style = inter(56.sp, FontWeight.ExtraBold), color = WmmColors.Text, modifier = Modifier.padding(top = 6.dp))
            Text(liveRemindersCountTv(state.items.size, state.leadMinutes), style = inter(21.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            if (state.items.isEmpty() && !state.loading) Text(LIVE_REMINDERS_EMPTY_TV, style = inter(22.sp), color = WmmColors.TextSoft)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.items, key = { it.id }) { reminder ->
                    TvReminderRow(
                        reminder, channels.firstOrNull { it.id == reminder.channelId } ?: LiveChannel(reminder.channelId.orEmpty(), "", reminder.number, reminder.channelName.orEmpty()),
                        now, format, onRemove = { model.remove(reminder) },
                        modifier = if (reminder == state.items.first()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            Text(LIVE_REMINDERS_NOTE_TV, style = inter(18.sp, lineHeight = 26.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(bottom = 40.dp))
        }
        if (state.items.isNotEmpty()) InitialFocus(first)
    }
}

@Composable
private fun TvReminderRow(reminder: LiveReminder, channel: LiveChannel, now: Long, format: LiveClockFormat, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val gone = reminder.state == LiveReminderState.Gone
    val moved = reminder.state == LiveReminderState.Moved
    Row(Modifier.fillMaxWidth().background(WmmColors.Surface, RoundedCornerShape(16.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(200.dp).clip(RoundedCornerShape(10.dp))) {
            ProgrammePicture(null, channel, Modifier.fillMaxWidth())
            Row(Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (channel.logo != null) ChannelLogo(channel, Modifier.width(30.dp))
                Text(channel.number, style = inter(17.sp, FontWeight.Bold), color = WmmColors.Text)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(reminder.title, style = inter(26.sp, FontWeight.Bold), color = if (gone) WmmColors.TextMuted else WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reminder.tvLine(), style = inter(19.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (gone) WmmIcons.BellOff else if (moved) WmmIcons.Moved else WmmIcons.Bell, contentDescription = null, tint = if (gone) WmmColors.TextMuted else if (moved) WmmColors.Accent else WmmColors.TextSoft, modifier = Modifier.size(22.dp))
                Text(reminder.tvStatus(now, format), style = inter(20.sp, FontWeight.SemiBold), color = if (gone) WmmColors.TextMuted else if (moved) WmmColors.Accent else WmmColors.Text)
            }
            Text(reminder.tvWhen(format), style = inter(18.sp), color = WmmColors.TextMuted)
        }
        TvButton("Remove", onRemove, modifier.semantics { contentDescription = reminder.tvRemoveLabel(format) }, size = TvButtonSize.Medium)
    }
}

/**
 * The in-app reminder on Android TV (LiveReminder's banner): "Starting in
 * 5 min" with the programme, over whatever is on screen. It never takes
 * focus: Hold OK watches the channel ([onWatch]), Back dismisses it, and it
 * hides by itself after [BANNER_MS]. [reminder] is the one showing.
 */
@Composable
internal fun TvReminderBanner(reminder: LiveReminder, leadMinutes: Int, channel: LiveChannel?, format: LiveClockFormat, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(reminder.id, reminder.startMs) {
        delay(BANNER_MS)
        onDismiss()
    }
    Column(
        modifier.width(760.dp).background(Color(0xF2141416), RoundedCornerShape(20.dp)).border(1.dp, WmmColors.Line2, RoundedCornerShape(20.dp)).padding(20.dp)
            .clearAndSetSemantics { contentDescription = reminder.bannerSpoken(leadMinutes); liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(200.dp).clip(RoundedCornerShape(10.dp))) {
                val shown = channel ?: LiveChannel(reminder.channelId.orEmpty(), "", reminder.number, reminder.channelName.orEmpty())
                ProgrammePicture(null, shown, Modifier.fillMaxWidth())
                Row(Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (shown.logo != null) ChannelLogo(shown, Modifier.width(26.dp))
                    Text(reminder.number, style = inter(15.sp, FontWeight.Bold), color = WmmColors.Text)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(20.dp))
                    Text(liveReminderHeadline(leadMinutes), style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Accent)
                }
                Text(reminder.title, style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(reminder.bannerLine(format), style = inter(18.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (reminder.channelId != null) TvOkHint("Watch ${reminder.number}", key = "Hold OK")
            TvOkHint("Dismiss", key = "Back")
        }
    }
}

/** How long the TV's reminder banner shows by itself. */
internal const val BANNER_MS = 15_000L

/**
 * The banner's keys, read before anything on screen does: a long press of
 * OK (its first repeat) watches, and Back dismisses. A short OK, and every
 * other key, goes to what has focus; so does a long one when the banner has
 * nothing for it ([holdsOk] false).
 */
internal class BannerKeys(private val onWatch: () -> Unit, private val onDismiss: () -> Unit, private val holdsOk: Boolean = true) {
    private var swallowing = mutableSetOf<Int>()

    fun handle(code: Int, down: Boolean, repeat: Int): Boolean {
        if (!down) return swallowing.remove(code)
        return when (code) {
            AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER -> if (holdsOk && repeat >= 1) {
                if (code !in swallowing) {
                    swallowing += code
                    onWatch()
                }
                true
            } else false
            AndroidKey.KEYCODE_BACK -> {
                swallowing += code
                onDismiss()
                true
            }
            else -> false
        }
    }
}

/**
 * Search's On Live TV row (android-tv/LiveSearch): programmes on now and later
 * that match [query]. OK watches one that is on and opens a later one in the
 * guide; Hold OK opens Remind me and Watch.
 */
@Composable
fun TvLiveSearchRow(
    state: LiveSearchViewModel.State,
    onWatch: (LiveChannel) -> Unit,
    onOpen: (LiveSearchHit) -> Unit,
    onToggleReminder: (LiveSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = rememberLiveClockFormat()
    val now = rememberLiveNow()
    if (!state.available || state.searched == null || state.error != null) return
    var focused by remember { mutableStateOf<LiveSearchHit?>(null) }
    var menu by remember { mutableStateOf<LiveSearchHit?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Text("On Live TV", style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text)
            if (state.results.isNotEmpty()) Text("${state.results.size}", style = inter(20.sp), color = WmmColors.TextMuted)
        }
        if (state.nothing) {
            Text(liveSearchNoneTv(state.searched.orEmpty(), state.listedThroughMs, format), style = inter(21.sp), color = WmmColors.TextMuted)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
            items(state.results.filter { it.programme.endMs > now }, key = { it.programme.id }) { hit ->
                TvLiveSearchCard(
                    hit, now, format, reminded = state.reminderFor(hit) != null,
                    onClick = { if (hit.programme.airingAt(now)) onWatch(hit.channel) else onOpen(hit) },
                    onLongClick = { menu = hit },
                    onFocus = { focused = hit },
                )
            }
        }
        focused?.let { hit ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hit.programme.title, style = inter(20.sp, FontWeight.SemiBold), color = WmmColors.TextSoft, maxLines = 1)
                Text("·", style = inter(20.sp), color = WmmColors.TextMuted)
                val airing = hit.programme.airingAt(now)
                TvOkHint(if (airing) "Watch ${hit.channel.number}" else "Open it in the guide")
                val reminded = state.reminderFor(hit) != null
                if (!airing && (reminded || state.canRemind(hit))) TvOkHint(if (reminded) "Remove reminder" else "Remind me", key = "Hold OK")
            }
        }
    }
    menu?.let { hit ->
        val reminded = state.reminderFor(hit) != null
        val airing = hit.programme.airingAt(now)
        val canRemind = !airing && (reminded || hit.programme.canRemind(now, state.leadMinutes))
        TvLiveMenu(
            title = hit.programme.title, subtitle = "${format.weekday(hit.programme.startMs)} ${format.time(hit.programme.startMs)} on ${hit.channel.number}", logo = null,
            items = listOfNotNull(
                if (canRemind) TvMenuItem(if (reminded) "Remove reminder" else "Remind me", if (reminded) WmmIcons.BellOff else WmmIcons.Bell) { menu = null; onToggleReminder(hit) } else null,
                TvMenuItem(if (airing) "Watch ${hit.channel.number}" else "Watch ${hit.channel.number} now", WmmIcons.Play, enabled = hit.channel.available) { menu = null; onWatch(hit.channel) },
            ),
            note = media.watchmy.client.model.LIVE_REMINDERS_MENU_NOTE_TV.takeIf { canRemind },
            onDismiss = { menu = null },
        )
    }
}

@Composable
private fun TvLiveSearchCard(hit: LiveSearchHit, now: Long, format: LiveClockFormat, reminded: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    val p = hit.programme
    val airing = p.airingAt(now)
    Column(modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusableBox(
            onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
                .clearAndSetSemantics { contentDescription = if (airing) "Watch ${hit.channel.number} ${hit.channel.name}, ${p.title}, ${liveMinutesLeft(now, p.endMs)}" else hit.spokenName(now, format, "Opens it in the guide") + if (reminded) ", reminder set" else "" },
            cornerRadius = 12.dp,
        ) {
            Box(Modifier.fillMaxWidth().background(WmmColors.Surface2, RoundedCornerShape(12.dp))) {
                ProgrammePicture(p, hit.channel, Modifier.fillMaxWidth())
                Row(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hit.channel.logo != null) ChannelLogo(hit.channel, Modifier.width(34.dp))
                    Text(hit.channel.number, style = inter(20.sp, FontWeight.Bold), color = WmmColors.Text)
                }
                if (airing) Row(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color(0xFFB42318), RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Color.White, RoundedCornerShape(4.dp)))
                    Text("LIVE", style = inter(14.sp, FontWeight.Bold), color = Color.White)
                } else if (reminded) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(16.dp)).padding(6.dp)) {
                    Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
                }
                if (airing) LiveProgress(p.progressAt(now), Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 5.dp, track = Color.Transparent)
            }
        }
        Text(p.title, style = inter(20.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.episodeLine() ?: p.categories.firstOrNull().orEmpty(), style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hit.whenLineTv(now, format, reminded), style = inter(17.sp), color = when { airing -> Color(0xFFFCA5A5); reminded -> WmmColors.Accent; else -> WmmColors.TextMuted }, maxLines = 1)
    }
}
