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

/** A failed load: what went wrong and a focused Try again. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = "Something went wrong") {
    val focus = remember { FocusRequester() }
    MessageState(title, message, modifier) {
        TvButton("Try again", onRetry, Modifier.focusRequester(focus), style = TvButtonStyle.Primary)
    }
    InitialFocus(focus)
}

/** Nothing to show, with an optional action. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    MessageState(title, message, modifier, action)
}

@Composable
private fun MessageState(title: String, message: String, modifier: Modifier, action: (@Composable () -> Unit)?) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = inter(34.sp, FontWeight.Bold, tracking = -0.02f), color = WmmColors.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(message, style = inter(22.sp, lineHeight = 32.sp), color = WmmColors.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.dp))
        if (action != null) {
            Spacer(Modifier.height(34.dp))
            action()
        }
    }
}

/** A short confirmation pill at the bottom of the screen, gone after a few seconds. */
@Composable
fun Notice(text: String?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    if (text == null) return
    LaunchedEffect(text) {
        delay(2600)
        onShown()
    }
    Box(modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            modifier = Modifier
                .background(WmmColors.Surface3, RoundedCornerShape(30.dp))
                .border(1.dp, WmmColors.Line3, RoundedCornerShape(30.dp))
                .padding(horizontal = 28.dp, vertical = 16.dp),
            style = inter(20.sp, FontWeight.SemiBold),
            color = WmmColors.Text,
        )
    }
}
