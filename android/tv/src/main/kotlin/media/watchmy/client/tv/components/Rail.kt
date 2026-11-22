package media.watchmy.client.tv.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.model.SERVERS
import media.watchmy.client.presentation.servers.ServersViewModel
import media.watchmy.client.tv.servers.LocalServersHost
import media.watchmy.client.tv.servers.ServersPanel
import media.watchmy.client.ui.TestTags
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.RequestBadge
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** Your requests and the admins' Inbox count, provided by the nav host for the rail's Settings badge. */
val LocalRequestBadge = staticCompositionLocalOf { RequestBadge() }

/**
 * Navigation-wide capability from the one shared ViewModel. Like the request
 * badge, every screen's rail consumes it without making its own server request.
 */
@Suppress("ComposeCompositionLocalUsage")
val LocalLiveTVAvailable = staticCompositionLocalOf { false }

/** The rail's sections, top to bottom, and where each goes. */
enum class RailSection(val label: String, val icon: ImageVector, val route: Any) {
    Search("Search", WmmIcons.Search, TvRoutes.Search),
    Home("Home", WmmIcons.Home, TvRoutes.Home),
    Shows("TV Shows", WmmIcons.Tv, TvRoutes.Library("tv")),
    Movies("Movies", WmmIcons.Movie, TvRoutes.Library("movie")),
    LiveTV("Live TV", WmmIcons.Tv, TvRoutes.LiveTV),
    Settings("Settings", WmmIcons.Settings, TvRoutes.Settings),
    ;

    companion object {
        fun shown(liveTV: Boolean): List<RailSection> = entries.filter { it != LiveTV || liveTV }
    }
}

/**
 * A signed-in screen: [content] full screen with the icon rail over its
 * left edge. The rail marks [section] in gold, opens with labels while it
 * has focus, and takes focus back to the active section when entered;
 * the account button at its foot opens Servers, a panel over the screen;
 * leaving it to the right puts focus back where it was in [content].
 * Only moving left opens the rail: focus that comes back from nowhere (the
 * focused item went away, as when the player closes) goes to [content].
 * Choosing [section] again goes back to the page, or to the section itself
 * from a page inside it ([sectionRoot] false: a show or a movie).
 */
@Composable
fun TvScaffold(
    section: RailSection?,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    sectionRoot: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    val hint = remember { RailHint() }
    Box(modifier.fillMaxSize().background(WmmColors.Ground)) {
        CompositionLocalProvider(LocalRailHint provides hint) {
            Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusRestorer(), content = content)
        }
        Rail(section, sectionRoot, onNavigate, contentFocus, hint)
    }
}

@Composable
private fun Rail(active: RailSection?, sectionRoot: Boolean, onNavigate: (Any) -> Unit, content: FocusRequester, hint: RailHint) {
    val services = LocalAppServices.current
    val user by services.auth.user.collectAsStateWithLifecycle()
    // Servers, the switcher: OK on the account button opens it over this screen.
    val serversModel = viewModel(key = "servers") { ServersViewModel(services.servers, cast = services.cast) }
    val servers by serversModel.state.collectAsStateWithLifecycle()
    var serversOpen by rememberSaveable { mutableStateOf(false) }
    // A switch that happened, or one that went to a sign-in, closes it; the app then rebuilds its screens.
    LaunchedEffect(serversModel) { serversModel.events.collect { serversOpen = false } }
    val host = LocalServersHost.current
    val inbox = LocalRequestBadge.current.inbox ?: 0
    val sections = RailSection.shown(LocalLiveTVAvailable.current)
    var focused by remember { mutableStateOf(false) }
    // Servers opens beside the closed rail: the rail keeps this window's focus under the panel, and its labels would show at the panel's edge.
    val expanded = focused && !serversOpen
    // A row stopped a run of ◀ at its first card: the active section pulses, since the next ◀ comes here.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(hint.stops) {
        if (hint.stops > 0) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(520))
        }
    }
    val activeFocus = remember { FocusRequester() }
    val avatarFocus = remember { FocusRequester() }
    val scrim = if (expanded) {
        Brush.horizontalGradient(0f to WmmColors.Ground, 0.7f to WmmColors.Ground.copy(alpha = 0.94f), 1f to Color.Transparent)
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) 340.dp else 96.dp)
            .background(scrim)
            .onFocusChanged { focused = it.hasFocus }
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Left || !content.requestFocus()) {
                        if (active != null && active in sections) activeFocus.requestFocus() else avatarFocus.requestFocus()
                    }
                }
            }
            .focusGroup()
            .padding(start = 18.dp, top = 44.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The page's scrolling list starts under the rail, so a plain search to the right
        // never counts it as being to the right: send Right to the page.
        val toContent = Modifier.focusProperties { right = content }
        for (s in sections) {
            RailItem(
                label = s.label,
                icon = s.icon,
                active = s == active,
                expanded = expanded,
                count = if (s == RailSection.Settings) inbox else 0,
                onClick = { if (s != active || !sectionRoot) onNavigate(s.route) else content.requestFocus() },
                modifier = toContent.then(if (s == active) Modifier.focusRequester(activeFocus) else Modifier),
                pulse = if (s == active) { { pulse.value } } else null,
            )
        }
        Spacer(Modifier.weight(1f))
        val name = user?.name.orEmpty()
        // The rail has no room for the server's name: it is the button's label while it has focus, and the first row of Servers.
        val serverName = servers.current?.name
        FocusableBox(
            onClick = { serversOpen = true },
            modifier = toContent.focusRequester(avatarFocus).testTag(TestTags.ACCOUNT).semantics {
                contentDescription = listOfNotNull(name.ifEmpty { null }, serverName).joinToString(", on ").let { who -> if (who.isEmpty()) SERVERS else "$SERVERS. $who" }
            },
            cornerRadius = 30.dp,
            focusedScale = 1.04f,
            role = Role.Button,
        ) { focused ->
            // The button's own words say who and where: the initial and the names under it aren't read again.
            Row(Modifier.height(60.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                    // Ringed in gold while its panel is open, as the control that opened it; focus is in the panel.
                    // (Its own focus ring does that while this window still counts it focused: one ring, not two.)
                    if (serversOpen && !focused) Box(Modifier.size(60.dp).border(3.dp, WmmColors.Accent, CircleShape))
                    Box(Modifier.size(48.dp).background(WmmColors.Brand, CircleShape), contentAlignment = Alignment.Center) {
                        Text(name.take(1).uppercase(), style = inter(20.sp, FontWeight.Bold), color = WmmColors.Text)
                    }
                }
                if (expanded) {
                    Column(Modifier.padding(start = 8.dp, end = 20.dp).widthIn(max = 220.dp)) {
                        Text(name, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (serverName != null && servers.several) Text(serverName, style = inter(16.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (serversOpen) {
        val close = {
            serversOpen = false
            serversModel.dismissFailure()
        }
        ServersPanel(
            servers, serversModel,
            onAdd = {
                close()
                host?.onAdd?.invoke()
            },
            onManage = {
                close()
                host?.manage = true
                onNavigate(TvRoutes.Settings)
            },
            onDismiss = close,
            onWatchlists = host?.onWatchlists?.let { open -> { close(); open() } },
        )
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, active: Boolean, expanded: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier, pulse: (() -> Float)? = null) {
    val described = if (count > 0) modifier.semantics { contentDescription = "$label, $count in the Inbox" } else modifier
    FocusableBox(onClick = onClick, modifier = described, cornerRadius = 16.dp, focusedScale = 1.04f) { focused ->
        val bg = when {
            active -> WmmColors.Accent.copy(alpha = 0.14f)
            focused -> Color.White.copy(alpha = 0.10f)
            else -> Color.Transparent
        }
        val fg = when {
            active -> WmmColors.Accent
            focused -> WmmColors.Text
            else -> WmmColors.TextMuted
        }
        Row(
            Modifier
                .height(60.dp)
                .width(if (expanded) 250.dp else 60.dp)
                .drawBehind {
                    // A gold outline that spreads from the item and fades.
                    val p = pulse?.invoke() ?: 0f
                    if (p > 0f) {
                        val out = (1f - p) * 18.dp.toPx()
                        drawRoundRect(
                            color = WmmColors.Accent.copy(alpha = 0.75f * p),
                            topLeft = Offset(-out, -out),
                            size = Size(size.width + 2 * out, size.height + 2 * out),
                            cornerRadius = CornerRadius(16.dp.toPx() + out),
                            style = Stroke(3.dp.toPx()),
                        )
                    }
                }
                .background(bg, RoundedCornerShape(16.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(30.dp))
                if (count > 0) {
                    // The Inbox's count for admins.
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).heightIn(min = 24.dp).widthIn(min = 24.dp).background(WmmColors.Accent, CircleShape).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", style = inter(14.sp, FontWeight.ExtraBold), color = WmmColors.OnAccent)
                    }
                }
            }
            if (expanded) Text(label, style = inter(21.sp, FontWeight.SemiBold), color = fg, maxLines = 1)
        }
    }
}
