package media.watchmy.client.tv.detail

import androidx.compose.ui.geometry.Offset
import media.watchmy.client.model.monthDay
import media.watchmy.client.model.plural
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import media.watchmy.client.model.FOLLOW_TV_LINE
import media.watchmy.client.model.followLabel
import media.watchmy.client.model.followSpoken
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.tv.watchlists.WatchlistButtonTv
import media.watchmy.client.tv.watchlists.savesHere
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.markShowLabel
import media.watchmy.client.model.missingEpisodesLabel
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.bringWholeIntoView
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/**
 * About the show (`section`, past the row): the poster, title, facts and
 * status, the overview, then Notify me, Mark show watched, Show missing
 * episodes, Request missing seasons and Cast & details. ▲ goes back to the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun About(show: ShowDetail, flags: AboutFlags, vm: ShowPageActions, library: LibraryRepository, memory: FocusMemory, episodeFocus: () -> FocusRequester, onAsk: (Int) -> Unit, onDetails: () -> Unit) {
    val nav = remember(episodeFocus) { Modifier.focusProperties { this.up = episodeFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ContentStart, end = ContentEnd, top = 36.dp)
            .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(top = 36.dp)
            .bringWholeIntoView(),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Artwork(library.imageUrl(show.posterPath, ImageSize.W342), seed = show.title, contentDescription = null, modifier = Modifier.size(200.dp, 300.dp), shape = RoundedCornerShape(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(show.title, style = inter(48.sp, FontWeight.ExtraBold, lineHeight = 52.sp, tracking = -0.02f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(showFacts(show), style = inter(22.sp), color = TextBright)
                showStatus(show)?.let { (text, active) ->
                    val color = if (active) WmmColors.Accent else WmmColors.TextMuted
                    Text(text, style = inter(22.sp, FontWeight.SemiBold), color = color, modifier = Modifier.border(1.dp, color, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            if (show.overview.isNotBlank()) Text(show.overview, style = inter(22.sp, lineHeight = 33.sp), color = WmmColors.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.dp))
            // Notify me, first of the show's buttons, with who notifies under the buttons while it has focus,
            // and Watchlist beside it. With Watchlist there is a second row for the rest: one row can't hold them.
            var bellFocused by remember { mutableStateOf(false) }
            val saves = savesHere(show.watchlistTitle(), show.watchlists)
            // The second row's ▲ goes to the first row, not back to the episodes.
            val up = episodeFocus.takeIf { !saves }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                flags.following?.let { following ->
                    TvButton(
                        followLabel(following),
                        vm::toggleFollow,
                        nav.focusMemory(memory, "about:follow").onFocusChanged { bellFocused = it.isFocused }
                            .semantics { contentDescription = followSpoken(following, show.title) },
                        icon = if (following) WmmIcons.BellFilled else WmmIcons.Bell,
                        iconTint = if (following) WmmColors.Accent else Color.White,
                    )
                }
                WatchlistButtonTv(show.watchlistTitle(), show.watchlists, nav.focusMemory(memory, "about:watchlist"))
                if (flags.canMarkShow) TvButton(markShowLabel(flags.showWatched), { vm.setShowWatched(!flags.showWatched) }, nav.focusMemory(memory, "about:mark"), enabled = !flags.acting)
                if (!saves) AboutMore(flags, vm, memory, up, onAsk, onDetails)
            }
            if (saves) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { AboutMore(flags, vm, memory, up, onAsk, onDetails) }
            if (bellFocused && flags.following != null) Text(FOLLOW_TV_LINE, style = inter(22.sp), color = WmmColors.TextMuted)
        }
    }
}

/** Show missing episodes, Request missing seasons and Cast & details: after the show's first buttons, or on a row of their own. */
@Composable
private fun RowScope.AboutMore(flags: AboutFlags, vm: ShowPageActions, memory: FocusMemory, episodeFocus: (() -> FocusRequester)?, onAsk: (Int) -> Unit, onDetails: () -> Unit) {
    val nav = remember(episodeFocus) { episodeFocus?.let { focus -> Modifier.focusProperties { this.up = focus() } } ?: Modifier }
    if (flags.canShowMissing) TvButton(missingEpisodesLabel(flags.showMissing), { vm.setShowMissing(!flags.showMissing) }, nav.focusMemory(memory, "about:missing"))
    if (flags.missingSeasons) TvButton("Request missing seasons", { onAsk(ALL_SEASONS) }, nav.focusMemory(memory, "about:request"))
    TvButton("Cast & details", onDetails, nav.focusMemory(memory, "about:details"), icon = WmmIcons.Info)
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
)

internal fun ShowPageViewModel.State.aboutFlags() = AboutFlags(canMarkShow, showWatched, acting, canShowMissing, showMissing, missingSeasons, following)

/** "2022– · 3 seasons · 24 episodes · Drama · ★ 8.1". */
private fun showFacts(show: ShowDetail): String {
    val seasons = show.seasons.count { it.number > 0 }
    val ended = show.status.isEnded()
    return listOfNotNull(
        show.year?.let { if (ended) "$it" else "$it–" },
        plural(seasons, "season").takeIf { seasons > 0 },
        plural(show.episodesOnDisk, "episode").takeIf { show.episodesOnDisk > 0 },
        show.genres.firstOrNull(),
        formatRating(show.rating),
    ).joinToString(" · ")
}

/** "Continuing · next Nov 18" (gold) or "Ended". */
private fun showStatus(show: ShowDetail): Pair<String, Boolean>? {
    val status = show.status ?: return null
    if (status.isEnded()) return (if (status.contains("cancel", ignoreCase = true)) "Cancelled" else "Ended") to false
    val next = monthDay(show.nextAirDate)
    return (if (next != null) "Continuing · next $next" else "Continuing") to true
}

private fun String?.isEnded() = this != null && (contains("ended", ignoreCase = true) || contains("cancel", ignoreCase = true))
