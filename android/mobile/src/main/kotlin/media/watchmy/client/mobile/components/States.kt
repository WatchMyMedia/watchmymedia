package media.watchmy.client.mobile.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/** A gold spinner in the middle of the space it has. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxSize().then(modifier), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(36.dp), color = WmmColors.Accent, strokeWidth = 3.dp, trackColor = WmmColors.Line2)
    }
}

/** A failed load: what went wrong and Try again. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = "Couldn't load this") {
    MessageState(title, message, "Try again", onRetry, modifier)
}

/** Nothing to show: a title, a line of help and an optional action. */
@Composable
fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    MessageState(title, body, action, onAction, modifier)
}

@Composable
private fun MessageState(title: String, body: String?, action: String?, onAction: () -> Unit, modifier: Modifier) {
    Box(Modifier.fillMaxSize().then(modifier).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), textAlign = TextAlign.Center)
            if (body != null) {
                Text(body, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextMuted), textAlign = TextAlign.Center)
            }
            if (action != null) {
                TonalButton(action, onAction, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A small inline failure with Try again, for part of a screen (a season, the next page). */
@Composable
fun InlineError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted), textAlign = TextAlign.Center)
        TonalButton("Try again", onRetry)
    }
}

/** Shows [message] as a snackbar once, then calls [onShown]. */
@Composable
fun MessageHost(message: String?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            onShown()
        }
    }
    SnackbarHost(host, modifier) {
        Snackbar(it, containerColor = WmmColors.Surface3, contentColor = WmmColors.Text)
    }
}
