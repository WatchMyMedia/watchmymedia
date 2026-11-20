package media.watchmy.client.mobile.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.Avatar
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.MobileTab
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.live.rememberReminderSettings
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.mobile.navigation.OnTabReselected
import androidx.compose.foundation.layout.offset
import media.watchmy.client.model.SERVERS
import media.watchmy.client.model.TURN_ON_NOTIFICATIONS
import media.watchmy.client.model.text
import media.watchmy.client.model.title
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.ServerFeatures
import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.model.languagesSummary
import media.watchmy.client.model.serverSettingsUrl
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.requests.MyRequestsViewModel
import media.watchmy.client.presentation.rows.RowLayoutViewModel
import media.watchmy.client.presentation.settings.AccountLoad
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.rememberTabletLayout

/**
 * Settings as groups. On a phone, [open] null
 * is the overview, a row per group with what is chosen under its name, and
 * a group's route shows its page with Up. On a tablet (600 wide and up) the
 * list sits beside the open group's page, Playback's when [open] is null.
 *
 * [onOpenPage] opens a group's page: pushed on a phone, in place of the one
 * beside the list on a tablet ([beside]). Home rows, Your requests, the
 * Inbox and Server settings open their own screens.
 *
 * [servers] is Account & devices' Servers page or one server's page, in
 * the place a group's page has; [onOpenServers] opens the list (null) or a
 * server's page, and [onAddServer] starts Add a server.
 */
@Composable
fun SettingsScreen(
    onAddServer: () -> Unit,
    onSignedOut: () -> Unit,
    onLinkTv: () -> Unit,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    open: SettingsGroup? = null,
    onOpenPage: (group: SettingsGroup, beside: Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onChangePassword: (asDialog: Boolean) -> Unit = {},
    onOpenRequests: (() -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    inboxCount: Int = 0,
    servers: ServersPage? = null,
    onOpenServers: (serverId: String?) -> Unit = {},
) {
    val services = LocalAppServices.current
    val vm = settingsModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val downloadsVm = downloadsModel()
    val downloads by downloadsVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { if (it == SettingsViewModel.Event.SignedOut) onSignedOut() } }
    val online = rememberOnline()
    // Back online, what didn't load for want of a connection comes back by itself.
    LaunchedEffect(online) {
        if (online) {
            if (vm.state.value.account is AccountLoad.Failed) vm.reloadAccount()
            if (vm.state.value.sessions is Load.Failed) vm.loadSessions()
        }
    }
    val uri = LocalUriHandler.current
    // ACTION_VIEW in the browser; a device with none says so rather than crashing.
    var noBrowser by remember { mutableStateOf<String?>(null) }
    val openWeb = { url: String -> if (runCatching { uri.openUri(url) }.isFailure) noBrowser = url }
    val twoPane = rememberTabletLayout().isTablet
    val device = deviceWord()
    val reminders = rememberReminderSettings()
    val apiLevel = state.server?.server?.apiLevel ?: 0
    val rowsAvailable = ServerFeatures(apiLevel).rows
    // Following shows: the group's line, and the one card with reminders'.
    val followsAvailable = ServerFeatures(apiLevel).follows
    val notifications = if (followsAvailable) notificationsModel().state.collectAsStateWithLifecycle().value else null
    val following = notifications?.count?.let { it > 0 } == true
    val channels = rememberChannelsAllowed()
    val blocked = channels.blocked(remindersShown = reminders.shown, newEpisodesOn = following && notifications?.notifyHere == true)
    val rows = if (rowsAvailable) homeRows() else null
    val requests = if (onOpenRequests != null) myRequests() else null
    val user = state.user
    val server = state.server?.server
    val locale = LocalConfiguration.current.locales[0]

    val lines = groupLines(
        OverviewFacts(
            device = device,
            quality = state.quality,
            autoplayNext = state.playback.autoplayNext,
            account = state.account,
            online = online,
            languages = languagesSummary(state.playback, state.audioLanguagesAvailable, locale).takeIf { state.subtitlePreferencesAvailable },
            downloads = downloads.all.size,
            downloadBytes = downloads.usedBytes,
            wifiOnly = downloads.settings.wifiOnly,
            rows = rows,
            userName = user?.name ?: "You",
            devices = (state.sessions as? Load.Ready)?.data?.size,
            requests = requests,
            inbox = inboxCount.takeIf { onOpenInbox != null },
            appVersion = services.client.version,
            serverName = server?.name,
            serverVersion = server?.version,
            remindersBlocked = reminders.shown && !reminders.allowed,
            follows = notifications?.list?.let { list ->
                when (list) {
                    is Load.Ready -> Load.Ready(list.data.size)
                    is Load.Failed -> list
                    Load.Loading -> Load.Loading
                }
            },
            notifyHere = notifications?.notifyHere ?: true,
            newEpisodesBlocked = blocked.newEpisodes,
        ),
    )
    val groups = SettingsGroup.entries.filter { group ->
        when (group) {
            SettingsGroup.Languages -> state.subtitlePreferencesAvailable
            SettingsGroup.Downloads -> downloads.enabled || downloads.all.isNotEmpty()
            SettingsGroup.HomeRows -> rowsAvailable
            SettingsGroup.Notifications -> followsAvailable
            SettingsGroup.Requests -> onOpenRequests != null
            SettingsGroup.Inbox -> onOpenInbox != null
            SettingsGroup.ServerSettings -> user?.isAdmin == true && server != null
            else -> true
        }
    }.map { group ->
        val line = lines[group]
        GroupRow(group, if (group == SettingsGroup.Languages) state.languagesTitle else group.title, line?.long, line?.short, line?.warn == true, inboxCount.takeIf { group == SettingsGroup.Inbox && it > 0 })
    }
    val choose = { group: SettingsGroup ->
        when (group) {
            SettingsGroup.HomeRows -> onNavigate(if (twoPane) MobileRoutes.EditRowsDialog(RowScreens.HOME, "Home") else MobileRoutes.EditRows(RowScreens.HOME, "Home"))
            SettingsGroup.Requests -> onOpenRequests?.invoke()
            SettingsGroup.Inbox -> onOpenInbox?.invoke()
            SettingsGroup.ServerSettings -> server?.let { openWeb(serverSettingsUrl(it.baseUrl)) }
            else -> onOpenPage(group, twoPane)
        }
        Unit
    }
    val page = @Composable { group: SettingsGroup, pageModifier: Modifier ->
        when (group) {
            SettingsGroup.Playback -> PlaybackPage(twoPane, online, pageModifier)
            // Its two-by-two panels need a wide page; beside the list on a tablet held upright it stacks, as on a phone.
            SettingsGroup.Languages -> LanguageSettings(twoPane && windowWidth() >= 1000.dp, online, pageModifier)
            SettingsGroup.Downloads -> DownloadsPage(twoPane, onSpace = { onNavigate(MobileRoutes.Downloads) }, modifier = pageModifier)
            SettingsGroup.Notifications -> NotificationsPage(
                twoPane, online, remindersShown = reminders.shown,
                onOpenShow = { onNavigate(MobileRoutes.Show(it)) },
                onOpenPlayback = { onOpenPage(SettingsGroup.Playback, twoPane) },
                modifier = pageModifier,
            )
            SettingsGroup.Account -> when (servers) {
                null -> AccountPage(twoPane, onChangePassword = { onChangePassword(twoPane) }, onLinkTv = onLinkTv, onServers = { onOpenServers(null) }, modifier = pageModifier)
                ServersPage.List -> ServersListPage(twoPane, onOpenServer = onOpenServers, onAdd = onAddServer, modifier = pageModifier)
                is ServersPage.Server -> ServerPage(servers.id, twoPane, onGone = onBack, modifier = pageModifier)
            }
            SettingsGroup.About -> AboutPage(twoPane, openWeb, pageModifier)
            else -> Unit
        }
    }

    val scroll = rememberScrollState()
    OnTabReselected(MobileTab.Settings) { if (!twoPane && open != null) onBack() else scroll.animateScrollTo(0) }
    LaunchedEffect(open, twoPane, servers) { scroll.scrollTo(0) }
    // A Servers page takes its group's place, under its own title, with the way back to where it came from.
    val serversTitle = when (servers) {
        null -> null
        ServersPage.List -> SERVERS
        is ServersPage.Server -> serversModel().state.collectAsStateWithLifecycle().value.row(servers.id)?.name ?: SERVERS
    }
    when {
        twoPane -> {
            val shown = open ?: SettingsGroup.Playback
            TabletSettings(
                groups = groups,
                open = shown,
                title = serversTitle ?: groups.firstOrNull { it.group == shown }?.title ?: shown.title,
                up = when (servers) {
                    null -> null
                    ServersPage.List -> SettingsGroup.Account.title
                    is ServersPage.Server -> SERVERS
                },
                onUp = onBack,
                userName = user?.name,
                role = listOfNotNull(if (user?.isAdmin == true) "Admin" else "Member", server?.name).joinToString(" · "),
                onChoose = choose,
                scroll = scroll,
                modifier = modifier,
            ) { page(shown, Modifier) }
        }
        open == null -> PhoneOverview(
            groups = groups,
            userName = user?.name,
            header = listOfNotNull(user?.name?.let { "Signed in as $it" }, server?.name).joinToString(" · "),
            onChoose = choose,
            scroll = scroll,
            modifier = modifier,
        ) {
            val failed = state.account as? AccountLoad.Failed
            val serverName = server?.name ?: "The server"
            when {
                failed != null && !online -> StatusCard("You’re offline", "Settings kept on this $device still work. The rest come back when you reconnect.", NoteTone.Info, Modifier.cardInset(false))
                failed != null -> StatusCard(
                    "Your account’s settings didn’t load",
                    (if (failed.unreachable) "$serverName didn’t answer." else failed.reason.trimEnd('.') + ".") + " The settings kept on this $device still work.",
                    NoteTone.Warn,
                    Modifier.cardInset(false),
                    action = "Try again",
                    onAction = vm::reloadAccount,
                )
            }
            // One card, never two: what can't show, and one button for it (the boards' `off`).
            if (blocked.any) {
                val context = LocalContext.current
                StatusCard(
                    blocked.title(device),
                    blocked.text(),
                    NoteTone.Warn,
                    Modifier.cardInset(false),
                    action = TURN_ON_NOTIFICATIONS,
                    onAction = { openNotificationsFor(context, blocked) },
                )
            }
        }
        else -> Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
            // The page's title matches its row; system Back and Up do the same.
            BackTopBar(serversTitle ?: groups.firstOrNull { it.group == open }?.title ?: open.title, onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 24.dp)) {
                page(open, Modifier)
            }
        }
    }

    noBrowser?.let { url ->
        AlertDialog(
            onDismissRequest = { noBrowser = null },
            containerColor = WmmColors.Surface2,
            title = { Text("No browser to open it") },
            text = { Text("This $device has no app that opens $url. Install a browser, then try again.") },
            confirmButton = { GoldTextButton("OK", { noBrowser = null }) },
        )
    }
}

/** A group's row in the overview or the tablet's list. */
@Immutable
internal data class GroupRow(val group: SettingsGroup, val title: String, val line: String?, val short: String?, val warn: Boolean, val count: Int?)

/** Home's rows for the overview's line, from the Settings route's own model. */
@Composable
private fun homeRows(): Load<List<RowLayoutEntry>> {
    val services = LocalAppServices.current
    val model = viewModel(key = "settings-home-rows") { RowLayoutViewModel(services.rows, RowScreens.HOME) }
    val state by model.state.collectAsStateWithLifecycle()
    return when (val layout = state.layout) {
        is Load.Ready -> Load.Ready(state.entries)
        is Load.Failed -> layout
        Load.Loading -> Load.Loading
    }
}

/** The person's requests for Your requests' line. */
@Composable
private fun myRequests(): Load<List<MediaRequest>> {
    val services = LocalAppServices.current
    val model = viewModel(key = "settings-requests") { MyRequestsViewModel(services.requests) }
    return model.state.collectAsStateWithLifecycle().value.mine
}

private val SettingsGroup.icon: ImageVector
    get() = when (this) {
        SettingsGroup.Playback -> WmmIcons.Play
        SettingsGroup.Languages -> WmmIcons.Subtitles
        SettingsGroup.Downloads -> PhoneIcons.Downloads
        SettingsGroup.HomeRows -> PhoneIcons.Lines
        SettingsGroup.Notifications -> WmmIcons.Bell
        SettingsGroup.Account -> WmmIcons.Person
        SettingsGroup.Requests -> WmmIcons.ListBullets
        SettingsGroup.About -> WmmIcons.Info
        SettingsGroup.Inbox -> WmmIcons.Tray
        SettingsGroup.ServerSettings -> WmmIcons.Server
    }

/**
 * The phone's overview (SettingsOverview): who is signed in where, the cards
 * for what needs attention ([cards]), then a row per group, with the
 * account's groups below a divider and the admins' Server group last.
 */
@Composable
private fun PhoneOverview(
    groups: List<GroupRow>,
    userName: String?,
    header: String,
    onChoose: (SettingsGroup) -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    cards: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        MainTopBar("Settings", userName, onSearch = null, onAvatar = null)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 24.dp)) {
            if (header.isNotEmpty()) Text(header, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
            cards()
            var serverShown = false
            groups.forEach { row ->
                if (row.group == SettingsGroup.Account) Rule()
                if (row.group.server && !serverShown) {
                    serverShown = true
                    PageHeading("Server", tablet = false)
                }
                ListRow(
                    row.title,
                    subtitle = row.line,
                    // A group that needs attention says why in the warning colour (the new-episodes boards' `off`).
                    subtitleColor = if (row.warn) WmmColors.Warn else WmmColors.TextMuted,
                    minHeight = 60.dp,
                    modifier = Modifier.clickable(onClickLabel = if (row.group == SettingsGroup.ServerSettings) "Open in your browser" else "Open") { onChoose(row.group) },
                    leading = { IconTile(row.group.icon, background = WmmColors.Surface2, tint = WmmColors.TextSoft) },
                    trailing = { GroupTrailing(row) },
                )
            }
        }
    }
}

/** What sits at the end of a group's row: a warning, the Inbox's count, and where a tap goes. */
@Composable
private fun GroupTrailing(row: GroupRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (row.warn) Icon(WmmIcons.Alert, contentDescription = "Needs attention", tint = WmmColors.Warn, modifier = Modifier.size(18.dp))
        if (row.count != null) Count(row.count)
        Icon(
            if (row.group == SettingsGroup.ServerSettings) PhoneIcons.External else WmmIcons.ChevronRight,
            contentDescription = null,
            tint = WmmColors.TextMuted,
            modifier = Modifier.size(if (row.group == SettingsGroup.ServerSettings) 18.dp else 22.dp),
        )
    }
}

/** The Inbox's count in a gold bubble (`.cnt`). */
@Composable
private fun Count(count: Int) {
    Box(
        Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(WmmColors.Accent).padding(horizontal = 6.dp)
            .semantics { contentDescription = "$count to look at" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = WmmColors.OnAccent))
    }
}

/**
 * The tablet's Settings (TabSettingsOverview): the groups in a list at the
 * left, each with its value, the open one highlighted; its page at the
 * right under its title, at most 720 wide.
 */
@Composable
private fun TabletSettings(
    groups: List<GroupRow>,
    open: SettingsGroup,
    title: String,
    userName: String?,
    role: String,
    onChoose: (SettingsGroup) -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    /** The page this one was opened from, by name, for a page inside a group (Servers, a server); null for a group's own. */
    up: String? = null,
    onUp: () -> Unit = {},
    page: @Composable () -> Unit,
) {
    val wide = windowWidth() >= 1000.dp
    Row(modifier.fillMaxSize().background(WmmColors.Ground)) {
        Column(
            Modifier.width(if (wide) 400.dp else 320.dp).fillMaxHeight().background(ListGround).verticalScroll(rememberScrollState())
                .statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 16.dp),
        ) {
            if (userName != null) {
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(userName, size = 44.dp)
                    Column {
                        Text(userName, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WmmColors.Text))
                        Text(role, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                    }
                }
            }
            var serverShown = false
            groups.forEach { row ->
                if (row.group == SettingsGroup.Account) Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(WmmColors.Line))
                if (row.group.server && !serverShown) {
                    serverShown = true
                    Text(
                        "SERVER",
                        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.88.sp, color = WmmColors.TextMuted),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                TabletGroupItem(row, selected = row.group == open, onClick = { onChoose(row.group) })
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(WmmColors.Line))
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).statusBarsPadding().padding(start = if (wide) 40.dp else 24.dp, end = if (wide) 48.dp else 24.dp, top = 34.dp, bottom = 32.dp)) {
            Column(Modifier.widthIn(max = 720.dp)) {
                if (up != null) {
                    Row(
                        Modifier.offset(x = (-8).dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = "Back to $up", onClick = onUp).padding(start = 4.dp, end = 12.dp).heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(WmmIcons.ChevronLeft, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(20.dp))
                        Text(up, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.TextSoft))
                    }
                }
                Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = WmmColors.Text), modifier = Modifier.padding(bottom = 2.dp).semantics { heading() })
                page()
            }
        }
    }
}

/** A group in the tablet's list (`.gi`): its name, and its value at the right. */
@Composable
private fun TabletGroupItem(row: GroupRow, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp))
            .background(if (selected) WmmColors.Accent.copy(alpha = 0.16f) else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(row.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = if (selected) WmmColors.Accent else WmmColors.Text), maxLines = 1)
        Text(
            row.short.orEmpty(),
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = if (row.warn) WmmColors.Warn else WmmColors.TextMuted, textAlign = TextAlign.End),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.warn) Icon(WmmIcons.Alert, contentDescription = "Needs attention", tint = WmmColors.Warn, modifier = Modifier.size(16.dp))
        if (row.count != null) Count(row.count)
        if (row.group == SettingsGroup.ServerSettings) Icon(PhoneIcons.External, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(16.dp))
        else Spacer(Modifier.width(0.dp))
    }
}

private val ListGround = Color(0xFF121213)
