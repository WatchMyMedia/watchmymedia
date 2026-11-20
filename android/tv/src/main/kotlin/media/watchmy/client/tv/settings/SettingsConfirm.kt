package media.watchmy.client.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * What a confirmation's buttons say: [keep] changes nothing, [go] does it,
 * [busy] while it is on its way.
 */
internal data class ConfirmWords(val title: String, val keep: String, val go: String, val busy: String)

/**
 * A confirmation: a centred overlay
 * with the question, what stays and what goes ([content]), and two buttons.
 * Focus starts on the one that changes nothing, so a stray OK does no harm.
 * [working]: both buttons are off and [ConfirmWords.busy] says why; after a
 * [failure] its line shows and the button says Try again. Back, like
 * [ConfirmWords.keep], calls [onKeep] whatever the state: a TV's Back is
 * never held up.
 */
@Composable
internal fun ConfirmDialog(
    words: ConfirmWords,
    working: Boolean,
    failure: String?,
    onKeep: () -> Unit,
    onGo: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val keep = remember { FocusRequester() }
    TvDialog(onKeep) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(1000.dp)
                    .background(WmmColors.Surface2, RoundedCornerShape(18.dp))
                    .border(1.dp, WmmColors.Line2, RoundedCornerShape(18.dp))
                    .padding(start = 56.dp, end = 56.dp, top = 48.dp, bottom = 52.dp)
                    .semantics { paneTitle = words.title },
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(words.title, style = inter(44.sp, FontWeight.ExtraBold, lineHeight = 48.sp, tracking = -0.02f), color = WmmColors.Text)
                content()
                if (failure != null) {
                    Row(
                        Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(WmmIcons.Warning, contentDescription = null, tint = FailRed, modifier = Modifier.size(26.dp))
                        Text(failure, style = inter(22.sp, lineHeight = 33.sp), color = FailRed)
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton(words.keep, onKeep, Modifier.focusRequester(keep), enabled = !working)
                    TvButton(
                        when {
                            working -> words.busy
                            failure != null -> "Try again"
                            else -> words.go
                        },
                        onGo,
                        style = TvButtonStyle.Danger,
                        enabled = !working,
                    )
                }
            }
        }
        InitialFocus(keep)
    }
}

/** A part of a confirmation: what [label] ("Stays", "Deleted") and the sentence under it. */
@Composable
internal fun ConfirmPart(label: String?, body: String, modifier: Modifier = Modifier, color: Color = TextBright) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label.uppercase(), style = inter(15.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted)
        Text(body, style = inter(22.sp, lineHeight = 33.sp), color = color)
    }
}
