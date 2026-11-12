package media.watchmy.client.mobile.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.model.cardMeta
import media.watchmy.client.model.cardTitle
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.upNextButton
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.Artwork

/** "Skip Intro": in auto-delay mode a burgundy fill runs across it before the skip happens by itself. */
@Composable
internal fun SkipButton(prompt: SkipPrompt, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(prompt.autoProgress ?: 0f, tween(250, easing = LinearEasing), label = "skipFill")
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(WmmColors.Ground.copy(alpha = 0.86f))
            .drawBehind { if (fill > 0f) drawRect(WmmColors.Brand, size = Size(size.width * fill, size.height)) }
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .clickable(role = Role.Button, onClick = onSkip)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(prompt.label, style = PlayerType.Skip)
        Icon(PlayerIcons.SkipSegment, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(18.dp))
    }
}

/**
 * Automatic quality's note (PlayerAuto.dc.html): "Lowered to 720p for your
 * connection." It shows for a few seconds, with or without the controls, and
 * takes no touch.
 */
@Composable
internal fun AutoNote(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(36.dp)
            .shadow(12.dp, shape)
            .background(WmmColors.Ground.copy(alpha = 0.86f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PlayerIcons.Signal, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
        Text(text, style = PlayerType.Note, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The Next Episode card (PlayerNext.dc.html): the still, the episode, and
 * a button that fills as the countdown runs ("Next episode in 7"), or
 * "Play now" when autoplay is off; "Watch credits" hides the card.
 */
@Composable
internal fun NextEpisodeCard(prompt: UpNextPrompt, stillUrl: String?, onPlayNow: () -> Unit, onWatchCredits: () -> Unit, modifier: Modifier = Modifier) {
    val next = prompt.next
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(320.dp)
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(WmmColors.Ground.copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Artwork(stillUrl, seed = "${next.showTitle} ${next.title}", contentDescription = null, modifier = Modifier.fillMaxSize())
            Badge("Next episode", Modifier.align(Alignment.TopStart).padding(10.dp), horizontalPadding = 7.dp)
            next.runtimeMs?.let { Badge(formatDuration(it), Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalPadding = 7.dp, uppercase = false) }
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(next.cardTitle, style = PlayerType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(next.cardMeta, style = PlayerType.CardMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 7.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountdownButton(prompt.countdownMs, prompt.countdownProgress, onPlayNow, Modifier.weight(1f))
                val ghost = RoundedCornerShape(20.dp)
                Box(
                    Modifier.height(40.dp).clip(ghost).border(1.dp, Color.White.copy(alpha = 0.28f), ghost)
                        .clickable(role = Role.Button, onClick = onWatchCredits).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Watch credits", style = PlayerType.Button.copy(color = PlayerColors.Light), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CountdownButton(countdownMs: Long?, progress: Float, onClick: () -> Unit, modifier: Modifier) {
    val fill by animateFloatAsState(progress, tween(250, easing = LinearEasing), label = "nextFill")
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .drawBehind { if (fill > 0f) drawRect(WmmColors.Brand, size = Size(size.width * fill, size.height)) }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(WmmIcons.Play, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(16.dp))
        Text(
            upNextButton(countdownMs),
            style = PlayerType.Button,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The double-tap seek feedback: a soft arc on that side with the run's total ("−20 s"). */
@Composable
internal fun SeekRipple(burst: SeekBurst, modifier: Modifier = Modifier) {
    val back = burst.zone == SeekZone.Back
    Box(modifier.fillMaxHeight().fillMaxWidth(0.4f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 1.6f
            val left = if (back) -size.width * 0.6f else 0f
            drawOval(Color.White.copy(alpha = 0.12f), Offset(left, -size.height * 0.1f), Size(w, size.height * 1.2f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (back) PlayerIcons.Replay else PlayerIcons.Forward, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(32.dp))
            val seconds = burst.totalMs / 1000
            Text(if (back) "−${-seconds} s" else "+$seconds s", style = PlayerType.Ripple)
        }
    }
}

/** What shows over the dimmed video while the picker is open (PlayerPicker.dc.html): the title, faded. */
@Composable
internal fun PickerBackdrop(title: String, subtitle: String?, paused: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PlayerType.Title.copy(color = WmmColors.Text.copy(alpha = 0.7f)), maxLines = 1)
            val line = listOfNotNull(subtitle, if (paused) "paused" else null).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = PlayerType.Subtitle.copy(color = WmmColors.TextSoft.copy(alpha = 0.7f)), maxLines = 1)
        }
    }
}

@Composable
internal fun StillWatchingDialog(title: String, subtitle: String?, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        // Back or any dismissal means someone is there.
        onDismissRequest = { onAnswer(true) },
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = WmmColors.Surface2,
        titleContentColor = WmmColors.Text,
        textContentColor = WmmColors.TextSoft,
        title = {
            HideSystemBarsInDialog()
            Text("Are you still watching?")
        },
        text = { Text(listOfNotNull(title, subtitle).joinToString(" · ")) },
        confirmButton = {
            Button(onClick = { onAnswer(true) }, colors = ButtonDefaults.buttonColors(containerColor = WmmColors.Brand, contentColor = WmmColors.Text)) {
                Text("Keep watching")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }, colors = ButtonDefaults.textButtonColors(contentColor = WmmColors.Text)) { Text("Stop") }
        },
    )
}

/** An admin stopped this play: the message, then OK leaves the player. */
@Composable
internal fun StopMessageDialog(message: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = WmmColors.Surface2,
        titleContentColor = WmmColors.Text,
        textContentColor = WmmColors.TextSoft,
        title = {
            HideSystemBarsInDialog()
            Text("Playback stopped")
        },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onOk, colors = ButtonDefaults.buttonColors(containerColor = WmmColors.Brand, contentColor = WmmColors.Text)) { Text("OK") }
        },
    )
}

/** Playback failed: the server's or player's message, with Try again and Back. */
@Composable
internal fun ErrorOverlay(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Can't play this", style = MaterialTheme.typography.titleMedium, color = WmmColors.Text)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = WmmColors.TextMuted, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, colors = ButtonDefaults.outlinedButtonColors(contentColor = WmmColors.Text)) { Text("Back") }
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = WmmColors.Brand, contentColor = WmmColors.Text)) { Text("Try again") }
            }
        }
    }
}

/** Casting stopped (the TV went away, or someone ended it there): carry on here from where the TV was, or leave. */
@Composable
internal fun ContinueHereDialog(device: String, onContinue: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLeave,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = WmmColors.Surface2,
        titleContentColor = WmmColors.Text,
        textContentColor = WmmColors.TextSoft,
        title = { Text("Casting stopped") },
        text = { Text("$device is no longer playing. Carry on watching on this phone from where it was?") },
        confirmButton = {
            Button(onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = WmmColors.Brand, contentColor = WmmColors.Text)) {
                Text("Continue on this phone")
            }
        },
        dismissButton = {
            TextButton(onClick = onLeave, colors = ButtonDefaults.textButtonColors(contentColor = WmmColors.Text)) { Text("Leave") }
        },
    )
}
