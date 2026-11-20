package media.watchmy.client.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.live.LiveRemindersBlock
import media.watchmy.client.model.ON_EVERY_DEVICE
import media.watchmy.client.model.SKIP_KINDS
import media.watchmy.client.model.SKIP_SEEK_NOTE
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.label
import media.watchmy.client.model.onThisDevice
import media.watchmy.client.model.settingsLabel
import media.watchmy.client.model.shortLabel
import media.watchmy.client.model.skipRowLabel
import media.watchmy.client.presentation.settings.AccountLoad
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.WmmColors

/**
 * Settings › Playback: On this phone, Default quality and On mobile
 * data; under Your account, Autoplay next episode, Skipping and Live TV
 * reminders, held until the account's settings have loaded.
 */
@Composable
internal fun PlaybackPage(tablet: Boolean, online: Boolean, modifier: Modifier = Modifier) {
    val vm = settingsModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val device = deviceWord()
    val ready = state.accountReady

    Column(modifier) {
        AccountLoadCard(state, online, tablet, onRetry = vm::reloadAccount, held = "the settings under Your account can’t be changed yet. The ones on this $device still work.")
        PageHeading(onThisDevice(device), tablet)
        SettingsRows(tablet) {
            ValueRow(
                "Default quality",
                line = when (state.quality) {
                    StreamQuality.Auto -> AUTO_NOTE
                    StreamQuality.Original -> "Direct play when the $device can"
                    else -> "Converts anything above this"
                },
                value = if (state.quality == StreamQuality.Original) "Original" else state.quality.label,
                state = state, key = SettingKey.Quality, still = state.quality.shortLabel, onRetry = vm::retry,
                onClick = { dialog = DIALOG_QUALITY },
            )
            ValueRow(
                "On mobile data",
                line = "When this $device isn’t on Wi-Fi",
                value = state.device.mobileQuality?.shortLabel ?: SAME_AS_WIFI,
                state = state, key = SettingKey.MobileQuality, still = state.device.mobileQuality?.shortLabel ?: SAME_AS_WIFI.lowercase(), onRetry = vm::retry,
                onClick = { dialog = DIALOG_MOBILE },
            )
        }
        PageHeading(ON_EVERY_DEVICE, tablet)
        AccountRows(ready) {
            SettingsRows(tablet) {
                AutoplayRow(state, ready, onChange = vm::setAutoplayNext, onRetry = vm::retry)
                if (!tablet) SubHeading("Skipping")
                SKIP_KINDS.forEach { kind ->
                    val key = SettingKey.Skip(kind)
                    SkipRow(
                        if (tablet) kind.skipRowLabel else kind.settingsLabel,
                        state.playback.skipMode(kind),
                        onChange = { vm.setSkip(kind, it) },
                        enabled = ready,
                        tablet = tablet,
                        saving = key in state.saving,
                        failure = state.failed[key],
                        onRetry = { vm.retry(key) },
                    )
                    SavedAnnouncement(state, key)
                }
            }
            FootNote(skipNote(tablet), tablet = tablet)
            LiveRemindersBlock(enabled = ready, tablet = tablet)
        }
    }

    when (dialog) {
        DIALOG_QUALITY -> QualityDialog(state.quality, state.qualities, onDismiss = { dialog = null }) {
            vm.setQuality(it)
            dialog = null
        }
        DIALOG_MOBILE -> MobileQualityDialog(state.device.mobileQuality, state.quality, state.qualities, device, onDismiss = { dialog = null }) {
            vm.setMobileQuality(it)
            dialog = null
        }
    }
}

/** Under the skip rows: what Ask and the two Autos do, as the web says it. */
private fun skipNote(tablet: Boolean): String {
    val delay = if (tablet) SkipMode.AutoDelay.label else SkipMode.AutoDelay.shortLabel
    return "Ask shows a Skip button. Auto skips after one second. $delay shows the button filling up, then skips. $SKIP_SEEK_NOTE"
}

/** One switch for TalkBack: the row toggles, and says what it is and whether it's on. */
@Composable
private fun AutoplayRow(state: SettingsViewModel.State, ready: Boolean, onChange: (Boolean) -> Unit, onRetry: (SettingKey) -> Unit) {
    val key = SettingKey.Autoplay
    val on = state.playback.autoplayNext
    Column {
        ListRow(
            "Autoplay next episode",
            subtitle = "Counts down 10 s from the credits".takeIf { key !in state.saving },
            below = if (key in state.saving) ({ SavingLine() }) else null,
            modifier = Modifier.toggleable(value = on, enabled = ready, role = Role.Switch, onValueChange = onChange),
            trailing = { Switch(checked = on, onCheckedChange = null, enabled = ready, colors = SettingSwitchColors) },
        )
        RowFailure(state.failed[key], if (on) "on" else "off") { onRetry(key) }
        SavedAnnouncement(state, key)
    }
}

/**
 * A row that opens a choice and shows its value: Saving… in its line while
 * the change goes, and a failure under it with Try again.
 */
@Composable
internal fun ValueRow(
    title: String,
    line: String?,
    value: String,
    state: SettingsViewModel.State,
    key: SettingKey,
    still: String,
    onRetry: (SettingKey) -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val saving = key in state.saving
    Column {
        ListRow(
            title,
            subtitle = line.takeIf { !saving },
            below = if (saving) ({ SavingLine() }) else null,
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            trailing = { RowValue(value) },
        )
        RowFailure(state.failed[key], still) { onRetry(key) }
        SavedAnnouncement(state, key)
    }
}

/**
 * The card at the top of a page with account settings: they didn't
 * load, with Try again; or the device is offline, and they come back by
 * themselves. [held] says what can't be done until then.
 */
@Composable
internal fun AccountLoadCard(state: SettingsViewModel.State, online: Boolean, tablet: Boolean, onRetry: () -> Unit, held: String) {
    val failed = state.account as? AccountLoad.Failed ?: return
    val server = state.server?.server?.name ?: "The server"
    if (!online) {
        StatusCard("You’re offline", "Your account’s settings come back when you reconnect.", NoteTone.Info, Modifier.cardInset(tablet))
    } else {
        val why = if (failed.unreachable) "$server didn’t answer, so " else failed.reason.trimEnd('.') + ", so "
        StatusCard("Your account’s settings didn’t load", why + held, NoteTone.Warn, Modifier.cardInset(tablet), action = "Try again", onAction = onRetry)
    }
}

/** A switch at rest, on and off; dimmed rows keep these colours under their own fade. */
internal val SettingSwitchColors @Composable get() = SwitchDefaults.colors(
    checkedThumbColor = WmmColors.Ground,
    checkedTrackColor = WmmColors.Accent,
    checkedBorderColor = WmmColors.Accent,
    uncheckedThumbColor = WmmColors.TextMuted,
    uncheckedTrackColor = WmmColors.Surface3,
    uncheckedBorderColor = WmmColors.FieldOutline,
    disabledCheckedThumbColor = WmmColors.Ground,
    disabledCheckedTrackColor = WmmColors.Accent,
    disabledCheckedBorderColor = WmmColors.Accent,
    disabledUncheckedThumbColor = WmmColors.TextMuted,
    disabledUncheckedTrackColor = WmmColors.Surface3,
    disabledUncheckedBorderColor = WmmColors.FieldOutline,
)

/** Automatic in the Default quality row and dialog. */
internal const val AUTO_NOTE = "Original first, lower when it can’t keep up"
internal const val AUTO_DETAIL = "Original first, lowered when the connection can’t keep up"

/** On mobile data, when it plays at the Default quality. */
internal const val SAME_AS_WIFI = "Same as on Wi-Fi"

private const val DIALOG_QUALITY = "quality"
private const val DIALOG_MOBILE = "mobile"
