package media.watchmy.client.tv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.cardPanelAt
import media.watchmy.client.model.headingLine
import media.watchmy.client.model.pictureUrl
import media.watchmy.client.model.drawn
import media.watchmy.client.model.episodeCards
import media.watchmy.client.model.seeAllType
import media.watchmy.client.tv.rows.OnFocusItem
import media.watchmy.client.tv.rows.TvArrangedRow
import media.watchmy.client.tv.rows.TvRowActions
import media.watchmy.client.tv.rows.cardKey
import media.watchmy.client.tv.rows.tvHomeSeeAll
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.totalsLine
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.stopBringIntoView
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.open
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.rememberLiveNow
import media.watchmy.client.ui.components.rememberLiveClockFormat
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.onBack

/**
 * Home in the person's order (PLAN-ROWS.md §8.2) as screens (PLAN-HOME.md
 * §7.3, HomeStart.dc.html and HomeRow.dc.html): page 0 is the hero and the
 * first row drawn, every later row fills a page with the focused card's
 * picture, kicker, title, facts and overview above it, and the last page is
 * Edit rows and the totals. ▼ at a row pages down and ▲ pages up (the D-pad
 * pages, never a fling); each row comes back to the card it had focus on;
 * Back from a row page goes to page 0 first. Live TV sits in its place.
 * A row with somewhere to go ends in See all: a list's page, or its
 * library's grid sorted the row's way ([onOpenLibrary], PLAN-SEE-ALL.md).
 *
 * Focus moves first and the pager follows it: ▼ puts focus on the next
 * row's card at once and the screen slides after, so a key pressed during
 * the slide acts on the row arriving, and the page showing is always the
 * one that holds focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArrangedHomeContent(
    home: HomeData,
    library: LibraryRepository,
    memory: FocusMemory,
    actions: TvRowActions,
    liveChannels: List<LiveChannel>,
    onWatchLive: (LiveChannel) -> Unit,
    liveListings: Boolean,
    onLiveGuide: () -> Unit,
    onEditRows: () -> Unit,
    onOpenLibrary: (LibraryType) -> Unit = {},
) {
    val live = liveChannels.any { it.available }
    val requests = LocalAppServices.current.browseRequests
    val rows = home.rows.orEmpty().drawn(live)
    val pages = remember(home, rows) { homePages(home.hero != null, rows) }
    val pager = rememberPagerState { pages.size }
    val available = liveChannels.filter { it.available }
    // The card each row last had focus on, by row id, so a page restores it (FocusMemory keeps only the last key overall).
    // Saved with the screen: after Back from a page, ▲ into a row left scrolled sideways finds its card again.
    val lastFocus = rememberSaveable(saver = RowFocusSaver) { mutableStateMapOf<String, String>() }
    val heroKey = home.hero?.let { if (it.fileId != null) "hero:play" else "hero:open" }
    // The page Home is on or heading to: the one that holds focus (each page reports it), or one asked for that could not take focus yet.
    var goal by remember { mutableStateOf<PageGoal?>(null) }

    fun firstKey(row: ScreenRow): String? = if (row.style == RowStyle.Live) available.firstOrNull()?.let { "live:${it.id}" } else row.cardKey()
    fun focus(key: String?): Boolean = key != null && runCatching { memory.requester(key).requestFocus() }.getOrDefault(false)

    /** Puts focus on [page]: the card its row last had, else the row's first, else the hero. False while the page is not composed. */
    fun focusPage(page: HomePage): Boolean = when (page) {
        is HomePage.Start -> page.row?.let { focus(lastFocus[it.id]) || focus(firstKey(it)) } == true || focus(heroKey)
        is HomePage.Row -> focus(lastFocus[page.row.id]) || focus(firstKey(page.row))
        HomePage.End -> focus("end:edit")
    }

    fun ask(page: HomePage) {
        goal = PageGoal(page.key, focus = true, ask = (goal?.ask ?: 0) + 1)
    }

    /**
     * Goes to [target]. Focus moves there at once (the screens either side of the one showing are composed) and the
     * pager follows it; a page too far away to be composed is scrolled to first and takes focus on arrival. One page
     * move at a time, unless [interrupting] (Back).
     */
    fun pageTo(target: Int, interrupting: Boolean = false) {
        if (target !in pages.indices || (pager.isScrollInProgress && !interrupting)) return
        if (!focusPage(pages[target])) ask(pages[target])
    }

    val currentPages by rememberUpdatedState(pages)
    val focusOn by rememberUpdatedState<(HomePage) -> Boolean> { focusPage(it) }

    // The pager follows the goal, whatever set it: ▲ ▼, a focus search, or focus coming back from the rail or a closed page.
    LaunchedEffect(pager) {
        snapshotFlow { goal?.let { g -> currentPages.indexOfFirst { it.key == g.key }.takeIf { it >= 0 }?.let { it to g } } }
            .collectLatest { target ->
                val (index, wanted) = target ?: return@collectLatest
                // Nothing else scrolls the pager. Should a scroll be cut short all the same, go again rather than rest between pages.
                var tries = 0
                while ((pager.currentPage != index || pager.currentPageOffsetFraction != 0f) && tries++ < SCROLL_TRIES) {
                    try {
                        pager.animateScrollToPage(index)
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (wanted.focus) {
                    // The page composes as it comes into view: its cards may be a frame or two behind.
                    repeat(FOCUS_FRAMES) {
                        if (currentPages.getOrNull(index)?.let(focusOn) == true) return@collectLatest
                        withFrameNanos { }
                    }
                }
            }
    }

    LaunchedEffect(Unit) {
        // The first row's cards compose inside the pager's first page a frame after it: try again once if they weren't there yet.
        val fallback = home.arrangedFirstFocusKey(liveChannels) ?: liveChannels.firstOrNull { it.available }?.let { "live:${it.id}" }
        repeat(2) {
            withFrameNanos { }
            if (memory.restore(fallback)) return@LaunchedEffect
        }
        // Neither is there (the remembered card has gone, and the hero is not on the page showing): the page showing takes focus.
        pages.getOrNull(pager.currentPage)?.let(::ask)
    }

    // Back from a row page goes to the first page before leaving Home, also while the pager is still on its way down.
    val awayFromTop by remember { derivedStateOf { pager.currentPage > 0 || goal.let { it != null && it.key != currentPages.first().key } } }

    val now = rememberLiveNow()
    val format = rememberLiveClockFormat()
    val liveRepository = LocalAppServices.current.live

    @Composable
    fun RowBlock(row: ScreenRow, modifier: Modifier = Modifier, onFocusIndex: (Int) -> Unit = {}) {
        val counter = "Row ${rows.indexOf(row) + 1} of ${rows.size}"
        val onFocusItem: OnFocusItem = { i, key ->
            lastFocus[row.id] = key
            onFocusIndex(i)
        }
        if (row.style == RowStyle.Live) LiveRow(liveChannels, liveListings, memory, onWatchLive, onLiveGuide, modifier, counter, onFocusItem)
        else {
            // Kept across focus moves, which redraw the page, so the card's click stays the same one.
            val seeAll = remember(row, home, actions, onOpenLibrary) { tvHomeSeeAll(row, home, requests, actions, onOpenLibrary) }
            TvArrangedRow(row, library, memory, actions, modifier, counter, onFocusItem, seeAll = seeAll)
        }
    }

    fun nextName(index: Int): String = when (val next = pages.getOrNull(index + 1)) {
        is HomePage.Row -> next.row.headingLine(liveListings)
        else -> "End of Home"
    }

    VerticalPager(
        state = pager,
        modifier = Modifier.fillMaxSize().testTag(TestTags.HOME).onBack(enabled = awayFromTop) { pageTo(0, interrupting = true) },
        beyondViewportPageCount = 1,
        userScrollEnabled = false,
        key = { pages[it].key },
    ) { index ->
        val page = pages[index]
        // The page that holds focus is the goal, and a card focused inside it never moves the pager by itself. Not a
        // focus group: the rail's way back restores the row or the hero button it left, which a group here would hide.
        Box(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) goal = PageGoal(page.key, focus = false) }.stopBringIntoView()) {
            when (page) {
                is HomePage.Start -> Box(Modifier.fillMaxSize()) {
                    Column {
                        home.hero?.let { hero ->
                            val episode = hero.episodeId?.takeIf { hero.kind == Hero.Kind.Episode }?.let { id -> home.episodeCards.firstOrNull { it.episodeId == id } }
                            val openEpisode = episode?.let { e -> { actions.onOpenEpisode(e.showId, e.season, e.episodeId) } }
                            HeroSection(hero, home, library, memory, actions.onPlay, actions.onOpenShow, actions.onOpenMovie, openEpisode)
                        }
                        page.row?.let { row ->
                            Spacer(Modifier.height(80.dp))
                            // ▲ from the row reaches the hero's buttons by itself; ▼ goes to the first row screen.
                            RowBlock(row, Modifier.pageKeys(up = null, down = { pageTo(index + 1) }))
                        }
                    }
                    PeekLine(nextName(index), up = false, Modifier.align(Alignment.BottomStart))
                }
                is HomePage.Row -> {
                    val row = page.row
                    var focused by rememberSaveable(row.id) { mutableIntStateOf(0) }
                    val panel = remember(row, focused, available, now) { row.cardPanelAt(focused, available, now, format, home.seeAllType(row)) }
                    Box(Modifier.fillMaxSize()) {
                        RowBackdrop(panel?.pictureUrl(library, liveRepository, ImageSize.W1280), panel?.seed ?: row.title)
                        if (panel != null) FocusPanel(panel, Modifier.padding(start = ContentStart, top = 96.dp).width(940.dp), first = focused == 0)
                        PrefetchNeighbours(row, focused, available, library, liveRepository, now, format)
                        Box(Modifier.padding(top = 556.dp)) {
                            RowBlock(row, Modifier.pageKeys(up = { pageTo(index - 1) }, down = { pageTo(index + 1) })) { focused = it }
                        }
                        PeekLine(nextName(index), up = false, Modifier.align(Alignment.BottomStart))
                    }
                }
                HomePage.End -> Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(start = ContentStart, top = 300.dp).width(1000.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Text("That's everything on Home", style = inter(44.sp, FontWeight.Bold, tracking = -0.02f), color = WmmColors.Text)
                        Text("Move, hide and add rows. Your order follows you to every device.", style = inter(22.sp, lineHeight = 33.sp), color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 760.dp))
                        Row(Modifier.padding(top = 6.dp).pageKeys(up = { pageTo(index - 1) }, down = null), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            TvButton("Edit rows", onEditRows, Modifier.focusMemory(memory, "end:edit"), icon = WmmIcons.Sliders)
                            TvButton("Back to the top", { pageTo(0) }, Modifier.focusMemory(memory, "end:top"))
                        }
                        Text(home.totalsLine(), style = inter(17.sp), color = WmmColors.TextFaint, modifier = Modifier.padding(top = 18.dp))
                    }
                    val before = pages.getOrNull(index - 1)?.row?.headingLine(liveListings) ?: "Home"
                    PeekLine(before, up = true, Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
}

/**
 * The page Home's pager goes to. [focus]: put focus on it on arrival, for a page that was not composed when it was
 * asked for; [ask] tells one such request from the next.
 */
private data class PageGoal(val key: String, val focus: Boolean, val ask: Int = 0)

/** Scrolls tried before leaving the pager where it is; nothing else moves it, so one is the rule. */
private const val SCROLL_TRIES = 3

/** Frames a page asked for is given to compose its cards before focus gives up on it. */
private const val FOCUS_FRAMES = 10

/** Saves each row's last focused card (row id to card key) with the screen. */
private val RowFocusSaver = listSaver<SnapshotStateMap<String, String>, String>(
    save = { map -> map.flatMap { (row, card) -> listOf(row, card) } },
    restore = { saved -> mutableStateMapOf<String, String>().apply { saved.chunked(2).forEach { (row, card) -> put(row, card) } } },
)
