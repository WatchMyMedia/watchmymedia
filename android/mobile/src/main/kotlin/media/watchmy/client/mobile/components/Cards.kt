package media.watchmy.client.mobile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons

/** An entry in a card's long-press menu. */
data class CardAction(val label: String, val onClick: () -> Unit)

/** "Mark as watched" or "Mark as unwatched", whichever undoes [watched]. */
fun watchToggle(watched: Boolean, onSet: (Boolean) -> Unit) =
    CardAction(if (watched) "Mark as unwatched" else "Mark as watched") { onSet(!watched) }

/**
 * Opens on tap; a long-press shows [actions] as a menu (mark watched or
 * unwatched), as phones do instead of the TV's long-press toggle.
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Column(
            Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(
                onClick = onClick,
                onLongClickLabel = if (actions.isEmpty()) null else "Options",
                onLongClick = if (actions.isEmpty()) null else {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    }
                },
            ),
            content = content,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = WmmColors.Surface3) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text)) },
                    onClick = {
                        menu = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

private val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, color = WmmColors.Text)
private val CardSub = TextStyle(fontFamily = Inter, color = WmmColors.TextMuted)

/** A card's title and line under it ("S03 E07 · today"). */
@Composable
fun CardCaption(title: String, subtitle: String?, titleSize: TextUnit = 14.sp, subtitleSize: TextUnit = 12.sp) {
    Spacer(Modifier.height(6.dp))
    Text(title, style = CardTitle.copy(fontSize = titleSize, lineHeight = titleSize * 1.3f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (subtitle != null) {
        Text(subtitle, style = CardSub.copy(fontSize = subtitleSize, lineHeight = subtitleSize * 1.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A 2:3 poster card for a show or movie: the New badge, the watched check
 * and the progress strip; no quality badge (PLAN-HOME.md §5: quality is a
 * fact on the hero and the detail pages, not a chip on every poster). The
 * title under it names the card, so the artwork has no description of its
 * own. With [onSetWatched], a long-press offers Mark as watched or
 * unwatched. The card builds that menu itself, so a grid recomposing passes
 * it the same values and the card skips. [outlined] rings the artwork in
 * gold: the leading card of a tablet Home row (TabHomeRow.dc.html).
 */
@Composable
fun PosterCard(
    item: BrowseItem,
    imageUrl: String?,
    subtitle: String?,
    onClick: () -> Unit,
    onSetWatched: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    titleSize: TextUnit = 14.sp,
    subtitleSize: TextUnit = 12.sp,
    outlined: Boolean = false,
) {
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    val actions = remember(item.watched, onSetWatched) { listOfNotNull(onSetWatched?.let { watchToggle(item.watched, it) }) }
    PressableCard(onClick, actions, sized) {
        Artwork(
            url = imageUrl,
            seed = item.title,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).leadRing(outlined),
            shape = RoundedCornerShape(8.dp),
        ) {
            Box(Modifier.fillMaxSize()) { ArtBadges(item) }
        }
        CardCaption(item.title, subtitle, titleSize, subtitleSize)
    }
}

@Composable
private fun BoxScope.ArtBadges(item: BrowseItem) {
    if (item.isNew) Badge("New", Modifier.align(Alignment.TopStart).padding(6.dp), style = BadgeStyle.Gold)
    if (item.watched) WatchedCheck(Modifier.align(Alignment.BottomEnd).padding(6.dp))
    item.progress?.takeIf { it > 0f && !item.watched }?.let { ProgressStrip(it, Modifier.align(Alignment.BottomStart)) }
}

/** The gold ring on the leading card of a tablet Home row (TabHomeRow.dc.html `.card.lead`). */
private fun Modifier.leadRing(outlined: Boolean): Modifier =
    if (outlined) border(2.dp, WmmColors.Accent, RoundedCornerShape(8.dp)) else this

/**
 * A 16:9 card (Continue watching, Recently aired, Recently released):
 * artwork of [width] with [overlay] on it, then the caption. [outlined] as
 * on [PosterCard].
 */
@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    seed: String,
    width: Dp,
    onClick: () -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    PressableCard(onClick, actions, modifier.width(width)) {
        Artwork(
            url = imageUrl,
            seed = seed,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).leadRing(outlined),
            shape = RoundedCornerShape(8.dp),
        ) {
            Box(Modifier.fillMaxSize()) { overlay() }
        }
        CardCaption(title, subtitle)
    }
}

/** The round play glyph centred on Continue watching cards. */
@Composable
fun BoxScope.PlayGlyph() {
    Box(
        Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(WmmIcons.Play, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(18.dp))
    }
}

/** The fade from transparent to the ground over the lower part of artwork. */
fun groundFade(from: Float, to: Float, alpha: Float = 1f) = Brush.verticalGradient(
    from to Color.Transparent,
    to to WmmColors.Ground.copy(alpha = alpha),
)
