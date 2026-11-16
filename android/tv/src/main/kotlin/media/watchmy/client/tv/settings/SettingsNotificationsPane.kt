package media.watchmy.client.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.FOLLOWS_EMPTY
import media.watchmy.client.model.FOLLOWS_FAILED_TITLE
import media.watchmy.client.model.FOLLOW_TV_LINE
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.SHOWS_YOU_FOLLOW
import media.watchmy.client.model.line
import media.watchmy.client.model.spoken
import media.watchmy.client.model.stopRefused
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.NotificationsViewModel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.ReportShown
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/** A followed show's key in Settings' focus memory. */
internal fun followKey(tmdbId: Long) = "follow:$tmdbId"

private const val FOLLOWS_FAILED_KEY = "follows-failed"

/**
 * Settings › Notifications on the TV (PLAN-NEW-EPISODES.md §7.2; the boards'
 * `tv-settings`): who notifies, then Shows you follow, a row a show with its
 * poster and line. No switch: this TV doesn't notify. OK on a show opens the
 * side panel ([onShow]); a stop the server refused says so in the row, and OK
 * tries it again. When the row that had focus goes, focus moves to the next,
 * else the one before, else the group ([onEmptied]).
 */
@Composable
internal fun ColumnScope.NotificationsPane(
    state: NotificationsViewModel.State,
    library: LibraryRepository,
    first: FocusRequester,
    onShow: (FollowedShow) -> Unit,
    onStop: (FollowedShow) -> Unit,
    onRetry: () -> Unit,
    onEmptied: () -> Unit,
    onShown: (Boolean) -> Unit,
) {
    // The list is read again each time the pane opens, and for changes while it is on screen.
    ReportShown(onShown)
    Note(FOLLOW_TV_LINE, Modifier.padding(top = 4.dp, bottom = 6.dp))
    val list = state.list
    if (list is Load.Failed) {
        SettingRow(FOLLOWS_FAILED_TITLE, onClick = onRetry, description = list.message, stacked = true, modifier = Modifier.focusRequester(first), focusKey = FOLLOWS_FAILED_KEY) { Value("Try again") }
    }
    PaneHeading(SHOWS_YOU_FOLLOW)
    when (list) {
        Load.Loading -> Note("Loading…", Modifier.padding(vertical = 12.dp))
        is Load.Failed -> Unit
        is Load.Ready -> {
            if (list.data.isEmpty()) Note(FOLLOWS_EMPTY, Modifier.padding(vertical = 8.dp))
            list.data.forEachIndexed { i, show ->
                key(show.tmdbId) {
                    val refused = show.tmdbId in state.refused
                    FollowedShowRow(
                        show,
                        library,
                        failure = if (refused) stopRefused(show.title, tv = true) + " " + PRESS_OK_AGAIN else null,
                        onClick = { if (refused) onStop(show) else onShow(show) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            FocusAfterStop(list.data.map { it.tmdbId }, onEmptied)
        }
    }
}

/**
 * Moves focus on when the show that had it leaves the list: to the next,
 * else the one before, else [onEmptied] (the group). In the same frame the
 * row goes, as after signing a device out.
 */
@Composable
private fun FocusAfterStop(ids: List<Long>, onEmptied: () -> Unit) {
    val memory = LocalSettingsFocus.current ?: return
    val before = remember { arrayOf(ids) }
    val previous = before[0]
    if (previous == ids) return
    before[0] = ids
    val had = previous.filter { it !in ids }.firstOrNull { memory.key == followKey(it) } ?: return
    val next = ids.getOrNull(previous.indexOf(had)) ?: ids.lastOrNull()
    SideEffect { if (next == null || !memory.focus(followKey(next))) onEmptied() }
}

/** A show followed (`.tvrow`): its poster, title and line, and a chevron to its panel; a refused stop's line in red. */
@Composable
private fun FollowedShowRow(show: FollowedShow, library: LibraryRepository, failure: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val memory = LocalSettingsFocus.current
    val remembered = if (memory != null) Modifier.focusMemory(memory, followKey(show.tmdbId)) else Modifier
    val line = show.line()
    FocusableBox(
        onClick = onClick,
        modifier = modifier.then(remembered).fillMaxWidth().semantics { contentDescription = show.spoken(failure ?: line) },
        cornerRadius = 12.dp,
        focusedScale = 1f,
        ringOffset = (-4).dp,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .background(if (focused) WmmColors.Surface2 else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (show.gone) {
                Box(Modifier.size(56.dp, 84.dp).background(WmmColors.Surface2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Icon(WmmIcons.Tv, contentDescription = null, tint = WmmColors.TextFaint, modifier = Modifier.size(26.dp))
                }
            } else {
                Artwork(library.imageUrl(show.posterPath, ImageSize.W185), seed = show.title, contentDescription = null, modifier = Modifier.size(56.dp, 84.dp), shape = RoundedCornerShape(8.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(show.title, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (failure != null) {
                    Text(failure, style = inter(17.sp, lineHeight = 23.sp), color = FailRed, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                } else {
                    Text(line, style = inter(17.sp), color = if (show.gone) WmmColors.Warn else WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (failure == null) Value("", chevron = true)
        }
    }
}
