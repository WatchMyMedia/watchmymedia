package media.watchmy.client.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.partway
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.player.PlayerEpisodesViewModel
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvTab
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WatchedCheck

/** Where focus goes in the episode list: the playing episode's row, else the first. */
internal class EpisodesFocus {
    val panel = FocusRequester()
    private val rows = HashMap<Long, FocusRequester>()
    private val tabs = HashMap<Int, FocusRequester>()

    fun row(id: Long) = rows.getOrPut(id) { FocusRequester() }

    fun tab(number: Int) = tabs.getOrPut(number) { FocusRequester() }
}

/**
 * The player's episode list, a panel from the right: the season tabs,
 * Specials last, the chosen one underlined in gold, and the season's
 * episodes with their stills. ◀ ▶ on the tabs changes the season at once;
 * OK on an episode plays it, with the person's audio and subtitle
 * languages, and OK on the one playing goes back to the video. Back closes
 * it (the player's Back).
 */
@Composable
internal fun EpisodesPanel(
    state: PlayerEpisodesViewModel.State,
    focus: EpisodesFocus,
    imageUrl: (String?, ImageSize) -> String?,
    positionMinutes: () -> Long,
    onSeason: (Int) -> Unit,
    onPlay: (EpisodeDetail) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvSidePanel(title = "Episodes", modifier = modifier, context = state.showTitle) {
        if (state.seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(10.dp)) {
                items(state.seasons, key = { it.number }) { season ->
                    TvTab(
                        seasonName(season.number),
                        selected = season.number == state.seasonNumber,
                        onClick = { onSeason(season.number) },
                        modifier = Modifier.focusRequester(focus.tab(season.number)).onFocusChanged { if (it.isFocused) onSeason(season.number) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val season = state.season) {
                Load.Loading -> Text("Loading…", style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.padding(24.dp))
                is Load.Failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(season.message, style = TvType.Body, color = WmmColors.TextSoft)
                    TvButton("Try again", onRetry, Modifier.focusRequester(focus.panel), style = TvButtonStyle.Primary)
                }
                is Load.Ready -> {
                    val episodes = state.episodes
                    val playingId = state.playing?.id
                    val start = episodes.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
                    // Read through derivedStateOf: the line changes only when the first row does, not at every scroll frame.
                    val hidden by remember(list) { derivedStateOf { list.firstVisibleItemIndex } }
                    Column {
                        if (hidden > 0) {
                            val above = episodes.take(hidden)
                            val watched = above.all { it.watched }
                            Text(
                                "▲ Episodes ${above.first().number} to ${above.last().number}" + if (watched) ", watched" else "",
                                style = TvType.Caption,
                                color = TvPalette.Muted,
                                modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                            )
                        }
                        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(10.dp)) {
                            items(episodes, key = { it.id }) { episode ->
                                val playing = episode.id == playingId
                                EpisodeRow(
                                    episode,
                                    playing = playing,
                                    still = imageUrl(episode.stillPath, ImageSize.W342),
                                    seed = "${state.showTitle} ${episode.season}x${episode.number}",
                                    positionMinutes = positionMinutes,
                                    modifier = Modifier
                                        .focusRequester(focus.row(episode.id))
                                        .then(if (episode.id == (playingId ?: episodes.firstOrNull()?.id)) Modifier.focusRequester(focus.panel) else Modifier),
                                    onClick = { onPlay(episode) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One episode: its still with progress and a check, its number and title, Playing, and how long it is. */
@Composable
private fun EpisodeRow(
    episode: EpisodeDetail,
    playing: Boolean,
    still: String?,
    seed: String,
    positionMinutes: () -> Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runtime = episode.runtimeMin?.let { "$it min" }
    val line = when {
        playing -> listOfNotNull(runtime, "${positionMinutes()} min in").joinToString(" · ")
        episode.watched -> listOfNotNull(runtime, "watched").joinToString(" · ")
        else -> runtime.orEmpty()
    }
    FocusableBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Episode ${episode.number}, ${episode.title}" + (if (playing) ", playing now" else "") + (if (line.isNotEmpty()) ", $line" else "")
        },
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.ROW,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .background(if (focused) TvPalette.Lift else Color.Transparent, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(192.dp, 108.dp)) {
                Artwork(still, seed = seed, contentDescription = null, modifier = Modifier.size(192.dp, 108.dp), shape = RoundedCornerShape(10.dp))
                if (episode.watched && !playing) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(6.dp), size = 30.dp)
                val pct = episode.partway?.let { it.positionMs.toFloat() / it.durationMs }
                if (pct != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(8.dp).background(Color.Black.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).height(8.dp).background(WmmColors.Accent))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${episode.number}. ${episode.title}", style = TvType.BodyStrong, color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (playing) TvTag("Playing", gold = true)
                    if (line.isNotEmpty()) Text(line, style = TvType.Caption, color = TvPalette.Muted, maxLines = 1)
                }
            }
        }
    }
}
