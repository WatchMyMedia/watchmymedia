package media.watchmy.client.tv.settings

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.SKIP_KINDS
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.subtitleLanguageLabel
import media.watchmy.client.model.subtitleLanguageOptions
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.formatAgo
import media.watchmy.client.model.kindLabel
import media.watchmy.client.model.label
import media.watchmy.client.model.settingsLabel
import media.watchmy.client.model.versionLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.tv.components.BackClosesNote
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.KeyboardDialog
import media.watchmy.client.tv.components.LocalRequestBadge
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.QrCode
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.model.LIVE_REMINDERS_NOTE_TV
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.liveLeadBefore
import media.watchmy.client.model.liveLeadNote
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.radialGlow
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.TMDB_NOTICE
import media.watchmy.client.ui.PRIVACY_POLICY_URL
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

private enum class Overlay { Quality, Passthrough, SubtitleSize, SubtitleChoice, AddLanguage, LanguageActions, DeviceName, Privacy, Licences, ReminderLead }

/** The list Add language and a language's options are for. */
private enum class LanguageList(val title: String) { Audio("Audio languages"), Subtitles("Subtitle languages") }

/** What a quality does, under it in Settings (SettingsQuality.dc.html). */
private fun qualityDescription(quality: StreamQuality): String = when (quality) {
    StreamQuality.Auto -> "Original first, lower when it can’t keep up"
    StreamQuality.Original -> "Direct play when the device can"
    else -> "Transcode to fit"
}

/**
 * Settings (mockup: design/mockups/android-tv/Settings.dc.html and
 * SettingsPlayback.dc.html): default quality, autoplay, frame rate,
 * passthrough, subtitle size and a skip mode per segment kind on the left; Your
 * requests and the admins' Inbox when the server takes requests, this
 * device, signed-in devices, sign out, switch server and About on the right.
 * [onOpenRequests] takes whether to open on the Inbox.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onSwitchServer: () -> Unit,
    onSignedOut: () -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenRequests: (inbox: Boolean) -> Unit = {},
    /** Opens on Home rows for this screen (a library's Edit rows, PLAN-ROWS.md §8.2). */
    rowsScreen: String? = null,
) {
    val services = LocalAppServices.current
    val vm = viewModel { SettingsViewModel(services.auth, services.servers, services.preferences) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { if (it == SettingsViewModel.Event.SignedOut) onSignedOut() } }
    var editingLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var languageList by rememberSaveable { mutableStateOf(LanguageList.Subtitles) }
    // Where focus goes when a change takes away the row that had it: a language
    // added from Use English goes to the list, a removed one to its Add language.
    val audioFirst = remember { FocusRequester() }
    val audioAdd = remember { FocusRequester() }
    val subtitleFirst = remember { FocusRequester() }
    val subtitleAdd = remember { FocusRequester() }
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(refocus, state.playback) {
        val target = refocus ?: return@LaunchedEffect
        // Not there until the change shows; this runs again when it does.
        if (runCatching { target.requestFocus() }.getOrDefault(false)) refocus = null
    }
    val displayLocale = LocalConfiguration.current.locales[0]
    val languageChoices = remember(displayLocale) { subtitleLanguageOptions(displayLocale) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    val first = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 40.dp.toPx(), trailing = 40.dp.toPx()) } }
    val server = state.server?.server
    // Live TV › Reminders (android-tv/LiveReminder's settings): the lead time, the same on all the person's devices.
    val reminders = viewModel(key = "tv-reminder-settings") { LiveRemindersViewModel(services.live, services.servers) }
    val reminderState by reminders.state.collectAsStateWithLifecycle()

    TvScaffold(RailSection.Settings, onNavigate) {
        Column(Modifier.padding(start = ContentStart, top = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
            Text(
                listOfNotNull(state.user?.name?.let { "Signed in as $it" }, server?.name).joinToString(" · "),
                style = inter(20.sp),
                color = WmmColors.TextMuted,
            )
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            Column(
                Modifier.padding(start = ContentStart - 8.dp, top = 180.dp).width(1036.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Section("Playback") {
                    SettingRow(
                        "Default quality",
                        onClick = { overlay = Overlay.Quality },
                        description = qualityDescription(state.quality),
                        modifier = Modifier.focusRequester(first),
                    ) { Value(state.quality.label, chevron = true) }
                    SettingRow(
                        "Autoplay next episode",
                        onClick = { vm.setAutoplayNext(!state.playback.autoplayNext) },
                        description = "Counts down 10 s from the credits",
                        modifier = Modifier.switch(state.playback.autoplayNext),
                    ) { Toggle(state.playback.autoplayNext) }
                    SettingRow(
                        "Match frame rate",
                        onClick = { vm.setMatchFrameRate(!state.device.matchFrameRate) },
                        description = "Switches the TV to the video’s frame rate. The screen may go dark for a moment.",
                        stacked = true,
                        modifier = Modifier.switch(state.device.matchFrameRate),
                    ) { Toggle(state.device.matchFrameRate) }
                    SettingRow(
                        "Audio passthrough",
                        onClick = { overlay = Overlay.Passthrough },
                        description = "Off sends stereo or surround as PCM, for receivers that don’t decode Dolby or DTS.",
                        stacked = true,
                    ) { Value(state.device.audioPassthrough.label, chevron = true) }
                    SettingRow("Subtitle size", onClick = { overlay = Overlay.SubtitleSize }) { Value(state.device.subtitleSize.label, chevron = true) }
                    state.prefsError?.let { Text(it, style = inter(17.sp), color = WmmColors.Error, modifier = Modifier.padding(horizontal = 18.dp)) }
                }
                if (state.subtitlePreferencesAvailable) Section(state.languagesTitle) {
                    // Audio & subtitles (android-tv/SettingsLanguages.dc.html, PLAN-LANGUAGES.md §5.6).
                    val subtitles = state.playback.subtitles
                    if (state.audioLanguagesAvailable) {
                        SubHeading("Audio languages", top = 8.dp)
                        Note("Videos start in the first of these languages they have. If they have none, they play the file’s default.")
                        val audio = state.playback.audio.languages
                        if (audio.isEmpty()) {
                            Note("No audio language yet. Videos play the file’s default audio.", Modifier.padding(top = 6.dp))
                            // Speech has no script or region to choose: the TV's language alone.
                            val suggested = displayLocale.language.takeIf { it.isNotBlank() && it != "und" }
                            if (suggested != null) {
                                val name = subtitleLanguageLabel(suggested, displayLocale)
                                SettingRow("Use $name", onClick = { vm.addAudioLanguage(suggested); refocus = audioFirst }, description = "Your TV uses $name.") {}
                            }
                        }
                        audio.forEachIndexed { index, tag ->
                            SettingRow(
                                "${index + 1}. ${subtitleLanguageLabel(tag, displayLocale)}",
                                onClick = { languageList = LanguageList.Audio; editingLanguage = tag; overlay = Overlay.LanguageActions },
                                modifier = if (index == 0) Modifier.focusRequester(audioFirst) else Modifier,
                            ) { Value("Options", chevron = true) }
                        }
                        SettingRow("Add language", onClick = { languageList = LanguageList.Audio; overlay = Overlay.AddLanguage }, modifier = Modifier.focusRequester(audioAdd)) { Value("", chevron = true) }
                        Note("Commentary and audio description are never chosen for you. You can pick them in the player.", color = WmmColors.TextFaint)
                    }
                    SettingRow("Start subtitles", onClick = { overlay = Overlay.SubtitleChoice }, description = "When a video starts", modifier = Modifier.padding(top = if (state.audioLanguagesAvailable) 16.dp else 0.dp)) {
                        Value(subtitles.choice.shortLabel, chevron = true)
                    }
                    // Inline, not the notice pill: that lasts 2.6 s, too short to read this.
                    if (state.showForeignAudioNotice) ForeignAudioNotice()

                    SubHeading("Subtitle languages", top = 16.dp)
                    Note("Use the first of these languages that has a subtitle.")
                    if (state.subtitlesFollowAudio && state.audioLanguagesAvailable) Note("Until you add one, subtitles use your audio languages.", Modifier.padding(top = 6.dp))
                    if (subtitles.languages.isEmpty()) {
                        val tag = displayLocale.toLanguageTag()
                        if (tag != "und") SettingRow("Use device language", onClick = { vm.addSubtitleLanguage(tag); refocus = subtitleFirst }) { Value(subtitleLanguageLabel(tag, displayLocale)) }
                    }
                    subtitles.languages.forEachIndexed { index, tag ->
                        SettingRow(
                            "${index + 1}. ${subtitleLanguageLabel(tag, displayLocale)}",
                            onClick = { languageList = LanguageList.Subtitles; editingLanguage = tag; overlay = Overlay.LanguageActions },
                            modifier = if (index == 0) Modifier.focusRequester(subtitleFirst) else Modifier,
                        ) { Value("Options", chevron = true) }
                    }
                    SettingRow("Add language", onClick = { languageList = LanguageList.Subtitles; overlay = Overlay.AddLanguage }, modifier = Modifier.focusRequester(subtitleAdd)) { Value("", chevron = true) }
                    val automatic = subtitles.automaticAvailable
                    val on = automatic && subtitles.automaticDownload
                    SettingRow(
                        "Automatic download",
                        onClick = { if (automatic) vm.setAutomaticSubtitles(!subtitles.automaticDownload) },
                        description = if (automatic) "When subtitles would start and no local track matches, try one exact-file match from OpenSubtitles.com after the video starts. Uses the server’s download allowance."
                        else state.automaticBlockedNote,
                        stacked = true, labelColor = if (automatic) WmmColors.Text else WmmColors.TextMuted,
                        modifier = Modifier.switch(on, enabled = automatic),
                    ) { Toggle(on, Modifier.alpha(if (automatic) 1f else 0.45f)) }
                    if (state.subtitleSaving) Text("Saving…", style = inter(17.sp), color = WmmColors.TextMuted)
                }
                Section("Skip") {
                    for (kind in SKIP_KINDS) {
                        SkipRow(kind.settingsLabel, skipNote(kind), state.playback.skipMode(kind)) { vm.setSkip(kind, it) }
                    }
                    Text(
                        "Ask shows a Skip button. Auto skips after one second. Auto after 3 s shows the button filling up, then skips. A segment you seek into is never skipped for you.",
                        style = inter(17.sp, lineHeight = 25.sp),
                        color = WmmColors.TextFaint,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
                    )
                }
                if (reminderState.available && reminderState.liveTv) Section("Live TV") {
                    SettingRow(
                        "Reminders",
                        onClick = { overlay = Overlay.ReminderLead },
                        description = "How long before a programme starts. The same on all your devices.",
                        stacked = true,
                    ) { Value(liveLeadBefore(reminderState.leadMinutes), chevron = true) }
                    Text(LIVE_REMINDERS_NOTE_TV, style = inter(17.sp, lineHeight = 25.sp), color = WmmColors.TextFaint, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))
                }
                // Home rows (android-tv/SettingsHomeRows.dc.html): from servers that arrange rows.
                if ((server?.apiLevel ?: 0) >= ROWS_API_LEVEL) HomeRowsSettings(rowsScreen, rowsFocus)
            }

            Column(
                Modifier.padding(start = 1232.dp, top = 180.dp).width(638.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                val badge = LocalRequestBadge.current
                if (badge.requests || badge.inbox != null) {
                    Section("Requests") {
                        if (badge.requests) {
                            SettingRow("Your requests", onClick = { onOpenRequests(false) }, description = "Where each one stands") { Value("", chevron = true) }
                        }
                        val inbox = badge.inbox
                        if (inbox != null) {
                            SettingRow("Inbox", onClick = { onOpenRequests(true) }, description = "Requests and problems") {
                                Value(if (inbox > 0) "$inbox waiting" else "", chevron = true)
                            }
                        }
                    }
                }
                Section("This device") {
                    SettingRow("Device name", onClick = { overlay = Overlay.DeviceName }) { Value(state.deviceName, chevron = true) }
                    Text(
                        "${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                        style = inter(17.sp),
                        color = WmmColors.TextFaint,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
                Section("Signed-in devices") {
                    when (val s = state.sessions) {
                        Load.Loading -> Text("Loading…", style = inter(17.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                        is Load.Failed -> SettingRow("Couldn't load the list", onClick = vm::loadSessions, description = s.message) { Value("Try again") }
                        is Load.Ready -> {
                            if (s.data.isEmpty()) Text("No other devices.", style = inter(17.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
                            for (d in s.data) DeviceRow(d, revoking = state.revoking == d.id, onRevoke = { vm.revoke(d) })
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton(
                        if (state.signingOut) "Signing out…" else "Sign out of this TV",
                        vm::signOut,
                        style = TvButtonStyle.Primary,
                        size = TvButtonSize.Settings,
                        enabled = !state.signingOut,
                    )
                    TvButton("Switch server", onSwitchServer, size = TvButtonSize.Settings)
                }
                Section("About") {
                    AboutRow("Server", server?.let { "${it.name} · ${versionLabel(it.version)} · ${displayAddress(it.baseUrl)}" } ?: "Not chosen")
                    AboutRow("App", "${services.client.product} ${services.client.version}")
                    AboutRow("Admin tools", server?.let { "In the web app at ${displayAddress(it.baseUrl)}" } ?: "In the web app")
                    SettingRow("Privacy policy", onClick = { overlay = Overlay.Privacy }, minHeight = 56.dp, labelSize = 19) {
                        Value(PRIVACY_POLICY_URL.removePrefix("https://"), chevron = true)
                    }
                    SettingRow("Open-source licences", onClick = { overlay = Overlay.Licences }, minHeight = 56.dp, labelSize = 19) {
                        Value("", chevron = true)
                    }
                    // TMDB's terms ask apps that use its API to say so (PLAN-ROWS.md §3).
                    Note(TMDB_NOTICE, Modifier.padding(top = 6.dp), color = WmmColors.TextFaint)
                }
            }
        }
        Notice(state.message, vm::messageShown)
    }
    InitialFocus(if (rowsScreen != null && (server?.apiLevel ?: 0) >= ROWS_API_LEVEL) rowsFocus else first)

    when (overlay) {
        Overlay.Quality -> OptionsDialog(
            title = "Default quality",
            options = state.qualities,
            selected = state.quality,
            label = { it.label },
            description = ::qualityDescription,
            onSelect = {
                overlay = null
                vm.setQuality(it)
            },
            onDismiss = { overlay = null },
        )
        Overlay.Passthrough -> OptionsDialog(
            title = "Audio passthrough",
            options = AudioPassthrough.entries,
            selected = state.device.audioPassthrough,
            label = { it.label },
            description = { it.description },
            onSelect = {
                overlay = null
                vm.setAudioPassthrough(it)
            },
            onDismiss = { overlay = null },
            footer = {
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
        Overlay.SubtitleChoice -> OptionsDialog(
            title = "Start subtitles", context = "When a video starts", options = state.subtitleChoices, selected = state.playback.subtitles.choice,
            label = { it.label }, description = { it.detail },
            onSelect = { vm.setSubtitleChoice(it); overlay = null }, onDismiss = { overlay = null },
            footer = {
                Text("A track you choose in the player always wins.", style = inter(17.sp, lineHeight = 25.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
        Overlay.AddLanguage -> {
            val chosen = if (languageList == LanguageList.Audio) state.playback.audio.languages else state.playback.subtitles.languages
            OptionsDialog(
                title = "Add language", context = languageList.title, options = languageChoices.filter { it.tag !in chosen }, selected = languageChoices.first(),
                label = { it.label },
                onSelect = {
                    if (languageList == LanguageList.Audio) vm.addAudioLanguage(it.tag) else vm.addSubtitleLanguage(it.tag)
                    overlay = null
                },
                onDismiss = { overlay = null },
            )
        }
        Overlay.LanguageActions -> {
            val tag = editingLanguage.orEmpty()
            val audio = languageList == LanguageList.Audio
            val languages = if (audio) state.playback.audio.languages else state.playback.subtitles.languages
            val index = languages.indexOf(tag)
            val actions = buildList {
                if (index > 0) add("Move up")
                if (index >= 0 && index < languages.lastIndex) add("Move down")
                add("Remove language")
            }
            OptionsDialog(title = subtitleLanguageLabel(tag, displayLocale), context = languageList.title, options = actions, selected = actions.first(), label = { it },
                onSelect = {
                    when (it) {
                        "Move up" -> if (audio) vm.moveAudioLanguage(tag, -1) else vm.moveSubtitleLanguage(tag, -1)
                        "Move down" -> if (audio) vm.moveAudioLanguage(tag, 1) else vm.moveSubtitleLanguage(tag, 1)
                        else -> {
                            if (audio) vm.removeAudioLanguage(tag) else vm.removeSubtitleLanguage(tag)
                            refocus = if (audio) audioAdd else subtitleAdd
                        }
                    }
                    overlay = null
                }, onDismiss = { overlay = null },
            )
        }
        Overlay.SubtitleSize -> OptionsDialog(
            title = "Subtitle size",
            options = SubtitleSize.entries,
            selected = state.device.subtitleSize,
            label = { it.label },
            onSelect = {
                overlay = null
                vm.setSubtitleSize(it)
            },
            onDismiss = { overlay = null },
            footer = {
                SubtitlePreview(state.device.subtitleSize, Modifier.padding(top = 8.dp))
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
        Overlay.DeviceName -> KeyboardDialog(
            title = "Device name",
            initial = state.deviceName,
            note = "People see this name in Signed-in devices and Activity.",
            submitLabel = "Save",
            onDismiss = { overlay = null },
            onSubmit = {
                overlay = null
                vm.rename(it)
            },
        )
        Overlay.ReminderLead -> OptionsDialog(
            title = "Reminders",
            options = LIVE_REMINDER_LEADS,
            selected = reminderState.leadMinutes,
            label = ::liveLeadBefore,
            description = ::liveLeadNote,
            onSelect = {
                overlay = null
                reminders.setLead(it)
            },
            onDismiss = { overlay = null },
            footer = {
                Text("How long before a programme starts. The same on all your devices.", style = inter(18.sp, lineHeight = 26.sp), color = WmmColors.TextMuted)
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
        Overlay.Privacy -> PrivacyDialog(onDismiss = { overlay = null })
        Overlay.Licences -> LicencesDialog(onDismiss = { overlay = null })
        null -> Unit
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            style = inter(17.sp, FontWeight.Bold, tracking = 0.1f),
            color = WmmColors.TextMuted,
            modifier = Modifier.padding(start = 0.dp, bottom = 2.dp),
        )
        content()
    }
}

/**
 * A settings row (`.row`): label, grey description, and a value or control
 * on the right; the inset ring when focused. [stacked] puts a longer
 * description under the label (`.row.two`).
 */
@Composable
internal fun SettingRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    stacked: Boolean = false,
    minHeight: Dp = if (stacked) 88.dp else 68.dp,
    labelSize: Int = 21,
    labelColor: Color = WmmColors.Text,
    trailing: @Composable () -> Unit,
) {
    FocusableBox(onClick = onClick, modifier = modifier.fillMaxWidth(), cornerRadius = 12.dp, focusedScale = 1f, ringOffset = (-4).dp) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = if (stacked) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = inter(labelSize.sp, FontWeight.SemiBold), color = labelColor, maxLines = 1)
                    if (description != null) Text(description, style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted)
                }
            } else {
                Text(label, style = inter(labelSize.sp, FontWeight.SemiBold), color = labelColor, maxLines = 1)
                if (description != null) Text(description, style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                else Box(Modifier.weight(1f))
            }
            trailing()
        }
    }
}

/** A row that TalkBack reads as a switch, on or off, and dimmed when it can't change. */
private fun Modifier.switch(on: Boolean, enabled: Boolean = true): Modifier = semantics {
    role = Role.Switch
    toggleableState = ToggleableState(on)
    if (!enabled) disabled()
}

/** A heading inside a section (`.sh`): Audio languages, Subtitle languages. */
@Composable
private fun SubHeading(text: String, top: Dp = 0.dp) {
    Text(text, style = inter(20.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = top).semantics { heading() })
}

/** A grey line under a heading or a list (`.note`, `.foot`). */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: Color = WmmColors.TextMuted) {
    Text(text, style = inter(17.sp, lineHeight = 25.sp), color = color, modifier = modifier.padding(horizontal = 18.dp))
}

/** Said once, after the server turned subtitles for foreign audio on with the first audio languages. */
@Composable
private fun ForeignAudioNotice() {
    Row(
        Modifier
            .padding(top = 2.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(WmmColors.Surface3, RoundedCornerShape(12.dp))
            .border(1.dp, WmmColors.Line3, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(WmmIcons.Info, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.padding(top = 1.dp).size(26.dp))
        Text(
            "Subtitles now start when the audio isn’t in your languages. You can change this under Start subtitles.",
            style = inter(19.sp, lineHeight = 27.sp), color = WmmColors.Text,
        )
    }
}

@Composable
internal fun Value(text: String, chevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (text.isNotEmpty()) Text(text, style = inter(19.sp), color = WmmColors.TextSoft, maxLines = 1)
        if (chevron) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

/** The gold switch (`.toggle`); off, a grey rim and a smaller knob (`.toggle.off`). */
@Composable
private fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .size(64.dp, 36.dp)
            .then(if (on) Modifier.background(WmmColors.Accent, shape) else Modifier.background(WmmColors.Surface2, shape).border(2.dp, WmmColors.FieldOutline, shape))
            .padding(if (on) 4.dp else 6.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(if (on) 28.dp else 20.dp).background(if (on) WmmColors.OnAccent else WmmColors.FieldOutline, CircleShape))
    }
}

/** How [size] reads over a picture: the subtitle size panel's Preview. */
@Composable
private fun SubtitlePreview(size: SubtitleSize, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PREVIEW", style = inter(17.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PreviewScene)
                .radialGlow(Color(0xFFFFCD78).copy(alpha = 0.16f), 0.6f, 0.44f, 420.dp, 180.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.BottomCenter,
        ) {
            val px = 28 * size.scale
            Text(
                "Who moved the markers, Maren?",
                style = inter(px.sp, FontWeight.SemiBold, lineHeight = (px * 1.35f).sp),
                color = WmmColors.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** The preview's harbour at dusk (`linear-gradient(180deg, …)`). */
private val PreviewScene = Brush.verticalGradient(
    0f to Color(0xFF0C1A22),
    0.44f to Color(0xFF1B3542),
    0.46f to Color(0xFF0A1216),
    1f to Color(0xFF06090B),
)

/**
 * A skip row: the label and a four-way segmented control (`.seg`). Each
 * segment takes focus; coming into the row lands on the selected one, and
 * the row lights up while any segment has focus, as in the mockup.
 */
@Composable
private fun SkipRow(label: String, note: String?, mode: SkipMode, onSelect: (SkipMode) -> Unit) {
    var rowFocused by remember { mutableStateOf(false) }
    val selected = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { rowFocused = it.hasFocus }
            .background(if (rowFocused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp))
            .then(
                if (rowFocused) Modifier.border(4.dp, WmmColors.Accent, RoundedCornerShape(12.dp)) else Modifier,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text)
            if (note != null) Text(note, style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else Box(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(WmmColors.Surface2)
                    .border(1.dp, WmmColors.Line2, RoundedCornerShape(10.dp))
                    .focusProperties { onEnter = { selected.requestFocus() } }
                    .focusGroup(),
            ) {
                for (m in SkipMode.entries) {
                    val on = m == mode
                    FocusableBox(
                        onClick = { onSelect(m) },
                        modifier = if (on) Modifier.focusRequester(selected) else Modifier,
                        cornerRadius = 0.dp,
                        focusedScale = 1f,
                        ringOffset = (-4).dp,
                        // Gold on the gold selected segment would not show.
                        ringColor = if (on) WmmColors.OnAccent else WmmColors.Accent,
                    ) {
                        Box(Modifier.height(44.dp).background(if (on) WmmColors.Accent else Color.Transparent).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(m.label, style = inter(17.sp, FontWeight.SemiBold), color = if (on) WmmColors.OnAccent else WmmColors.TextSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceSession, revoking: Boolean, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.name, style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(d.model ?: d.kindLabel, if (d.current) "now" else formatAgo(d.lastSeenAtMs)).joinToString(" · "),
                style = inter(15.sp),
                color = WmmColors.TextMuted,
                maxLines = 1,
            )
        }
        if (d.current) {
            Text(
                "THIS TV",
                modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = inter(13.sp, FontWeight.Bold, tracking = 0.05f),
                color = WmmColors.OnAccent,
            )
        } else {
            TvButton(if (revoking) "Signing out…" else "Sign out", onRevoke, size = TvButtonSize.Compact, enabled = !revoking)
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Text)
        Box(Modifier.weight(1f))
        Text(value, style = inter(17.sp), color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The privacy policy: a TV has no browser, so its address and a QR code to open it on a phone. */
@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
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
private fun LicencesDialog(onDismiss: () -> Unit) {
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
