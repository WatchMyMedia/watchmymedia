package media.watchmy.client.tv.library

import media.watchmy.client.model.cardLine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.UNRATED
import media.watchmy.client.model.episodesLeftLine
import media.watchmy.client.model.nextEpisodeLine
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.label
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.library.LibraryViewModel
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.OptionRow
import media.watchmy.client.tv.components.PanelRow
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvTab
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.timeLeft
import media.watchmy.client.tv.home.FactsLine
import media.watchmy.client.tv.home.HomeFact
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * Movies' or TV Shows' controls, at the right of the header: All and
 * Unwatched (one press each), then Filter and Sort, which open the Sort and
 * filter panel. All clears every filter; Filter says how many are on.
 */
@Composable
internal fun LibraryControls(
    choices: LibraryViewModel.Choices,
    memory: FocusMemory,
    onAll: () -> Unit,
    onUnwatched: () -> Unit,
    onFilter: () -> Unit,
    onSort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTab("All", selected = !choices.narrowed, onClick = onAll, modifier = Modifier.focusMemory(memory, "ctl:all"))
        TvTab("Unwatched", selected = choices.filter == ItemFilter.Unwatched && choices.filterCount == 1, onClick = onUnwatched, modifier = Modifier.focusMemory(memory, "ctl:unwatched"))
        TvButton(
            if (choices.narrowed) "Filter · ${choices.filterCount}" else "Filter",
            onFilter,
            Modifier.focusMemory(memory, "ctl:filter"),
            size = TvButtonSize.Small,
            icon = WmmIcons.Sliders,
        )
        TvButton("Sort: ${choices.sort.label}", onSort, Modifier.focusMemory(memory, "ctl:sort"), size = TvButtonSize.Small)
    }
}

/** The title and its counts at the top left of Movies or TV Shows: "214 movies · 96 unwatched". */
@Composable
internal fun LibraryTitle(title: String, counts: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = TvType.Title, color = WmmColors.Text, maxLines = 1, modifier = Modifier.alignByBaseline())
        Text(counts ?: " ", style = TvType.Second, color = TvPalette.Muted, maxLines = 1, modifier = Modifier.alignByBaseline())
    }
}

/**
 * The focused poster's facts, one line above the grid, so the posters can
 * carry their titles and nothing else: "Ninety Fathoms · 2025 · 1 h 58 min ·
 * Thriller · Not watched yet".
 */
@Composable
internal fun FocusedFacts(item: BrowseItem?, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item == null) return@Row
        Text(item.title, style = TvType.CardTitle, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val facts = item.gridFacts()
        if (facts.isNotEmpty()) {
            Text("·", style = TvType.Body, color = TvPalette.Muted)
            FactsLine(facts)
        }
    }
}

/** A certification as the rating filter words it: "PG-13", or "Not rated". */
internal fun ratingWords(rating: String): String = if (rating == UNRATED) "Not rated" else rating

/**
 * What a grid card's facts line says, in a viewer's order: when, how long,
 * what kind, its rating, then where they are with it. A show says its
 * seasons and when the next episode airs, and how many episodes are left,
 * where the server says so.
 */
internal fun BrowseItem.gridFacts(): List<HomeFact> = buildList {
    when (kind) {
        ItemKind.Movie -> {
            year?.let { add(HomeFact(it.toString())) }
            runtimeMin?.let { add(HomeFact(formatDuration(it * 60_000L))) }
        }
        ItemKind.Show -> {
            val seasons = seasonCount
            if (seasons != null) add(HomeFact(plural(seasons, "season"))) else year?.let { add(HomeFact(it.toString())) }
            nextEpisodeLine(nextAirDate)?.let { add(HomeFact(it)) } ?: if (seasons == null) episodeCount?.let { add(HomeFact(plural(it, "episode"))) } else Unit
        }
        ItemKind.Unmatched -> fileName?.let { add(HomeFact(it)) }
    }
    if (kind == ItemKind.Movie) genres.firstOrNull()?.let { add(HomeFact(it)) }
    contentRating?.let { add(HomeFact(it, HomeFact.Kind.Tag)) }
    // The server it is on, when the server in use doesn't have it.
    where.cardLine()?.let { add(HomeFact(it)) }
    val partway = progress?.takeIf { it > 0f && it < 1f }
    val left = episodesLeftLine()
    when {
        left != null -> add(HomeFact(left, HomeFact.Kind.Strong))
        watched -> add(HomeFact("Watched", HomeFact.Kind.Strong))
        partway != null && runtimeMin != null -> add(HomeFact(timeLeft((runtimeMin!! * 60_000L * (1f - partway)).toLong()), HomeFact.Kind.Strong))
        kind == ItemKind.Movie -> add(HomeFact("Not watched yet", HomeFact.Kind.Strong))
        else -> Unit
    }
}

/** A sort's one-line description in the Sort by list. */
private val ItemSort.order: String
    get() = when (this) {
        ItemSort.Title -> "A to Z, with letters to jump by"
        ItemSort.Runtime -> "Shortest first"
        ItemSort.Rating -> "Highest first"
        else -> "Newest first"
    }

/** Which list the Sort and filter panel shows: its rows, or one row's choices. */
internal enum class FilterList { Show, Genre, Status, Decade, Rating, Picture, Sort }

/**
 * Sort and filter, a panel from the right: a row for each way to narrow
 * the grid, saying in words what is chosen, and Sort by; OK on a row opens
 * its choices in the same panel, a choice applies at once, and Back goes
 * from the choices to the rows, then closes. [start] opens it on Sort by.
 * Clear filters is last, dimmed while nothing is filtered.
 */
@Composable
internal fun SortFilterPanel(
    type: LibraryType,
    state: LibraryViewModel.State,
    filters: List<ItemFilter>,
    sorts: List<ItemSort>,
    start: FilterList?,
    onFilter: (ItemFilter) -> Unit,
    onGenre: (String?) -> Unit,
    onDecade: (Int?) -> Unit,
    onSort: (ItemSort) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onRating: (String?) -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(start) }
    // The row a list was opened from takes focus again when Back returns to the rows.
    var cameFrom by rememberSaveable { mutableStateOf(start) }
    val c = state.choices
    val noun = if (type == LibraryType.Tv) "shows" else "movies"
    TvDialog(onDismiss = { if (open != null && start == null) open = null else onDismiss() }) {
        when (val list = open) {
            null -> {
                val focus = remember { FocusRequester() }
                val rowFocus = remember { FilterList.entries.associateWith { FocusRequester() } }
                TvSidePanel("Sort and filter", context = if (c.narrowed) "Showing ${plural(state.total, noun.dropLast(1))}" else "Showing all ${state.total} $noun") {
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(10.dp)) {
                        val rows = filterRows(state, filters)
                        itemsIndexed(rows) { i, row ->
                            PanelRow(
                                row.title,
                                onClick = {
                                    cameFrom = row.list
                                    open = row.list
                                },
                                modifier = Modifier.focusRequester(rowFocus.getValue(row.list)).then(if (i == 0) Modifier.focusRequester(focus) else Modifier),
                                description = row.value,
                                chevron = true,
                            )
                        }
                        item {
                            TvButton(
                                "Clear filters",
                                onClear,
                                Modifier.padding(top = 10.dp),
                                enabled = c.narrowed,
                                growFrom = TransformOrigin(0f, 0.5f),
                            )
                        }
                    }
                    Text(gridKeptLine(type), style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.padding(start = 10.dp))
                }
                LaunchedEffect(Unit) {
                    val back = cameFrom?.let(rowFocus::get)
                    if (back == null || runCatching { back.requestFocus() }.getOrDefault(false).not()) focus.requestFocus()
                }
            }
            else -> ChoiceList(list, state, filters, sorts, onFilter, onGenre, onDecade, onSort, onRating)
        }
    }
}

/** One row of the Sort and filter panel: what it narrows by and what is chosen, in words. */
private class FilterRow(val list: FilterList, val title: String, val value: String)

private fun filterRows(state: LibraryViewModel.State, filters: List<ItemFilter>): List<FilterRow> {
    val c = state.choices
    val f = c.filter
    return buildList {
        add(FilterRow(FilterList.Show, "Show", if (f == ItemFilter.Unwatched) "Only what you haven’t watched" else "Everything"))
        if (state.genres.isNotEmpty()) add(FilterRow(FilterList.Genre, "Genre", c.genre ?: "Any"))
        if (ItemFilter.Continuing in filters) {
            add(FilterRow(FilterList.Status, "Status", when (f) { ItemFilter.Continuing -> "Still running"; ItemFilter.Ended -> "Ended"; else -> "Any" }))
        }
        if (state.decades.isNotEmpty()) add(FilterRow(FilterList.Decade, "Decade", c.decade?.let { "The ${it}s" } ?: "Any"))
        if (state.ratings != null) add(FilterRow(FilterList.Rating, "Rating", c.rating?.let(::ratingWords) ?: "Any"))
        if (ItemFilter.FourK in filters || ItemFilter.Hdr in filters) {
            add(FilterRow(FilterList.Picture, "Picture", when (f) { ItemFilter.FourK -> "4K"; ItemFilter.Hdr -> "HDR"; else -> "Any" }))
        }
        add(FilterRow(FilterList.Sort, "Sort by", c.sort.label))
    }
}

/** A row's choices, with a gold tick by the one chosen; focus opens on it. */
@Composable
private fun ChoiceList(
    list: FilterList,
    state: LibraryViewModel.State,
    filters: List<ItemFilter>,
    sorts: List<ItemSort>,
    onFilter: (ItemFilter) -> Unit,
    onGenre: (String?) -> Unit,
    onDecade: (Int?) -> Unit,
    onSort: (ItemSort) -> Unit,
    onRating: (String?) -> Unit,
) {
    val c = state.choices
    val f = c.filter
    class Choice(val label: String, val description: String?, val chosen: Boolean, val pick: () -> Unit)
    val (title, choices) = when (list) {
        FilterList.Show -> "Show" to listOf(
            Choice("Everything", null, f != ItemFilter.Unwatched) { if (f == ItemFilter.Unwatched) onFilter(ItemFilter.All) },
            Choice("Only what you haven’t watched", null, f == ItemFilter.Unwatched) { onFilter(ItemFilter.Unwatched) },
        )
        FilterList.Genre -> "Genre" to (listOf(Choice("Any", null, c.genre == null) { c.genre?.let(onGenre) }) + state.genres.map { g -> Choice(g, null, c.genre == g) { if (c.genre != g) onGenre(g) } })
        FilterList.Status -> "Status" to listOf(
            Choice("Any", null, f != ItemFilter.Continuing && f != ItemFilter.Ended) { if (f == ItemFilter.Continuing || f == ItemFilter.Ended) onFilter(ItemFilter.All) },
            Choice("Still running", "New episodes still come", f == ItemFilter.Continuing) { onFilter(ItemFilter.Continuing) },
            Choice("Ended", "No more episodes are coming", f == ItemFilter.Ended) { onFilter(ItemFilter.Ended) },
        )
        FilterList.Decade -> "Decade" to (listOf(Choice("Any", null, c.decade == null) { onDecade(null) }) + state.decades.map { d -> Choice("The ${d}s", null, c.decade == d) { onDecade(d) } })
        FilterList.Rating -> "Rating" to (listOf(Choice("Any", null, c.rating == null) { c.rating?.let(onRating) }) + state.ratings.orEmpty().map { r ->
            Choice(ratingWords(r), state.ratingCounts[r]?.let { n -> plural(n, "title") }, c.rating == r) { if (c.rating != r) onRating(r) }
        })
        FilterList.Picture -> "Picture" to listOfNotNull(
            Choice("Any", null, f != ItemFilter.FourK && f != ItemFilter.Hdr) { if (f == ItemFilter.FourK || f == ItemFilter.Hdr) onFilter(ItemFilter.All) },
            Choice("4K", "Ultra HD versions", f == ItemFilter.FourK) { onFilter(ItemFilter.FourK) }.takeIf { ItemFilter.FourK in filters },
            Choice("HDR", "High dynamic range, on a TV that shows it", f == ItemFilter.Hdr) { onFilter(ItemFilter.Hdr) }.takeIf { ItemFilter.Hdr in filters },
        )
        FilterList.Sort -> "Sort by" to sorts.map { s -> Choice(s.label, s.order, c.sort == s) { onSort(s) } }
    }
    val focus = remember(list) { FocusRequester() }
    val chosenIndex = choices.indexOfFirst { it.chosen }.coerceAtLeast(0)
    TvSidePanel(title, kicker = "Sort and filter") {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(10.dp)) {
            itemsIndexed(choices) { i, choice ->
                OptionRow(
                    choice.label,
                    selected = choice.chosen,
                    onClick = choice.pick,
                    modifier = if (i == chosenIndex) Modifier.focusRequester(focus) else Modifier,
                    description = choice.description,
                )
            }
        }
    }
    InitialFocus(focus)
}

/** Under Sort and filter: what the TV does with them. */
internal fun gridKeptLine(type: LibraryType): String = "This TV remembers these for ${if (type == LibraryType.Tv) "TV Shows" else "Movies"}."
