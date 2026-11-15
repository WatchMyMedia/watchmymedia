package media.watchmy.client.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.model.ServerFeatures
import media.watchmy.client.model.TMDB_NOTICE
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.olderServerNotice
import media.watchmy.client.model.settingsTheServerLacks
import media.watchmy.client.model.versionLabel
import media.watchmy.client.ui.LocalAppServices

/** The privacy policy Google Play requires the app to link to. */
const val PRIVACY_POLICY_URL = "https://watchmy.media/privacy"

/**
 * Settings › About (PLAN-ANDROID-SETTINGS.md §5.6, SettingsAbout): the app's
 * and the server's versions, the privacy policy, Copy diagnostics and the
 * licences. A server too old for some of the app's settings says which, and
 * who can update it (§4.7), where those settings are otherwise just missing.
 */
@Composable
internal fun AboutPage(tablet: Boolean, onOpenWeb: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by settingsModel().state.collectAsStateWithLifecycle()
    val services = LocalAppServices.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val server = state.server?.server

    Column(modifier) {
        if (server != null) {
            val missing = settingsTheServerLacks(ServerFeatures(server.apiLevel))
            if (missing.isNotEmpty()) {
                val notice = olderServerNotice(server.name, versionLabel(server.version), missing, admin = state.user?.isAdmin == true)
                StatusCard(notice.title, notice.body, NoteTone.Info, Modifier.cardInset(tablet))
            }
        }
        SettingsRows(tablet, Modifier.padding(top = if (tablet) 12.dp else 0.dp)) {
            ListRow("App", minHeight = 52.dp, titleSize = 15, trailing = { RowValue("WatchMyMedia ${services.client.version}", chevron = false, size = 13) })
            if (server != null) {
                ListRow("Server", minHeight = 52.dp, titleSize = 15, trailing = { RowValue("${server.name} · ${versionLabel(server.version)} · ${displayAddress(server.baseUrl)}", chevron = false, size = 13) })
            }
        }
        if (!tablet) Rule()
        SettingsRows(tablet, Modifier.padding(top = if (tablet) 16.dp else 0.dp)) {
            ListRow(
                "Privacy policy",
                modifier = Modifier.clickable { onOpenWeb(PRIVACY_POLICY_URL) },
                trailing = { RowValue("watchmy.media", icon = PhoneIcons.External) },
            )
            // The app's last exits and why, for a bug report (PLAN-ANDROID-REVIEW §6.4, the Diagnostics board).
            ListRow(
                "Copy diagnostics",
                subtitle = "The app’s last exits and why, for a bug report",
                modifier = Modifier.clickable { dialog = DIALOG_DIAGNOSTICS },
                trailing = { RowValue("") },
            )
            ListRow("Open-source licences", modifier = Modifier.clickable { dialog = DIALOG_LICENCES }, trailing = { RowValue("") })
        }
        // TMDB's terms ask apps that use its API to say so (PLAN-ROWS.md §3).
        FootNote(TMDB_NOTICE, Modifier.padding(top = 6.dp), tablet = tablet)
    }

    when (dialog) {
        DIALOG_LICENCES -> LicencesDialog { dialog = null }
        DIALOG_DIAGNOSTICS -> DiagnosticsDialog { dialog = null }
    }
}

private const val DIALOG_LICENCES = "licences"
private const val DIALOG_DIAGNOSTICS = "diagnostics"
