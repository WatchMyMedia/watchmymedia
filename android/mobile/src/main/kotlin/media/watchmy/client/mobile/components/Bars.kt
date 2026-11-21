package media.watchmy.client.mobile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import media.watchmy.client.model.onServerLabel
import media.watchmy.client.ui.TestTags
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

private val BarTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text)

/**
 * The account button's part in Servers, from the signed-in screens' host:
 * what it is called ("Sam on Living room server. Open Servers"), the server
 * in use's name where two or more are registered, and what opens Servers.
 */
@Immutable
class ServersButton(val label: String, val serverName: String?, val open: () -> Unit)

/** Null where there is no Servers to open (a preview, a screen on its own): the avatar then does what its screen says. */
@Suppress("ComposeCompositionLocalUsage")
val LocalServersButton = compositionLocalOf<ServersButton?> { null }

/**
 * The top bar of the bottom-bar screens: title, search and the avatar, the
 * account button, which opens Servers ([LocalServersButton]; [onAvatar]
 * where there is none). [searchLabel] names the search ("Search listings" on
 * Live TV), and [actions] come between it and the avatar. [showServer]
 * (Home) writes the server in use's name under the title when there are
 * several, as a second way into Servers.
 */
@Composable
fun MainTopBar(
    title: String,
    userName: String?,
    onSearch: (() -> Unit)?,
    onAvatar: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showCast: Boolean = true,
    searchLabel: String = "Search",
    showServer: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val servers = LocalServersButton.current
    val serverName = servers?.serverName?.takeIf { showServer }
    Row(
        modifier.fillMaxWidth().background(WmmColors.Ground).statusBarsPadding().height(64.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BarTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (serverName != null && servers != null) {
                Row(
                    Modifier.heightIn(min = 22.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = servers.open)
                        .clearAndSetSemantics { contentDescription = onServerLabel(serverName) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(serverName, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(WmmIcons.ChevronDown, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (showCast) CastButton()
        if (onSearch != null) {
            IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                Icon(WmmIcons.Search, contentDescription = searchLabel, tint = WmmColors.Text)
            }
        }
        actions()
        if (onAvatar != null) {
            // The button's own words say who and where; the initial under it isn't read again.
            IconButton(
                onClick = servers?.open ?: onAvatar,
                modifier = Modifier.size(48.dp).testTag(TestTags.ACCOUNT).semantics { contentDescription = servers?.label ?: "Settings" },
            ) { Box(Modifier.clearAndSetSemantics {}) { Avatar(userName) } }
        } else if (userName != null) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Avatar(userName) }
        }
    }
}

/** The signed-in user's initial on burgundy. */
@Composable
fun Avatar(name: String?, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(WmmColors.Brand).semantics { contentDescription = name ?: "Account" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name?.trim()?.firstOrNull()?.uppercase() ?: "?",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = WmmColors.Text),
        )
    }
}

/**
 * A top bar with Back. [overArt] puts the buttons on translucent circles,
 * for bars drawn over a backdrop (Show, Movie).
 */
@Composable
fun BackTopBar(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overArt: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarIconButton(WmmIcons.Back, "Back", onBack, overArt)
        if (title != null) {
            Text(title, style = BarTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

@Composable
fun BarIconButton(icon: ImageVector, description: String, onClick: () -> Unit, overArt: Boolean = false) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (overArt) Color.Black.copy(alpha = 0.35f) else Color.Transparent,
            contentColor = WmmColors.Text,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** The bottom bar's destinations. */
enum class MobileTab(val label: String, val icon: ImageVector) {
    Home("Home", WmmIcons.Home),
    TvShows("TV Shows", WmmIcons.Tv),
    Movies("Movies", WmmIcons.Movie),
    Search("Search", WmmIcons.Search),
    Downloads("Downloads", PhoneIcons.Downloads),
    LiveTV("Live TV", PhoneIcons.LiveTV),
    Settings("Settings", WmmIcons.Settings),
    ;

    companion object {
        /**
         * The tabs shown: with downloads Downloads
         * takes Search's place in the phone's bottom bar, where Search stays
         * in the top bar, and joins Search in the tablet's rail.
         */
        fun shown(downloads: Boolean, rail: Boolean, liveTV: Boolean = false): List<MobileTab> {
            val base = when {
                !downloads -> entries - Downloads - LiveTV
                rail -> entries - LiveTV
                else -> entries - Search - LiveTV
            }
            return when {
                !liveTV -> base
                else -> base.filter { it != Settings }.toMutableList().apply { add(3.coerceAtMost(size), LiveTV) }
            }
        }
    }
}

/**
 * The Material 3 navigation bar, in the mockups' colours: a gold pill on the
 * selected item. [settingsCount] is the admins' Inbox count, a gold badge on
 * Settings.
 */
@Composable
fun MobileBottomBar(selected: MobileTab?, onSelect: (MobileTab) -> Unit, settingsCount: Int = 0, tabs: List<MobileTab> = MobileTab.shown(downloads = false, rail = false)) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = WmmColors.Line)
        NavigationBar(containerColor = WmmColors.Surface, contentColor = WmmColors.TextMuted, tonalElevation = 0.dp) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        if (tab == MobileTab.Settings && settingsCount > 0) {
                            BadgedBox(badge = { Badge(containerColor = WmmColors.Accent, contentColor = WmmColors.OnAccent) { Text("$settingsCount", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp)) } }) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    modifier = if (tab == MobileTab.Settings && settingsCount > 0) Modifier.semantics { contentDescription = "Settings, $settingsCount in the Inbox" } else Modifier,
                    // At a large font size five labels can't fit: they end in an ellipsis rather than being cut off.
                    label = { Text(tab.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = WmmColors.Accent,
                        selectedTextColor = WmmColors.Text,
                        indicatorColor = WmmColors.Accent.copy(alpha = 0.18f),
                        unselectedIconColor = WmmColors.TextMuted,
                        unselectedTextColor = WmmColors.TextMuted,
                    ),
                )
            }
        }
    }
}

/** A rounded tile behind a list icon (servers, Link a TV). */
@Composable
fun IconTile(icon: ImageVector, background: Color = WmmColors.Brand, tint: Color = WmmColors.Text) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A card outline: the mockups' 12 dp panel on #151517 with a #222225 rim. */
fun Modifier.panel(selected: Boolean = false): Modifier = this
    .clip(RoundedCornerShape(12.dp))
    .background(WmmColors.Surface)
    .border(1.dp, if (selected) WmmColors.Accent else WmmColors.Line, RoundedCornerShape(12.dp))
