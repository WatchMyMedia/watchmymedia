package media.watchmy.client.ui.components

import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import android.view.accessibility.CaptioningManager.CaptionStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The system's caption settings (Accessibility › Caption preferences) as a
 * [CaptionLook] for [SubtitleCues]: the text size, and the style's colours,
 * edge and typeface, kept up to date while they change. Followed only while
 * captions are on there, as Media3's own subtitle view does; the style the
 * system starts with (white on black) keeps the players' look.
 */
@Composable
fun rememberSystemCaptionLook(): CaptionLook {
    val context = LocalContext.current
    val captioning = remember(context) { context.getSystemService(CaptioningManager::class.java) } ?: return CaptionLook.Default
    var look by remember(captioning) { mutableStateOf(captioning.look()) }
    DisposableEffect(captioning) {
        val listener = object : CaptioningManager.CaptioningChangeListener() {
            override fun onEnabledChanged(enabled: Boolean) {
                look = captioning.look()
            }

            override fun onUserStyleChanged(userStyle: CaptionStyle) {
                look = captioning.look()
            }

            override fun onFontScaleChanged(fontScale: Float) {
                look = captioning.look()
            }
        }
        captioning.addCaptioningChangeListener(listener)
        onDispose { captioning.removeCaptioningChangeListener(listener) }
    }
    return look
}

private fun CaptioningManager.look() = captionLook(isEnabled, fontScale, userStyle.parts(), SYSTEM_STANDARD)

/** A caption style's parts, colours as ARGB; null where the style leaves them to the app. */
internal data class CaptionStyleParts(
    val foreground: Int? = null,
    val background: Int? = null,
    val window: Int? = null,
    val edgeType: Int = CaptionStyle.EDGE_TYPE_NONE,
    val edgeColor: Int? = null,
    val typeface: Typeface? = null,
)

/**
 * The style people have without choosing one: the system's first preset,
 * white on black with no edge (CaptionStyle.DEFAULT, which apps can't read).
 * Its window colour is clear.
 */
internal val SYSTEM_STANDARD = CaptionStyleParts(
    foreground = 0xFFFFFFFF.toInt(),
    background = 0xFF000000.toInt(),
    window = 0x000000FF,
    edgeType = CaptionStyle.EDGE_TYPE_NONE,
    edgeColor = 0xFF000000.toInt(),
)

private fun CaptionStyle.parts() = CaptionStyleParts(
    foreground = foregroundColor.takeIf { hasForegroundColor() },
    background = backgroundColor.takeIf { hasBackgroundColor() },
    window = windowColor.takeIf { hasWindowColor() },
    edgeType = if (hasEdgeType()) edgeType else CaptionStyle.EDGE_TYPE_NONE,
    edgeColor = edgeColor.takeIf { hasEdgeColor() },
    typeface = typeface,
)

/**
 * The look for the system's caption settings: the players' own while
 * captions are off ([enabled] false); at [fontScale] once they're on; and
 * with [style]'s colours, edge and typeface unless it is the system's
 * [standard] one, which people get without choosing a style.
 */
internal fun captionLook(enabled: Boolean, fontScale: Float, style: CaptionStyleParts, standard: CaptionStyleParts): CaptionLook {
    if (!enabled) return CaptionLook.Default
    val sized = CaptionLook.Default.copy(scale = fontScale.takeIf { it > 0f } ?: 1f)
    if (style == standard) return sized
    return sized.copy(
        text = style.foreground?.let { Color(it) } ?: sized.text,
        plate = style.background?.let { Color(it) } ?: sized.plate,
        window = style.window?.let { Color(it) } ?: sized.window,
        edge = when (style.edgeType) {
            CaptionStyle.EDGE_TYPE_OUTLINE -> CaptionEdge.Outline
            CaptionStyle.EDGE_TYPE_DROP_SHADOW -> CaptionEdge.DropShadow
            CaptionStyle.EDGE_TYPE_RAISED -> CaptionEdge.Raised
            CaptionStyle.EDGE_TYPE_DEPRESSED -> CaptionEdge.Depressed
            else -> CaptionEdge.None
        },
        edgeColor = style.edgeColor?.let { Color(it) } ?: sized.edgeColor,
        typeface = style.typeface,
    )
}
