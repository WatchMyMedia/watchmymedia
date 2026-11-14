package media.watchmy.client.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
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
 * The player's episode list (android-tv/TvPlayerEpisodes): a 760-wide panel
 * on the right with the season tabs, Specials last, and the season's
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
    var onTab by remember { mutableStateOf(false) }
    var focusedId by remember { mutableStateOf<Long?>(null) }
    Column(
        modifier
            .fillMaxHeight()
            .width(760.dp)
            .shadow(60.dp, ambientColor = Color.Black, spotColor = Color.Black)
            .background(WmmColors.Surface)
            .drawBehind { drawLine(WmmColors.Line, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Episodes", style = playerText(30, FontWeight.Bold))
            Text(state.showTitle, style = playerText(19, color = WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                items(state.seasons, key = { it.number }) { season ->
                    SeasonTab(
                        seasonName(season.number),
                        on = season.number == state.seasonNumber,
                        modifier = Modifier.focusRequester(focus.tab(season.number)).onFocusChanged {
                            if (it.isFocused) {
                                onTab = true
                                onSeason(season.number)
                            }
                        },
                        onClick = { onSeason(season.number) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val season = state.season) {
                Load.Loading -> Text("Loading…", style = playerText(19, color = WmmColors.TextMuted), modifier = Modifier.padding(18.dp))
                is Load.Failed -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(season.message, style = playerText(19, color = WmmColors.TextMuted))
                    TvButton("Try again", onRetry, Modifier.focusRequester(focus.panel), style = TvButtonStyle.Primary, size = TvButtonSize.Medium)
                }
                is Load.Ready -> {
                    val episodes = state.episodes
                    val playingId = state.playing?.id
                    val start = episodes.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
                    Column {
                        if (list.firstVisibleItemIndex > 0) {
                            val above = episodes.take(list.firstVisibleItemIndex)
                            val watched = above.all { it.watched }
                            Text(
                                "▲ Episodes ${above.first().number} to ${above.last().number}" + if (watched) ", watched" else "",
                                style = playerText(16, color = WmmColors.TextMuted),
                                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
                            )
                        }
                        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                        .then(if (episode.id == (playingId ?: episodes.firstOrNull()?.id)) Modifier.focusRequester(focus.panel) else Modifier)
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                onTab = false
                                                focusedId = episode.id
                                            }
                                        },
                                    onClick = { onPlay(episode) },
                                )
                            }
                        }
                    }
                }
            }
        }
        val ok = when {
            onTab -> "◀ ▶ changes season at once; ▼ goes to its episodes"
            focusedId != null && focusedId == state.playing?.id -> "Goes back to the video where it is"
            else -> "Plays it; your audio and subtitle choices carry over"
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Key("OK")
            Text(ok, style = playerText(17, color = WmmColors.TextSoft))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Key("Back")
            Text("Closes this panel", style = playerText(17, color = WmmColors.TextSoft))
        }
    }
}

@Composable
private fun SeasonTab(name: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.Tab
            selected = on
        },
        cornerRadius = 24.dp,
        focusedScale = 1f,
    ) {
        Box(
            Modifier
                .height(48.dp)
                .background(if (on) WmmColors.Accent else Color(0xFF1A1A1D), shape)
                .border(1.dp, if (on) WmmColors.Accent else Color(0xFF2A2A2E), shape)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(name, style = playerText(19, FontWeight.SemiBold, color = if (on) WmmColors.OnAccent else Color(0xFFE5E5E5)), maxLines = 1)
        }
    }
}

/** One episode (`.ep`): its still with progress and a check, number and title, Playing, and its line. */
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
        playing -> listOfNotNull(runtime, "${positionMinutes()} min in", "playing now").joinToString(" · ")
        episode.watched -> listOfNotNull(runtime, "watched").joinToString(" · ")
        else -> runtime.orEmpty()
    }
    FocusableBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Episode ${episode.number}, ${episode.title}" + (if (line.isNotEmpty()) ", $line" else "")
        },
        cornerRadius = 14.dp,
        focusedScale = 1f,
        ringOffset = (-4).dp,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 118.dp)
                .background(if (focused) Color(0xFF202024) else Color.Transparent, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(168.dp, 94.dp)) {
                Artwork(still, seed = seed, contentDescription = null, modifier = Modifier.size(168.dp, 94.dp), shape = RoundedCornerShape(8.dp))
                if (episode.watched && !playing) WatchedCheck(Modifier.align(Alignment.TopEnd).padding(6.dp), size = 24.dp)
                val pct = episode.partway?.let { it.positionMs.toFloat() / it.durationMs }
                if (pct != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).height(5.dp).background(WmmColors.Accent))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${episode.number}. ${episode.title}", style = playerText(21, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (playing) {
                        Text(
                            "PLAYING",
                            style = playerText(14, FontWeight.Bold, color = WmmColors.OnAccent, tracking = 0.04),
                            modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }
                if (line.isNotEmpty()) Text(line, style = playerText(17, color = WmmColors.TextMuted), maxLines = 1)
            }
        }
    }
}

/** A remote key's name in a box (`.kbd`). */
@Composable
private fun Key(name: String) {
    Box(
        Modifier
            .heightIn(min = 34.dp)
            .border(2.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = playerText(16, FontWeight.Bold))
    }
}
