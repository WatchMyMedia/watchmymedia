package media.watchmy.client.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

private val TvColors = darkColorScheme(
    primary = WmmColors.Brand,
    onPrimary = WmmColors.Text,
    secondary = WmmColors.Accent,
    onSecondary = WmmColors.OnAccent,
    background = WmmColors.Ground,
    onBackground = WmmColors.Text,
    surface = WmmColors.Surface2,
    onSurface = WmmColors.Text,
    surfaceVariant = WmmColors.Surface3,
    onSurfaceVariant = WmmColors.TextMuted,
    border = WmmColors.Accent,
    error = WmmColors.Error,
)

private fun inter(size: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.25).toInt().sp, letterSpacing = (size * tracking).sp)

/** TV type scale: the Android TV mockups' sizes, in mockup px (see [TvCanvasWidth]). */
val TvTypography = Typography(
    displayLarge = inter(72, FontWeight.ExtraBold, -0.025),
    displayMedium = inter(60, FontWeight.Bold, -0.02),
    displaySmall = inter(46, FontWeight.ExtraBold, -0.02),
    headlineMedium = inter(30, FontWeight.SemiBold),
    headlineSmall = inter(28, FontWeight.SemiBold),
    titleLarge = inter(26, FontWeight.SemiBold),
    titleMedium = inter(22, FontWeight.SemiBold),
    titleSmall = inter(20, FontWeight.SemiBold),
    bodyLarge = inter(23, FontWeight.Normal),
    bodyMedium = inter(20, FontWeight.Normal),
    bodySmall = inter(17, FontWeight.Normal),
    labelLarge = inter(20, FontWeight.SemiBold, 0.14),
    labelMedium = inter(17, FontWeight.SemiBold),
    labelSmall = inter(14, FontWeight.Bold, 0.05),
)

/**
 * The width of the TV mockups' canvas. A 1080p TV is 1920×1080 px but only
 * 960×540 dp (xhdpi), so the mockups' px are not dp; [TvTheme] instead sets
 * the density so the screen is [TvCanvasWidth] dp wide, and every size in
 * the TV UI is the mockups' px written as dp (or sp). The same layout then
 * fills a 1080p TV, a 4K one and a 16:10 tablet emulator.
 */
const val TvCanvasWidth = 1920f

/**
 * The density that makes the window [TvCanvasWidth] dp wide, keeping the
 * viewer's font scale. Overlays in their own window (dialogs, popups) start
 * from the system density again, so they provide this one inside.
 */
@Composable
fun rememberTvDensity(): Density {
    val system = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    return remember(system, widthPx) {
        if (widthPx <= 0) system else Density(density = widthPx / TvCanvasWidth, fontScale = system.fontScale)
    }
}

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides rememberTvDensity()) {
        MaterialTheme(colorScheme = TvColors, typography = TvTypography, content = content)
    }
}
