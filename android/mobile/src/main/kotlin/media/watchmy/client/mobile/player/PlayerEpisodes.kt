package media.watchmy.client.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.tracks
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork

@Composable
internal fun PortraitPlayerDetails(
    player: PlayerUiState,
    episodes: PlayerEpisodesState,
    onPicker: (PickerTab) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.background(WmmColors.Ground).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(player.title, style = PlayerType.Title)
                player.subtitle?.let { Text(it, style = PlayerType.Subtitle) }
                Text("Rotate for full screen", style = PlayerType.OptionDetail)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerTab.entries.forEach { tab ->
                    val detail = when (tab) {
                        PickerTab.Audio -> currentAudio(player.tracks) ?: "Default"
                        PickerTab.Subtitles -> currentSubtitle(player.tracks)
                        PickerTab.Quality -> qualitySummary(player.tracks)
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(WmmColors.Surface2)
                            .clickable(role = Role.Button) { onPicker(tab) }.heightIn(min = 64.dp).padding(12.dp),
                    ) {
                        Text(tab.title, style = PlayerType.OptionDetail)
                        Text(detail, style = PlayerType.Button, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        episodeItems(episodes, onPlay, onRetry)
    }
}

@Composable
internal fun PlayerEpisodesPanel(state: PlayerEpisodesState, onPlay: (PlayRequest) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    TabletPlayerPanel("Episodes", onDismiss) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            episodeItems(state, onPlay, onRetry, small = true)
        }
    }
}

private fun LazyListScope.episodeItems(state: PlayerEpisodesState, onPlay: (PlayRequest) -> Unit, onRetry: () -> Unit, small: Boolean = false) {
    when {
        state.loading -> item { CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp), color = WmmColors.Accent) }
        state.error != null -> item {
            Column(Modifier.padding(12.dp)) {
                Text(state.error, style = PlayerType.OptionDetail)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
        state.season != null -> {
            val season = state.season
            item { Text(season.season.name, style = PlayerType.Title, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            items(season.episodes, key = { it.id }) { episode ->
                val request = season.playRequest(episode)
                val playing = episode.versions.any { it.fileId == state.fileId }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (playing) WmmColors.Accent.copy(alpha = 0.12f) else WmmColors.Ground)
                        .clickable(enabled = request != null, role = Role.Button) { request?.let(onPlay) }.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        url = LocalAppServices.current.library.imageUrl(episode.stillPath, ImageSize.W342),
                        seed = episode.title, contentDescription = null,
                        modifier = Modifier.size(if (small) 112.dp else 192.dp, if (small) 63.dp else 108.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${episodeCode(episode.season, episode.number)} · ${episode.title}", style = PlayerType.Button, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            playing -> "Now playing"
                            request == null -> if (!episode.aired) "Not yet aired" else "Not in library"
                            episode.watch?.completed == true -> "Watched"
                            else -> episode.runtimeMin?.let { "$it min" } ?: "Play episode"
                        }, style = PlayerType.OptionDetail, color = if (playing) WmmColors.Accent else WmmColors.TextMuted)
                        if (!small) Text(episode.overview, style = PlayerType.OptionDetail, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
