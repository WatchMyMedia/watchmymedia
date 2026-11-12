package media.watchmy.client.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.LIVE_SEARCH_NONE_TITLE
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveDevice
import media.watchmy.client.model.LiveSearchHit
import media.watchmy.client.model.episodeLine
import media.watchmy.client.model.liveSearchNone
import media.watchmy.client.model.liveSearchNote
import media.watchmy.client.model.liveSearchNoteShort
import media.watchmy.client.model.remindLabel
import media.watchmy.client.model.reminderSetLabel
import media.watchmy.client.model.spokenName
import media.watchmy.client.model.timeLine
import media.watchmy.client.model.whenLine
import media.watchmy.client.presentation.live.LiveSearchViewModel
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.ProgrammePicture
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * LiveSearch on the phone: Search listings from the Live TV top bar, What is
 * on now with Watch, and later with Remind me. Choosing one opens the guide
 * at it ([onOpen]).
 */
@Composable
fun LiveSearchScreen(onBack: () -> Unit, onWatch: (LiveChannel) -> Unit, onOpen: (LiveSearchHit) -> Unit, modifier: Modifier = Modifier, castDevice: String? = null) {
    val services = LocalAppServices.current
    val model = viewModel { LiveSearchViewModel(createSavedStateHandle(), services.live, services.servers) }
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val now = rememberLiveNow()
    val ask = rememberReminderAsk()
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.query.isEmpty()) runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(WmmIcons.Back, contentDescription = "Back to the guide", tint = WmmColors.Text) }
            SearchField(state.query, model::search, model::clear, Modifier.weight(1f).focusRequester(focus))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            searchResults(state, now, format, onWatch, onOpen, onToggle = { hit ->
                if (state.reminderFor(hit) == null) ask.after(context, hit.channel, hit.programme)
                model.toggleReminder(hit)
            }, onBack = onBack, wide = false, castDevice = castDevice)
        }
    }
    ReminderAskDialog(ask, state.leadMinutes, LiveDevice.Phone)
}

/** The tablet's Search listings panel beside the guide (TabLiveExtras). */
@Composable
internal fun LiveSearchPanel(onClose: () -> Unit, onWatch: (LiveChannel) -> Unit, onOpen: (LiveSearchHit) -> Unit, modifier: Modifier = Modifier, castDevice: String? = null) {
    val services = LocalAppServices.current
    val model = viewModel(key = "live-search-panel") { LiveSearchViewModel(createSavedStateHandle(), services.live, services.servers) }
    val state by model.state.collectAsStateWithLifecycle()
    val format = rememberLiveClockFormat()
    val now = rememberLiveNow()
    val ask = rememberReminderAsk()
    val context = LocalContext.current
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search listings", color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onClose) { Icon(WmmIcons.Close, contentDescription = "Close search", tint = WmmColors.Text) }
        }
        SearchField(state.query, model::search, model::clear, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            searchResults(state, now, format, onWatch, onOpen, onToggle = { hit ->
                if (state.reminderFor(hit) == null) ask.after(context, hit.channel, hit.programme)
                model.toggleReminder(hit)
            }, onBack = onClose, wide = true, castDevice = castDevice)
        }
    }
    ReminderAskDialog(ask, state.leadMinutes, LiveDevice.Tablet)
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search listings") },
        leadingIcon = { Icon(WmmIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = onClear) { Icon(WmmIcons.Close, contentDescription = "Clear") } }
        } else null,
        singleLine = true,
        modifier = modifier.semantics { contentDescription = "Search listings" },
    )
}

private fun LazyListScope.searchResults(
    state: LiveSearchViewModel.State,
    now: Long,
    format: LiveClockFormat,
    onWatch: (LiveChannel) -> Unit,
    onOpen: (LiveSearchHit) -> Unit,
    onToggle: (LiveSearchHit) -> Unit,
    onBack: () -> Unit,
    wide: Boolean,
    castDevice: String?,
) {
    when {
        state.loading && state.searched == null -> item("loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = WmmColors.Accent, strokeWidth = 2.dp) }
        }
        state.error != null -> item("error") { Text(state.error!!, color = WmmColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
        state.nothing -> item("none") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(WmmIcons.Search, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(40.dp))
                Text(LIVE_SEARCH_NONE_TITLE, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, textAlign = TextAlign.Center)
                Text(liveSearchNone(state.searched.orEmpty(), state.listedThroughMs, format), color = WmmColors.TextMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                TonalButton("Back to the guide", onBack)
            }
        }
        state.searched != null -> {
            val onNow = state.onNowAt(now)
            val later = state.laterAt(now)
            item("now-head") { GroupHeader("On now", onNow.size, wide) }
            items(onNow, key = { "now-" + it.programme.id }) { hit -> ResultRow(hit, state, now, format, onWatch, onOpen, onToggle, wide, castDevice = castDevice) }
            item("later-head") { GroupHeader("Later", later.size, wide) }
            items(later, key = { "later-" + it.programme.id }) { hit -> ResultRow(hit, state, now, format, onWatch, onOpen, onToggle, wide, castDevice = castDevice) }
            item("note") {
                Text(
                    if (wide) liveSearchNoteShort(state.listedThroughMs, format) else liveSearchNote(state.listedThroughMs, format),
                    color = WmmColors.TextFaint, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** "On now 1" on the phone, "On now · 1" on the tablet. */
@Composable
private fun GroupHeader(label: String, count: Int, wide: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (wide) Text("$label · $count", color = WmmColors.TextMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        else {
            Text(label, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("$count", color = WmmColors.TextMuted, fontSize = 13.sp)
        }
    }
}

/** A result: the picture with the channel over it, what and when, then Watch, Remind me or Reminder set. */
@Composable
private fun ResultRow(
    hit: LiveSearchHit,
    state: LiveSearchViewModel.State,
    now: Long,
    format: LiveClockFormat,
    onWatch: (LiveChannel) -> Unit,
    onOpen: (LiveSearchHit) -> Unit,
    onToggle: (LiveSearchHit) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    castDevice: String? = null,
) {
    val p = hit.programme
    val airing = p.airingAt(now)
    val pictureWidth: Dp = if (wide) 104.dp else 128.dp
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(hit) }
                .semantics(mergeDescendants = true) { contentDescription = hit.spokenName(now, format, if (wide) "show in the guide" else "open in the guide") },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(pictureWidth).clip(RoundedCornerShape(6.dp))) {
                ProgrammePicture(p, hit.channel, Modifier.fillMaxWidth())
                ChannelBadge(hit.channel, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, color = WmmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!wide) ProgrammeTags(p, small = true)
                }
                p.episodeLine()?.let { Text(it, color = WmmColors.TextSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(hit.whenLine(now, format), color = if (airing) LiveRedText else WmmColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (!wide) Text(hit.timeLine(format), color = WmmColors.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.padding(start = pictureWidth + 12.dp)) {
            when {
                airing -> FilledButton(watchLabel(hit.channel, castDevice), { onWatch(hit.channel) }, Modifier.watchDescription(hit.channel, castDevice), icon = WmmIcons.Play, enabled = hit.channel.available)
                state.reminderFor(hit) != null -> ReminderSetButton(hit.reminderSetLabel(), { onToggle(hit) }, small = wide)
                state.canRemind(hit) -> TonalButton("Remind me", { onToggle(hit) }, icon = WmmIcons.Bell, compact = true, modifier = Modifier.semantics { contentDescription = hit.remindLabel(now, format) })
            }
        }
    }
}
