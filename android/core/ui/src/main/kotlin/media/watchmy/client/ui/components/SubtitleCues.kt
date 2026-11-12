package media.watchmy.client.ui.components

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/**
 * Text subtitles from [player]'s cues (WebVTT and ASS, as Media3 parses
 * them), centred on a dark plate, [sidePadding] in from either edge:
 * [bottomPadding] up from the bottom, which the players lift clear of their
 * controls (the change is animated), or [topPadding] down from the top for a
 * cue placed in the top half (an ASS `\an8`, a WebVTT `line:0`). Bold,
 * italics and underlines are kept. A cue that spans HLS segments comes in
 * each of them and Media3 keeps every copy, so a line shows once. Image
 * subtitles never reach here: the server burns them in. [look] is the
 * players' own unless a setting changes it (the TV's Subtitle size, the
 * phone's caption settings). media3-ui-compose has no subtitle view yet,
 * hence this one.
 */
@Composable
fun SubtitleCues(
    player: Player,
    fontSize: TextUnit,
    sidePadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    topPadding: Dp = 24.dp,
    look: CaptionLook = CaptionLook.Default,
) {
    var cues by remember(player) { mutableStateOf<List<Cue>>(player.currentCues.cues) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val lines = cueLines(cues)
    val bottom by animateDpAsState(bottomPadding, label = "subtitle lift")
    if (lines.isEmpty()) return
    val size = fontSize * look.scale
    // The plate, the gaps and the edge scale with the text.
    val density = LocalDensity.current
    val em = with(density) { size.toDp() }
    val emPx = with(density) { size.toPx() }
    val style = TextStyle(
        fontFamily = look.typeface?.let { FontFamily(it) } ?: Inter,
        fontWeight = FontWeight.Medium,
        fontSize = size,
        lineHeight = size * 1.25,
        color = look.text,
        shadow = look.shadow(emPx),
    )
    val (top, below) = lines.partition { it.top }
    Box(modifier.fillMaxSize().padding(horizontal = sidePadding)) {
        if (top.isNotEmpty()) CueStack(top, style, look, em, emPx, Modifier.align(Alignment.TopCenter).padding(top = topPadding))
        if (below.isNotEmpty()) CueStack(below, style, look, em, emPx, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }
}

@Composable
private fun CueStack(lines: List<CueLine>, style: TextStyle, look: CaptionLook, em: Dp, emPx: Float, modifier: Modifier = Modifier) {
    val window = if (look.window.alpha > 0f) Modifier.background(look.window, RoundedCornerShape(em * 0.14f)).padding(em * 0.14f) else Modifier
    Column(
        modifier.then(window),
        verticalArrangement = Arrangement.spacedBy(em * 0.14f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (line in lines) {
            Box(Modifier.background(look.plate, RoundedCornerShape(em * 0.14f)).padding(horizontal = em * 0.32f, vertical = em * 0.09f)) {
                if (look.edge == CaptionEdge.Outline) {
                    // The outline under the text, drawn once more; it isn't read out.
                    val outline = style.copy(color = look.edgeColor, shadow = null, drawStyle = Stroke(width = emPx * 0.1f, join = StrokeJoin.Round))
                    Text(line.text, Modifier.clearAndSetSemantics {}, style = outline, textAlign = TextAlign.Center)
                }
                Text(line.text, style = style, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * How subtitles are drawn. [Default] is the players' own look: Inter Medium
 * in the brand's white on a 60% black plate, at the size the player asks
 * for. The TV scales it with its Subtitle size setting; the phone takes the
 * system's caption settings ([rememberSystemCaptionLook]).
 */
@Immutable
data class CaptionLook(
    /** Against the size the player asks for. */
    val scale: Float = 1f,
    val text: Color = WmmColors.Text,
    /** Behind each line. */
    val plate: Color = Color.Black.copy(alpha = 0.6f),
    /** Behind all the lines together; none in the players' own look. */
    val window: Color = Color.Transparent,
    val edge: CaptionEdge = CaptionEdge.None,
    val edgeColor: Color = Color.Black,
    /** Null: Inter. */
    val typeface: Typeface? = null,
) {
    companion object {
        val Default = CaptionLook()
    }
}

/** The text's edge, as the system's caption styles name them. */
enum class CaptionEdge { None, Outline, DropShadow, Raised, Depressed }

private fun CaptionLook.shadow(emPx: Float): Shadow? {
    val offset = emPx * 0.06f
    return when (edge) {
        CaptionEdge.DropShadow -> Shadow(edgeColor, Offset(offset, offset), blurRadius = emPx * 0.1f)
        CaptionEdge.Raised -> Shadow(edgeColor, Offset(offset / 2, offset / 2), blurRadius = emPx * 0.05f)
        CaptionEdge.Depressed -> Shadow(edgeColor, Offset(-offset / 2, -offset / 2), blurRadius = emPx * 0.05f)
        CaptionEdge.None, CaptionEdge.Outline -> null
    }
}

/** A cue's text as drawn, and whether it was placed in the top half. */
internal data class CueLine(val text: AnnotatedString, val top: Boolean)

/** The lines to draw for [cues]: blank ones left out, and each line once. */
internal fun cueLines(cues: List<Cue>): List<CueLine> =
    cues.mapNotNull { cue -> cue.text?.takeIf { it.isNotBlank() }?.let { CueLine(styledText(it), placedAtTop(cue)) } }
        .distinctBy { it.text.text to it.top }

/**
 * A cue placed in the top half: an ASS top alignment or position, a WebVTT
 * line counted from the top (`line:0`) or a percentage above the middle.
 * Media3 leaves [Cue.line] unset, or counts it from the bottom, for the
 * usual bottom cues.
 */
internal fun placedAtTop(cue: Cue): Boolean = when {
    cue.line == Cue.DIMEN_UNSET -> false
    cue.lineType == Cue.LINE_TYPE_NUMBER -> cue.line >= 0
    cue.lineType == Cue.LINE_TYPE_FRACTION -> if (cue.lineAnchor == Cue.ANCHOR_TYPE_END) cue.line <= 0.5f else cue.line < 0.5f
    else -> false
}

/** [text] with the styling Media3 kept that the players draw: bold, italics, underlines and strike-throughs. */
internal fun styledText(text: CharSequence): AnnotatedString {
    val plain = text.toString()
    if (text !is Spanned) return AnnotatedString(plain)
    return buildAnnotatedString {
        append(plain)
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            val style = spanStyle(span) ?: continue
            addStyle(style, text.getSpanStart(span), text.getSpanEnd(span))
        }
    }
}

private fun spanStyle(span: Any): SpanStyle? = when (span) {
    is StyleSpan -> when (span.style) {
        Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        else -> null
    }
    is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
    is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    else -> null
}
