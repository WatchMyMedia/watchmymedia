package media.watchmy.client.tv.live

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.liveTVError
import media.watchmy.client.presentation.live.editLiveNumber
import media.watchmy.client.presentation.live.exactLiveChannel
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors

/** The TV boards' Go to channel keypad, also opened by a remote's first numeric key. */
@Composable
internal fun ChannelNumberDialog(
    channels: List<LiveChannel>,
    initial: String,
    onWatch: (LiveChannel) -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit = onDismiss,
    onInteraction: () -> Unit = {},
) {
    var number by remember(initial) { mutableStateOf(initial) }
    var remoteEntry by remember(initial) { mutableStateOf(initial.isNotEmpty()) }
    val first = remember { FocusRequester() }
    val match = exactLiveChannel(channels, number)
    val exact = channels.filter { it.number == number }
    val explanation = when {
        number.isEmpty() -> "Enter the channel number, including its dot."
        match != null -> "${match.number} · ${match.name}"
        exact.count { it.available } > 1 -> "More than one channel uses $number. Choose one from Channels."
        exact.isNotEmpty() -> liveTVError(exact.first().reason)
        channels.any { it.number.startsWith(number) } -> "Keep entering the full channel number."
        else -> "No channel $number. Check the number and try again."
    }
    fun edit(key: Char, remote: Boolean = false) {
        remoteEntry = remote
        onInteraction()
        number = editLiveNumber(number, key)
    }
    TvDialog(onDismiss) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val char = liveNumberKey(code)
                when {
                    code == AndroidKey.KEYCODE_BACK -> {
                        if (event.type == KeyEventType.KeyUp) onDismiss()
                        true
                    }
                    code == AndroidKey.KEYCODE_MEDIA_STOP -> {
                        if (event.type == KeyEventType.KeyUp) onStop()
                        true
                    }
                    char != null -> {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) edit(char, remote = true)
                        true
                    }
                    remoteEntry && code in listOf(AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER) -> {
                        if (event.type == KeyEventType.KeyUp) match?.let(onWatch)
                        true
                    }
                    else -> {
                        if (code in listOf(AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT)) remoteEntry = false
                        false
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(620.dp).background(WmmColors.Surface, RoundedCornerShape(24.dp)).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Go to channel", style = inter(30.sp, FontWeight.Bold), color = WmmColors.Text)
                Text(number.ifEmpty { "–" }, style = inter(44.sp, FontWeight.Bold), color = WmmColors.Text,
                    modifier = Modifier.fillMaxWidth().border(2.dp, WmmColors.Accent, RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 14.dp))
                Text(explanation, style = inter(19.sp, lineHeight = 26.sp), color = WmmColors.TextMuted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('.', '0', '\b')).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { key ->
                            FocusableBox(onClick = { edit(key) }, modifier = Modifier.weight(1f).then(if (key == '1') Modifier.focusRequester(first) else Modifier), cornerRadius = 14.dp) { focused ->
                                Box(Modifier.fillMaxWidth().height(64.dp).background(if (focused) WmmColors.Surface3 else WmmColors.Surface2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Text(if (key == '\b') "Delete" else "$key", style = inter(26.sp, FontWeight.SemiBold), color = WmmColors.Text)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TvButton("Cancel", onDismiss, size = TvButtonSize.Medium)
                    TvButton(match?.let { "Watch ${it.number}" } ?: "Watch", { match?.let(onWatch) },
                        modifier = Modifier.padding(start = 18.dp), style = TvButtonStyle.Primary, size = TvButtonSize.Medium, enabled = match != null)
                }
                Text("Select Watch to tune. A partial number never changes channel.", style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted)
            }
        }
        InitialFocus(first)
    }
}

@Composable
internal fun LiveMessageDialog(title: String, message: String, onDismiss: () -> Unit, onStop: () -> Unit = onDismiss) {
    val focus = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).onPreviewKeyEvent {
            if (it.nativeKeyEvent.keyCode == AndroidKey.KEYCODE_MEDIA_STOP) {
                if (it.type == KeyEventType.KeyUp) onStop()
                true
            } else false
        }, contentAlignment = Alignment.Center) {
            Column(Modifier.width(850.dp).background(WmmColors.Surface, RoundedCornerShape(24.dp)).padding(44.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(title, style = inter(34.sp, FontWeight.Bold), color = WmmColors.Text)
                Text(message, style = inter(23.sp, lineHeight = 32.sp), color = WmmColors.TextMuted)
                TvButton("Back to channels", onDismiss, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
            }
        }
        InitialFocus(focus)
    }
}
