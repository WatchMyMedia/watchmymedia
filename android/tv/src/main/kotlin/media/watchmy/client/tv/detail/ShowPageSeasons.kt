package media.watchmy.client.tv.detail

import media.watchmy.client.model.cardLine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.SeasonMissing
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.markSeasonButtonLabel
import media.watchmy.client.model.partway
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.seasonCountLine
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ArtBadge
import media.watchmy.client.tv.components.ArtProgress
import media.watchmy.client.tv.components.CardGap
import media.watchmy.client.tv.components.CardTitleGap
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.FocusedArt
import media.watchmy.client.tv.components.KeylineSpec
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.WideCardHeight
import media.watchmy.client.tv.components.WideCardWidth
import media.watchmy.client.tv.components.fadePassedCards
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.rememberRowStart
import media.watchmy.client.tv.components.rowStart
import media.watchmy.client.tv.components.timeLeft
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons

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
    // With missing episodes shown, the season's count says what is missing; the tabs say what is left to watch.
    val summary = seasonSummary?.takeIf { showMissing }?.let { seasonCountLine(it.episodeCount, it.availableCount) }
    return SeasonBar(number, seasons, oneSeason, showMissing, summary, canMarkSeason, seasonWatched, acting, seasonActions)
}

/**
 * The season tabs (Specials last): the season showing is underlined in gold,
 * a season watched to the end has a gold tick, and one under way says how
 * many are left ("Season 3 · 4 left"). Focus on a tab shows its season at
 * once; OK goes down to its episodes. Mark season watched sits at the row's
 * end, with Request, Search now and Monitor while missing episodes show.
 * ▲ goes back to the button last focused above; ▼ to the episode on top.
 */
@Composable
internal fun SeasonTabs(
    bar: SeasonBar,
    vm: ShowPageActions,
    memory: FocusMemory,
    lastButton: () -> String,
    episodeFocus: () -> FocusRequester,
    onAsk: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val number = bar.number
    val nav = remember(memory, episodeFocus) { Modifier.focusProperties { this.down = episodeFocus() } }
    Row(modifier.upTo(memory) { listOf(lastButton(), "play") }.fillMaxWidth().padding(start = ContentStart - 10.dp, end = ContentEnd, top = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The tabs and the summary take what the buttons leave; a Spacer with a weight of its own beside the tabs'
        // weight split that space in two and cut the tabs off at half ("Season 3 · 4 le").
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        if (bar.oneSeason) {
            Text(seasonName(number), style = TvType.CardTitle, color = WmmColors.Text, modifier = Modifier.padding(start = 10.dp, end = 8.dp))
        } else {
            val index = bar.seasons.indexOfFirst { it.number == number }
            val tabs = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
            LazyRow(
                Modifier.weight(1f, fill = false).focusRestorer(memory.requester("tab:$number")),
                state = tabs,
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(bar.seasons, key = { it.number }) { s ->
                    SeasonTab(
                        s,
                        on = s.number == number,
                        missing = bar.showMissing,
                        modifier = nav.focusMemory(memory, "tab:${s.number}").onFocusChanged { if (it.isFocused) vm.selectSeason(s.number) },
                    ) { runCatching { episodeFocus().requestFocus() } }
                }
            }
        }
        if (bar.summary != null) Text(bar.summary, style = TvType.Second, color = TvPalette.Muted, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
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

/** "4 left": a season's episodes still to watch, while it is under way or showing; null otherwise. */
internal fun seasonLeft(s: SeasonSummary, on: Boolean): String? {
    val left = (s.availableCount - s.watchedCount).coerceAtLeast(0)
    return when {
        left == 0 || s.availableCount == 0 -> null
        on || s.watchedCount > 0 -> "$left left"
        else -> null
    }
}

/** A season watched to the end: every episode here watched. */
internal fun SeasonSummary.finished(): Boolean = availableCount > 0 && watchedCount >= availableCount

@Composable
private fun SeasonTab(s: SeasonSummary, on: Boolean, missing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = on
            role = Role.Tab
        },
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.BUTTON,
    ) { focused ->
        Row(
            Modifier
                .heightIn(min = 64.dp)
                .background(if (focused) TvPalette.Lift else Color.Transparent, shape)
                .padding(horizontal = 26.dp)
                .drawBehind {
                    // The season showing: a gold underline as wide as its words, never a fill or a ring.
                    if (on) {
                        val h = 4.dp.toPx()
                        drawRoundRect(WmmColors.Accent, topLeft = Offset(0f, size.height - 6.dp.toPx() - h), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (s.finished()) Icon(WmmIcons.Check, contentDescription = "Watched", tint = WmmColors.Accent, modifier = Modifier.size(26.dp))
            val fg = if (on || focused) WmmColors.Text else TvPalette.Muted
            val count = when {
                missing && s.availableCount < s.episodeCount -> if (s.availableCount == 0) "not in library" else "${s.availableCount} of ${s.episodeCount}"
                else -> seasonLeft(s, on)
            }
            Text(listOfNotNull(seasonName(s.number), count).joinToString(" · "), style = TvType.BodyStrong, color = fg, maxLines = 1)
        }
    }
}

/**
 * The season's episodes as wide cards. Focus on a card puts its episode on
 * top; OK ([onOpen]) goes back up to its Play and never plays. While focus
 * is elsewhere a gold bar under a card marks the episode on top. ▲ goes to
 * the season's tab, ▼ to About the show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeRow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, library: LibraryRepository, memory: FocusMemory, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // As Home's rows: the episode before the focused one stays in view, and a run of ◀ stops at the first.
    val spec = remember(density) { with(density) { KeylineSpec(leading = 12.dp.toPx(), gap = CardGap.toPx(), trailing = (ContentEnd + 12.dp).toPx()) } }
    val rowStart = rememberRowStart()
    val episodes = state.episodes
    val index = episodes.indexOfFirst { it.id == state.episodeId }
    val row = rememberLazyListState(initialFirstVisibleItemIndex = (index - 1).coerceAtLeast(0))
    var rowFocused by remember { mutableStateOf(false) }
    // A new season starts at its start: the row scrolls there without waiting for focus.
    LaunchedEffect(state.season?.season?.number) {
        val i = episodes.indexOfFirst { it.id == state.episodeId }
        if (i >= 0 && row.layoutInfo.visibleItemsInfo.none { it.index == i }) row.scrollToItem((i - 1).coerceAtLeast(0))
    }
    val up = memory.requester(seasonKey(state))
    val nav = remember(up) { Modifier.focusProperties { this.up = up } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            modifier
                .fillMaxWidth()
                .padding(start = ContentStart - 12.dp, top = 14.dp)
                .onFocusChanged { rowFocused = it.hasFocus }
                .fadePassedCards(row)
                .rowStart(rowStart)
                .focusRestorer(memory.requester("ep:${state.episodeId}")),
            state = row,
            contentPadding = PaddingValues(start = 12.dp, end = ContentEnd, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
        ) {
            items(episodes, key = { it.id }) { e ->
                // Kept across moves along the row, so only the cards whose state changes draw again.
                val card = remember(e.id, up, memory, vm) { nav.focusMemory(memory, "ep:${e.id}").onFocusChanged { if (it.isFocused) vm.selectEpisode(e.id) } }
                SeasonCard(
                    e,
                    library.imageUrl(e.stillPath, ImageSize.W780),
                    "${show.title} ${e.season}x${e.number}",
                    onTop = e.id == state.episodeId && !rowFocused,
                    missing = state.missingFor(e),
                    modifier = card,
                    onClick = onOpen,
                )
            }
        }
    }
}

@Composable
private fun SeasonCard(ep: EpisodeDetail, url: String?, seed: String, onTop: Boolean, missing: MissingEpisode?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    FocusableBox(onClick = onClick, modifier = modifier.width(WideCardWidth), cornerRadius = 14.dp, ring = false) { focused ->
        Column {
            FocusedArt(focused) {
                Box(Modifier.size(WideCardWidth, WideCardHeight)) {
                    when {
                        missing != null -> MissingStill(missing, radius = 14.dp, iconSize = 48.dp, modifier = Modifier.fillMaxSize())
                        !ep.aired || !ep.hasFile -> Box(
                            Modifier.fillMaxSize().background(WmmColors.Surface, shape).drawBehind {
                                drawRoundRect(WmmColors.Line3, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (ep.aired) "Not in library" else "Not aired yet", style = TvType.Second.copy(fontWeight = FontWeight.SemiBold), color = if (ep.aired) WmmColors.Warn else TvPalette.Muted)
                        }
                        else -> Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.fillMaxSize(), shape = shape) {
                            Box(Modifier.fillMaxSize()) {
                                if (ep.watched) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(10.dp), size = 38.dp)
                                ArtProgress(ep.partway?.let { it.positionMs.toFloat() / it.durationMs })
                            }
                        }
                    }
                    if (ep.isNew && !ep.watched) ArtBadge("New", Modifier.align(Alignment.TopStart).padding(10.dp), BadgeStyle.Gold)
                    // Only another server has it so far: it plays from there.
                    ep.where.cardLine()?.let { ArtBadge(it, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 16.dp)) }
                }
            }
            // The episode on top, while focus is elsewhere: a gold bar, never a ring, so it can't be taken for focus.
            if (onTop) {
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).background(WmmColors.Accent, CircleShape))
            }
            val gap = when {
                focused -> CardTitleGap + WideCardHeight * ((FocusGrowth.CARD - 1f) / 2f)
                onTop -> 8.dp
                else -> CardTitleGap
            }
            Text(
                "${ep.number} · ${ep.title}",
                style = TvType.Body.copy(fontWeight = FontWeight.SemiBold),
                color = if (ep.aired) WmmColors.Text else TvPalette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = gap),
            )
            Text(cardLine(ep, missing), style = TvType.Caption, color = TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Under a card: "52 min · 29 min left", "52 min", "Airs Thursday", or when a missing one is waiting. */
private fun cardLine(ep: EpisodeDetail, missing: MissingEpisode?): String = when {
    missing != null -> missing.label.substringBefore(" · ")
    !ep.aired -> episodeMeta(ep)
    ep.partway != null -> listOfNotNull(ep.runtimeMin?.let { "$it min" }, ep.partway!!.let { timeLeft(it.durationMs - it.positionMs) }).joinToString(" · ")
    else -> ep.runtimeMin?.let { "$it min" } ?: episodeMeta(ep)
}
