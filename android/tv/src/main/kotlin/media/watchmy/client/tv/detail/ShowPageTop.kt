package media.watchmy.client.tv.detail

import media.watchmy.client.model.watchedLine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.WATCHED_IT_ALL_NOTE
import media.watchmy.client.model.airedLabel
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.episodePlayLabel
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.parseDate
import media.watchmy.client.model.partway
import media.watchmy.client.model.plural
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.watchToggleLabel
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.endsAt
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.overflowHeight
import media.watchmy.client.tv.components.rememberNow
import media.watchmy.client.tv.components.timeLeft
import media.watchmy.client.tv.components.wouldEndAt
import media.watchmy.client.tv.requests.TvDownloadBar
import media.watchmy.client.tv.requests.tvIcon
import media.watchmy.client.tv.requests.tvTone
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The episode on top, over the show's backdrop: the show's name and when its
 * next episode comes, the episode's code and title, its facts (runtime,
 * rating, when it aired, why it is on top), how far through it is and when
 * it would end, two lines about it, and its buttons. The file and its
 * tracks are in More › File details. Its height is fixed, so the tabs and
 * the row stay put as the episode on top changes with focus.
 */
@Composable
internal fun Top(
    show: ShowDetail,
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    library: LibraryRepository,
    memory: FocusMemory,
    onButtonFocused: (String) -> Unit,
    onPlay: (startMs: Long?) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
    onChooser: () -> Unit = {},
) {
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    val copies = state.copies
    // With several copies, the place is the latest on any of them.
    val further = copies?.latest?.takeIf { it.serverId != copies.all.firstOrNull()?.serverId && ep.where.serverId == null }
    val partway = further?.place ?: ep.partway
    val missing = state.missing
    Box(Modifier.fillMaxWidth().height(TopHeight).bringWholeIntoView()) {
        PageBackdrop(library.imageUrl(show.backdropPath, ImageSize.W1280), show.title, Modifier.fillMaxWidth().overflowHeight(1080.dp, reported = 0.dp), solidFrom = 0.6f)
        Column(Modifier.padding(start = ContentStart, end = ContentEnd, top = SafeY).widthIn(max = 1500.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(show.title, style = TvType.Heading, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline().weight(1f, fill = false))
                showLine(show)?.let { Text(it, style = TvType.Second, color = TvPalette.Muted, maxLines = 1, modifier = Modifier.alignByBaseline()) }
            }
            Text(
                "${episodeCode(ep.season, ep.number)} · ${ep.title.ifBlank { "Episode ${ep.number}" }}",
                style = TvType.Title,
                color = WmmColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            FactsLine(episodeFacts(show, ep, state.reason), Modifier.padding(top = 14.dp))
            // Where the episode is, when it matters: only another server has it, or others too.
            val where = when {
                ep.where.serverName != null -> "On ${media.watchmy.client.model.shortServerName(ep.where.serverName!!)}"
                state.hereName != null -> listOfNotNull("On ${media.watchmy.client.model.serverHeading(state.hereName!!)}", "away from home".takeIf { state.hereAway }).joinToString(" · ")
                copies != null && copies.several -> "Also on " + media.watchmy.client.model.serverList(copies.all.drop(1).map { it.serverName }) + (if (further != null) " · you got further there" else "")
                else -> null
            }
            if (where != null) Text(where, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            if (partway != null) Meter(partway.positionMs.toFloat() / partway.durationMs, Modifier.padding(top = 20.dp), spoken = "${formatDuration(partway.positionMs)} of ${formatDuration(partway.durationMs)} watched")
            val line = Modifier.padding(top = 12.dp)
            val runtime = ep.runtimeMin?.takeIf { it > 0 }?.let { it * 60_000L }
            when {
                missing != null -> MissingLine(missing, line)
                state.reason == OnTopReason.WatchedItAll -> Text(WATCHED_IT_ALL_NOTE, style = TvType.Body, color = WmmColors.TextSoft, modifier = line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                !ep.aired -> WhenLine(airedLabel(ep.airDate) ?: "Not aired yet", null, line)
                !ep.hasFile -> WhenLine("Not in library", null, line)
                partway != null -> WhenLine(timeLeft(partway.durationMs - partway.positionMs), " · ${endsAt(partway.durationMs - partway.positionMs, now, format)}", line)
                ep.watched -> WhenLine(ep.watch?.watchedLine() ?: "Watched", runtime?.let { " · ${wouldEndAt(it, now, format)} if you watched it again" }, line)
                else -> WhenLine("Not watched yet", runtime?.let { " · ${wouldEndAt(it, now, format)} if you started now" }, line)
            }
            Overview(ep.overview, lines = 2, Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(28.dp))
            Buttons(ep, state, vm, missing, memory, onButtonFocused, onPlay, onMore, onAsk, onChooser)
        }
    }
}

/** What is missing about the episode on top, in its tone, with its download's progress. */
@Composable
private fun MissingLine(missing: MissingEpisode, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(missing.kind.tvIcon(), contentDescription = null, tint = missing.kind.tvTone(), modifier = Modifier.size(30.dp))
            Text(missing.label, style = TvType.BodyStrong, color = missing.kind.tvTone(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        missing.progress?.let { TvDownloadBar(it, width = 560.dp) }
    }
}

/** The episode's facts in a viewer's order: how long, the show's rating, when it aired, why it is on top, New. */
private fun episodeFacts(show: ShowDetail, ep: EpisodeDetail, reason: OnTopReason?): List<Fact> = buildList {
    ep.runtimeMin?.takeIf { it > 0 && ep.aired }?.let { add(Fact.Words("$it min", bold = true)) }
    show.contentRating?.takeIf { it.isNotBlank() }?.let { add(Fact.Tag(it)) }
    airedLabel(ep.airDate)?.takeIf { ep.aired }?.let { add(Fact.Words(it)) }
    when {
        ep.isNew && !ep.watched -> add(Fact.Tag("New", gold = true))
        reason != null && reason != OnTopReason.WatchedItAll -> add(Fact.Tag(reason.label, gold = true))
    }
}

/** "3 seasons · new episode Thursday": beside the show's name over the episode on top. */
internal fun showLine(show: ShowDetail, today: LocalDate = LocalDate.now()): String? {
    val seasons = show.seasons.count { it.number > 0 }
    return listOfNotNull(
        plural(seasons, "season").takeIf { seasons > 0 },
        nextEpisodeDay(show.nextAirDate, today)?.let { "new episode $it" },
    ).joinToString(" · ").ifEmpty { null }
}

/** When the next episode airs, as a person says it: "today", "tomorrow", "Thursday" within the week, else "Nov 26". */
internal fun nextEpisodeDay(iso: String?, today: LocalDate = LocalDate.now(), long: Boolean = false): String? {
    val date = parseDate(iso) ?: return null
    val days = ChronoUnit.DAYS.between(today, date)
    val monthDay = media.watchmy.client.model.monthDay(date)
    return when {
        days < 0 -> null
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days < 7 -> weekday(date.dayOfWeek) + if (long) ", $monthDay" else ""
        else -> monthDay
    }
}

private fun weekday(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/** One of the episode's buttons; the first is keyed "play", where focus starts. */
private class Action(val key: String, val label: String, val onClick: () -> Unit, val style: TvButtonStyle = TvButtonStyle.Ghost, val icon: ImageVector? = null, val enabled: Boolean = true)

/**
 * Resume or Play, From the start, Mark watched and More, or for a missing
 * episode what can be done about it. ▼ goes to the season tabs; ▲ stops.
 */
@Composable
private fun Buttons(
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    missing: MissingEpisode?,
    memory: FocusMemory,
    onButtonFocused: (String) -> Unit,
    onPlay: (startMs: Long?) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
    onChooser: () -> Unit,
) {
    val play = episodePlayLabel(ep)
    val copies = state.copies
    val actions = buildList {
        if (play != null) {
            val further = copies?.latest?.takeIf { it.serverId != copies.all.firstOrNull()?.serverId && ep.where.serverId == null }
            add(Action("play", (if (further != null) copies.resumeLabel(copies.all.firstOrNull()?.registeredId) else null) ?: play, { onPlay(null) }, TvButtonStyle.Primary, WmmIcons.Play))
            // While the show is on two servers, every episode has the button naming the copy Play uses, so the row doesn't move along the episodes.
            if (state.onSeveral) {
                val label = copies?.buttonLabel()
                if (label != null) add(Action("copy", label, onChooser, icon = WmmIcons.ChevronDown))
            }
            if (ep.partway != null) add(Action("start", "From the start", { onPlay(0) }))
        } else if (missing != null) {
            if (missing.request) add(Action("request", requestSeasonLabel(ep.season), { onAsk(ep.season) }, TvButtonStyle.Primary, WmmIcons.Plus))
            if (missing.search) add(Action("search", "Search now", vm::searchMissing, enabled = !state.acting))
            if (missing.monitor) add(Action("monitor", "Monitor", vm::monitor, enabled = !state.acting))
            missing.cancel?.let { r -> add(Action("cancel", "Cancel request", { vm.cancelRequest(r.id) }, enabled = !state.acting)) }
        }
        if (ep.aired) add(Action("watched", watchToggleLabel(ep.watched), vm::toggleWatched))
        if (state.hasMore || ep.versions.isNotEmpty()) add(Action("more", "More", onMore))
    }
    val down = memory.requester(seasonKey(state))
    val nav = Modifier.focusProperties {
        this.up = FocusRequester.Cancel
        this.down = down
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        actions.forEachIndexed { i, a ->
            // The first takes "play", where the page's focus starts, whatever it is.
            val key = if (i == 0) "play" else "action:${a.key}"
            val tag = if (i == 0 && play != null) Modifier.testTag(TestTags.PLAY) else Modifier
            TvButton(
                a.label,
                a.onClick,
                nav.focusMemory(memory, key).onFocused { onButtonFocused(key) }.then(tag),
                style = a.style,
                icon = a.icon,
                enabled = a.enabled,
                growFrom = if (i == 0) TransformOrigin(0f, 0.5f) else TransformOrigin.Center,
            )
        }
    }
}
