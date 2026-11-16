package media.watchmy.client.mobile.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import media.watchmy.client.model.plural
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.markShowLabel
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork

/**
 * About the show: its poster, facts and status, overview, Notify me, Mark
 * show watched and Request missing seasons, cast and what is on disk. [compact] is the
 * tablet's card beside the episode.
 */
@Composable
internal fun AboutShow(show: ShowDetail, state: ShowPageViewModel.State, vm: ShowPageActions, page: PageActions, divider: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val library = LocalAppServices.current.library
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (divider) HorizontalDivider(color = WmmColors.Line, modifier = Modifier.padding(bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(
                library.imageUrl(show.posterPath, ImageSize.W342),
                seed = show.title,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 64.dp else 72.dp, if (compact) 96.dp else 108.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    show.title,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 24.sp, color = WmmColors.Text),
                    modifier = Modifier.semantics { heading() },
                )
                DetailFacts(showFacts(show))
                statusLine(show)?.let { (text, active) -> StatusPill(text, active) }
            }
        }
        if (show.overview.isNotBlank()) Overview(show.overview, collapsedLines = if (compact) 3 else 4)
        // Notify me, first of the show's actions (PLAN-NEW-EPISODES.md §7.1).
        state.following?.let { following -> FollowButton(following, show.title, page.onFollow, Modifier.fillMaxWidth()) }
        if (state.canMarkShow) TonalButton(markShowLabel(state.showWatched), { vm.setShowWatched(!state.showWatched) }, Modifier.fillMaxWidth(), enabled = !state.acting)
        if (state.missingSeasons) TonalButton("Request missing seasons", { page.onAsk(ALL_SEASONS) }, Modifier.fillMaxWidth())
        if (compact) {
            if (show.cast.isNotEmpty()) AboutLine("Cast", show.cast.take(3).joinToString(", ") { it.name })
            if (show.genres.isNotEmpty()) AboutLine("Genres", show.genres.joinToString(", "))
            AboutLine("On disk", onDiskLine(show))
        } else {
            Box(Modifier.layout { m, c ->
                // The cast row runs to the screen's edges, past the column's padding.
                val wide = c.maxWidth + 32.dp.roundToPx()
                val p = m.measure(c.copy(minWidth = wide, maxWidth = wide))
                layout(c.maxWidth, p.height) { p.place(-16.dp.roundToPx(), 0) }
            }) { CastRow(show.cast) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                show.status?.let { AboutLine("Status", it) }
                if (show.genres.isNotEmpty()) AboutLine("Genres", show.genres.joinToString(", "))
                AboutLine("On disk", onDiskLine(show))
            }
        }
    }
}

/** "24 episodes · 57.6 GB". */
private fun onDiskLine(show: ShowDetail): String = listOf(plural(show.episodesOnDisk, "episode"), formatBytes(show.totalSizeBytes)).joinToString(" · ")

@Composable
private fun AboutLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted), modifier = Modifier.width(72.dp))
        Text(value, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp, color = WmmColors.TextSoft))
    }
}
