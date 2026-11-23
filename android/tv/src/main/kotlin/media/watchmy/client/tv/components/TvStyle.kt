package media.watchmy.client.tv.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter

/**
 * The TV's type scale: seven sizes that read from a couch three metres away,
 * nothing under 22. A size is in 1080p pixels (the theme makes the window
 * 1920 dp wide), and follows the TV's own text size setting as sp.
 *
 * - [Display] 64 and [Title] 48: a screen's main title.
 * - [Heading] 34 and [CardTitle] 28: headings and the titles of rows.
 * - [Body] 26: what is read, buttons and rows in a panel.
 * - [Second] 24: second lines, facts under a title.
 * - [Caption] 22: captions, tags and key hints; the smallest there is.
 *
 * Pairing codes, channel numbers and clocks are numerals and may be larger.
 */
object TvType {
    val Display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.28).sp)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 53.sp, letterSpacing = (-0.72).sp)
    val Heading = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 41.sp)
    val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 35.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 35.sp)
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold)
    val Second = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 29.sp)

    /** The gold upper-case line over a big title ("CONTINUE WATCHING"). */
    val Kicker = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 29.sp, letterSpacing = 1.76.sp)

    /** A small upper-case heading over a group of rows in a panel ("SUBTITLES"). */
    val Group = Kicker
}

/** The TV's own surfaces and greys, beside the shared palette (`WmmColors`). */
object TvPalette {
    /** A focused control that isn't the main button lifts to this. */
    val Lift = Color(0xFF2A2A2E)

    /** A resting button's fill: dark and a little see-through, over pictures too. */
    val ButtonFill = Color(0xDB28282D)

    /** Second-line grey: lighter than the phone's, so it reads at 3 m. */
    val Muted = Color(0xFFB4B4B4)

    /** Hairlines: a panel's edge, a resting card's frame. */
    val Line = Color(0xFF3A3A3F)

    /** A tag's outline, a key's edge. */
    val Line3 = Color(0xFF55555C)

    /** The dim behind a panel. */
    val Dim = Color(0x9E000000)
}

/**
 * The safe area: text and anything focusable stays [SafeX] from the sides
 * and [SafeY] from the top and bottom, since some TVs still crop the picture.
 * Pictures run to the edges.
 */
val SafeX = 96.dp
val SafeY = 54.dp

/** The rail's icons sit at the safe edge and are this wide. */
val RailItemSize = 72.dp
