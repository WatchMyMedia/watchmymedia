package media.watchmy.client.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.ui.WmmColors

/**
 * Change password (PLAN-ANDROID-SETTINGS.md §5.3; the SettingsPassword and
 * TabSettingsPassword boards): a full-screen task on a phone, a centred
 * surface over Settings on a tablet ([asDialog]). [onClose] leaves without
 * changing anything; [onChanged] reports a changed password and how many
 * other devices it signed out, after which the caller closes it and calls
 * `SettingsViewModel.passwordChanged`.
 *
 * The frame only, so Settings can open it while phase AN3 builds the task.
 */
@Composable
internal fun ChangePasswordScreen(
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onChanged: (signedOut: Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") asDialog: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        BackTopBar("Change password", onClose)
    }
}
