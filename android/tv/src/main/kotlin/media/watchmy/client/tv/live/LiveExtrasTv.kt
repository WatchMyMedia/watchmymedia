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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.bringWholeIntoView
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
import androidx.compose.ui.semantics.focused
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
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ChannelLogo
import media.watchmy.client.ui.components.LiveProgress
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/*
 * Favourites, search and reminders on Android TV: Hold OK menus,
 * the Reminders list, the On Live TV row and the in-app reminder banner.
 * Android TV posts no system notification for a reminder.
 */

/** One row of a Hold OK menu. */
@Immutable
internal class TvMenuItem(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * A thing's choices (a recording's, a search card's): a panel from the
 * right with what it is for, its rows (the first focused) and a note. Back
 * closes it and focus goes back to what opened it. [first] picks the row
 * focus opens on: the one that changes nothing, where a row can't be undone.
 */
@Composable
internal fun TvLiveMenu(title: String, subtitle: String, logo: LiveChannel?, items: List<TvMenuItem>, onDismiss: () -> Unit, note: String? = null, first: Int = 0) {
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss) {
        TvSidePanel(title = null, modifier = Modifier.focusGroup()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (logo?.logo != null) ChannelLogo(logo, Modifier.width(64.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = TvType.Heading, color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = TvType.Second, color = TvPalette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEachIndexed { index, item ->
                    PanelRow(item.label, item.onClick, if (index == first.coerceIn(0, items.lastIndex)) Modifier.focusRequester(focus) else Modifier, icon = item.icon, enabled = item.enabled)
                }
            }
            note?.let { Text(it, style = TvType.Second, color = TvPalette.Muted) }
        }
        InitialFocus(focus)
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
    val tab = remember { FocusRequester() }
    val recorder by rememberTvRecordingActions().state.collectAsStateWithLifecycle()
    TvScaffold(RailSection.LiveTV, onNavigate, modifier) {
        Column(Modifier.fillMaxSize().padding(start = ContentStart, top = SafeY)) {
            LiveTabsHeader(LiveTab.Reminders, liveTabs(recordings = recorder.canRecord, reminders = true), onNavigate, first = tab, onShowing = { first.tryFocus() })
            Column(Modifier.weight(1f).padding(top = 28.dp, end = TvSafeEnd)) {
                Text(liveRemindersCountTv(state.items.size, state.leadMinutes), style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.padding(bottom = 24.dp))
                if (state.items.isEmpty() && !state.loading) Text(LIVE_REMINDERS_EMPTY_TV, style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 900.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    items(state.items, key = { it.id }) { reminder ->
                        TvReminderRow(
                            reminder, channels.firstOrNull { it.id == reminder.channelId } ?: LiveChannel(reminder.channelId.orEmpty(), "", reminder.number, reminder.channelName.orEmpty()),
                            now, format, onRemove = { model.remove(reminder) },
                            modifier = if (reminder == state.items.first()) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                Text(LIVE_REMINDERS_NOTE_TV, style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.padding(top = 12.dp, bottom = SafeY).widthIn(max = 1200.dp))
            }
        }
        // With nothing to list, the tab keeps focus, so the remote always has somewhere to be.
        if (state.items.isNotEmpty()) InitialFocus(first) else if (!state.loading) InitialFocus(tab)
    }
}

@Composable
private fun TvReminderRow(reminder: LiveReminder, channel: LiveChannel, now: Long, format: LiveClockFormat, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val gone = reminder.state == LiveReminderState.Gone
    val moved = reminder.state == LiveReminderState.Moved
    Row(Modifier.fillMaxWidth().background(WmmColors.Surface, RoundedCornerShape(18.dp)).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(240.dp).clip(RoundedCornerShape(12.dp))) {
            ProgrammePicture(null, channel, Modifier.fillMaxWidth())
            PictureFlag(channel.number, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(reminder.title, style = TvType.CardTitle, color = if (gone) TvPalette.Muted else WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reminder.tvLine(), style = TvType.Second, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (gone) WmmIcons.BellOff else if (moved) WmmIcons.Moved else WmmIcons.Bell, contentDescription = null, tint = if (gone) TvPalette.Muted else if (moved) WmmColors.Accent else WmmColors.TextSoft, modifier = Modifier.size(26.dp))
                Text(reminder.tvStatus(now, format), style = TvType.Second.copy(fontWeight = FontWeight.SemiBold), color = if (gone) TvPalette.Muted else if (moved) WmmColors.Accent else WmmColors.Text)
            }
            Text(reminder.tvWhen(format), style = TvType.Caption, color = TvPalette.Muted)
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
                    Text(reminder.number, style = inter(22.sp, FontWeight.Bold), color = WmmColors.Text)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(20.dp))
                    Text(liveReminderHeadline(leadMinutes), style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Accent)
                }
                Text(reminder.title, style = inter(28.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(reminder.bannerLine(format), style = inter(22.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * The banner's OK, read before anything on screen does: a long press (its
 * first repeat) watches. A short OK, and every other key, goes to what has
 * focus; so does a long one when the banner has nothing for it ([holdsOk]
 * false). Back, which dismisses it, comes by `Modifier.onBack` on the same
 * element, so it works where the remote's Back is not a key.
 */
internal class BannerKeys(private val onWatch: () -> Unit, private val holdsOk: Boolean = true) {
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
            else -> false
        }
    }
}

/**
 * Search's On Live TV row (android-tv/LiveSearch): programmes on now and later
 * that match [query]. OK watches one that is on and opens a later one in the
 * guide; Hold OK opens Remind me and Watch.
 *
 * A card in focus brings the whole row into view, down to the line under the
 * cards that says what OK and Hold OK do. Left to the card's picture, the
 * page stopped scrolling with that line cut off at the foot of the screen.
 * The line is there only while one of the row's cards has focus (or its
 * menu is open): left in place, it went on naming a card and its keys after
 * focus had gone to another row or the keyboard.
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
    // The line under the cards comes with a card in focus, after the page has scrolled for it: ask again once the row has grown.
    val whole = remember { BringIntoViewRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    LaunchedEffect(hasFocus, height) { if (hasFocus) whole.bringIntoView() }
    Column(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(whole)
            .onSizeChanged { height = it.height }
            .onFocusChanged { hasFocus = it.hasFocus }
            .bringWholeIntoView(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Text("On Live TV", style = inter(28.sp, FontWeight.Bold), color = WmmColors.Text)
            if (state.results.isNotEmpty()) Text("${state.results.size}", style = inter(22.sp), color = WmmColors.TextMuted)
        }
        if (state.nothing) {
            Text(liveSearchNoneTv(state.searched.orEmpty(), state.listedThroughMs, format), style = inter(22.sp), color = WmmColors.TextMuted)
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
        focused?.takeIf { hasFocus || menu != null }?.let { hit ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hit.programme.title, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.TextSoft, maxLines = 1)
                Text("·", style = inter(22.sp), color = WmmColors.TextMuted)
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
    // The card reads as one thing; its focus state stays in the semantics it clears, so TalkBack (and the focus tests) see it.
    var isFocused by remember { mutableStateOf(false) }
    Column(modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusableBox(
            onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .clearAndSetSemantics {
                    contentDescription = if (airing) "Watch ${hit.channel.number} ${hit.channel.name}, ${p.title}, ${liveMinutesLeft(now, p.endMs)}" else hit.spokenName(now, format, "Opens it in the guide") + if (reminded) ", reminder set" else ""
                    focused = isFocused
                },
            cornerRadius = 12.dp,
        ) {
            Box(Modifier.fillMaxWidth().background(WmmColors.Surface2, RoundedCornerShape(12.dp))) {
                ProgrammePicture(p, hit.channel, Modifier.fillMaxWidth())
                Row(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hit.channel.logo != null) ChannelLogo(hit.channel, Modifier.width(34.dp))
                    Text(hit.channel.number, style = inter(22.sp, FontWeight.Bold), color = WmmColors.Text)
                }
                if (airing) PictureFlag("LIVE", Modifier.align(Alignment.TopEnd).padding(10.dp), rec = true)
                else if (reminded) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(16.dp)).padding(6.dp)) {
                    Icon(WmmIcons.BellFilled, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
                }
                if (airing) LiveProgress(p.progressAt(now), Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 5.dp, track = Color.Transparent)
            }
        }
        Text(p.title, style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.episodeLine() ?: p.categories.firstOrNull().orEmpty(), style = inter(22.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hit.whenLineTv(now, format, reminded), style = inter(22.sp), color = when { airing -> WmmColors.Text; reminded -> WmmColors.Accent; else -> WmmColors.TextMuted }, maxLines = 1)
    }
}
