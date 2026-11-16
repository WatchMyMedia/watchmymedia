package media.watchmy.client.tv.settings

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.LIVE_REMINDERS_NOTE_TV
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.ON_EVERY_DEVICE
import media.watchmy.client.model.REVOKE_BODY
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.SKIP_SEEK_NOTE
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.ServerFeatures
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.aboutSummary
import media.watchmy.client.model.accountSettingsUrl
import media.watchmy.client.model.GO_TO_SHOW
import media.watchmy.client.model.SHOWS_YOU_FOLLOW
import media.watchmy.client.model.STOP_NOTIFYING
import media.watchmy.client.model.accountSummary
import media.watchmy.client.model.line
import media.watchmy.client.model.notificationsTvSummary
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.explanation
import media.watchmy.client.model.label
import media.watchmy.client.model.languagesSummary
import media.watchmy.client.model.liveLeadBefore
import media.watchmy.client.model.liveLeadNote
import media.watchmy.client.model.olderServerNotice
import media.watchmy.client.model.playbackSummary
import media.watchmy.client.model.requestsSummary
import media.watchmy.client.model.revokeTitle
import media.watchmy.client.model.rowsSummary
import media.watchmy.client.model.serverSettingsUrl
import media.watchmy.client.model.settingsTheServerLacks
import media.watchmy.client.model.signOutStays
import media.watchmy.client.model.signOutTitle
import media.watchmy.client.model.skipRowLabel
import media.watchmy.client.model.subtitleLanguageLabel
import media.watchmy.client.model.subtitleLanguageOptions
import media.watchmy.client.model.versionLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.live.LiveRemindersViewModel
import media.watchmy.client.presentation.requests.MyRequestsViewModel
import media.watchmy.client.presentation.rows.RowLayoutViewModel
import media.watchmy.client.presentation.settings.AccountLoad
import media.watchmy.client.presentation.settings.LanguageAction
import media.watchmy.client.presentation.settings.LanguageList
import media.watchmy.client.presentation.settings.NotificationsViewModel
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.tv.components.BackClosesNote
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.KeyboardDialog
import media.watchmy.client.tv.components.LocalRequestBadge
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.rememberFocusMemory
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.onBack

/** Settings' panels and confirmations; one is open at a time. */
internal enum class Overlay {
    Quality, Passthrough, SubtitleSize, SubtitleChoice, AddLanguage, LanguageActions, DeviceName, Privacy, Licences, ReminderLead, Diagnostics,
    Skip, ChangePassword, SignOut, Revoke, FollowedShow,
}

/** A followed show's side panel (the boards' `tv-settings`): its page, or stop notifying. */
private enum class FollowAction(val label: String) { GoToShow(GO_TO_SHOW), Stop(STOP_NOTIFYING) }

/** What a quality does, under it in Settings (SettingsQuality.dc.html). */
internal fun qualityDescription(quality: StreamQuality): String = when (quality) {
    StreamQuality.Auto -> "Original first, lower when it can’t keep up"
    StreamQuality.Original -> "Direct play when the device can"
    else -> "Transcode to fit"
}

private const val GROUP_KEY = "group:"

/** A group's key in Settings' focus memory. */
private fun groupKey(group: SettingsGroup) = GROUP_KEY + group.name

/** The key of the row at the top of a pane when the account's settings didn't load. */
private const val NOT_LOADED_KEY = "account-not-loaded"

/** A group at the left, with what is chosen in it. */
@Immutable
private data class GroupLine(val group: SettingsGroup, val title: String, val line: String)

/**
 * Focus between the groups and the open group's pane: [enter] goes to the
 * row left last time in that pane ([lastRow], by group), else its top row
 * ([first]); [toGroup] goes back to the open group. False where there is
 * nowhere to go (a pane without rows), so focus stays where it is.
 */
@Stable
private class PaneFocus(val memory: FocusMemory, openState: MutableState<SettingsGroup>, private val lastRow: SnapshotStateMap<SettingsGroup, String>) {
    var open: SettingsGroup by openState

    /** On the open pane's top row. */
    val first = FocusRequester()

    fun enter(): Boolean = lastRow[open]?.let(memory::focus) == true || first.tryFocus()

    fun toGroup(): Boolean = memory.focus(groupKey(open)) || memory.focus(groupKey(SettingsGroup.Playback))

    /** Notes that [key], a row in the open pane, has focus. */
    fun rowFocused(key: String) {
        lastRow[open] = key
    }
}

/** [PaneFocus]'s rows left last time, kept with the screen as group and key pairs. */
private val LastRowSaver = listSaver<SnapshotStateMap<SettingsGroup, String>, String>(
    save = { rows -> rows.flatMap { (group, key) -> listOf(group.name, key) } },
    restore = { saved -> mutableStateMapOf<SettingsGroup, String>().apply { saved.chunked(2).forEach { (group, key) -> put(SettingsGroup.valueOf(group), key) } } },
)

/**
 * Settings as groups (android-tv/SettingsGroups.dc.html,
 * PLAN-ANDROID-SETTINGS.md §4.3): the groups down the left in
 * [SettingsGroup] order, each with what is chosen in it, and the focused
 * group's rows on the right. Moving focus through the groups swaps the
 * pane, with no press; ▶ or OK goes into it, on its top row or the row left
 * last time; ◀ or Back goes back to the group, which stays marked while
 * focus is in its pane; ▲▼ only move within a list. Back from the groups
 * leaves Settings, and nothing asks first. Every row is one focus stop, and
 * a choice opens the side panel, where OK saves, closes it and puts focus
 * back on the row. Signing out, signing another device out and resetting
 * rows ask first ([ConfirmDialog]).
 *
 * Downloads is the phone's; Audio & subtitles and Home rows show where the
 * server serves them; Your requests where requests are possible; Inbox and
 * Server settings for admins. [onOpenRequests] takes whether to open on the
 * Inbox. [rowsScreen] opens on Home rows for that screen (a library's Edit
 * rows, PLAN-ROWS.md §8.2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onSwitchServer: () -> Unit,
    onSignedOut: () -> Unit,
    onNavigate: (Any) -> Unit,
    onOpenRequests: (inbox: Boolean) -> Unit = {},
    rowsScreen: String? = null,
) {
    val services = LocalAppServices.current
    val vm = viewModel { SettingsViewModel(services.auth, services.servers, services.preferences) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { if (it == SettingsViewModel.Event.SignedOut) onSignedOut() } }
    val server = state.server?.server
    val apiLevel = server?.apiLevel ?: 0
    val user = state.user
    val badge = LocalRequestBadge.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val languageChoices = remember(displayLocale) { subtitleLanguageOptions(displayLocale) }
    // Live TV reminders (android-tv/LiveReminder's settings): the lead time, the same on all the person's devices.
    val reminders = viewModel(key = "tv-reminder-settings") { LiveRemindersViewModel(services.live, services.servers) }
    val reminderState by reminders.state.collectAsStateWithLifecycle()
    val rowsAvailable = apiLevel >= ROWS_API_LEVEL
    // The same view models the Home rows pane and Your requests draw, for the groups' state lines.
    val homeRows = if (rowsAvailable) viewModel(key = "tv-rows-${RowScreens.HOME}") { RowLayoutViewModel(services.rows, RowScreens.HOME, "Home", autosave = true) } else null
    val homeRowsState = homeRows?.state?.collectAsStateWithLifecycle()?.value
    val myRequests = if (badge.requests) viewModel(key = "tv-settings-requests") { MyRequestsViewModel(services.requests) } else null
    val myRequestsState = myRequests?.state?.collectAsStateWithLifecycle()?.value
    // Following shows (PLAN-NEW-EPISODES.md §7.2): the list, for the group's line and its pane; this TV doesn't notify.
    val followsAvailable = ServerFeatures(apiLevel).follows
    val notifications = if (followsAvailable) viewModel(key = "tv-notifications") { NotificationsViewModel(services.follows) } else null
    val notificationsState = notifications?.state?.collectAsStateWithLifecycle()?.value
    var followedShow by rememberSaveable { mutableStateOf<Long?>(null) }

    // Your requests' line, in the group and on its pane; "Where each one stands" until they have loaded.
    val requestsLine = (myRequestsState?.mine as? Load.Ready)?.data?.let { requestsSummary(it) } ?: "Where each one stands"
    val lines = buildList {
        add(GroupLine(SettingsGroup.Playback, SettingsGroup.Playback.title, playbackSummary(state.quality, state.playback.autoplayNext)))
        if (state.subtitlePreferencesAvailable) {
            add(GroupLine(SettingsGroup.Languages, state.languagesTitle, languagesSummary(state.playback, state.audioLanguagesAvailable, displayLocale)))
        }
        if (rowsAvailable) {
            val rows = homeRowsState?.takeIf { it.layout is Load.Ready }?.entries
            add(GroupLine(SettingsGroup.HomeRows, SettingsGroup.HomeRows.title, rows?.let { r -> rowsSummary(r.count { !it.hidden }, r.count { it.hidden }, onHome = false) } ?: "The rows on Home"))
        }
        if (notificationsState != null) {
            val line = when (notificationsState.list) {
                is Load.Ready -> notificationsTvSummary(notificationsState.count ?: 0)
                is Load.Failed -> "Didn’t load"
                Load.Loading -> SHOWS_YOU_FOLLOW
            }
            add(GroupLine(SettingsGroup.Notifications, SettingsGroup.Notifications.title, line))
        }
        add(GroupLine(SettingsGroup.Account, SettingsGroup.Account.title, accountSummary(user?.name.orEmpty(), (state.sessions as? Load.Ready)?.data?.size)))
        if (badge.requests) {
            add(GroupLine(SettingsGroup.Requests, SettingsGroup.Requests.title, requestsLine))
        }
        add(GroupLine(SettingsGroup.About, SettingsGroup.About.title, aboutSummary(services.client.version, server?.name, server?.version?.removePrefix("v"))))
        if (badge.inbox != null) add(GroupLine(SettingsGroup.Inbox, SettingsGroup.Inbox.title, inboxSummary(badge.inbox)))
        if (user?.isAdmin == true && server != null) add(GroupLine(SettingsGroup.ServerSettings, SettingsGroup.ServerSettings.title, "On your phone or computer"))
    }

    val openState = rememberSaveable { mutableStateOf(if (rowsScreen != null) SettingsGroup.HomeRows else SettingsGroup.Playback) }
    val lastRow = rememberSaveable(saver = LastRowSaver) { mutableStateMapOf() }
    // The row or group focused last, kept with the screen: back from Your requests, focus is where it was.
    val memory = rememberFocusMemory()
    val panes = remember { PaneFocus(memory, openState, lastRow) }
    LaunchedEffect(memory) { snapshotFlow { memory.key }.collect { key -> if (key != null && !key.startsWith(GROUP_KEY)) panes.rowFocused(key) } }
    val shown = lines.firstOrNull { it.group == panes.open } ?: lines.first()
    val rowsFocus = remember { FocusRequester() }

    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var skipKind by rememberSaveable { mutableStateOf(SegmentKind.Intro) }
    var revokeId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var languageList by rememberSaveable { mutableStateOf(LanguageList.Subtitles) }
    val languageFocus = remember { LanguageFocus() }
    var retryingAccount by remember { mutableStateOf(false) }
    LaunchedEffect(state.account) { if (state.account !is AccountLoad.Loading) retryingAccount = false }

    val switchServer by rememberUpdatedState(onSwitchServer)
    val openRequests by rememberUpdatedState(onOpenRequests)
    val calls = remember(vm) {
        object : SettingsCalls {
            override fun show(overlay: Overlay) {
                overlayTo(overlay)
            }

            override fun skip(kind: SegmentKind) {
                skipKind = kind
                overlayTo(Overlay.Skip)
            }

            override fun revoke(session: DeviceSession) {
                vm.clearConfirmation()
                revokeId = session.id
                overlayTo(Overlay.Revoke)
            }

            override fun retry(key: SettingKey) = vm.retry(key)

            override fun reloadAccount() {
                retryingAccount = true
                vm.reloadAccount()
            }

            override fun loadSessions() = vm.loadSessions()

            override fun setAutoplayNext(enabled: Boolean) = vm.setAutoplayNext(enabled)

            override fun setMatchFrameRate(enabled: Boolean) = vm.setMatchFrameRate(enabled)

            override fun setAutomaticSubtitles(enabled: Boolean) = vm.setAutomaticSubtitles(enabled)

            override fun addLanguage(list: LanguageList, tag: String) = vm.addLanguage(list, tag)

            override fun editLanguage(list: LanguageList, tag: String) {
                languageList = list
                editingLanguage = tag
                overlayTo(Overlay.LanguageActions)
            }

            override fun addTo(list: LanguageList) {
                languageList = list
                overlayTo(Overlay.AddLanguage)
            }

            override fun switchServer() = switchServer()

            override fun openRequests(inbox: Boolean) = openRequests(inbox)

            private fun overlayTo(next: Overlay) {
                overlay = next
            }
        }
    }

    // Where focus starts: where it was (back from a page Settings opened), else Home rows from a library's Edit rows, else the first group.
    LaunchedEffect(server != null) {
        if (server == null) return@LaunchedEffect
        withFrameNanos { }
        if (memory.restore(fallback = null)) return@LaunchedEffect
        if (rowsScreen != null && rowsAvailable && panes.open == SettingsGroup.HomeRows && rowsFocus.tryFocus()) return@LaunchedEffect
        panes.toGroup()
    }
    // The not-loaded row goes once the settings load: focus moves to the row that is now at the top.
    val noteShown by rememberUpdatedState(state.prefsError != null || retryingAccount)
    LaunchedEffect(state.accountReady) {
        if (!state.accountReady || memory.key != NOT_LOADED_KEY) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (!noteShown) {
                panes.first.tryFocus()
                return@LaunchedEffect
            }
        }
    }

    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 40.dp.toPx(), trailing = 40.dp.toPx()) } }
    val serverName = server?.name ?: "The server"
    val notLoadedDetail = when (val account = state.account) {
        is AccountLoad.Failed -> if (account.unreachable) "$serverName didn’t answer." else account.reason
        else -> "Loading them again…"
    }

    TvScaffold(RailSection.Settings, onNavigate) {
        CompositionLocalProvider(LocalSettingsFocus provides memory, LocalBringIntoViewSpec provides spec) {
            Column(Modifier.padding(start = ContentStart, top = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
                Text(
                    listOfNotNull(user?.name?.let { "Signed in as $it" }, server?.name).joinToString(" · "),
                    style = inter(20.sp),
                    color = WmmColors.TextMuted,
                )
            }
            GroupList(lines, panes, Modifier.padding(start = 136.dp, top = 172.dp).width(540.dp).fillMaxHeight())
            Box(Modifier.padding(start = 706.dp, top = 56.dp, bottom = 54.dp).width(1.dp).fillMaxHeight().background(WmmColors.Line))
            Pane(panes, shown, Modifier.padding(start = 740.dp).width(1084.dp).fillMaxHeight()) {
                when (shown.group) {
                    SettingsGroup.Playback -> PlaybackPane(
                        state,
                        reminderLead = reminderState.leadMinutes.takeIf { reminderState.available && reminderState.liveTv },
                        notLoadedDetail = "$notLoadedDetail The settings on this TV still work.",
                        retrying = retryingAccount,
                        first = panes.first,
                        calls = calls,
                    )
                    SettingsGroup.Languages -> LanguagesPane(state, displayLocale, languageFocus, notLoadedDetail, retryingAccount, panes.first, calls)
                    SettingsGroup.HomeRows -> HomeRowsSettings(rowsScreen, rowsFocus, panes.first)
                    SettingsGroup.Notifications -> if (notifications != null && notificationsState != null) NotificationsPane(
                        notificationsState,
                        services.library,
                        panes.first,
                        onShow = {
                            followedShow = it.tmdbId
                            overlay = Overlay.FollowedShow
                        },
                        onStop = notifications::stop,
                        onRetry = notifications::retry,
                        onEmptied = { panes.toGroup() },
                    )
                    SettingsGroup.Account -> AccountPane(state, server?.let { "${it.name} · ${displayAddress(it.baseUrl)}" }, panes.first, calls)
                    SettingsGroup.Requests -> RequestsPane(requestsLine, panes.first, calls)
                    SettingsGroup.About -> AboutPane(
                        serverLine = server?.let { "${it.name} · ${versionLabel(it.version)} · ${displayAddress(it.baseUrl)}" } ?: "Not chosen",
                        appLine = "${services.client.product} ${services.client.version}",
                        olderServer = server?.let { s ->
                            settingsTheServerLacks(ServerFeatures(s.apiLevel)).takeIf { it.isNotEmpty() }?.let { missing ->
                                olderServerNotice(s.name, versionLabel(s.version), missing, admin = user?.isAdmin == true).let { "${it.title}. ${it.body}" }
                            }
                        },
                        first = panes.first,
                        calls = calls,
                    )
                    SettingsGroup.Inbox -> InboxPane(badge.inbox, panes.first, calls)
                    SettingsGroup.ServerSettings -> if (server != null) ServerSettingsPane(serverSettingsUrl(server.baseUrl), server.name, user?.name)
                    SettingsGroup.Downloads -> Unit
                }
            }
        }
        Notice(state.notice?.title, vm::noticeShown)
        Notice(state.message, vm::messageShown)
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
            onSelect = {
                overlay = null
                vm.setSubtitleChoice(it)
            },
            onDismiss = { overlay = null },
            footer = {
                Text("A track you choose in the player always wins.", style = inter(17.sp, lineHeight = 25.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
        Overlay.AddLanguage -> {
            val chosen = vm.languages(languageList)
            OptionsDialog(
                title = "Add language", context = languageList.title, options = languageChoices.filter { it.tag !in chosen }, selected = languageChoices.first(),
                label = { it.label },
                onSelect = {
                    overlay = null
                    languageFocus.changed = languageList to it.tag
                    vm.addLanguage(languageList, it.tag)
                },
                onDismiss = { overlay = null },
            )
        }
        Overlay.LanguageActions -> {
            val tag = editingLanguage.orEmpty()
            val audio = languageList == LanguageList.Audio
            val actions = vm.languageActions(languageList, tag)
            OptionsDialog(
                title = subtitleLanguageLabel(tag, displayLocale), context = languageList.title, options = actions, selected = actions.first(), label = { it.label },
                onSelect = {
                    overlay = null
                    languageFocus.changed = languageList to tag
                    vm.act(languageList, tag, it)
                    if (it == LanguageAction.Remove) languageFocus.refocus = if (audio) languageFocus.audioAdd else languageFocus.subtitleAdd
                },
                onDismiss = { overlay = null },
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
        // A skip row's four modes, each with what it does (android-tv/SettingsGroupPlayback.dc.html, Tweak `overlay`: `choices`).
        Overlay.Skip -> OptionsDialog(
            title = skipKind.skipRowLabel,
            context = "Playback · your account",
            options = SkipMode.entries,
            selected = state.playback.skipMode(skipKind),
            label = { it.label },
            description = { it.explanation(skipKind) },
            onSelect = {
                overlay = null
                vm.setSkip(skipKind, it)
            },
            onDismiss = { overlay = null },
            footer = {
                Note(SKIP_SEEK_NOTE)
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
            title = "Live TV reminders",
            context = ON_EVERY_DEVICE,
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
                Text("How long before a programme starts. $LIVE_REMINDERS_NOTE_TV", style = inter(17.sp, lineHeight = 25.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
        Overlay.ChangePassword -> server?.let { ChangePasswordPanel(accountSettingsUrl(it.baseUrl), onDismiss = { overlay = null }) }
        Overlay.SignOut -> ConfirmDialog(
            ConfirmWords(signOutTitle("TV"), keep = "Stay signed in", go = "Sign out of this TV", busy = "Signing out…"),
            working = state.signingOut,
            failure = state.signOutFailure?.let { reason ->
                "Couldn’t sign out. This TV is still signed in." + if (reason.startsWith("Couldn")) "" else " $reason"
            },
            onKeep = {
                overlay = null
                vm.clearConfirmation()
            },
            onGo = vm::signOut,
        ) {
            ConfirmPart("Stays", signOutStays(server?.name ?: "this server"))
            ConfirmPart(null, "This TV goes back to the sign-in screen and shows a code to link it again.", color = WmmColors.TextSoft)
        }
        Overlay.Revoke -> {
            val session = (state.sessions as? Load.Ready)?.data?.firstOrNull { it.id == revokeId }
            if (session == null) {
                // Signed out: the dialog closes, the device's row goes, and focus moves on (AccountPane).
                LaunchedEffect(Unit) {
                    overlay = null
                    vm.clearConfirmation()
                }
            } else {
                ConfirmDialog(
                    ConfirmWords(revokeTitle(session.name), keep = "Keep it signed in", go = "Sign out ${session.name}", busy = "Signing out…"),
                    working = state.revoking == session.id,
                    failure = state.revokeFailure?.takeIf { it.sessionId == session.id }?.reason,
                    onKeep = {
                        overlay = null
                        vm.clearConfirmation()
                    },
                    onGo = { vm.revoke(session) },
                ) { ConfirmPart(null, REVOKE_BODY) }
            }
        }
        Overlay.Privacy -> PrivacyDialog(onDismiss = { overlay = null })
        Overlay.Licences -> LicencesDialog(onDismiss = { overlay = null })
        Overlay.FollowedShow -> {
            val show = (notificationsState?.list as? Load.Ready)?.data?.firstOrNull { it.tmdbId == followedShow }
            if (show == null || notifications == null) {
                // The show went (stopped on another device): the panel goes with it.
                SideEffect { overlay = null }
            } else {
                // A show no longer in the library has only Stop notifying.
                val actions = if (show.showId == null) listOf(FollowAction.Stop) else FollowAction.entries
                OptionsDialog(
                    title = show.title,
                    kicker = SHOWS_YOU_FOLLOW,
                    context = show.line(),
                    options = actions,
                    selected = actions.first(),
                    label = { it.label },
                    onSelect = {
                        overlay = null
                        when (it) {
                            FollowAction.GoToShow -> show.showId?.let { id -> onNavigate(TvRoutes.Show(id)) }
                            FollowAction.Stop -> notifications.stop(show)
                        }
                    },
                    onDismiss = { overlay = null },
                    footer = {
                        Box(Modifier.weight(1f))
                        BackClosesNote()
                    },
                )
            }
        }
        Overlay.Diagnostics -> DiagnosticsPanel(
            services.diagnostics,
            app = "${services.client.product} ${services.client.version} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}",
            onDismiss = { overlay = null },
        )
        null -> Unit
    }
}

/** `.grp.open`: the open group while focus is in its pane. */
private val OpenFill = Color(0xFF17171A)

/** The groups down the left, the Server ones under their heading. */
@Composable
private fun GroupList(lines: List<GroupLine>, panes: PaneFocus, modifier: Modifier = Modifier) {
    Column(
        modifier
            // ▶ goes into the pane on the row left last time, or its top row, not the row nearest the group.
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && panes.enter() }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val firstServer = lines.firstOrNull { it.group.server }?.group
        for (line in lines) {
            key(line.group) {
                if (line.group == firstServer) {
                    Text(
                        "SERVER",
                        style = inter(17.sp, FontWeight.Bold, tracking = 0.1f),
                        color = WmmColors.TextMuted,
                        modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 2.dp).semantics { heading() },
                    )
                }
                GroupRow(line, open = line.group == panes.open, panes)
            }
        }
    }
}

/**
 * A group (`.grp`): its name and state line. Focus on it opens its pane; OK
 * goes into the pane. Its focus modifier and click are remembered by group:
 * moving focus changes the screen's state, and built inline they would be
 * new on every move, so every group drew again (android/AGENTS.md).
 */
@Composable
private fun GroupRow(line: GroupLine, open: Boolean, panes: PaneFocus) {
    val group = line.group
    val focus = remember(group) { Modifier.focusMemory(panes.memory, groupKey(group)).onFocusChanged { if (it.isFocused) panes.open = group } }
    val enter = remember(group) { { panes.enter(); Unit } }
    FocusableBox(
        onClick = enter,
        modifier = focus.fillMaxWidth().semantics {
            role = Role.Tab
            selected = open
        },
        cornerRadius = 14.dp,
        focusedScale = 1f,
        ringOffset = (-4).dp,
    ) { focused ->
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .background(
                    when {
                        focused -> WmmColors.Surface2
                        open -> OpenFill
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(line.title, style = inter(24.sp, FontWeight.SemiBold), color = if (open && !focused) WmmColors.Accent else WmmColors.Text, maxLines = 1)
            Text(line.line, style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The open group's pane, its title over its rows. ◀ and Back go back to the
 * group. The pane scrolls; each group's starts at its top.
 */
@Composable
private fun Pane(panes: PaneFocus, shown: GroupLine, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var inPane by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { inPane = it.hasFocus }
            .onBack(enabled = inPane) { panes.toGroup() }
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && panes.toGroup() }
            .focusGroup(),
    ) {
        key(shown.group) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 56.dp, bottom = 54.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    shown.title,
                    style = inter(40.sp, FontWeight.ExtraBold, tracking = -0.02f),
                    color = WmmColors.Text,
                    modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth().semantics { heading() },
                )
                content()
            }
        }
    }
}
