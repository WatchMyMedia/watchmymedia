package media.watchmy.client.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.ui.WmmColors

private val Digits = "1234567890".map { it.toString() }
private val Letters = listOf("qwertyuiop", "asdfghjkl'", "zxcvbnm,.-").map { row -> row.map { it.toString() } }
private val Symbols = listOf("!@#$%^&*()", "_=+[]{};:\"", "/\\|~`<>?€£").map { row -> row.map { it.toString() } }

private enum class Layer { Lower, Upper, Symbols }

/**
 * An on-screen keyboard for the D-pad: digits, QWERTY letters (with Shift)
 * and symbols, Space, Delete and [doneLabel]. [extraKeys] adds a row of
 * shortcuts (".local", "https://"). [firstKey] goes on the first letter
 * (q), where focus starts. A hardware or remote keyboard types straight in
 * while any key has focus.
 */
@Composable
fun OnScreenKeyboard(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    doneLabel: String = "Done",
    extraKeys: List<String> = emptyList(),
    keySize: Dp = 64.dp,
    firstKey: FocusRequester? = null,
) {
    var layer by remember { mutableStateOf(Layer.Lower) }
    val gap = 8.dp
    val unit = keySize + gap
    fun type(s: String) {
        onText(s)
        if (layer == Layer.Upper) layer = Layer.Lower
    }
    Column(
        modifier
            .focusRestorer()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Backspace -> {
                        onBackspace()
                        true
                    }
                    e.utf16CodePoint >= 32 && e.key != Key.DirectionCenter -> {
                        onText(e.utf16CodePoint.toChar().toString())
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (extraKeys.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (k in extraKeys) Key(k, { type(k) }, Modifier.width(unit * 2.5f - gap).height(keySize), fontSize = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (d in Digits) Key(d, { type(d) }, Modifier.size(keySize))
        }
        val rows = if (layer == Layer.Symbols) Symbols else Letters
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { i, k ->
                    val label = if (layer == Layer.Upper) k.uppercase() else k
                    val first = r == 0 && i == 0 && firstKey != null
                    Key(label, { type(label) }, Modifier.size(keySize).then(if (first) Modifier.focusRequester(firstKey) else Modifier))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Key(
                if (layer == Layer.Upper) "⇧ ABC" else "⇧ abc",
                { layer = if (layer == Layer.Upper) Layer.Lower else Layer.Upper },
                Modifier.width(unit * 1.5f - gap / 2).height(keySize),
                fontSize = 18.sp,
                on = layer == Layer.Upper,
            )
            Key(
                if (layer == Layer.Symbols) "abc" else "#+=",
                { layer = if (layer == Layer.Symbols) Layer.Lower else Layer.Symbols },
                Modifier.width(unit * 1.5f - gap / 2).height(keySize),
                fontSize = 18.sp,
            )
            Key("Space", { onText(" ") }, Modifier.width(unit * 3 - gap * 2).height(keySize), fontSize = 18.sp)
            Key("⌫", onBackspace, Modifier.width(unit * 1.5f - gap / 2).height(keySize), fontSize = 22.sp, onLongClick = { repeat(64) { onBackspace() } })
            if (onDone != null) {
                Key(doneLabel, onDone, Modifier.width(unit * 2.5f - gap * 1.5f).height(keySize), fontSize = 18.sp, gold = true)
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 24.sp,
    on: Boolean = false,
    gold: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    FocusableBox(onClick = onClick, onLongClick = onLongClick, modifier = modifier, cornerRadius = 12.dp, focusedScale = 1.08f, contentAlignment = Alignment.Center) { focused ->
        val bg = when {
            gold || on -> WmmColors.Accent
            focused -> WmmColors.Surface3
            else -> WmmColors.Surface2
        }
        Box(
            Modifier.fillMaxSize().background(bg, shape).border(1.dp, if (gold || on) WmmColors.Accent else WmmColors.Line2, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = inter(fontSize, FontWeight.SemiBold), color = if (gold || on) WmmColors.OnAccent else WmmColors.Text, maxLines = 1)
        }
    }
}

/**
 * What a keyboard is typing into: a field-looking box (`input` in the
 * mockups) with a gold caret while [active]. [masked] shows dots.
 */
@Composable
fun KeyboardField(
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    masked: Boolean = false,
    active: Boolean = false,
    height: Dp = 72.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(WmmColors.Surface, shape)
            .border(if (active) 2.dp else 1.dp, if (active) WmmColors.Accent else WmmColors.FieldOutline, shape)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = if (masked) "•".repeat(value.length) else value
        if (shown.isEmpty() && !active) {
            Text(placeholder, style = inter(24.sp), color = WmmColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(shown, style = inter(24.sp), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
            if (active) Box(Modifier.padding(start = 2.dp).width(3.dp).height(30.dp).background(WmmColors.Accent))
            if (shown.isEmpty()) Text(placeholder, style = inter(24.sp), color = WmmColors.TextFaint, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/**
 * Typing one value full screen: [title], the field, an optional [note] and
 * the keyboard. [onSubmit] gets the text from the keyboard's [submitLabel]
 * key; Back closes it without a change. [noteWarns] turns the note orange
 * for text it applies to.
 */
@Composable
fun KeyboardDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    masked: Boolean = false,
    placeholder: String = "",
    note: String? = null,
    submitLabel: String = "Done",
    extraKeys: List<String> = emptyList(),
    noteWarns: (String) -> Boolean = { false },
) {
    var text by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(WmmColors.Surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .border(1.dp, WmmColors.Line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(start = 160.dp, end = 160.dp, top = 44.dp, bottom = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(Modifier.width(980.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, style = inter(30.sp, FontWeight.SemiBold), color = WmmColors.Text)
                    KeyboardField(text, placeholder = placeholder, masked = masked, active = true)
                    if (note != null) Text(note, style = inter(18.sp, lineHeight = 27.sp), color = if (noteWarns(text)) WmmColors.Warn else WmmColors.TextFaint)
                }
                OnScreenKeyboard(
                    onText = { text += it },
                    onBackspace = { text = text.dropLast(1) },
                    onDone = { onSubmit(text) },
                    doneLabel = submitLabel,
                    extraKeys = extraKeys,
                    firstKey = first,
                )
            }
        }
        InitialFocus(first)
    }
}
