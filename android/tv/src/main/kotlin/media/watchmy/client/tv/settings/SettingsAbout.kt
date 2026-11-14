package media.watchmy.client.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.QrCode
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.PRIVACY_POLICY_URL
import media.watchmy.client.ui.WmmColors

/** The privacy policy: a TV has no browser, so its address and a QR code to open it on a phone. */
@Composable
internal fun PrivacyDialog(onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(700.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text("Privacy policy", style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
                Text(
                    "Scan the code with your phone, or open this address on any device.",
                    style = inter(19.sp, lineHeight = 28.sp),
                    color = WmmColors.TextMuted,
                )
                Text(PRIVACY_POLICY_URL.removePrefix("https://"), style = inter(26.sp, FontWeight.SemiBold), color = WmmColors.Text)
                Box(
                    Modifier.size(340.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCode(PRIVACY_POLICY_URL, contentDescription = "QR code for the privacy policy", modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.weight(1f))
                TvButton("Close", onDismiss, Modifier.focusRequester(close))
            }
        }
        InitialFocus(close)
    }
}

/** The libraries this app ships, with their licences. */
private val Licences = listOf(
    "Inter typeface" to "SIL Open Font License 1.1",
    "AndroidX, Jetpack Compose and Compose for TV" to "Apache License 2.0",
    "Media3 (ExoPlayer)" to "Apache License 2.0",
    "Kotlin and kotlinx libraries" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "ZXing" to "Apache License 2.0",
)

@Composable
internal fun LicencesDialog(onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(700.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Open-source licences", style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
                Text("WatchMyMedia is built with these. Thank you to their authors.", style = inter(18.sp, lineHeight = 26.sp), color = WmmColors.TextMuted)
                for ((name, licence) in Licences) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(name, style = inter(20.sp, FontWeight.SemiBold), color = WmmColors.Text)
                        Text(licence, style = inter(17.sp), color = WmmColors.TextMuted)
                    }
                }
                Box(Modifier.weight(1f))
                TvButton("Close", onDismiss, Modifier.focusRequester(close))
            }
        }
        InitialFocus(close)
    }
}
