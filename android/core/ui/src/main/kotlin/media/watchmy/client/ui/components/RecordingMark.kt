package media.watchmy.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import media.watchmy.client.model.RecordingGlyph
import media.watchmy.client.model.RecordingMark

/** The Live tag's red, for a programme that will record or is recording (PLAN-LIVE-TV-DVR.md §3.1, Colours). */
private val RecordRed = Color(0xFFF04438)
private val RecTag = Color(0xFFB42318)

/** A reminder no longer listed's orange, for one that won't record because of a clash. */
private val ClashOrange = Color(0xFFFDBA74)

/**
 * The guide's recording mark before a programme's title: a red dot (will
 * record), a ringed dot (a series will), REC (recording now) or a hollow
 * orange dot (won't record: a clash). Nothing for any other state. Words go
 * with it in the accessible name, never colour alone.
 */
@Composable
fun RecordingMarkBadge(mark: RecordingMark, modifier: Modifier = Modifier, size: Dp = 10.dp, textSize: TextUnit = 10.sp) {
    val glyph = mark.glyph ?: return
    val described = modifier.semantics { contentDescription = mark.label }
    when (glyph) {
        RecordingGlyph.Rec -> Row(
            described.background(RecTag, RoundedCornerShape(4.dp)).height(size + 6.dp).padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(size / 2).background(Color.White, CircleShape))
            Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = textSize, letterSpacing = .06.em)
        }
        else -> Canvas(described.size(size)) {
            val radius = this.size.minDimension / 2
            val stroke = (radius / 3.5f).coerceAtLeast(1.5f)
            when (glyph) {
                RecordingGlyph.Dot -> drawCircle(RecordRed, radius = radius * .85f)
                RecordingGlyph.Ringed -> {
                    drawCircle(RecordRed, radius = radius * .5f)
                    drawCircle(RecordRed, radius = radius - stroke / 2, style = Stroke(stroke))
                }
                else -> drawCircle(ClashOrange, radius = radius * .8f - stroke / 2, style = Stroke(stroke))
            }
        }
    }
}
