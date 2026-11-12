package media.watchmy.client.tv.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.zIndex
import media.watchmy.client.ui.WmmColors

/**
 * The mockups' focus ring: a gold outline [width] wide, drawn [offset]
 * outside the bounds (CSS outline-offset; negative draws inside).
 */
fun Modifier.focusRing(focused: Boolean, cornerRadius: Dp, offset: Dp = 4.dp, width: Dp = 4.dp, color: Color = WmmColors.Accent): Modifier =
    drawWithContent {
        drawContent()
        if (focused) {
            val o = offset.toPx() + width.toPx() / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(-o, -o),
                size = Size(size.width + 2 * o, size.height + 2 * o),
                cornerRadius = CornerRadius((cornerRadius.toPx() + o).coerceAtLeast(0f)),
                style = Stroke(width.toPx()),
            )
        }
    }

/**
 * A D-pad target: centre (or Enter) clicks, holding it long-clicks, and
 * focus draws the 4 dp gold ring and scales by [focusedScale], as the
 * mockups' `.focus` class does. [content] gets the focused state.
 *
 * The focus target sits outside the scale, so the bounds a scrolling
 * parent brings into view stay put while the scale animates. Inside it,
 * Home's pinned rows chased the growing card on every move along a row and
 * the screen bounced.
 */
@Composable
fun FocusableBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    cornerRadius: Dp = 12.dp,
    focusedScale: Float = 1.06f,
    ringOffset: Dp = 4.dp,
    ringColor: Color = WmmColors.Accent,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(140), label = "focusScale")
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(focused, cornerRadius, ringOffset, color = ringColor),
        contentAlignment = contentAlignment,
    ) { content(focused) }
}

/**
 * Which item of a screen had focus, by key, kept across navigation (it is
 * saved with the screen), so coming back puts focus where it was.
 */
@Stable
class FocusMemory internal constructor(private val saved: MutableState<String?>) {
    private val requesters = HashMap<String, FocusRequester>()

    /** The key of the item focused last, or null before anything was. */
    val key: String? get() = saved.value

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    internal fun focused(key: String) {
        saved.value = key
    }

    /** Focuses the remembered item, else [fallback]. False when neither is on screen. */
    fun restore(fallback: String?): Boolean {
        saved.value?.let { k -> if (requesters[k]?.requestFocus() == true) return true }
        return fallback?.let { requesters[it]?.requestFocus() } == true
    }
}

@Composable
fun rememberFocusMemory(): FocusMemory {
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    return remember { FocusMemory(saved) }
}

/** Registers this item with [memory] under [key]. */
fun Modifier.focusMemory(memory: FocusMemory, key: String): Modifier =
    focusRequester(memory.requester(key)).onFocusChanged { if (it.isFocused) memory.focused(key) }

/**
 * Focus scrolling for TV rows and pages: no scroll while the focused item is
 * inside [leading]..container-[trailing]; otherwise the least scroll that
 * puts it back inside, so the rows around it stay in view.
 */
@OptIn(ExperimentalFoundationApi::class)
class KeepInViewSpec(private val leading: Float, private val trailing: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = leading
        val end = containerSize - trailing
        return when {
            offset >= start && offset + size <= end -> 0f
            size > end - start -> offset - start
            offset < start -> offset - start
            else -> offset + size - end
        }
    }
}

/**
 * Focus scrolling that pins the focused item's top [pivot] from the top of
 * the container (the scroll stops early at either end): Home's focused row
 * sits at the top with its heading in view, as in the scrolled mockup. It
 * has no "already visible" case, because the spec runs every animation frame
 * and would stop the scroll halfway.
 */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val pivot: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivot
}

/**
 * Focus inside this element asks the scrolling parent to show the whole
 * element rather than just the focused child: the hero's buttons keep the
 * page at the top instead of pivoting like a row's cards.
 */
fun Modifier.bringWholeIntoView(): Modifier = this then WholeIntoViewElement

private object WholeIntoViewElement : ModifierNodeElement<WholeIntoViewNode>() {
    override fun create() = WholeIntoViewNode()

    override fun update(node: WholeIntoViewNode) = Unit

    override fun hashCode() = "bringWholeIntoView".hashCode()

    override fun equals(other: Any?) = other === this

    override fun InspectorInfo.inspectableProperties() {
        name = "bringWholeIntoView"
    }
}

private class WholeIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        bringIntoView(bounds = null)
    }
}
