package media.watchmy.client.tv.settings

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.SKIP_KINDS
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.subtitleLanguageLabel
import media.watchmy.client.model.subtitleLanguageOptions
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.label
import media.watchmy.client.model.settingsLabel
import media.watchmy.client.model.versionLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.tv.components.BackClosesNote
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.KeyboardDialog
import media.watchmy.client.tv.components.LocalRequestBadge
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.model.LIVE_REMINDERS_NOTE_TV
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.liveLeadBefore
import media.watchmy.client.model.liveLeadNote
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.TMDB_NOTICE
import media.watchmy.client.ui.PRIVACY_POLICY_URL
import media.watchmy.client.ui.WmmColors

private enum class Overlay { Quality, Passthrough, SubtitleSize, SubtitleChoice, AddLanguage, LanguageActions, DeviceName, Privacy, Licences, ReminderLead, Diagnostics }

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
    // The row focused last, kept with the screen: back from Your requests, or from another section, focus is where it was.
    val memory = rememberFocusMemory()
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 40.dp.toPx(), trailing = 40.dp.toPx()) } }
    val server = state.server?.server
    // Live TV › Reminders (android-tv/LiveReminder's settings): the lead time, the same on all the person's devices.
    val reminders = viewModel(key = "tv-reminder-settings") { LiveRemindersViewModel(services.live, services.servers) }
    val reminderState by reminders.state.collectAsStateWithLifecycle()

    TvScaffold(RailSection.Settings, onNavigate) { CompositionLocalProvider(LocalSettingsFocus provides memory) {
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
                                focusKey = "audio:$tag",
                            ) { Value("Options", chevron = true) }
                        }
                        SettingRow("Add language", onClick = { languageList = LanguageList.Audio; overlay = Overlay.AddLanguage }, modifier = Modifier.focusRequester(audioAdd), focusKey = "audio:add") { Value("", chevron = true) }
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
                            focusKey = "subtitles:$tag",
                        ) { Value("Options", chevron = true) }
                    }
                    SettingRow("Add language", onClick = { languageList = LanguageList.Subtitles; overlay = Overlay.AddLanguage }, modifier = Modifier.focusRequester(subtitleAdd), focusKey = "subtitles:add") { Value("", chevron = true) }
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
                        Modifier.focusMemory(memory, "sign-out"),
                        style = TvButtonStyle.Primary,
                        size = TvButtonSize.Settings,
                        enabled = !state.signingOut,
                    )
                    TvButton("Switch server", onSwitchServer, Modifier.focusMemory(memory, "switch-server"), size = TvButtonSize.Settings)
                }
                Section("About") {
                    AboutRow("Server", server?.let { "${it.name} · ${versionLabel(it.version)} · ${displayAddress(it.baseUrl)}" } ?: "Not chosen")
                    AboutRow("App", "${services.client.product} ${services.client.version}")
                    AboutRow("Admin tools", server?.let { "In the web app at ${displayAddress(it.baseUrl)}" } ?: "In the web app")
                    // The app's last exits, for a bug report (android-tv/TvDiagnostics).
                    SettingRow("Copy diagnostics", onClick = { overlay = Overlay.Diagnostics }, description = "The app’s last exits and why, for a bug report", stacked = true, labelSize = 19) {
                        Value("", chevron = true)
                    }
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
    } }
    val opensOnRows = rowsScreen != null && (server?.apiLevel ?: 0) >= ROWS_API_LEVEL
    LaunchedEffect(opensOnRows) {
        withFrameNanos { }
        if (!memory.restore(fallback = null)) (if (opensOnRows) rowsFocus else first).requestFocus()
    }

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
        Overlay.Diagnostics -> DiagnosticsPanel(
            services.diagnostics,
            app = "${services.client.product} ${services.client.version} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}",
            onDismiss = { overlay = null },
        )
        null -> Unit
    }
}
