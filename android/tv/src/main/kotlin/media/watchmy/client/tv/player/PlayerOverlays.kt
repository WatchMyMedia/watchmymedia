package media.watchmy.client.tv.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.cardMeta
import media.watchmy.client.model.cardTitle
import media.watchmy.client.model.formatClock
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.fractionOf
import media.watchmy.client.model.upNextButton
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.WmmIcons

/**
 * PlayerControls' Skip button ("Skip Intro"). Focused it turns white with
 * the gold ring; in auto-delay mode a fill runs across it while the
 * controller counts its 3 s down.
 */
@Composable
internal fun SkipButton(prompt: SkipPrompt, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "skip scale")
    val fill by animateFloatAsState(prompt.autoProgress ?: 0f, tween(250, easing = LinearEasing), label = "skip fill")
    val shape = RoundedCornerShape(14.dp)
    val fg = if (focused) WmmColors.Ground else WmmColors.Text
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(focused, 14.dp)
            .clip(shape)
            .background(if (focused) WmmColors.Text else WmmColors.Ground.copy(alpha = 0.86f))
            .drawBehind {
                if (prompt.autoProgress != null) {
                    drawRect(WmmColors.Accent.copy(alpha = if (focused) 0.55f else 0.45f), size = Size(size.width * fill, size.height))
                }
            }
            .border(1.dp, if (focused) WmmColors.Text else Color.White.copy(alpha = 0.35f), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .height(62.dp)
            .padding(horizontal = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prompt.label, style = playerText(23, FontWeight.SemiBold, fg))
        Icon(PlayerIcons.SkipAhead, null, Modifier.size(24.dp), tint = fg)
    }
}

/**
 * PlayerNext's card: the next episode's still, title, and Play now (with the
 * countdown) or Watch credits. 480 wide rather than the artboard's 440, where
 * "Next episode in 10" and "Watch credits" do not fit side by side.
 */
@Composable
internal fun NextEpisodeCard(
    prompt: UpNextPrompt,
    stillUrl: String?,
    onPlayNow: () -> Unit,
    onWatchCredits: () -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val next = prompt.next
    val progress by animateFloatAsState(prompt.countdownProgress, tween(250, easing = LinearEasing), label = "countdown")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .width(480.dp)
            .shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(WmmColors.Ground.copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Artwork(
            url = stillUrl,
            seed = "${next.showTitle} ${next.title}",
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(270.dp),
            overlay = {
                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    Badge("Next episode", Modifier.align(Alignment.TopStart), fontSize = 14.sp, horizontalPadding = 10.dp, verticalPadding = 5.dp)
                    next.runtimeMs?.let {
                        Badge(formatDuration(it), Modifier.align(Alignment.BottomEnd), fontSize = 14.sp, horizontalPadding = 10.dp, verticalPadding = 5.dp, uppercase = false)
                    }
                }
            },
        )
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(next.cardTitle, style = playerText(24, FontWeight.Bold), maxLines = 1)
            Text(next.cardMeta, style = playerText(18, color = WmmColors.TextMuted), maxLines = 1)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardButton(
                    upNextButton(prompt.countdownMs),
                    onPlayNow,
                    Modifier.weight(1f).focusRequester(playFocus),
                    icon = WmmIcons.Play,
                    progress = progress,
                )
                CardButton("Watch credits", onWatchCredits, filled = false)
            }
        }
    }
}

/** PlayerNext's small progress line at the bottom left while the card is up; it reads [position] itself. */
@Composable
internal fun MiniProgress(position: () -> Long, durationMs: Long, modifier: Modifier = Modifier) {
    val positionMs = position()
    val style = playerText(19, color = WmmColors.TextMuted, tabular = true)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(positionMs), style = style)
        Box(Modifier.width(420.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
            Box(
                Modifier.fillMaxWidth(fractionOf(positionMs, durationMs)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(WmmColors.Accent),
            )
        }
        Text(formatClock(durationMs), style = style)
    }
}

/**
 * A centred question over the dimmed video: Still watching, an admin's stop
 * message, a fatal error. [primaryFocus] goes on the first button.
 */
@Composable
internal fun PromptDialog(
    title: String,
    message: String?,
    primary: String,
    onPrimary: () -> Unit,
    primaryFocus: FocusRequester,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(760.dp)
                .shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(WmmColors.Surface)
                .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = playerText(32, FontWeight.Bold, tracking = -0.01))
            if (message != null) Text(message, style = playerText(21, color = WmmColors.TextSoft))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardButton(primary, onPrimary, Modifier.focusRequester(primaryFocus))
                if (secondary != null) CardButton(secondary, onSecondary, filled = false)
            }
        }
    }
}

/** Loading and buffering: a gold arc turning in the middle of the screen. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "spinner turn",
    )
    Canvas(modifier.size(72.dp)) {
        val w = 6.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        drawArc(WmmColors.Accent, turn, 90f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A non-fatal problem (a track change failed, say): a line at the top that does not take focus. */
@Composable
internal fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(WmmColors.Surface.copy(alpha = 0.94f))
            .border(1.dp, WmmColors.Error.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        style = playerText(19, FontWeight.Medium, WmmColors.Text),
    )
}

/**
 * Automatic quality's note (PlayerAuto.dc.html): "Lowered to 720p for your
 * connection." It shows for a few seconds and never takes focus.
 */
@Composable
internal fun AutoNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(WmmColors.Ground.copy(alpha = 0.86f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PlayerIcons.Signal, null, Modifier.size(28.dp), tint = WmmColors.Accent)
        Text(text, style = playerText(21, FontWeight.Medium, WmmColors.Text))
    }
}

/** The picker's selected mark: a gold disc with a dark check. */
@Composable
internal fun SelectedMark() {
    Box(Modifier.size(30.dp).background(WmmColors.Accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(PlayerIcons.Check, "Selected", Modifier.size(18.dp), tint = WmmColors.OnAccent)
    }
}
