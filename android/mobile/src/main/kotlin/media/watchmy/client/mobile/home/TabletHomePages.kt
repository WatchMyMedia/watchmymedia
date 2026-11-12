package media.watchmy.client.mobile.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.components.groundFade
import media.watchmy.client.mobile.live.LiveHomeRow
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.mobile.rows.ArrangedRow
import media.watchmy.client.mobile.rows.RowActions
import media.watchmy.client.mobile.rows.rowEditorRoute
import media.watchmy.client.model.CardPanel
import media.watchmy.client.model.CardTarget
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.cardPanelAt
import media.watchmy.client.model.drawn
import media.watchmy.client.model.episodeCards
import media.watchmy.client.model.headingLine
import media.watchmy.client.model.heroBadges
import media.watchmy.client.model.pictureUrl
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.totalsLine
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.rememberLiveNow

/**
 * The tablet's arranged Home as screens (PLAN-HOME.md §7.5; TabHomeStart,
 * TabHomeRow and their portrait boards): page 0 is the hero beside (or, in
 * portrait and when the first row isn't Continue watching, over) the first
 * row; every later row fills a page with a panel of its leading card, the
 * one the row snapped to at its start, outlined in gold; the last page is
 * Edit Home, Back to the top and the totals. A vertical swipe pages, the
 * dots at the right say where, and the next row's name along the bottom
 * edge pages too. Phones keep the scrolling Home.
 */
@Composable
internal fun TabletPagedHome(
    home: HomeData,
    library: LibraryRepository,
    actions: RowActions,
    twoPane: Boolean,
    onNavigate: (Any) -> Unit,
    liveChannels: List<LiveChannel>,
    onWatchLive: (LiveChannel) -> Unit,
    liveListings: Boolean,
    modifier: Modifier = Modifier,
) {
    val rows = home.rows.orEmpty().drawn(live = liveChannels.isNotEmpty())
    val pages = remember(home, rows) { tabletPages(home.hero != null, rows) }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val live = LocalAppServices.current.live
    val available = liveChannels.filter { it.available }
    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    fun pageTo(target: Int) {
        if (target in pages.indices) scope.launch { pager.animateScrollToPage(target) }
    }
    fun nextName(index: Int): String = when (val next = pages.getOrNull(index + 1)) {
        is TabletPage.Row -> next.row.headingLine(liveListings)
        else -> "End of Home"
    }

    Box(modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { pages[it].key }) { index ->
            Column(Modifier.fillMaxSize()) {
                when (val page = pages[index]) {
                    is TabletPage.Start -> StartPage(home, page.row, library, actions, twoPane, rows, onNavigate, liveChannels, onWatchLive, liveListings, Modifier.weight(1f))
                    is TabletPage.Row -> RowPage(page.row, rows, library, live, home.tvLibraryId, actions, twoPane, onNavigate, liveChannels, onWatchLive, liveListings, available, now, format, Modifier.weight(1f))
                    TabletPage.End -> EndPage(home, onEdit = { onNavigate(rowEditorRoute(RowScreens.HOME, "Home", tablet = true)) }, onTop = { pageTo(0) }, Modifier.weight(1f))
                }
                val end = pages[index] == TabletPage.End
                val label = if (end) pages.getOrNull(index - 1)?.row?.headingLine(liveListings) ?: "Home" else nextName(index)
                PeekLine(label, up = end, onClick = { pageTo(if (end) index - 1 else index + 1) })
            }
        }
        PageDots(pages.size, pager.currentPage, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
    }
}

/** The tablet's Home screens: the hero and first row, a row each, the end. */
internal sealed interface TabletPage {
    val key: String
    val row: ScreenRow?

    data class Start(override val row: ScreenRow?) : TabletPage {
        override val key: String get() = "start"
    }

    data class Row(override val row: ScreenRow) : TabletPage {
        override val key: String get() = "row-${row.id}"
    }

    data object End : TabletPage {
        override val key: String get() = "end"
        override val row: ScreenRow? get() = null
    }
}

internal fun tabletPages(hero: Boolean, rows: List<ScreenRow>): List<TabletPage> = buildList {
    if (hero) add(TabletPage.Start(rows.firstOrNull()))
    rows.drop(if (hero) 1 else 0).forEach { add(TabletPage.Row(it)) }
    add(TabletPage.End)
}

/**
 * Screen 1: the hero and the first row. In two panes with Continue watching
 * first they sit side by side, the panel the hero's height with six items
 * (TabHomeStart); otherwise the hero is over the row (TabHomeStartPortrait),
 * at most 520 tall so the row keeps its place.
 */
@Composable
private fun StartPage(
    home: HomeData,
    row: ScreenRow?,
    library: LibraryRepository,
    actions: RowActions,
    twoPane: Boolean,
    rows: List<ScreenRow>,
    onNavigate: (Any) -> Unit,
    liveChannels: List<LiveChannel>,
    onWatchLive: (LiveChannel) -> Unit,
    liveListings: Boolean,
    modifier: Modifier = Modifier,
) {
    val hero = home.hero ?: return
    val counter = row?.let { "Row 1 of ${rows.size}" }
    val goTo = {
        when {
            hero.movieId != null -> actions.onOpenMovie(hero.movieId!!)
            hero.showId != null -> actions.onOpenShow(hero.showId!!)
        }
    }
    val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.episodeCards.firstOrNull { it.episodeId == id } }
    val heroCard: @Composable (Modifier, Dp?) -> Unit = { m, height ->
        HeroCard(
            modifier = m,
            height = height,
            hero = hero,
            badges = home.heroBadges,
            imageUrl = library.imageUrl(hero.backdropPath, ImageSize.W780),
            onPlay = hero.playRequest(home.episodeCards)?.let { r -> { actions.onPlay(r) } },
            onOpen = episode?.let { e -> { actions.onOpenEpisode(e.showId, e.season, e.episodeId) } } ?: goTo,
            openLabel = when {
                episode != null -> "Go to episode"
                hero.kind == Hero.Kind.Movie -> "Go to movie"
                else -> "Go to show"
            },
        )
    }
    val sideContinue = twoPane && row?.style == RowStyle.Continue
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (sideContinue) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                heroCard(Modifier.weight(1f).fillMaxHeight(), null)
                ContinuePanel(row!!.continueItems, library, actions.onSetWatched, actions.onPlay, actions.onOpenShow, actions.onOpenMovie, modifier = Modifier.width(364.dp).fillMaxHeight(), count = 6, counter = counter)
            }
        } else {
            val heroHeight = if (row == null) maxHeight - 8.dp else minOf(520.dp, maxHeight * 0.55f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                heroCard(Modifier.padding(horizontal = 24.dp).padding(end = 16.dp), heroHeight)
                if (row != null) {
                    if (row.style == RowStyle.Live) LiveHomeRow(liveChannels, onWatchLive, onSeeAll = { onNavigate(MobileRoutes.LiveTV) }, listings = liveListings)
                    else ArrangedRow(row, library, tablet = true, actions, onSeeAll = row.seeAll?.let { seeAll(it, home.tvLibraryId, onNavigate) }, counter = counter)
                }
            }
        }
    }
}

/**
 * A row screen: the leading card's panel over the row (TabHomeRow; the
 * portrait board puts a 440-tall panel over the row and leaves the rest).
 * The row snaps card by card; the card at its start after the snap is the
 * leading one, outlined, and the panel follows it.
 */
@Composable
private fun RowPage(
    row: ScreenRow,
    rows: List<ScreenRow>,
    library: LibraryRepository,
    live: LiveRepository,
    tvLibraryId: Long?,
    actions: RowActions,
    twoPane: Boolean,
    onNavigate: (Any) -> Unit,
    liveChannels: List<LiveChannel>,
    onWatchLive: (LiveChannel) -> Unit,
    liveListings: Boolean,
    available: List<LiveChannel>,
    now: Long,
    format: LiveClockFormat,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(listState, SnapPosition.Start)
    var lead by rememberSaveable(row.id) { mutableIntStateOf(0) }
    // The leading card once the snap settles: the visible card nearest the row's start.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.layoutInfo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset) }?.index }
            .filter { (scrolling, _) -> !scrolling }
            .distinctUntilChanged()
            .collect { (_, index) -> if (index != null) lead = index }
    }
    val panel = remember(row, lead, available, now) { row.cardPanelAt(lead, available, now, format) }
    val counter = "Row ${rows.indexOf(row) + 1} of ${rows.size}"
    PrefetchNeighbours(row, lead, available, library, live, now, format)
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val panelModifier = Modifier.padding(start = 24.dp, end = 40.dp).fillMaxWidth()
        if (panel != null) {
            LeadPanel(
                panel,
                imageUrl = panel.pictureUrl(library, live, ImageSize.W780),
                onPrimary = panel.primaryAction(row, lead, available, actions, onWatchLive, onNavigate),
                onSecondary = panel.secondaryAction(row, lead, available, actions, onNavigate),
                modifier = if (twoPane) panelModifier.weight(1f) else panelModifier.height(440.dp),
            )
        }
        if (row.style == RowStyle.Live) {
            LiveHomeRow(liveChannels, onWatchLive, onSeeAll = { onNavigate(MobileRoutes.LiveTV) }, listings = liveListings, state = listState, flingBehavior = fling, lead = lead, counter = counter)
        } else {
            ArrangedRow(row, library, tablet = true, actions, onSeeAll = row.seeAll?.let { seeAll(it, tvLibraryId, onNavigate) }, state = listState, flingBehavior = fling, lead = lead, counter = counter)
        }
        if (!twoPane) Spacer(Modifier.weight(1f))
    }
}

/** Where a row's See all goes: a list's page, or the library's tab. */
private fun seeAll(link: RowLink, tvLibraryId: Long?, onNavigate: (Any) -> Unit): () -> Unit = when (link) {
    is RowLink.ToList -> ({ onNavigate(MobileRoutes.MediaList(link.listId)) })
    is RowLink.Library -> ({ onNavigate(if (link.libraryId == tvLibraryId) MobileRoutes.TvShows else MobileRoutes.Movies) })
}

/** A panel button: its label and what it does. */
private data class PanelAction(val label: String, val onClick: () -> Unit)

/**
 * The panel's first button: what the card opens, or plays. A movie or show
 * card carries no file id, so its button opens the page the card would.
 */
private fun CardPanel.primaryAction(row: ScreenRow, index: Int, channels: List<LiveChannel>, actions: RowActions, onWatchLive: (LiveChannel) -> Unit, onNavigate: (Any) -> Unit): PanelAction? = when (target) {
    CardTarget.Film -> row.items.getOrNull(index)?.let { m -> PanelAction("Go to film") { actions.onOpenMovie(m.id) } }
    CardTarget.Show -> row.items.getOrNull(index)?.let { s -> PanelAction("Go to show") { actions.onOpenShow(s.id) } }
    CardTarget.Episode -> when (row.style) {
        RowStyle.Episode -> row.episodes.getOrNull(index)?.let { e ->
            val request = e.playRequest()
            if (request != null) PanelAction("Play") { actions.onPlay(request) } else PanelAction("Go to episode") { actions.onOpenEpisode(e.showId, e.season, e.episodeId) }
        }
        else -> row.items.getOrNull(index)?.let { s ->
            val episodeId = s.episodeId ?: return@let null
            PanelAction("Open episode") { actions.onOpenEpisode(s.id, s.episodeSeason ?: 1, episodeId) }
        }
    }
    CardTarget.Resume -> row.continueItems.getOrNull(index)?.let { c -> PanelAction("Resume") { actions.onPlay(c.playRequest()) } }
    CardTarget.Channel -> channels.getOrNull(index)?.let { ch -> PanelAction("Watch ${ch.number}") { onWatchLive(ch) } }
    CardTarget.Guide -> PanelAction("Full guide") { onNavigate(MobileRoutes.LiveTV) }
    CardTarget.List -> row.listId?.let { id -> PanelAction("See all") { onNavigate(MobileRoutes.MediaList(id)) } }
    CardTarget.File -> PanelAction("Open") { actions.onNotMatched() }
}

/** The panel's second button: the show or film behind an episode or a resumable title, or the guide behind a channel. */
private fun CardPanel.secondaryAction(row: ScreenRow, index: Int, channels: List<LiveChannel>, actions: RowActions, onNavigate: (Any) -> Unit): PanelAction? = when (target) {
    CardTarget.Episode -> when (row.style) {
        RowStyle.Episode -> row.episodes.getOrNull(index)?.let { e ->
            if (e.playRequest() != null) PanelAction("Go to episode") { actions.onOpenEpisode(e.showId, e.season, e.episodeId) } else PanelAction("Go to show") { actions.onOpenShow(e.showId) }
        }
        else -> row.items.getOrNull(index)?.let { s -> PanelAction("Go to show") { actions.onOpenShow(s.id) } }
    }
    CardTarget.Resume -> row.continueItems.getOrNull(index)?.let { c ->
        c.showId?.let { id -> PanelAction("Go to show") { actions.onOpenShow(id) } } ?: c.movieId?.let { id -> PanelAction("Go to film") { actions.onOpenMovie(id) } }
    }
    CardTarget.Channel -> if (channels.getOrNull(index) != null) PanelAction("Full guide") { onNavigate(MobileRoutes.LiveTV) } else null
    else -> null
}

/** Loads the pictures of the cards either side of [index], so snapping along the row does not flash the placeholder. */
@Composable
private fun PrefetchNeighbours(row: ScreenRow, index: Int, channels: List<LiveChannel>, library: LibraryRepository, live: LiveRepository, now: Long, format: LiveClockFormat) {
    val context = LocalContext.current
    LaunchedEffect(row.id, index) {
        val loader = SingletonImageLoader.get(context)
        listOf(index - 1, index + 1).filter { it >= 0 }.forEach { i ->
            val url = row.cardPanelAt(i, channels, now, format)?.pictureUrl(library, live, ImageSize.W780) ?: return@forEach
            loader.enqueue(ImageRequest.Builder(context).data(url).build())
        }
    }
}

/**
 * The leading card's panel (TabHomeRow `Leading title`): its picture faded
 * into the ground from the left and the bottom, over it the kicker, title,
 * facts, two-line overview and the two buttons. Tapping the picture does
 * what the first button does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeadPanel(panel: CardPanel, imageUrl: String?, onPrimary: PanelAction?, onSecondary: PanelAction?, modifier: Modifier = Modifier) {
    val open = onPrimary?.onClick ?: {}
    Box(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = open).semantics { contentDescription = "${panel.kicker}, ${panel.title}" }) {
        Crossfade(targetState = imageUrl to panel.seed, animationSpec = tween(200), label = "leadPicture") { (url, seed) ->
            Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.radialGradient(0f to WmmColors.Accent.copy(alpha = 0.13f), 0.6f to Color.Transparent, center = Offset(size.width * 0.72f, size.height * 0.18f), radius = size.width * 0.45f))
                drawRect(Brush.horizontalGradient(0f to WmmColors.Ground.copy(alpha = 0.92f), 0.5f to WmmColors.Ground.copy(alpha = 0.55f), 0.8f to Color.Transparent))
                drawRect(groundFade(0.4f, 1f, alpha = 0.9f))
            },
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 24.dp).widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(panel.kicker.uppercase(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.32.sp, color = WmmColors.Accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(panel.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-0.95).sp, color = WmmColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val facts = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Color(0xFFE5E5E5))
                if (panel.facts.isNotEmpty()) Text(panel.facts.joinToString(" · "), style = facts)
                panel.tags.forEach { Badge(it, style = BadgeStyle.Outline, horizontalPadding = 5.dp, verticalPadding = 2.dp) }
                panel.rating?.let {
                    if (panel.facts.isNotEmpty() || panel.tags.isNotEmpty()) Text("·", style = facts.copy(color = WmmColors.TextFaint))
                    Text(it, style = facts)
                }
            }
            if (panel.overview.isNotBlank()) {
                Text(panel.overview, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.3.sp, color = WmmColors.TextSoft), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onPrimary != null || onSecondary != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    onPrimary?.let { FilledButton(it.label, it.onClick, large = true, icon = if (it.label == "Play" || it.label == "Resume" || it.label.startsWith("Watch ")) WmmIcons.Play else null) }
                    onSecondary?.let { TonalButton(it.label, it.onClick, large = true) }
                }
            }
        }
    }
}

/** The end of Home (TabHomeRow `End of Home`): Edit Home, Back to the top and the totals. */
@Composable
private fun EndPage(home: HomeData, onEdit: () -> Unit, onTop: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.padding(start = 24.dp, end = 40.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("That's everything on Home", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.6).sp, color = WmmColors.Text))
            Text("Move, hide and add rows. Your order follows you to every device.", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 22.5.sp, color = WmmColors.TextSoft))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TonalButton("Edit Home", onEdit, large = true, icon = WmmIcons.Sliders)
                TonalButton("Back to the top", onTop, large = true)
            }
            Text(home.totalsLine(), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.5.sp, color = WmmColors.TextFaint), modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** The next row's name (or the one before, [up]) along the bottom edge; a tap pages (TabHomeRow `.peek`). */
@Composable
private fun PeekLine(text: String, up: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 40.dp)
            .height(44.dp)
            .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .clickable(onClick = onClick, onClickLabel = if (up) "Previous row" else "Next row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WmmIcons.ChevronDown, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(18.dp).rotate(if (up) 180f else 0f))
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The screen dots at the right (TabHomeRow `.dots`): the current one a 22-tall gold bar. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = "Screen ${current + 1} of $count" }, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(count) { i ->
            val height by animateDpAsState(if (i == current) 22.dp else 6.dp, tween(180), label = "dot")
            Box(Modifier.width(6.dp).height(height).background(if (i == current) WmmColors.Accent else Color.White.copy(alpha = 0.28f), RoundedCornerShape(3.dp)))
        }
    }
}
