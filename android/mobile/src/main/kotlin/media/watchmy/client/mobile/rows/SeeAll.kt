package media.watchmy.client.mobile.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.navigation.LibraryTab
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.BrowseRequests
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.SeeAllWords
import media.watchmy.client.model.browseRequest
import media.watchmy.client.model.heading
import media.watchmy.client.model.libraryType
import media.watchmy.client.model.seeAllWords
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/**
 * A row's See all (PLAN-SEE-ALL.md §4.4): what it says, and what a tap on
 * the row's last card or on its heading's link does.
 */
internal class RowSeeAll(val words: SeeAllWords, val list: Boolean, val onClick: () -> Unit)

/**
 * A Home row's See all, on the phone as on the tablet: a list's page, or
 * its library's tab at the grid, sorted the row's way ([requests] carries
 * the sort there). Null for a row without a link, and for a library Home
 * has no tab for: one of several of its type, which the server sends no
 * link for anyway (PLAN-SEE-ALL.md decision 6).
 */
internal fun homeSeeAll(row: ScreenRow, home: HomeData, requests: BrowseRequests, onNavigate: (Any) -> Unit): RowSeeAll? {
    val link = row.seeAll ?: return null
    val type = (link as? RowLink.Library)?.let { home.libraryType(it) }
    val words = seeAllWords(link, type, row.heading().first) ?: return null
    return RowSeeAll(words, list = link is RowLink.ToList) {
        when (link) {
            is RowLink.ToList -> onNavigate(MobileRoutes.MediaList(link.listId))
            is RowLink.Library -> {
                requests.post(link.browseRequest())
                type?.let { onNavigate(LibraryTab(it)) }
            }
        }
    }
}

/**
 * A See all on a row above the Movies or TV grid: a list's page, or the grid
 * below, sorted the row's way ([onOpen]). A library link to another library
 * than the grid's ([libraryId]) has none.
 */
internal fun librarySeeAll(row: ScreenRow, type: LibraryType, libraryId: Long?, onOpen: (BrowseRequest) -> Unit, onNavigate: (Any) -> Unit): RowSeeAll? {
    val link = row.seeAll ?: return null
    if (link is RowLink.Library && link.libraryId != libraryId) return null
    val words = seeAllWords(link, type, row.heading().first) ?: return null
    return RowSeeAll(words, list = link is RowLink.ToList) {
        when (link) {
            is RowLink.ToList -> onNavigate(MobileRoutes.MediaList(link.listId))
            is RowLink.Library -> onOpen(link.browseRequest())
        }
    }
}

/** The See all card's width and shape in a row of [style]: the row's own cards'. Null where a row has none. */
internal fun seeAllCardSize(style: RowStyle, tablet: Boolean): Pair<Dp, Float>? = when (style) {
    RowStyle.Poster -> (if (tablet) 150.dp else 116.dp) to 2f / 3f
    RowStyle.Wide -> (if (tablet) 240.dp else 160.dp) to 16f / 9f
    RowStyle.Episode -> 232.dp to 16f / 9f
    RowStyle.Continue, RowStyle.Live -> null
}

/**
 * A row's last card (HomeSeeAll.dc.html, TabHomeRowSeeAll.dc.html `.all`):
 * the size of the row's cards, with no title under it. A grid glyph (the
 * list glyph on a list row) on a gold tint, "See all TV shows" and "By last
 * aired", read aloud as one ("See all TV shows, by last aired"). The phone's
 * small 16:9 card has a smaller glyph.
 */
@Composable
internal fun SeeAllCard(seeAll: RowSeeAll, width: Dp, aspect: Float, tablet: Boolean, modifier: Modifier = Modifier) {
    val small = !tablet && width < 200.dp && aspect > 1f
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val ring = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .width(width)
            .aspectRatio(aspect)
            .clip(shape)
            .background(if (hovered) WmmColors.Surface3 else WmmColors.Surface2)
            .border(if (ring) 3.dp else 1.dp, if (ring) WmmColors.Accent else WmmColors.Line2, shape)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = seeAll.onClick)
            .semantics { contentDescription = seeAll.words.spoken }
            .padding(horizontal = if (tablet) 14.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(
            when {
                tablet -> 8.dp
                small -> 4.dp
                else -> 6.dp
            },
            Alignment.CenterVertically,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val circle = when {
            tablet -> 44.dp
            small -> 28.dp
            else -> 36.dp
        }
        Box(Modifier.size(circle).background(WmmColors.Accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (seeAll.list) WmmIcons.ListBullets else WmmIcons.Grid,
                contentDescription = null,
                tint = WmmColors.Accent,
                modifier = Modifier.size(
                    when {
                        tablet -> 24.dp
                        small -> 16.dp
                        else -> 20.dp
                    },
                ),
            )
        }
        val titleSize = if (tablet) 15.sp else 14.sp
        Text(
            seeAll.words.title,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = titleSize, lineHeight = titleSize * 1.25f, color = WmmColors.Text, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        seeAll.words.detail?.let { detail ->
            val detailSize = if (tablet) 13.sp else 12.sp
            Text(
                detail,
                style = TextStyle(fontFamily = Inter, fontSize = detailSize, lineHeight = detailSize * if (tablet) 1.3f else 1.25f, color = WmmColors.TextMuted, textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
