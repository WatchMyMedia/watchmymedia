package media.watchmy.client.tv.detail

import androidx.compose.ui.focus.onFocusChanged
import media.watchmy.client.model.markSeasonButtonLabel
import media.watchmy.client.model.seasonCountLine
import media.watchmy.client.model.nextToAir
import media.watchmy.client.model.seasonWatchLine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.SeasonMissing
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.formatRemaining
import media.watchmy.client.model.partway
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons
import java.util.Locale

/**
 * The season tabs (`.season`, Specials last), the chosen one in gold: focus
 * on a tab changes the season at once. A one-season show has a plain
 * heading. Then the season's summary and Mark season watched, and with Show
 * missing the season's Request, Search now and Monitor. ▲ goes to Play.
 */
/** What the season tabs draw: the same while focus moves along the episode row. */
@Immutable
internal data class SeasonBar(
    val number: Int,
    val seasons: List<SeasonSummary>,
    val oneSeason: Boolean,
    val showMissing: Boolean,
    val summary: String?,
    val canMark: Boolean,
    val watched: Boolean,
    val acting: Boolean,
    val actions: SeasonMissing?,
)

internal fun ShowPageViewModel.State.seasonBar(): SeasonBar? {
    val number = seasonNumber ?: return null
    val summary = seasonSummary?.let {
        if (showMissing) seasonCountLine(it.episodeCount, it.availableCount) else seasonWatchLine(it.availableCount, it.watchedCount, season?.nextToAir())
    }
    return SeasonBar(number, seasons, oneSeason, showMissing, summary, canMarkSeason, seasonWatched, acting, seasonActions)
}

@Composable
internal fun SeasonTabs(bar: SeasonBar, vm: ShowPageActions, memory: FocusMemory, episodeFocus: () -> FocusRequester, onAsk: (Int) -> Unit, modifier: Modifier = Modifier) {
    val number = bar.number
    val nav = remember(memory, episodeFocus) {
        Modifier.focusProperties {
            this.up = memory.requester("play")
            this.down = episodeFocus()
        }
    }
    Row(modifier.fillMaxWidth().padding(start = ContentStart - 6.dp, end = ContentEnd, top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bar.oneSeason) {
            Text(seasonName(number), style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text, modifier = Modifier.padding(start = 6.dp, end = 8.dp))
        } else {
            val index = bar.seasons.indexOfFirst { it.number == number }
            val tabs = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
            LazyRow(
                Modifier.weight(1f, fill = false).focusRestorer(memory.requester("tab:$number")),
                state = tabs,
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(bar.seasons, key = { it.number }) { s ->
                    SeasonTab(s, s.number == number, bar.showMissing, nav.focusMemory(memory, "tab:${s.number}").onFocusChanged { if (it.isFocused) vm.selectSeason(s.number) }) { vm.selectSeason(s.number) }
                }
            }
        }
        if (bar.summary != null) {
            Text(bar.summary, style = inter(18.sp), color = WmmColors.TextMuted, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (bar.canMark) {
            TvButton(markSeasonButtonLabel(bar.watched), { vm.setSeasonWatched(!bar.watched) }, nav.focusMemory(memory, "mark-season"), size = TvButtonSize.Small, enabled = !bar.acting)
        }
        bar.actions?.takeIf { it.any }?.let { a ->
            if (a.request) TvButton(requestSeasonLabel(number), { onAsk(number) }, nav.focusMemory(memory, "season-request"), size = TvButtonSize.Small, icon = WmmIcons.Plus)
            if (a.search) TvButton("Search now", vm::searchSeason, nav.focusMemory(memory, "season-search"), size = TvButtonSize.Small, enabled = !bar.acting)
            if (a.monitor) TvButton("Monitor", vm::monitorSeason, nav.focusMemory(memory, "season-monitor"), size = TvButtonSize.Small, enabled = !bar.acting)
        }
    }
}

@Composable
private fun SeasonTab(s: SeasonSummary, on: Boolean, missing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableBox(onClick = onClick, modifier = modifier, cornerRadius = 12.dp, focusedScale = 1.04f, ringColor = if (on) WmmColors.Text else WmmColors.Accent) { focused ->
        Row(
            Modifier
                .height(54.dp)
                .background(if (on) WmmColors.Accent else if (focused) WmmColors.Surface3 else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(seasonName(s.number), style = inter(21.sp, FontWeight.SemiBold), color = if (on) WmmColors.OnAccent else TextBright)
            val count = when {
                !missing || s.availableCount >= s.episodeCount -> "${s.availableCount}"
                s.availableCount == 0 -> "not in library"
                else -> "${s.availableCount} of ${s.episodeCount}"
            }
            Text(count, style = inter(21.sp, FontWeight.Medium), color = if (on) WmmColors.OnAccent else WmmColors.TextMuted)
        }
    }
}

/**
 * The season's episodes (`.card`), the one on top ringed in gold with On
 * top. Focus on a card puts its episode on top; OK ([onOpen]) goes back up
 * to its Play. ▲ goes to the chosen tab.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeRow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, library: LibraryRepository, memory: FocusMemory, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 12.dp.toPx(), trailing = (ContentEnd + 12.dp).toPx()) } }
    val episodes = state.episodes
    val index = episodes.indexOfFirst { it.id == state.episodeId }
    val row = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    // A new season starts at its start: the row scrolls there without waiting for focus.
    LaunchedEffect(state.season?.season?.number) {
        val i = episodes.indexOfFirst { it.id == state.episodeId }
        if (i >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == i }) row.scrollToItem((i - 1).coerceAtLeast(0))
    }
    val up = memory.requester(seasonKey(state))
    val nav = remember(up) { Modifier.focusProperties { this.up = up } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            modifier.fillMaxWidth().padding(start = ContentStart - 12.dp, top = 12.dp).focusRestorer(memory.requester("ep:${state.episodeId}")),
            state = row,
            contentPadding = PaddingValues(start = 12.dp, end = ContentEnd, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(episodes, key = { it.id }) { e ->
                // Kept across moves along the row, so only the cards whose outline changes draw again.
                val card = remember(e.id, up, memory, vm) { nav.focusMemory(memory, "ep:${e.id}").onFocusChanged { if (it.isFocused) vm.selectEpisode(e.id) } }
                SeasonCard(
                    e,
                    library.imageUrl(e.stillPath, ImageSize.W780),
                    "${show.title} ${e.season}x${e.number}",
                    e.id == state.episodeId,
                    state.missingFor(e),
                    card,
                    onOpen,
                )
            }
        }
    }
}

@Composable
private fun SeasonCard(ep: EpisodeDetail, url: String?, seed: String, onTop: Boolean, missing: MissingEpisode?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    FocusableBox(onClick = onClick, modifier = modifier.width(320.dp), cornerRadius = 12.dp, focusedScale = 1.06f) {
        Column {
            Box(Modifier.size(320.dp, 180.dp)) {
                if (missing != null) {
                    // As Show missing draws one: its state's icon in a dashed frame.
                    MissingStill(missing, radius = 12.dp, iconSize = 44.dp, modifier = Modifier.fillMaxSize())
                } else if (!ep.aired || !ep.hasFile) {
                    Box(
                        Modifier.fillMaxSize().background(WmmColors.Surface, shape).drawBehind {
                            drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (ep.aired) "Not in library" else "Not aired yet", style = inter(17.sp, FontWeight.SemiBold), color = if (ep.aired) WmmColors.Warn else WmmColors.TextMuted)
                    }
                } else {
                    Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.fillMaxSize(), shape = shape) {
                        Box(Modifier.fillMaxSize()) {
                            if (ep.watched) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(8.dp), size = 26.dp)
                            ArtProgress(ep.partway?.let { it.positionMs.toFloat() / it.durationMs })
                        }
                    }
                }
                ArtBadge(String.format(Locale.ROOT, "E%02d", ep.number), Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp))
                if (onTop) {
                    Box(Modifier.fillMaxSize().border(3.dp, WmmColors.Accent, shape))
                    ArtBadge("On top", Modifier.align(Alignment.TopStart).padding(10.dp), BadgeStyle.Gold)
                }
            }
            Text(ep.title, style = inter(20.sp, FontWeight.SemiBold), color = if (ep.aired) WmmColors.Text else WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(cardLine(ep, missing), style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Under a card: "29 min left", "Watched", "52 min", or when a missing one is waiting. */
private fun cardLine(ep: EpisodeDetail, missing: MissingEpisode?): String = when {
    missing != null -> missing.label.substringBefore(" · ")
    !ep.aired -> episodeMeta(ep)
    ep.partway != null -> ep.partway!!.let { formatRemaining(it.durationMs - it.positionMs) }
    ep.watched -> "Watched"
    else -> ep.runtimeMin?.let { "$it min" } ?: episodeMeta(ep)
}
