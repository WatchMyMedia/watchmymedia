package media.watchmy.client.mobile.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
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
import media.watchmy.client.ui.watchlistItem
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

/** These, then [item]'s watchlist entry (Add to Watchlist, Save to watchlist…) where it can be saved. */
@Composable
fun List<CardAction>.withWatchlist(item: BrowseItem): List<CardAction> {
    val saving = watchlistItem(item) ?: return this
    return remember(this, saving) { this + CardAction(saving.label, saving.onClick) }
}

/**
 * How a card is pointed at, for its artwork and title to show it
 * (TabCardMenuPointer): a mouse over it, keyboard focus on it, or a press.
 */
@Immutable
data class CardPointer(val hovered: Boolean = false, val keyboardFocused: Boolean = false, val pressed: Boolean = false)

/** The [CardPointer] of the card around the content. */
val LocalCardPointer = compositionLocalOf { CardPointer() }

/**
 * Opens on tap; a long-press shows [actions] as a menu (mark watched or
 * unwatched), as phones do instead of the TV's long-press toggle. With a
 * mouse or a keyboard (a tablet's), a right-click
 * opens the menu where the pointer is, and the Menu key (or Shift+F10)
 * below the focused card. The card tells its content how it is pointed at
 * through [LocalCardPointer].
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Where the menu opens in the card: the pointer's place for a right-click, null for below the card.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var cardHeight by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val hasMenu = actions.isNotEmpty()
    Box(modifier.onSizeChanged { cardHeight = it.height }) {
        CompositionLocalProvider(LocalCardPointer provides CardPointer(hovered, focused && keyboard, pressed)) {
            Column(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .pointerInput(hasMenu) {
                        if (!hasMenu) return@pointerInput
                        // Before the click below sees it, so a right-click opens the menu rather than the card.
                        awaitEachGesture {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                menuAt = event.changes.first().position
                                menu = true
                            }
                        }
                    }
                    .onKeyEvent { event ->
                        val menuKey = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
                        if (!hasMenu || !menuKey) return@onKeyEvent false
                        // On the press: Shift may be let go before F10.
                        if (event.type == KeyEventType.KeyDown) {
                            menuAt = null
                            menu = true
                        }
                        true
                    }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = onClick,
                        onLongClickLabel = if (hasMenu) "Options" else null,
                        onLongClick = if (!hasMenu) null else {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuAt = null
                                menu = true
                            }
                        },
                    ),
                content = content,
            )
        }
        val density = LocalDensity.current
        val offset = menuAt?.let { with(density) { DpOffset(it.x.toDp(), (it.y - cardHeight).toDp()) } } ?: DpOffset(0.dp, 4.dp)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = offset, containerColor = WmmColors.Surface3) {
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

/** A card's title and line under it ("S03 E07 · today"); the title is underlined while a mouse is over the card. */
@Composable
fun CardCaption(title: String, subtitle: String?, titleSize: TextUnit = 14.sp, subtitleSize: TextUnit = 12.sp) {
    Spacer(Modifier.height(6.dp))
    val underline = if (LocalCardPointer.current.hovered) TextDecoration.Underline else null
    Text(title, style = CardTitle.copy(fontSize = titleSize, lineHeight = titleSize * 1.3f, textDecoration = underline), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (subtitle != null) {
        Text(subtitle, style = CardSub.copy(fontSize = subtitleSize, lineHeight = subtitleSize * 1.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A 2:3 poster card for a show or movie: the New badge, the watched check
 * and the progress strip; no quality badge (quality is a
 * fact on the hero and the detail pages, not a chip on every poster). The
 * title under it names the card, so the artwork has no description of its
 * own. With [onSetWatched], a long-press offers Mark as watched or
 * unwatched, and where the server has watchlists the title's entry for
 * them. The card builds that menu itself, so a grid recomposing passes
 * it the same values and the card skips. [outlined] rings the artwork in
 * gold: the leading card of a tablet Home row.
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
    val actions = remember(item.watched, onSetWatched) { listOfNotNull(onSetWatched?.let { watchToggle(item.watched, it) }) }.withWatchlist(item)
    PressableCard(onClick, actions, sized) {
        Artwork(
            url = imageUrl,
            seed = item.title,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).leadRing(outlined).pointedArt(),
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

/** The gold ring on the leading card of a tablet Home row. */
private fun Modifier.leadRing(outlined: Boolean): Modifier =
    if (outlined) border(2.dp, WmmColors.Accent, RoundedCornerShape(8.dp)) else this

/**
 * A card's artwork as it is pointed at (TabCardMenuPointer): a light veil
 * and edge under a mouse, a gold ring with keyboard focus, and a little
 * smaller while pressed.
 */
@Composable
private fun Modifier.pointedArt(): Modifier {
    val pointer = LocalCardPointer.current
    val shape = RoundedCornerShape(8.dp)
    return this
        .then(if (pointer.pressed) Modifier.scale(0.97f) else Modifier)
        .then(if (pointer.keyboardFocused) Modifier.border(3.dp, WmmColors.Accent, shape) else Modifier)
        .then(
            if (pointer.hovered && !pointer.keyboardFocused) {
                Modifier.drawWithContent {
                    drawContent()
                    drawRect(Color.White.copy(alpha = 0.08f))
                }.border(2.dp, Color.White.copy(alpha = 0.55f), shape)
            } else {
                Modifier
            },
        )
}

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
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).leadRing(outlined).pointedArt(),
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
