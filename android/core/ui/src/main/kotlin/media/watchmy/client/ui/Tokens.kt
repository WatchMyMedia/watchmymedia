package media.watchmy.client.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import media.watchmy.client.core.ui.R

/**
 * The brand palette (brand/README.md), as the mockups use it: gold marks
 * focus, progress, selection and badges (with dark text on it); burgundy
 * fills primary buttons and the avatar and is never text on the ground.
 */
object WmmColors {
    val Ground = Color(0xFF0F0F10)
    val Surface = Color(0xFF151517)
    val Surface2 = Color(0xFF1A1A1D)
    val Surface3 = Color(0xFF202024)
    val Line = Color(0xFF222225)
    val Line2 = Color(0xFF2A2A2E)
    val Line3 = Color(0xFF3A3A3F)
    val Brand = Color(0xFF5A1414)
    val BrandHover = Color(0xFF6E1A1A)
    /** The rim on burgundy buttons, 3.4:1 on the ground. */
    val BrandLine = Color(0xFFB04545)
    val Accent = Color(0xFFFFB612)
    val OnAccent = Color(0xFF0F0F10)
    /** Orange, so warnings never read as the gold accent ("Not in library"). */
    val Warn = Color(0xFFFB923C)
    val Text = Color(0xFFF5F5F5)
    val TextSoft = Color(0xFFD4D4D4)
    val TextMuted = Color(0xFFA3A3A3)
    val TextFaint = Color(0xFF8C8C8C)
    /**
     * The rim of a text field, search box or switch at rest: the faint text's
     * grey, 5.7:1 on the ground (WCAG asks 3:1 of a control's edge; Line3
     * was 1.7:1). Focus turns it gold.
     */
    val FieldOutline = TextFaint
    val Error = Color(0xFFF87171)
}

/** Inter, bundled (OFL; licence in assets/licenses). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/**
 * Placeholder artwork colours from the mockups: a pair per card, picked by a
 * stable hash of the title, drawn as a diagonal gradient with a soft light.
 */
internal val PlaceholderPairs = listOf(
    Color(0xFF1F3A4D) to Color(0xFF0B161D),
    Color(0xFF4A2C3A) to Color(0xFF1A0E14),
    Color(0xFF3D4A2B) to Color(0xFF131A0E),
    Color(0xFF4D3A1F) to Color(0xFF1C140A),
    Color(0xFF2B2F4A) to Color(0xFF0F111C),
    Color(0xFF4A2B2B) to Color(0xFF1A0E0E),
    Color(0xFF2F4A44) to Color(0xFF0E1B18),
    Color(0xFF4A4530) to Color(0xFF1A1810),
    Color(0xFF36304A) to Color(0xFF120F1A),
    Color(0xFF1F4A3A) to Color(0xFF0A1A13),
)
