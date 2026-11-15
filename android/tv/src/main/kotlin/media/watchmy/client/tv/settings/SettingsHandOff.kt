package media.watchmy.client.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.displayAddress
import media.watchmy.client.tv.components.BackClosesNote
import media.watchmy.client.tv.components.QrCode
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.WmmColors

/**
 * Change password on a TV (android-tv/SettingsGroupAccount.dc.html, Tweak
 * `overlay`: `password`): a remote types a character at a time on a shared
 * screen, so the panel hands it to a phone or computer, with the web app's
 * Account & devices at [url] and a code to scan. Nothing in it takes focus;
 * Back closes it, back on its row.
 */
@Composable
internal fun ChangePasswordPanel(url: String, onDismiss: () -> Unit) {
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(700.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 44.dp, end = 44.dp, top = 64.dp, bottom = 54.dp)
                    .semantics { paneTitle = "Change password" },
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Change password", style = inter(34.sp, FontWeight.ExtraBold, tracking = -0.015f), color = WmmColors.Text)
                Text(
                    "A remote types one character at a time, and this screen is shared. So your password is changed on a phone or computer.",
                    style = inter(20.sp, lineHeight = 30.sp),
                    color = TextBright,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kicker("Scan, or open")
                    Text(displayAddress(url), style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text)
                }
                QrCard(url, "QR code for changing your password", 324.dp)
                Text(
                    "Changing it signs this TV out too. It will show a code to link it again.",
                    style = inter(19.sp, lineHeight = 28.sp),
                    color = WmmColors.TextMuted,
                )
                Box(Modifier.weight(1f))
                BackClosesNote()
            }
        }
    }
}

/**
 * Server settings on a TV (android-tv/SettingsGroups.dc.html, Tweak `group`:
 * `server`): the server's settings are in the web app, so the pane shows the
 * address of its Settings and a code to scan, as Privacy policy does
 * (PLAN-ANDROID-SETTINGS.md §5.8). It has no rows, so focus stays on its group.
 */
@Composable
internal fun ColumnScope.ServerSettingsPane(url: String, serverName: String, userName: String?) {
    Text(
        "Libraries, people, Live TV and the rest of $serverName’s settings are in the web app. Scan the code with your phone, or open this address on a phone or computer.",
        style = inter(22.sp, lineHeight = 33.sp),
        color = TextBright,
        modifier = Modifier.widthIn(max = 940.dp).padding(top = 16.dp),
    )
    Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
        QrCard(url, "QR code for the server’s settings", 344.dp)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Kicker("Address")
            Text(displayAddress(url), style = inter(28.sp, FontWeight.SemiBold), color = WmmColors.Text)
            Text(
                listOfNotNull(userName?.let { "Sign in there as $it." }, "Only admins see the server’s settings.").joinToString(" "),
                style = inter(19.sp, lineHeight = 27.sp),
                color = WmmColors.TextMuted,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
    }
}

/** The white card a QR code sits on (`.qr`), [size] square. */
@Composable
private fun QrCard(url: String, description: String, size: Dp) {
    Box(Modifier.size(size).background(Color.White, RoundedCornerShape(20.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
        QrCode(url, contentDescription = description, modifier = Modifier.fillMaxSize())
    }
}

/** A small grey label over an address (`.dk`). */
@Composable
private fun Kicker(text: String) {
    Text(text.uppercase(), style = inter(15.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted)
}
