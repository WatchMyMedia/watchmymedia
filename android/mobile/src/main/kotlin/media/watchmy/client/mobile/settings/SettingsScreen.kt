package media.watchmy.client.mobile.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.FieldText
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.InlineError
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.mobile.downloads.DangerText
import media.watchmy.client.mobile.downloads.usedSize
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.mobile.components.panel
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.freeSize
import media.watchmy.client.model.plural
import media.watchmy.client.model.SKIP_KINDS
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.languagesSummary
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.formatAgo
import media.watchmy.client.model.kindLabel
import media.watchmy.client.model.settingsLabel
import media.watchmy.client.model.shortLabel
import media.watchmy.client.model.versionLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.presentation.value
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.mobile.live.LiveReminderSettings
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.TMDB_NOTICE

/** The privacy policy Google Play requires the app to link to. */
const val PRIVACY_POLICY_URL = "https://watchmy.media/privacy"

private val Footnote = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint)

/**
 * Settings (Settings.dc.html, a scrolling page): Your requests and the
 * admins' Inbox when the server takes requests, device name, quality,
 * autoplay and the skip modes, signed-in devices, Link a TV, Switch server,
 * Sign out and About.
 */
@Composable
fun SettingsScreen(
    onSwitchServer: () -> Unit,
    onSignedOut: () -> Unit,
    onLinkTv: () -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenRequests: (() -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    inboxCount: Int = 0,
) {
    val services = LocalAppServices.current
    val vm = viewModel { SettingsViewModel(services.auth, services.servers, services.preferences) }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloadsVm = viewModel { DownloadsViewModel(services.downloads) }
    val downloads by downloadsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { downloadsVm.refreshStorage() }
    LaunchedEffect(vm) { vm.events.collect { if (it == SettingsViewModel.Event.SignedOut) onSignedOut() } }
    // The phone has no line for it: a playback settings load that failed is said once.
    LaunchedEffect(state.prefsError) { state.prefsError?.let(vm::say) }
    val uri = LocalUriHandler.current
    // ACTION_VIEW in the browser; a device with none gets a message rather than a crash.
    val openWeb = { url: String -> if (runCatching { uri.openUri(url) }.isFailure) vm.say("No browser to open $url") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<DeviceSession?>(null) }
    val twoPane = rememberTabletLayout().twoPane
    var page by rememberSaveable { mutableStateOf(SettingsPage.Playback) }
    val pageScroll = rememberScrollState()
    LaunchedEffect(page, twoPane) { pageScroll.scrollTo(0) }
    val phone = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    val rowsAvailable = (state.server?.server?.apiLevel ?: 0) >= ROWS_API_LEVEL

    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        MainTopBar("Settings", state.user?.name, onSearch = null, onAvatar = null)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                if (twoPane) {
                    Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.user?.name ?: "Account", color = WmmColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                        RequestLinks(onOpenRequests, onOpenInbox, inboxCount)
                        SettingsPage.entries.filter { (it != SettingsPage.Downloads || downloads.enabled) && (it != SettingsPage.Subtitles || state.subtitlePreferencesAvailable) }.forEach { destination ->
                            val languages = destination == SettingsPage.Subtitles
                            ListRow(if (languages) state.languagesTitle else destination.title,
                                modifier = Modifier.panel(selected = destination == page).clickable { page = destination },
                                trailing = {
                                    // Audio & subtitles says what is chosen: "Spanish · For foreign audio".
                                    if (languages) RowValue(languagesSummary(state.playback, state.audioLanguagesAvailable, LocalConfiguration.current.locales[0]), size = 13)
                                    else Icon(WmmIcons.ChevronRight, null, tint = WmmColors.TextMuted)
                                })
                        }
                        if (rowsAvailable) ListRow("Home rows", modifier = Modifier.clickable { onNavigate(MobileRoutes.EditRowsDialog(RowScreens.HOME, "Home")) })
                        ListRow("Link a TV", modifier = Modifier.clickable(onClick = onLinkTv))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().verticalScroll(pageScroll).padding(bottom = 24.dp)) {
                        Text(
                            listOfNotNull(state.user?.name?.let { "Signed in as $it" }, state.server?.server?.name).joinToString(" · "),
                            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        )

                        if (!twoPane) RequestLinks(onOpenRequests, onOpenInbox, inboxCount)
                        // You (PLAN-ROWS.md §8.2): Home's rows, the same on every device the person signs in on.
                        if (!twoPane && rowsAvailable) {
                            Section("You")
                            ListRow(
                                "Home rows",
                                subtitle = "Order, hide and add the rows on Home",
                                modifier = Modifier.clickable { onNavigate(MobileRoutes.EditRows(RowScreens.HOME, "Home")) },
                                trailing = { Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp)) },
                            )
                        }
                        if (!twoPane || page == SettingsPage.Device) {
                            Section("This device")
                            ListRow(
                                "Device name",
                                subtitle = listOfNotNull(Build.MODEL, Build.VERSION.RELEASE?.let { "Android $it" }, "app ${services.client.version}").joinToString(" · "),
                                modifier = Modifier.clickable { dialog = DIALOG_NAME },
                                trailing = { RowValue(state.deviceName) },
                            )

                        }
                        if (!twoPane || page == SettingsPage.Playback) {
                            Section("Playback")
                            if (twoPane) {
                                state.qualities.forEach { quality ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = quality == state.quality, role = Role.RadioButton, onClick = { vm.setQuality(quality) }).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = quality == state.quality, onClick = null)
                                        Column(Modifier.padding(start = 12.dp)) {
                                            Text(quality.label, color = WmmColors.Text)
                                            if (quality == StreamQuality.Auto) Text(AUTO_DETAIL, style = Footnote)
                                        }
                                    }
                                }
                            } else ListRow(
                                "Default quality",
                                subtitle = when (state.quality) {
                                    StreamQuality.Auto -> AUTO_NOTE
                                    StreamQuality.Original -> "Direct play when the $phone can"
                                    else -> "Converts anything above this"
                                },
                                modifier = Modifier.clickable { dialog = DIALOG_QUALITY },
                                trailing = { RowValue(if (state.quality == StreamQuality.Original) "Original" else state.quality.label) },
                            )
                            ListRow(
                                "On mobile data",
                                subtitle = "When this $phone isn’t on Wi-Fi",
                                modifier = Modifier.clickable { dialog = DIALOG_MOBILE },
                                trailing = { RowValue(state.device.mobileQuality?.shortLabel ?: SAME_AS_WIFI) },
                            )
                            // One switch for TalkBack: the row toggles, and says what it is and whether it's on.
                            ListRow(
                                "Autoplay next episode",
                                subtitle = "Counts down 10 s from the credits",
                                modifier = Modifier.toggleable(value = state.playback.autoplayNext, role = Role.Switch, onValueChange = vm::setAutoplayNext),
                                trailing = {
                                    Switch(
                                        checked = state.playback.autoplayNext,
                                        onCheckedChange = null,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = WmmColors.Ground,
                                            checkedTrackColor = WmmColors.Accent,
                                            checkedBorderColor = WmmColors.Accent,
                                            uncheckedThumbColor = WmmColors.TextMuted,
                                            uncheckedTrackColor = WmmColors.Surface3,
                                            uncheckedBorderColor = WmmColors.FieldOutline,
                                        ),
                                    )
                                },
                            )

                        }
                        if (!twoPane || page == SettingsPage.Subtitles) LanguageSettings(state, vm, tablet = twoPane)
                        if (!twoPane || page == SettingsPage.Playback) LiveReminderSettings()
                        if (downloads.enabled && (!twoPane || page == SettingsPage.Downloads)) {
                            DownloadSettings(
                                downloads,
                                phone,
                                onQuality = { dialog = DIALOG_DOWNLOAD_QUALITY },
                                onWifiOnly = downloadsVm::setWifiOnly,
                                onSpace = { onNavigate(MobileRoutes.Downloads) },
                                onRemoveAll = { dialog = DIALOG_REMOVE_ALL },
                            )
                        }
                        if (!twoPane || page == SettingsPage.Skip) {
                            Section("Skip")
                            SKIP_KINDS.forEach { kind ->
                                SkipRow(kind.settingsLabel, state.playback.skipMode(kind)) { vm.setSkip(kind, it) }
                            }
                            Text(
                                "Ask shows a Skip button. Auto skips after one second. Auto 3 s shows the button filling up, then skips. A segment you seek into is never skipped for you.",
                                style = Footnote,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                            )

                        }
                        if (!twoPane || page == SettingsPage.Devices) {
                            Section("Signed-in devices")
                            val devices = state.sessions.value
                            when {
                                devices == null && state.sessions is Load.Failed -> InlineError((state.sessions as Load.Failed).message, vm::loadSessions)
                                devices == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                                }
                                else -> devices.forEach { d ->
                                    ListRow(
                                        d.name,
                                        subtitle = "${d.model ?: d.kindLabel} · ${if (d.current) "now" else formatAgo(d.lastSeenAtMs)}",
                                        titleSize = 15,
                                        subtitleSize = 12,
                                        minHeight = 52.dp,
                                        trailing = {
                                            when {
                                                d.current -> Badge("This $phone", style = BadgeStyle.Gold, horizontalPadding = 8.dp)
                                                state.revoking == d.id -> CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                                                else -> GoldTextButton("Sign out", { revoking = d })
                                            }
                                        },
                                    )
                                }
                            }
                            ListRow(
                                "Link a TV",
                                subtitle = "Enter the code shown on your TV",
                                minHeight = 64.dp,
                                modifier = Modifier.clickable(onClick = onLinkTv),
                                leading = { IconTile(WmmIcons.Tv, background = WmmColors.Line2, tint = WmmColors.Accent) },
                                trailing = { Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp)) },
                            )
                        }
                        if (!twoPane || page == SettingsPage.Server) {
                            ListRow(
                                "Switch server",
                                subtitle = state.server?.server?.let { "${it.name} · ${displayAddress(it.baseUrl)}" },
                                minHeight = 64.dp,
                                modifier = Modifier.clickable(onClick = onSwitchServer),
                                leading = { IconTile(WmmIcons.Server) },
                                trailing = { Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp)) },
                            )
                            FilledButton(
                                "Sign out",
                                onClick = vm::signOut,
                                large = true,
                                busy = state.signingOut,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth(),
                            )

                        }
                        if (!twoPane || page == SettingsPage.About || page == SettingsPage.Server) {
                            Section("About")
                            state.server?.server?.let { s ->
                                ListRow("Server", minHeight = 44.dp, titleSize = 15, trailing = { RowValue("${s.name} · ${versionLabel(s.version)} · ${displayAddress(s.baseUrl)}", chevron = false, size = 13) })
                            }
                            ListRow("App", minHeight = 44.dp, titleSize = 15, trailing = { RowValue("WatchMyMedia ${services.client.version}", chevron = false, size = 13) })
                            state.server?.server?.let { s ->
                                ListRow(
                                    "Admin tools",
                                    minHeight = 44.dp,
                                    titleSize = 15,
                                    modifier = Modifier.clickable { openWeb(s.baseUrl) },
                                    trailing = { RowValue("Open the web app", icon = PhoneIcons.External, size = 13) },
                                )
                            }
                            ListRow(
                                "Privacy policy",
                                minHeight = 44.dp,
                                titleSize = 15,
                                modifier = Modifier.clickable { openWeb(PRIVACY_POLICY_URL) },
                                trailing = { RowValue("watchmy.media", icon = PhoneIcons.External, size = 13) },
                            )
                            ListRow("Open-source licences", minHeight = 44.dp, titleSize = 15, modifier = Modifier.clickable { dialog = DIALOG_LICENCES }, trailing = { RowValue("") })
                            // TMDB's terms ask apps that use its API to say so (PLAN-ROWS.md §3).
                            Text(TMDB_NOTICE, style = Footnote, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                    }
                    }
                }
            }
            MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
        }
    }

    when (dialog) {
        DIALOG_NAME -> RenameDialog(state.deviceName, onDismiss = { dialog = null }) {
            vm.rename(it)
            dialog = null
        }
        DIALOG_QUALITY -> QualityDialog(state.quality, state.qualities, onDismiss = { dialog = null }) {
            vm.setQuality(it)
            dialog = null
        }
        DIALOG_MOBILE -> MobileQualityDialog(state.device.mobileQuality, state.quality, state.qualities, phone, onDismiss = { dialog = null }) {
            vm.setMobileQuality(it)
            dialog = null
        }
        DIALOG_LICENCES -> LicencesDialog { dialog = null }
        DIALOG_DOWNLOAD_QUALITY -> DownloadQualityDialog(downloads.settings.quality, phone, onDismiss = { dialog = null }) {
            downloadsVm.setQuality(it)
            dialog = null
        }
        DIALOG_REMOVE_ALL -> RemoveAllDialog(downloads, phone, onDismiss = { dialog = null }) {
            downloadsVm.removeAll()
            dialog = null
        }
    }
    revoking?.let { d ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            containerColor = WmmColors.Surface2,
            title = { Text("Sign out ${d.name}?") },
            text = { Text("It will need to sign in again, with a password or a code.") },
            confirmButton = {
                GoldTextButton("Sign out", {
                    vm.revoke(d)
                    revoking = null
                })
            },
            dismissButton = { GoldTextButton("Cancel", { revoking = null }, color = WmmColors.TextSoft) },
        )
    }
}

@Composable
private fun Section(title: String) = SectionLabel(title, Modifier.padding(top = 16.dp, bottom = 4.dp))

/**
 * A skip kind and its four modes as an M3 segmented button row. Where the
 * row is too narrow for the label beside the buttons (under ~400 dp), the
 * label sits above them.
 */
@Composable
private fun SkipRow(label: String, mode: SkipMode, onChange: (SkipMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = (maxWidth - 32.dp - 64.dp - 12.dp) / 4 >= 76.dp
        val title = @Composable { m: Modifier -> Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text), modifier = m) }
        if (inline) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                title(Modifier.width(64.dp))
                SkipButtons(mode, onChange, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                title(Modifier)
                SkipButtons(mode, onChange, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SkipButtons(mode: SkipMode, onChange: (SkipMode) -> Unit, modifier: Modifier) {
    val modes = SkipMode.entries.map { it to it.shortLabel }
    SingleChoiceSegmentedButtonRow(modifier) {
        modes.forEachIndexed { i, (m, text) ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = WmmColors.Accent,
                    activeContentColor = WmmColors.OnAccent,
                    activeBorderColor = WmmColors.Line3,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = WmmColors.TextSoft,
                    inactiveBorderColor = WmmColors.Line3,
                ),
                contentPadding = PaddingValues(horizontal = 2.dp),
                label = { Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), maxLines = 1, softWrap = false) },
            )
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Device name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The name people see in Signed-in devices and in the server's Activity.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    singleLine = true,
                    textStyle = FieldText,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { GoldTextButton("Save", { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { GoldTextButton("Cancel", onDismiss, color = WmmColors.TextSoft) },
    )
}

/** SettingsQuality.dc.html: Automatic first (where the server can do it), with what it does under it. */
@Composable
private fun QualityDialog(current: StreamQuality, options: List<StreamQuality>, onDismiss: () -> Unit, onPick: (StreamQuality) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Default quality") },
        text = {
            // Scrolls where the dialog is shorter than its choices (a phone held sideways).
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = q == current, role = Role.RadioButton, onClick = { onPick(q) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted))
                        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                            Text(q.label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                            if (q == StreamQuality.Auto) {
                                Text(AUTO_DETAIL, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss, color = WmmColors.TextSoft) },
    )
}

/**
 * SettingsMobileData.dc.html: Same as on Wi-Fi first, with the Default
 * quality under it, then the qualities as Default quality lists them.
 */
@Composable
private fun MobileQualityDialog(
    current: StreamQuality?,
    wifi: StreamQuality,
    options: List<StreamQuality>,
    device: String,
    onDismiss: () -> Unit,
    onPick: (StreamQuality?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("On mobile data") },
        text = {
            Column {
                Text(
                    "Used when this $device isn’t on Wi-Fi.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    (listOf(null) + options).forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = q == current, role = Role.RadioButton, onClick = { onPick(q) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted))
                            Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(q?.label ?: SAME_AS_WIFI, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                                val detail = when (q) {
                                    null -> "Follows Default quality · ${wifi.label}"
                                    StreamQuality.Auto -> AUTO_DETAIL
                                    else -> null
                                }
                                if (detail != null) Text(detail, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss, color = WmmColors.TextSoft) },
    )
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val entries = listOf(
        "Inter" to "SIL Open Font License 1.1",
        "AndroidX, Jetpack Compose, Material 3, Media3" to "Apache License 2.0",
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
        "Coil" to "Apache License 2.0",
        "OkHttp" to "Apache License 2.0",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Open-source licences") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEach { (name, licence) ->
                    Column {
                        Text(name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Text))
                        Text(licence, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss) },
    )
}

/** Automatic in the Default quality row and dialog. */
private const val AUTO_NOTE = "Original first, lower when it can’t keep up"
private const val AUTO_DETAIL = "Original first, lowered when the connection can’t keep up"

/** On mobile data, when it plays at the Default quality. */
private const val SAME_AS_WIFI = "Same as on Wi-Fi"

private const val DIALOG_NAME = "name"
private const val DIALOG_QUALITY = "quality"
private const val DIALOG_MOBILE = "mobile"
private const val DIALOG_LICENCES = "licences"
private const val DIALOG_DOWNLOAD_QUALITY = "download-quality"
private const val DIALOG_REMOVE_ALL = "remove-all"

private enum class SettingsPage(val title: String) { Playback("Playback"), Subtitles("Audio & subtitles"), Downloads("Downloads"), Skip("Skip intros and credits"), Device("This tablet"), Devices("Signed-in devices"), Server("Server"), About("About this app") }

private val SwitchColors @Composable get() = SwitchDefaults.colors(
    checkedThumbColor = WmmColors.Ground,
    checkedTrackColor = WmmColors.Accent,
    checkedBorderColor = WmmColors.Accent,
    uncheckedThumbColor = WmmColors.TextMuted,
    uncheckedTrackColor = WmmColors.Surface3,
    uncheckedBorderColor = WmmColors.FieldOutline,
)

/**
 * Settings › Downloads (SettingsDownloads.dc.html): the quality new downloads
 * are made at, Wi-Fi only, the space they take (to Downloads), and Remove
 * all downloads.
 */
@Composable
private fun DownloadSettings(
    downloads: DownloadsViewModel.State,
    device: String,
    onQuality: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSpace: () -> Unit,
    onRemoveAll: () -> Unit,
) = Column {
    Section("Downloads")
    ListRow(
        "Quality",
        subtitle = "For new downloads; each can pick another",
        modifier = Modifier.clickable(onClick = onQuality),
        trailing = { RowValue(downloads.settings.quality.short) },
    )
    val wifiOnly = downloads.settings.wifiOnly
    ListRow(
        "Wi-Fi only",
        subtitle = if (wifiOnly) "Downloads wait for Wi-Fi" else "Downloads use mobile data too",
        modifier = Modifier.toggleable(value = wifiOnly, role = Role.Switch, onValueChange = onWifiOnly),
        trailing = { Switch(checked = wifiOnly, onCheckedChange = null, colors = SwitchColors) },
    )
    val held = downloads.all.size
    ListRow(
        "Space used",
        subtitle = if (held == 0) "${freeSize(downloads.storage.freeBytes)} free on this $device" else "${plural(held, "download")} · ${freeSize(downloads.storage.freeBytes)} free",
        modifier = Modifier.clickable(onClick = onSpace),
        trailing = { RowValue(usedSize(downloads.usedBytes)) },
    )
    if (held > 0) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onRemoveAll).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(WmmIcons.Trash, contentDescription = null, tint = DangerText, modifier = Modifier.size(22.dp))
            Text("Remove all downloads", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = DangerText))
        }
    }
}

/** Download quality (SettingsDownloads.dc.html, dialog quality): Original, said in full, then the steps. */
@Composable
private fun DownloadQualityDialog(current: DownloadQuality, device: String, onDismiss: () -> Unit, onPick: (DownloadQuality) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Download quality") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Used for new downloads. The download sheet shows each size before you start.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(Modifier.selectableGroup()) {
                    DownloadQuality.entries.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = q == current, role = Role.RadioButton, onClick = { onPick(q) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == current, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = WmmColors.Accent, unselectedColor = WmmColors.TextMuted))
                            Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(q.label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                                if (q == DownloadQuality.Original) {
                                    Text("Full quality: the file itself when this $device can play it", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { GoldTextButton("Close", onDismiss, color = WmmColors.TextSoft) },
    )
}

/** Remove all downloads? (SettingsDownloads.dc.html, dialog removeAll). */
@Composable
private fun RemoveAllDialog(downloads: DownloadsViewModel.State, device: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WmmColors.Surface2,
        title = { Text("Remove all downloads?") },
        text = {
            Text(
                "${plural(downloads.all.size, "download")}, ${usedSize(downloads.usedBytes)}, are deleted from this $device. You can download them again.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft),
            )
        },
        confirmButton = {
            Button(onClick = onRemove, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB42318), contentColor = Color.White)) {
                Text("Remove all", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
            }
        },
        dismissButton = { GoldTextButton("Cancel", onDismiss, color = WmmColors.TextSoft) },
    )
}

@Composable
private fun RequestLinks(onOpenRequests: (() -> Unit)?, onOpenInbox: (() -> Unit)?, inboxCount: Int) {
                if (onOpenRequests != null || onOpenInbox != null) {
                    Section("Requests")
                    if (onOpenRequests != null) {
                        ListRow(
                            "Your requests",
                            subtitle = "What you asked for, and where it stands",
                            modifier = Modifier.clickable(onClick = onOpenRequests),
                            trailing = { Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp)) },
                        )
                    }
                    if (onOpenInbox != null) {
                        ListRow(
                            "Inbox",
                            subtitle = "Requests to approve and problems people reported",
                            modifier = Modifier.clickable(onClick = onOpenInbox),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (inboxCount > 0) Badge("$inboxCount", style = BadgeStyle.Gold, horizontalPadding = 8.dp)
                                    Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
                                }
                            },
                        )
                    }
                }

}
