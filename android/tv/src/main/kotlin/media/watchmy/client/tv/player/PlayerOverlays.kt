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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.cardTitle
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.upNextButton
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvClock
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.rememberNow
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.tv.components.wouldEndAt
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat

/**
 * The Skip button ("Skip intro"), as the TV's other buttons; it takes focus
 * when it appears. In auto-delay mode a gold wash runs across it while the
 * controller counts its 3 s down.
 */
@Composable
internal fun SkipButton(prompt: SkipPrompt, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(prompt.autoProgress ?: 0f, tween(250, easing = LinearEasing), label = "skip fill")
    ControlButton(onClick, modifier, icon = PlayerIcons.SkipAhead, text = sentenceCase(prompt.label), iconAfter = true, fill = { fill })
}

/**
 * Up next, at the credits: the next episode's still, the countdown, which
 * episode, how long it is and when it would end if it played now, with Play
 * now (focused) and Watch credits. It sits at the foot of the screen, inside
 * the safe area.
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
    val shape = RoundedCornerShape(24.dp)
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    Row(
        modifier
            .width(920.dp)
            .shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(WmmColors.Surface.copy(alpha = 0.96f))
            .border(2.dp, TvPalette.Line, shape)
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Artwork(
            url = stillUrl,
            seed = "${next.showTitle} ${next.title}",
            contentDescription = null,
            modifier = Modifier.size(320.dp, 180.dp),
            shape = RoundedCornerShape(14.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                upNextKicker(prompt.countdownMs).uppercase(),
                style = TvType.Kicker,
                color = WmmColors.Accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(next.cardTitle, style = TvType.CardTitle, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(
                next.runtimeMs?.let { formatDuration(it) },
                next.runtimeMs?.let { wouldEndAt(it, now, format) },
                "Season finale".takeIf { next.seasonFinale },
            ).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Play now", onPlayNow, Modifier.focusRequester(playFocus), style = TvButtonStyle.Primary, icon = WmmIcons.Play, growFrom = TransformOrigin(0f, 0.5f))
                TvButton("Watch credits", onWatchCredits)
            }
        }
    }
}

/** "Next episode in 8" while it counts down, "Next episode" when it waits for a choice. */
internal fun upNextKicker(countdownMs: Long?): String = if (countdownMs == null) "Next episode" else upNextButton(countdownMs)

/** One of a problem screen's buttons. */
internal class ProblemButton(val text: String, val onClick: () -> Unit)

/**
 * When playing stops for a reason the viewer must answer (Still watching,
 * an admin's stop, a file that can't play, a lost server): the video dimmed
 * behind, what was playing at the top left, the clock, then a title, why,
 * a note ("Your place is kept at 23:10."), and the ways on. The first
 * button is the main one and has the focus ([firstFocus]).
 */
@Composable
internal fun ProblemScreen(
    state: PlayerUiState,
    title: String,
    body: String?,
    note: String?,
    buttons: List<ProblemButton>,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f))) {
        TitleBlock(state, Modifier.align(Alignment.TopStart).padding(start = SafeX, top = SafeY))
        TvClock()
        Column(
            Modifier.padding(start = SafeX, top = ProblemTop, end = SafeX),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = TvType.Title, color = WmmColors.Text, modifier = Modifier.widthIn(max = 1100.dp).semantics { liveRegion = LiveRegionMode.Assertive })
            if (body != null) Text(body, style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 860.dp))
            if (note != null) Text(note, style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.widthIn(max = 860.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                buttons.forEachIndexed { i, b ->
                    if (i == 0) {
                        TvButton(b.text, b.onClick, Modifier.focusRequester(firstFocus), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                    } else {
                        TvButton(b.text, b.onClick)
                    }
                }
            }
        }
    }
    LaunchedEffect(title) { firstFocus.tryFocus() }
}

/** Where a problem's title starts: about a third of the way down. */
private val ProblemTop = 330.dp

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

/** A problem that doesn't stop the play (a track change failed, say): a line at the top that does not take focus, outlined in the warning orange. */
@Composable
internal fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(WmmColors.Surface.copy(alpha = 0.94f))
            .border(2.dp, WmmColors.Warn, RoundedCornerShape(18.dp))
            .padding(horizontal = 26.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = TvType.Second,
        color = WmmColors.Text,
    )
}

/**
 * Automatic quality's note: "Lowered to 720p for your
 * connection." It shows for a few seconds and never takes focus.
 */
@Composable
internal fun AutoNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(WmmColors.Ground.copy(alpha = 0.9f))
            .border(2.dp, TvPalette.Line, RoundedCornerShape(18.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PlayerIcons.Signal, null, Modifier.size(30.dp), tint = WmmColors.Accent)
        Text(text, style = TvType.Second.copy(fontWeight = FontWeight.Medium), color = WmmColors.Text)
    }
}
