package media.watchmy.client.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.watchmy.client.mobile.components.CastButton
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.behindRecording
import media.watchmy.client.model.recordingEdgeMs
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.countdownSeconds
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.tracks
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** What the touch controls can ask of the player screen. */
internal class ControlsActions(
    val onBack: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onScrubbing: (Boolean) -> Unit,
    val onPicker: (PickerTab) -> Unit,
    val onPictureInPicture: (() -> Unit)?,
    val onToggleRotationLock: () -> Unit,
    val onPlayNext: () -> Unit,
    val onEpisodes: (() -> Unit)? = null,
)

/**
 * The touch controls (PlayerControls.dc.html): back and the title, the
 * stream chip, picture in picture and rotation lock along the top; replay,
 * play/pause and forward in the middle; the scrubber; and Audio, Subtitles,
 * Quality and Next episode along the bottom. Only the scrubber reads
 * [progress], so the rest stays put while the position moves. While a drag
 * shows a seek thumbnail the middle buttons make way for it (PlayerScrub).
 */
@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    progress: () -> PlaybackProgress,
    rotationLocked: Boolean,
    actions: ControlsActions,
    remote: Boolean = false,
    portrait: Boolean = false,
    rotationLockWorks: Boolean = true,
) {
    var scrubbing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )
        // Clear of the system bars, which show while this is a remote (and take no room while hidden).
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            // What each part shows, rather than the whole state, so the countdowns skip them.
            TopBar(state.title, state.subtitle, streamChip(state.tracks), rotationLocked, actions, remote, rotationLockWorks, state.canCast)
            if (!(scrubbing && state.trickplay != null)) Transport(state.status, actions, Modifier.align(Alignment.Center))
            ScrubberRow(
                progress = progress,
                segments = state.segments,
                trickplay = state.trickplay,
                onSeek = actions.onSeekTo,
                onScrubbing = {
                    scrubbing = it
                    actions.onScrubbing(it)
                },
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = if (portrait) 12.dp else if (LocalConfiguration.current.smallestScreenWidthDp >= 600) 64.dp else 56.dp),
            )
            if (state.growing != null) GrowingRecording(progress, actions.onSeekTo, Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 16.dp))
            if (!portrait) BottomBar(state, actions)
        }
    }
}

/** A recording still recording: the Recording label, and Go to now while the play is more than 10 s behind what has recorded. */
@Composable
private fun GrowingRecording(progress: () -> PlaybackProgress, onSeekTo: (Long) -> Unit, modifier: Modifier = Modifier) {
    val behind by remember(progress) { derivedStateOf { progress().behindRecording() } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("● Recording", color = WmmColors.Error, fontWeight = FontWeight.Bold)
        if (behind) androidx.compose.material3.TextButton(onClick = { onSeekTo(progress().recordingEdgeMs()) }) { Text("Go to now", color = WmmColors.Accent) }
    }
}

@Composable
private fun BoxScope.TopBar(title: String, subtitle: String?, chip: String?, rotationLocked: Boolean, actions: ControlsActions, remote: Boolean, rotationLockWorks: Boolean, canCast: Boolean) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RoundButton(WmmIcons.Back, "Back", actions.onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PlayerType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = PlayerType.Subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        chip?.let { StreamChip(it, Modifier.padding(end = 6.dp)) }
        actions.onPictureInPicture?.let { RoundButton(PlayerIcons.PictureInPicture, "Picture in picture", it, iconSize = 22.dp) }
        if (canCast) CastButton(overArt = true)
        // A remote follows the phone's own rotation; a large screen on Android 16 turns as the system says.
        if (!remote && rotationLockWorks) {
            RoundButton(
                PlayerIcons.Lock,
                if (rotationLocked) "Unlock rotation" else "Lock rotation",
                actions.onToggleRotationLock,
                iconSize = 22.dp,
                tint = if (rotationLocked) WmmColors.Accent else WmmColors.Text,
            )
        }
    }
}

@Composable
private fun StreamChip(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(28.dp).background(Color.Black.copy(alpha = 0.45f), shape).border(1.dp, Color.White.copy(alpha = 0.14f), shape).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PlayerType.Chip, maxLines = 1)
    }
}

@Composable
private fun Transport(status: PlayerStatus, actions: ControlsActions, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        RoundButton(PlayerIcons.Replay, "Back 10 seconds", { actions.onSeekBy(-DoubleTapSeek.STEP_MS) }, size = 56.dp, iconSize = 30.dp, background = Color.Black.copy(alpha = 0.35f))
        Box(contentAlignment = Alignment.Center) {
            val playing = status == PlayerStatus.Playing || status == PlayerStatus.Buffering || status == PlayerStatus.Loading
            RoundButton(
                if (playing) PlayerIcons.Pause else WmmIcons.Play,
                if (playing) "Pause" else "Play",
                actions.onTogglePlay,
                size = 72.dp,
                iconSize = 34.dp,
                background = WmmColors.Text,
                tint = WmmColors.Ground,
            )
            if (status == PlayerStatus.Loading || status == PlayerStatus.Buffering) {
                CircularProgressIndicator(Modifier.size(84.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = Color.Transparent)
            }
        }
        RoundButton(PlayerIcons.Forward, "Forward 10 seconds", { actions.onSeekBy(DoubleTapSeek.STEP_MS) }, size = 56.dp, iconSize = 30.dp, background = Color.Black.copy(alpha = 0.35f))
    }
}

@Composable
private fun BoxScope.BottomBar(state: PlayerUiState, actions: ControlsActions) {
    // A phone held upright (the remote while casting) has no room for the
    // pickers' labels: icons there, labels from 600 dp.
    val width = LocalConfiguration.current.screenWidthDp
    val narrow = width < 600
    val showDetail = actions.onEpisodes == null || width >= 1000
    Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (narrow) {
            RoundButton(PlayerIcons.Audio, "Audio: ${currentAudio(state.tracks) ?: "default"}", { actions.onPicker(PickerTab.Audio) }, size = 44.dp, iconSize = 22.dp)
            RoundButton(PlayerIcons.Captions, "Subtitles: ${currentSubtitle(state.tracks)}", { actions.onPicker(PickerTab.Subtitles) }, size = 44.dp, iconSize = 22.dp)
            if (!state.isOffline) RoundButton(PlayerIcons.Quality, "Quality: ${qualitySummary(state.tracks)}", { actions.onPicker(PickerTab.Quality) }, size = 44.dp, iconSize = 22.dp)
        } else {
            BarButton(PlayerIcons.Audio, "Audio", if (showDetail) currentAudio(state.tracks) else null) { actions.onPicker(PickerTab.Audio) }
            BarButton(PlayerIcons.Captions, "Subtitles", if (showDetail) currentSubtitle(state.tracks) else null) { actions.onPicker(PickerTab.Subtitles) }
            if (!state.isOffline) BarButton(PlayerIcons.Quality, "Quality", if (showDetail) qualitySummary(state.tracks) else null) { actions.onPicker(PickerTab.Quality) }
        }
        Spacer(Modifier.weight(1f))
        actions.onEpisodes?.let { BarButton(WmmIcons.Tv, "Episodes", null, it) }
        state.nextEpisode?.let { next ->
            val code = episodeCode(next.season, next.episode)
            val countdown = state.upNext?.countdownMs
            BarButton(PlayerIcons.SkipNext, "Next episode", if (actions.onEpisodes != null && width in 600..999) null else countdown?.let { "$code · in ${countdownSeconds(it)}" } ?: code, actions.onPlayNext)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.height(if (LocalConfiguration.current.smallestScreenWidthDp >= 600) 48.dp else 40.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(20.dp))
        Text(label, style = PlayerType.Button, maxLines = 1)
        detail?.let { Text(it, style = PlayerType.ButtonDetail, maxLines = 1) }
    }
}

/** A round icon button: 48 dp and clear by default, as along the top of the controls. */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    background: Color = Color.Transparent,
    tint: Color = WmmColors.Text,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background).clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}
