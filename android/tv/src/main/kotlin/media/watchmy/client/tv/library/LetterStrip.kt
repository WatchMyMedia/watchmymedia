package media.watchmy.client.tv.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import media.watchmy.client.presentation.library.LibraryViewModel
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.model.LetterOffset
import media.watchmy.client.model.plural
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.onBack

/** The strip's letters, as the server indexes titles: digits and the rest under #, then A to Z. */
internal val Alphabet: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

/** How many titles start with each letter, from where each letter's first title sits in the whole result. */
internal fun letterCounts(letters: List<LetterOffset>, total: Int): Map<String, Int> {
    val sorted = letters.sortedBy { it.offset }
    return sorted.mapIndexed { i, l -> l.letter to ((sorted.getOrNull(i + 1)?.offset ?: total) - l.offset) }.toMap()
}

/** The letter above or below [from] that has titles, skipping empty ones; null at either end. */
internal fun nextLetter(from: String, counts: Map<String, Int>, step: Int): String? {
    var i = Alphabet.indexOf(from) + step
    while (i in Alphabet.indices) {
        if ((counts[Alphabet[i]] ?: 0) > 0) return Alphabet[i]
        i += step
    }
    return null
}

/**
 * The A–Z strip at the grid's right edge, shown when
 * the grid is sorted by title. Focus comes in on [current], the letter of
 * the show it came from. ▲ and ▼ move to the next letter that has shows
 * and the grid follows ([onPreview]); OK or ◀ lands on the letter's first
 * show ([onPick]); Back goes back to the show focus came from ([onBack]).
 */
@Composable
internal fun LetterStrip(
    letters: List<LetterOffset>,
    total: Int,
    noun: String,
    current: String?,
    onPreview: (String) -> Unit,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    entryRequester: FocusRequester? = null,
) {
    val counts = remember(letters, total) { letterCounts(letters, total) }
    val requesters = remember { Alphabet.associateWith { FocusRequester() } }
    var focused by remember { mutableStateOf<String?>(null) }
    val entry = current?.takeIf { (counts[it] ?: 0) > 0 } ?: letters.minByOrNull { it.offset }?.letter
    // Back on the strip: the remote's key on older TVs, the back callback on newer ones (Modifier.onBack takes both).
    Box(modifier.onBack(enabled = focused != null, onBack = onBack)) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(vertical = 4.dp)
                .focusProperties { onEnter = { entry?.let { requesters[it]?.requestFocus() } } }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val at = focused ?: return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            // Empty letters are skipped; at either end focus stays on the strip.
                            nextLetter(at, counts, if (e.key == Key.DirectionUp) -1 else 1)?.let { requesters[it]?.requestFocus() }
                            true
                        }
                        Key.DirectionLeft -> {
                            onPick(at)
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (letter in Alphabet) {
                val count = counts[letter] ?: 0
                val label = if (count > 0) "Jump to ${if (letter == "#") "numbers" else letter}, ${plural(count, noun)}" else "$letter, no ${noun}s"
                FocusableBox(
                    onClick = { onPick(letter) },
                    enabled = count > 0,
                    cornerRadius = 12.dp,
                    focusedScale = FocusGrowth.BUTTON,
                    modifier = Modifier
                        .size(StripCell.width, StripCell.height)
                        .focusRequester(requesters.getValue(letter))
                        .then(if (letter == entry && entryRequester != null) Modifier.focusRequester(entryRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focused = letter
                                onPreview(letter)
                            } else if (focused == letter) {
                                focused = null
                            }
                        }
                        .semantics { contentDescription = label },
                ) { isFocused ->
                    Box(
                        Modifier
                            .size(StripCell.width, StripCell.height)
                            .background(if (isFocused) TvPalette.Lift else Color.Transparent, RoundedCornerShape(12.dp))
                            .alpha(if (count == 0) 0.4f else 1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        val here = letter == current
                        val color = if (isFocused || here) WmmColors.Accent else TvPalette.Muted
                        Text(
                            letter,
                            style = (if (isFocused) TvType.Body else TvType.Caption).copy(
                                fontWeight = if (isFocused || here) FontWeight.Bold else FontWeight.SemiBold,
                                // The letter showing in the grid is underlined in gold, so it reads apart from focus.
                                textDecoration = if (here && !isFocused) TextDecoration.Underline else null,
                            ),
                            color = color,
                        )
                    }
                }
            }
        }
        focused?.let { letter ->
            // The bubble beside the strip: the letter large, how many titles start with it, and what OK does.
            val index = Alphabet.indexOf(letter)
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = (4 + index * StripCell.height.value.toInt() - 24).coerceAtLeast(0).dp, end = StripCell.width + 24.dp)
                    .background(WmmColors.Surface2, RoundedCornerShape(18.dp))
                    .border(2.dp, TvPalette.Line, RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(letter, style = TvType.Title, color = WmmColors.Text)
                Text("${plural(counts[letter] ?: 0, noun)} · OK goes to the first", style = TvType.Second, color = WmmColors.TextSoft, maxLines = 1)
            }
        }
    }
}

/** A letter's place in the strip: wide enough for the focused letter at 26, 27 of them in 810. */
private val StripCell = DpSize(56.dp, 30.dp)

/**
 * The TV grid's ways to move a long way: the A–Z strip's previews,
 * picks and Back, and Page Up/Down or CH+/CH− for two rows in the same
 * column. [header] is how many grid items come before the posters.
 */
@Stable
internal class GridJumps(
    private val vm: LibraryViewModel,
    private val memory: FocusMemory,
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
) {
    var header by mutableIntStateOf(0)

    /** The strip's way in: ▶ from the grid's last column goes here, to the focused show's letter. */
    val strip = FocusRequester()

    /** The poster focused last, as an index of the loaded items. */
    var focusedIndex by mutableIntStateOf(-1)

    /** The letter focused on the strip, for the hints. */
    var stripLetter by mutableStateOf<String?>(null)

    /** The card focus came to the strip from, for Back. */
    private var cameFrom: String? = null
    private var preview: Job? = null
    private var landing = false

    /** The letter the focused poster's title is under. */
    fun currentLetter(state: LibraryViewModel.State): String? {
        val at = state.offset + focusedIndex.coerceAtLeast(0)
        return state.letters?.filter { it.offset <= at }?.maxByOrNull { it.offset }?.letter
    }

    fun enteredStrip() {
        cameFrom = memory.key
    }

    /** ▲ ▼ on the strip: the grid follows a moment after focus stops on a letter. */
    fun preview(letter: String) {
        stripLetter = letter
        preview?.cancel()
        preview = scope.launch {
            delay(PREVIEW_DELAY_MS)
            vm.jumpToLetter(letter, preview = true)
        }
    }

    /** OK or ◀: the letter's first title takes focus. */
    fun pick(letter: String) {
        preview?.cancel()
        landing = true
        vm.jumpToLetter(letter)
    }

    /** Back on the strip: the grid as it was, and focus on the card it came from. */
    fun back() {
        preview?.cancel()
        vm.cancelJump()
        val key = cameFrom ?: return
        scope.launch {
            val index = vm.state.value.items.indexOfFirst { "item:${it.id}" == key }
            if (index >= 0) grid.scrollToItem(header + index)
            focus(key)
        }
    }

    /** A jump came: show its row, and after a pick put focus on its first title. */
    suspend fun jumped(state: LibraryViewModel.State) {
        val jump = state.jump ?: return
        grid.scrollToItem(header + (jump.index / COLUMNS) * COLUMNS)
        if (!landing) return
        landing = false
        state.items.getOrNull(jump.index)?.let { focus("item:${it.id}") }
    }

    /** Two rows up ([rows] < 0) or down from the focused poster, in its column; false when there is none. */
    fun page(rows: Int): Boolean {
        val i = focusedIndex.takeIf { it >= 0 } ?: return false
        val state = vm.state.value
        val last = state.items.lastIndex
        val column = i % COLUMNS
        var target = i + rows * COLUMNS
        if (target > last) {
            if (state.hasMore) vm.loadMore()
            target = last - ((last - column) % COLUMNS + COLUMNS) % COLUMNS
            if (target <= i) return state.hasMore
        } else if (target < 0) {
            if (state.hasEarlier) vm.loadEarlier()
            target = column
            if (target >= i) return state.hasEarlier
        }
        val key = state.items.getOrNull(target)?.let { "item:${it.id}" } ?: return false
        scope.launch {
            grid.scrollToItem(header + (target / COLUMNS - 1).coerceAtLeast(0) * COLUMNS)
            focus(key)
        }
        return true
    }

    /**
     * Focuses the poster [key]. Coming from the strip, the grid's focus
     * restorer may hand focus to the poster it had before, so this asks
     * again until the poster asked for has it.
     */
    private suspend fun focus(key: String) {
        repeat(8) {
            withFrameNanos { }
            runCatching { memory.requester(key).requestFocus() }
            withFrameNanos { }
            if (memory.key == key) return
        }
    }

    private companion object {
        const val PREVIEW_DELAY_MS = 150L
    }
}

@Composable
internal fun rememberGridJumps(vm: LibraryViewModel, memory: FocusMemory, grid: LazyGridState): GridJumps {
    val scope = rememberCoroutineScope()
    return remember(vm, memory, grid) { GridJumps(vm, memory, grid, scope) }
}

/** Page Up/Down and CH+/CH− on the grid: two rows in the same column. */
internal fun Modifier.pageKeys(jumps: GridJumps): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.PageUp, Key.ChannelUp -> jumps.page(-PAGE_ROWS)
        Key.PageDown, Key.ChannelDown -> jumps.page(PAGE_ROWS)
        else -> false
    }
}

/** A screenful of the grid: two rows. */
private const val PAGE_ROWS = 2

/** The key hints at the foot of a grid: only what no button shows (the strip's keys, and CH+ CH− for a screen at a time). */
@Composable
internal fun JumpHints(onStrip: Boolean, letter: String?, byTitle: Boolean, modifier: Modifier = Modifier) {
    val text = when {
        onStrip -> "▲ ▼ Letter    OK First title under ${letter.orEmpty()}    Back The title you were on"
        byTitle -> "▶ from the last column: A–Z    CH+ CH− A screen up or down"
        else -> "CH+ CH− A screen up or down"
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(WmmColors.Ground.copy(alpha = 0f), WmmColors.Ground.copy(alpha = 0.94f), WmmColors.Ground)))
            .padding(start = ContentStart, top = 40.dp, bottom = SafeY),
    ) {
        Text(text, style = TvType.Caption, color = TvPalette.Muted)
    }
}
