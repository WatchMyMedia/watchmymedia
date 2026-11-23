package media.watchmy.client.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.ui.WmmColors

/** Requests focus on [requester] once this enters the composition, after the first layout. */
@Composable
fun InitialFocus(requester: FocusRequester) {
    LaunchedEffect(requester) {
        withFrameNanos { }
        requester.requestFocus()
    }
}

/** A gold spinner, shown after a short delay so quick loads don't flash it. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        visible = true
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator(color = WmmColors.Accent, trackColor = WmmColors.Line2, strokeWidth = 5.dp, modifier = Modifier.size(64.dp))
    }
}

/** A failed load: what went wrong and a focused Try again, so the remote always has somewhere to be. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = "Something went wrong", more: (@Composable () -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    MessageState(title, message, modifier) {
        TvButton("Try again", onRetry, Modifier.focusRequester(focus), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
        more?.invoke()
    }
    InitialFocus(focus)
}

/** Nothing to show, with an optional action (its first button should take focus). */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    MessageState(title, message, modifier, action)
}

/**
 * A screen that can't show its content: a 48 sp title and two lines that
 * say why and what to do, at the content's left edge and a third of the way
 * down, then its buttons in a row ([actions]).
 */
@Composable
private fun MessageState(title: String, message: String, modifier: Modifier, actions: (@Composable () -> Unit)?) {
    Column(modifier.fillMaxSize().padding(start = ContentStart, end = ContentEnd, top = MessageTop)) {
        Text(title, style = TvType.Title, color = WmmColors.Text, modifier = Modifier.widthIn(max = 1200.dp))
        Spacer(Modifier.height(20.dp))
        Text(message, style = TvType.Body, color = WmmColors.TextSoft, modifier = Modifier.widthIn(max = 820.dp))
        if (actions != null) {
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/** Where a full-screen state's title starts: about a third of the way down, so it reads as the screen's subject. */
private val MessageTop = 304.dp

/** A short confirmation pill at the bottom of the screen, gone after a few seconds. */
@Composable
fun Notice(text: String?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    if (text == null) return
    LaunchedEffect(text) {
        delay(2600)
        onShown()
    }
    Box(modifier.fillMaxSize().padding(bottom = SafeY + 24.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            modifier = Modifier
                .background(WmmColors.Surface3, RoundedCornerShape(30.dp))
                .border(1.dp, WmmColors.Line3, RoundedCornerShape(30.dp))
                .padding(horizontal = 32.dp, vertical = 18.dp),
            style = TvType.Second.copy(fontWeight = FontWeight.SemiBold),
            color = WmmColors.Text,
        )
    }
}
