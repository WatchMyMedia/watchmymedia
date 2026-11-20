package media.watchmy.client.mobile.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.markSeasonButtonLabel
import media.watchmy.client.model.seasonCountLine
import media.watchmy.client.model.nextToAir
import media.watchmy.client.model.seasonWatchLine
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.requests.MissingFrame
import media.watchmy.client.mobile.requests.OffPill
import media.watchmy.client.mobile.requests.tone
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.partway
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WatchedCheck

/** A still's size in the row: the phone board's 148 by 83, the tablet's 188 by 106. */
internal val PhoneStill = 148.dp
internal val TabletStill = 188.dp

/**
 * The season chips (Specials last; a plain heading for a one-season show),
 * what the season has and Mark season watched, Show missing, the season's
 * missing actions, then its episodes in a row with the one on top outlined.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonsSection(
    show: ShowDetail,
    state: ShowPageViewModel.State,
    vm: ShowPageActions,
    downloads: DownloadsViewModel.State,
    page: PageActions,
    still: Dp,
    modifier: Modifier = Modifier,
) {
    val library = LocalAppServices.current.library
    val number = state.seasonNumber ?: return
    val summary = state.seasonSummary
    val season = state.season
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.oneSeason) {
            Text(
                seasonName(number),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text),
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
        } else {
            val index = state.seasons.indexOfFirst { it.number == number }
            val chips = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
            LazyRow(Modifier.semantics { contentDescription = "Seasons" }, state = chips, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.seasons, key = { it.number }) { s -> SeasonChip(s, s.number == number, state.showMissing) { vm.selectSeason(s.number) } }
            }
        }
        if (summary != null) {
            // What the season has and Mark season watched, then Show missing; wrapping on a narrow phone.
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.showMissing) seasonCountLine(summary.episodeCount, summary.availableCount) else seasonWatchLine(summary.availableCount, summary.watchedCount, state.season?.nextToAir()),
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
                if (state.canMarkSeason) GoldTextButton(markSeasonButtonLabel(state.seasonWatched), { vm.setSeasonWatched(!state.seasonWatched) }, enabled = !state.acting)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (state.canShowMissing) MissingSwitch(state.showMissing, vm::setShowMissing)
                    if (downloads.enabled && season != null && !state.offline) SeasonDownloadMenu(summary, season, downloads, page.onDownloadSeason)
                }
            }
        }
        state.seasonActions?.takeIf { it.any }?.let { actions ->
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actions.request) TonalButton(requestSeasonLabel(number), { page.onAsk(number) }, compact = true)
                if (actions.requested) OffPill("Requested")
                if (actions.search) TonalButton("Search now", vm::searchSeason, compact = true, busy = state.acting)
                if (actions.monitor) TonalButton("Monitor", vm::monitorSeason, compact = true, enabled = !state.acting)
            }
        }
        if (season != null) EpisodeRow(show, state, vm, library, still)
    }
}

/** The season's episodes; the one on top outlined in gold and kept in view. */
@Composable
private fun EpisodeRow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, library: LibraryRepository, still: Dp) {
    val episodes = state.episodes
    val index = episodes.indexOfFirst { it.id == state.episodeId }
    val row = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    LaunchedEffect(state.episodeId, state.season?.season?.number) {
        if (index < 0) return@LaunchedEffect
        val seen = row.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val whole = seen != null && seen.offset >= row.layoutInfo.viewportStartOffset && seen.offset + seen.size <= row.layoutInfo.viewportEndOffset
        if (!whole) row.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(Modifier.padding(top = 4.dp), state = row, contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(episodes, key = { it.id }) { e ->
            SeasonCard(e, library.imageUrl(e.stillPath, ImageSize.W300), "${show.title} ${e.season}x${e.number}", e.id == state.episodeId, state.missingFor(e), still) { vm.selectEpisode(e.id) }
        }
    }
}

/** Show missing, a switch with its label. */
@Composable
private fun MissingSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WmmColors.Ground,
                checkedTrackColor = WmmColors.Accent,
                checkedBorderColor = WmmColors.Accent,
                uncheckedThumbColor = WmmColors.TextFaint,
                uncheckedTrackColor = WmmColors.Line2,
                uncheckedBorderColor = WmmColors.TextFaint,
            ),
        )
        Text("Show missing", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.Text))
    }
}

@Composable
private fun SeasonCard(ep: EpisodeDetail, url: String?, seed: String, current: Boolean, missing: MissingEpisode?, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val still = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(width + 12.dp).clip(shape)
            .then(if (current) Modifier.background(WmmColors.Surface2, shape).border(2.dp, WmmColors.Accent, shape) else Modifier)
            .clickable(onClickLabel = "Put it on top", onClick = onClick)
            .semantics { selected = current }
            .padding(6.dp),
    ) {
        val frame = Modifier.size(width, width * 9 / 16)
        if (missing != null) {
            // As the show page draws a missing episode: its state's icon in a dashed frame.
            MissingFrame(missing.kind, frame, radius = 8.dp)
        } else if (!ep.aired || !ep.hasFile) {
            Box(
                frame.background(WmmColors.Surface, still).drawBehind {
                    drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (ep.aired) "Not in library" else "Not aired yet",
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = if (ep.aired) WmmColors.Warn else WmmColors.TextMuted),
                )
            }
        } else {
            Artwork(url, seed = seed, contentDescription = null, modifier = frame, shape = still) {
                Box(Modifier.fillMaxSize()) {
                    if (ep.watched) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(5.dp), size = 18.dp)
                    if (ep.isNew && !ep.watched) Badge("New", Modifier.align(Alignment.TopStart).padding(5.dp), style = BadgeStyle.Gold, fontSize = 10.sp, horizontalPadding = 5.dp, verticalPadding = 2.dp)
                    ep.partway?.let { ProgressStrip(it.positionMs.toFloat() / it.durationMs, Modifier.align(Alignment.BottomStart)) }
                }
            }
        }
        Text(
            "${ep.number}. ${ep.title}",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            when {
                // What a missing one waits for, shortly: "Missing", "Downloading 45%", "Requested".
                missing != null -> missing.label.substringBefore(" · ")
                ep.aired -> ep.runtimeMin?.let { "$it min" }.orEmpty()
                else -> episodeMeta(ep)
            },
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = missing?.kind?.tone() ?: WmmColors.TextMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
