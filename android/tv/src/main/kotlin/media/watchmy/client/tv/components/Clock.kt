package media.watchmy.client.tv.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.rememberLiveClockFormat

/**
 * The wall clock, moving on at each minute (and once [stepMs] at most, for
 * a countdown that wants more). Screens read "ends 8:41 PM" against it.
 */
@Composable
fun rememberNow(stepMs: Long = 60_000L): State<Long> = produceState(System.currentTimeMillis(), stepMs) {
    while (true) {
        val now = System.currentTimeMillis()
        value = now
        // Wake at the next minute (or step), so the clock turns over with the TV's own.
        delay((stepMs - now % stepMs).coerceAtLeast(250L))
    }
}

/**
 * The time, top right inside the safe area, on Home, the title pages and
 * the players: what "ends 8:41 PM" is read against. TalkBack skips it.
 */
@Composable
fun TvClock(modifier: Modifier = Modifier) {
    val now by rememberNow()
    val format = rememberLiveClockFormat()
    Box(modifier.fillMaxSize().padding(top = SafeY, end = SafeX), contentAlignment = Alignment.TopEnd) {
        Text(
            format.time(now),
            style = TvType.Second.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = WmmColors.TextSoft,
            textAlign = TextAlign.End,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** "ends 8:41 PM": when something [remainingMs] long ends if it plays from [nowMs]. */
fun endsAt(remainingMs: Long, nowMs: Long, format: LiveClockFormat): String = "ends ${format.time(nowMs + remainingMs.coerceAtLeast(0L))}"

/** "would end at 10:10 PM": the same, for something not yet started. */
fun wouldEndAt(runtimeMs: Long, nowMs: Long, format: LiveClockFormat): String = "would end at ${format.time(nowMs + runtimeMs.coerceAtLeast(0L))}"

/** "29 min left", "1 h 12 min left": time left, rounded up to the minute so it never reads 0 while something plays. */
fun timeLeft(remainingMs: Long): String {
    val minutes = ((remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
    return when {
        minutes < 60 -> "$minutes min left"
        minutes % 60 == 0 -> "${minutes / 60} h left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }
}
