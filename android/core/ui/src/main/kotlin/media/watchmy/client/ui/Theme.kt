package media.watchmy.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import media.watchmy.client.model.AppServices

/** The app's services; provided once at the root by the app module. */
val LocalAppServices = staticCompositionLocalOf<AppServices> { error("No AppServices provided: wrap the UI in ProvideAppServices") }

@Composable
fun ProvideAppServices(services: AppServices, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppServices provides services, content = content)
}

private val PhoneColors = darkColorScheme(
    primary = WmmColors.Brand,
    onPrimary = WmmColors.Text,
    primaryContainer = WmmColors.Brand,
    onPrimaryContainer = WmmColors.Text,
    secondary = WmmColors.Accent,
    onSecondary = WmmColors.OnAccent,
    secondaryContainer = WmmColors.Accent.copy(alpha = 0.18f),
    onSecondaryContainer = WmmColors.Accent,
    tertiary = WmmColors.Accent,
    onTertiary = WmmColors.OnAccent,
    background = WmmColors.Ground,
    onBackground = WmmColors.Text,
    surface = WmmColors.Ground,
    onSurface = WmmColors.Text,
    surfaceVariant = WmmColors.Surface2,
    onSurfaceVariant = WmmColors.TextMuted,
    surfaceContainerLowest = WmmColors.Ground,
    surfaceContainerLow = WmmColors.Surface,
    surfaceContainer = WmmColors.Surface,
    surfaceContainerHigh = WmmColors.Surface2,
    surfaceContainerHighest = WmmColors.Surface3,
    outline = WmmColors.Line3,
    outlineVariant = WmmColors.Line,
    error = WmmColors.Error,
    onError = WmmColors.Ground,
)

private fun inter(size: Int, weight: FontWeight, line: Int? = null, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (line ?: (size * 1.3).toInt()).sp,
    letterSpacing = (size * tracking).sp,
)

/** Phone type scale (sizes from the Android mockups). */
val PhoneTypography = Typography(
    displaySmall = inter(30, FontWeight.ExtraBold, tracking = -0.02),
    headlineMedium = inter(28, FontWeight.Bold, tracking = -0.02),
    headlineSmall = inter(22, FontWeight.SemiBold),
    titleLarge = inter(22, FontWeight.SemiBold),
    titleMedium = inter(18, FontWeight.SemiBold),
    titleSmall = inter(15, FontWeight.SemiBold),
    bodyLarge = inter(16, FontWeight.Normal),
    bodyMedium = inter(14, FontWeight.Normal),
    bodySmall = inter(13, FontWeight.Normal),
    labelLarge = inter(14, FontWeight.SemiBold),
    labelMedium = inter(12, FontWeight.SemiBold),
    labelSmall = inter(11, FontWeight.Bold, tracking = 0.08),
)

/** Material 3 theme for the phone UI. The TV UI builds its own from [WmmColors] and [Inter]. */
@Composable
fun WatchMyMediaPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhoneColors, typography = PhoneTypography, content = content)
}
