package media.watchmy.client.tv.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.FOLLOW_TV_LINE
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.followLabel
import media.watchmy.client.model.followSpoken
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.markShowLabel
import media.watchmy.client.model.plural
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.watchlists.WatchlistButtonTv
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/**
 * Under the season's row, the name of what is below ("About Harbor Lights")
 * with a chevron, in sight before ▼ goes there, and what OK does on an
 * episode while the row has focus.
 */
@Composable
internal fun AboutPeek(show: ShowDetail, rowFocused: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = ContentStart, end = ContentEnd), verticalAlignment = Alignment.CenterVertically) {
        Icon(WmmIcons.ChevronDown, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(28.dp))
        Text("About ${show.title}", style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp).weight(1f))
        if (rowFocused) KeyHints(listOf("OK" to "Up to Play"))
    }
}

/**
 * About the show, past the row: the poster, the title, the show's facts,
 * when its next episode airs and how many are left to watch, two lines about
 * it, then Notify me, Watchlist, Mark show watched and More (Show missing
 * episodes, Request missing seasons). ▲ goes back to the episodes, ▼ to the
 * cast, whose ▲ comes back to the button last focused here.
 */
@Composable
internal fun About(
    show: ShowDetail,
    flags: AboutFlags,
    vm: ShowPageActions,
    library: LibraryRepository,
    memory: FocusMemory,
    onButtonFocused: (String) -> Unit,
    episodeFocus: () -> FocusRequester,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val nav = remember(episodeFocus) { Modifier.focusProperties { this.up = episodeFocus() } }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = ContentStart, end = ContentEnd)
            .bringWholeIntoView(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Artwork(library.imageUrl(show.posterPath, ImageSize.W342), seed = show.title, contentDescription = null, modifier = Modifier.size(200.dp, 300.dp), shape = RoundedCornerShape(14.dp))
        Column(Modifier.weight(1f)) {
            Text(show.title, style = TvType.Title, color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FactsLine(showFacts(show), Modifier.padding(top = 12.dp))
            aboutLine(show)?.let { (lead, rest) -> WhenLine(lead, rest, Modifier.padding(top = 10.dp)) }
            Overview(show.overview, lines = 2, Modifier.padding(top = 12.dp), width = 820.dp)
            Spacer(Modifier.height(22.dp))
            var bellFocused by remember { mutableStateOf(false) }
            fun Modifier.button(key: String) = nav.focusMemory(memory, key).onFocused { onButtonFocused(key) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                flags.following?.let { following ->
                    TvButton(
                        followLabel(following),
                        vm::toggleFollow,
                        Modifier.button("about:follow").onFocusChanged { bellFocused = it.isFocused }
                            .semantics { contentDescription = followSpoken(following, show.title) },
                        icon = if (following) WmmIcons.BellFilled else WmmIcons.Bell,
                        iconTint = if (following) WmmColors.Accent else Color.White,
                        growFrom = TransformOrigin(0f, 0.5f),
                    )
                }
                WatchlistButtonTv(show.watchlistTitle(), show.watchlists, Modifier.button("about:watchlist"))
                if (flags.canMarkShow) TvButton(markShowLabel(flags.showWatched), { vm.setShowWatched(!flags.showWatched) }, Modifier.button("about:mark"), enabled = !flags.acting)
                if (onMore != null) TvButton("More", onMore, Modifier.button("about:more"))
            }
            if (bellFocused && flags.following != null) Text(FOLLOW_TV_LINE, style = TvType.Caption, color = TvPalette.Muted, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** What About the show's buttons depend on: the same while focus moves along the episode row. */
@Immutable
internal data class AboutFlags(
    val canMarkShow: Boolean,
    val showWatched: Boolean,
    val acting: Boolean,
    val canShowMissing: Boolean,
    val showMissing: Boolean,
    val missingSeasons: Boolean,
    /** Notify me: whether the person follows the show; null where the bell is hidden. */
    val following: Boolean? = null,
) {
    /** About has a More: Show missing episodes, or Request missing seasons. */
    val hasMore: Boolean get() = canShowMissing || missingSeasons
}

internal fun ShowPageViewModel.State.aboutFlags() = AboutFlags(canMarkShow, showWatched, acting, canShowMissing, showMissing, missingSeasons, following)

/** "2021 to now · TV-14 · Drama · 3 seasons · ★ 8.4". */
private fun showFacts(show: ShowDetail): List<Fact> {
    val seasons = show.seasons.count { it.number > 0 }
    val ended = show.status.isEnded()
    return buildList {
        show.year?.let { add(Fact.Words(if (ended) "$it" else "$it to now")) }
        show.contentRating?.takeIf { it.isNotBlank() }?.let { add(Fact.Tag(it)) }
        show.genres.firstOrNull()?.let { add(Fact.Words(it)) }
        if (seasons > 0) add(Fact.Words(plural(seasons, "season")))
        formatRating(show.rating)?.let { add(Fact.Words(it)) }
    }
}

/**
 * "New episode Thursday, Nov 26" in bold and " · 4 episodes left to watch",
 * or "Ended" and the same; null when there is nothing to say.
 */
internal fun aboutLine(show: ShowDetail): Pair<String, String?>? {
    val left = show.seasons.filter { it.number > 0 }.sumOf { (it.availableCount - it.watchedCount).coerceAtLeast(0) }
    val leftWords = when {
        left > 0 -> plural(left, "episode") + " left to watch"
        show.seasons.any { it.number > 0 && it.availableCount > 0 } -> "all watched"
        else -> null
    }
    val lead = nextEpisodeDay(show.nextAirDate, long = true)?.let { "New episode $it" }
        ?: (if (show.status.isEnded()) (if (show.status?.contains("cancel", ignoreCase = true) == true) "Cancelled" else "Ended") else null)
    return when {
        lead != null -> lead to leftWords?.let { " · $it" }
        leftWords != null -> leftWords.replaceFirstChar { it.uppercase() } to null
        else -> null
    }
}

private fun String?.isEnded() = this != null && (contains("ended", ignoreCase = true) || contains("cancel", ignoreCase = true))
