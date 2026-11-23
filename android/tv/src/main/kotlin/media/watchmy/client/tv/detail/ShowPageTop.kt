package media.watchmy.client.tv.detail

import androidx.compose.foundation.shape.CircleShape
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.WATCHED_IT_ALL_NOTE
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.airedLabel
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.episodePlayLabel
import media.watchmy.client.model.episodeTags
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.formatRemaining
import media.watchmy.client.model.partway
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.subtitleLanguages
import media.watchmy.client.model.watchToggleLabel
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.Backdrop
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.overflowHeight
import media.watchmy.client.tv.requests.TvDownloadBar
import media.watchmy.client.tv.requests.tvIcon
import media.watchmy.client.tv.requests.tvTone
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons

/**
 * The episode on top (`section`): the eyebrow and why it is there, its
 * title, facts, overview, file and progress, its buttons; the still on the
 * right, over the show's backdrop. Its height is fixed so moving along the
 * row redraws only this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Top(
    show: ShowDetail,
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    library: LibraryRepository,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
) {
    val version = ep.versions.firstOrNull()
    val partway = ep.partway
    val missing = state.missing
    Box(Modifier.fillMaxWidth().height(TopHeight).bringWholeIntoView()) {
        Backdrop(
            library.imageUrl(show.backdropPath, ImageSize.W1280),
            seed = show.title,
            modifier = Modifier.align(Alignment.TopEnd).width(1360.dp).overflowHeight(700.dp, reported = 0.dp),
            leftFade = listOf(0f to 1f, 0.24f to 0.85f, 0.62f to 0f),
            bottomFadeFrom = 0.55f,
            glowX = 0.7f,
            glowY = 0.3f,
        )
        // The same size of picture as the row's cards, so a still already in the row is reused.
        TopStill(ep, library.imageUrl(ep.stillPath, ImageSize.W780), "${show.title} ${ep.season}x${ep.number}", missing, Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 100.dp))
        Column(Modifier.padding(start = ContentStart, top = 72.dp).width(1080.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(episodeKicker(show.title, ep.season, ep.number).uppercase(), style = inter(22.sp, FontWeight.Bold, tracking = 0.14f), color = WmmColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                state.reason?.let { r ->
                    Text(
                        r.label,
                        style = inter(22.sp, FontWeight.SemiBold),
                        color = WmmColors.Accent,
                        modifier = Modifier.border(1.dp, WmmColors.Accent, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (ep.isNew && !ep.watched) {
                    Text(
                        "NEW",
                        style = inter(22.sp, FontWeight.Bold, tracking = 0.05f),
                        color = WmmColors.OnAccent,
                        modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(ep.title.ifBlank { "Episode ${ep.number}" }, style = inter(64.sp, FontWeight.ExtraBold, lineHeight = 80.sp, tracking = -0.025f), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, maxLines = 1) {
                val facts = listOfNotNull(
                    airedLabel(ep.airDate) ?: "Not aired yet".takeIf { !ep.aired },
                    ep.runtimeMin?.takeIf { ep.aired }?.let { "$it min" },
                    formatRating(ep.rating),
                )
                facts.forEachIndexed { i, f ->
                    if (i > 0) Text("·", style = inter(24.sp), color = WmmColors.TextFaint)
                    Text(f, style = inter(24.sp), color = TextBright)
                }
                if (ep.watched) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WatchedCheck(size = 28.dp)
                        Text("Watched", style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.Accent)
                    }
                }
            }
            if (missing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(missing.kind.tvIcon(), contentDescription = null, tint = missing.kind.tvTone(), modifier = Modifier.size(26.dp))
                    Text(missing.label, style = inter(22.sp, FontWeight.SemiBold), color = missing.kind.tvTone(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                missing.progress?.let { TvDownloadBar(it, width = 520.dp) }
            }
            if (ep.overview.isNotBlank()) {
                Text(ep.overview, style = inter(22.sp, lineHeight = 33.sp), color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 960.dp))
            }
            if (state.reason == OnTopReason.WatchedItAll) Text(WATCHED_IT_ALL_NOTE, style = inter(22.sp), color = WmmColors.TextSoft)
            if (version != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, maxLines = 1) {
                    episodeTags(version).forEach { TvTag(it) }
                    val rest = listOfNotNull(subtitleLanguages(version)?.let { "Subtitles: $it" }, formatBytes(version.sizeBytes)).joinToString(" · ")
                    Text(rest, style = inter(22.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
                version.recording?.let { Text(it.label(media.watchmy.client.ui.components.rememberLiveClockFormat()), style = inter(22.sp), color = WmmColors.TextMuted) }
            }
            if (partway != null && version != null) {
                Row(Modifier.width(520.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1f).height(6.dp).background(WmmColors.Line2, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(partway.positionMs.toFloat() / partway.durationMs).fillMaxHeight().background(WmmColors.Accent, RoundedCornerShape(3.dp)))
                    }
                    Text(formatRemaining(partway.durationMs - partway.positionMs), style = inter(22.sp), color = WmmColors.TextSoft)
                }
            }
            Buttons(ep, state, vm, missing, memory, onPlay, onMore, onAsk)
        }
    }
}

/** The episode on top's still (`520 × 292`), dashed when it isn't here or hasn't aired. */
@Composable
private fun TopStill(ep: EpisodeDetail, url: String?, seed: String, missing: MissingEpisode?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val frame = modifier.size(520.dp, 292.dp)
    when {
        missing != null -> MissingStill(missing, radius = 14.dp, iconSize = 56.dp, modifier = frame)
        !ep.aired || !ep.hasFile -> Box(
            frame.background(WmmColors.Surface, shape).drawBehind {
                drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ep.aired) "Not in library" else "Not aired yet", style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
        }
        else -> Artwork(url, seed = seed, contentDescription = null, modifier = frame.border(1.dp, WmmColors.Line2, shape), shape = shape) {
            Box(Modifier.fillMaxSize()) { ArtProgress(ep.partway?.let { it.positionMs.toFloat() / it.durationMs }) }
        }
    }
}

/** One of the page's buttons; the first is keyed "play", where focus starts. */
private class Action(val label: String, val onClick: () -> Unit, val style: TvButtonStyle = TvButtonStyle.Ghost, val icon: ImageVector? = null, val enabled: Boolean = true)

/**
 * Play (Resume from and From the start), Mark watched and More, or for a
 * missing episode what can be done about it. ▼ goes to the season tabs.
 */
@Composable
private fun Buttons(
    ep: EpisodeDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    missing: MissingEpisode?,
    memory: FocusMemory,
    onPlay: (PlayRequest) -> Unit,
    onMore: () -> Unit,
    onAsk: (Int) -> Unit,
) {
    val play = episodePlayLabel(ep)
    val actions = buildList {
        if (play != null) {
            add(Action(play, { vm.playRequest()?.let(onPlay) }, TvButtonStyle.Primary, WmmIcons.Play))
            if (ep.partway != null) add(Action("From the start", { vm.playRequest(startMs = 0)?.let(onPlay) }))
        } else if (missing != null) {
            if (missing.request) add(Action(requestSeasonLabel(ep.season), { onAsk(ep.season) }, TvButtonStyle.Primary, WmmIcons.Plus))
            if (missing.search) add(Action("Search now", vm::searchMissing, enabled = !state.acting))
            if (missing.monitor) add(Action("Monitor", vm::monitor, enabled = !state.acting))
            missing.cancel?.let { r -> add(Action("Cancel request", { vm.cancelRequest(r.id) }, enabled = !state.acting)) }
        }
        if (ep.aired) add(Action(watchToggleLabel(ep.watched), vm::toggleWatched, icon = if (ep.watched) WmmIcons.Ring else WmmIcons.CheckCircle))
    }
    val down = memory.requester(seasonKey(state))
    val nav = Modifier.focusProperties {
        this.up = FocusRequester.Cancel
        this.down = down
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (play == null && (missing == null || !missing.actions)) {
            // Nothing to play yet: when it airs, or that it isn't here.
            Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).background(WmmColors.TextFaint, CircleShape))
                Text(if (ep.aired) "Not in library" else airedLabel(ep.airDate) ?: "Not aired yet", style = inter(22.sp, FontWeight.SemiBold), color = WmmColors.TextMuted)
            }
        }
        actions.forEachIndexed { i, a ->
            val key = if (i == 0) "play" else "action:${a.label}"
            val tag = if (i == 0 && play != null) Modifier.testTag(TestTags.PLAY) else Modifier
            TvButton(a.label, a.onClick, nav.focusMemory(memory, key).then(tag), style = a.style, size = TvButtonSize.Large, icon = a.icon, enabled = a.enabled)
        }
        if (state.hasMore) MoreButton(onMore, nav.focusMemory(memory, if (actions.isEmpty()) "play" else "more"))
    }
}

/** The square ⋯ button (`.btn.ghost.sq`), [size] square: the episode's 64, the movie's 60 beside its buttons. */
@Composable
internal fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp, iconSize: Dp = 28.dp) {
    val shape = RoundedCornerShape(14.dp)
    FocusableBox(onClick = onClick, modifier = modifier.semantics { contentDescription = "More" }, cornerRadius = 14.dp, focusedScale = FocusGrowth.BUTTON) { focused ->
        Box(
            Modifier.size(size).background(Color.White.copy(alpha = if (focused) 0.16f else 0.10f), shape).border(1.dp, Color.White.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WmmIcons.MoreHoriz, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(iconSize))
        }
    }
}
