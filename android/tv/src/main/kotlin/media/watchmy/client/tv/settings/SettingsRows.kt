package media.watchmy.client.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.formatAgo
import media.watchmy.client.model.kindLabel
import media.watchmy.client.model.label
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.radialGlow
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            style = inter(17.sp, FontWeight.Bold, tracking = 0.1f),
            color = WmmColors.TextMuted,
            modifier = Modifier.padding(start = 0.dp, bottom = 2.dp),
        )
        content()
    }
}

/**
 * A settings row (`.row`): label, grey description, and a value or control
 * on the right; the inset ring when focused. [stacked] puts a longer
 * description under the label (`.row.two`).
 */
@Composable
internal fun SettingRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    stacked: Boolean = false,
    minHeight: Dp = if (stacked) 88.dp else 68.dp,
    labelSize: Int = 21,
    labelColor: Color = WmmColors.Text,
    /** Who the row is to Settings' focus memory; give rows that share a label keys of their own. */
    focusKey: String = label,
    trailing: @Composable () -> Unit,
) {
    val memory = LocalSettingsFocus.current
    val remembered = if (memory != null) Modifier.focusMemory(memory, focusKey) else Modifier
    FocusableBox(onClick = onClick, modifier = modifier.then(remembered).fillMaxWidth(), cornerRadius = 12.dp, focusedScale = 1f, ringOffset = (-4).dp) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(if (focused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = if (stacked) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = inter(labelSize.sp, FontWeight.SemiBold), color = labelColor, maxLines = 1)
                    if (description != null) Text(description, style = inter(17.sp, lineHeight = 23.sp), color = WmmColors.TextMuted)
                }
            } else {
                Text(label, style = inter(labelSize.sp, FontWeight.SemiBold), color = labelColor, maxLines = 1)
                if (description != null) Text(description, style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                else Box(Modifier.weight(1f))
            }
            trailing()
        }
    }
}

/** Settings' [FocusMemory], which every [SettingRow] registers with. */
// Internal to Settings, like Cards' LocalCardRowFocus: it saves passing the memory to each of its two dozen rows.
@Suppress("ComposeCompositionLocalUsage")
internal val LocalSettingsFocus = staticCompositionLocalOf<FocusMemory?> { null }

/** A row that TalkBack reads as a switch, on or off, and dimmed when it can't change. */
internal fun Modifier.switch(on: Boolean, enabled: Boolean = true): Modifier = semantics {
    role = Role.Switch
    toggleableState = ToggleableState(on)
    if (!enabled) disabled()
}

/** A heading inside a section (`.sh`): Audio languages, Subtitle languages. */
@Composable
internal fun SubHeading(text: String, top: Dp = 0.dp) {
    Text(text, style = inter(20.sp, FontWeight.Bold), color = WmmColors.Text, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = top).semantics { heading() })
}

/** A grey line under a heading or a list (`.note`, `.foot`). */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: Color = WmmColors.TextMuted) {
    Text(text, style = inter(17.sp, lineHeight = 25.sp), color = color, modifier = modifier.padding(horizontal = 18.dp))
}

/** Said once, after the server turned subtitles for foreign audio on with the first audio languages. */
@Composable
internal fun ForeignAudioNotice() {
    Row(
        Modifier
            .padding(top = 2.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(WmmColors.Surface3, RoundedCornerShape(12.dp))
            .border(1.dp, WmmColors.Line3, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(WmmIcons.Info, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.padding(top = 1.dp).size(26.dp))
        Text(
            "Subtitles now start when the audio isn’t in your languages. You can change this under Start subtitles.",
            style = inter(19.sp, lineHeight = 27.sp), color = WmmColors.Text,
        )
    }
}

@Composable
internal fun Value(text: String, chevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (text.isNotEmpty()) Text(text, style = inter(19.sp), color = WmmColors.TextSoft, maxLines = 1)
        if (chevron) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

/** The gold switch (`.toggle`); off, a grey rim and a smaller knob (`.toggle.off`). */
@Composable
internal fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .size(64.dp, 36.dp)
            .then(if (on) Modifier.background(WmmColors.Accent, shape) else Modifier.background(WmmColors.Surface2, shape).border(2.dp, WmmColors.FieldOutline, shape))
            .padding(if (on) 4.dp else 6.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(if (on) 28.dp else 20.dp).background(if (on) WmmColors.OnAccent else WmmColors.FieldOutline, CircleShape))
    }
}

/** How [size] reads over a picture: the subtitle size panel's Preview. */
@Composable
internal fun SubtitlePreview(size: SubtitleSize, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PREVIEW", style = inter(17.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(PreviewScene)
                .radialGlow(Color(0xFFFFCD78).copy(alpha = 0.16f), 0.6f, 0.44f, 420.dp, 180.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.BottomCenter,
        ) {
            val px = 28 * size.scale
            Text(
                "Who moved the markers, Maren?",
                style = inter(px.sp, FontWeight.SemiBold, lineHeight = (px * 1.35f).sp),
                color = WmmColors.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** The preview's harbour at dusk (`linear-gradient(180deg, …)`). */
private val PreviewScene = Brush.verticalGradient(
    0f to Color(0xFF0C1A22),
    0.44f to Color(0xFF1B3542),
    0.46f to Color(0xFF0A1216),
    1f to Color(0xFF06090B),
)

/**
 * A skip row: the label and a four-way segmented control (`.seg`). Each
 * segment takes focus; coming into the row lands on the selected one, and
 * the row lights up while any segment has focus, as in the mockup.
 */
@Composable
internal fun SkipRow(label: String, note: String?, mode: SkipMode, onSelect: (SkipMode) -> Unit) {
    var rowFocused by remember { mutableStateOf(false) }
    val selected = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { rowFocused = it.hasFocus }
            .background(if (rowFocused) WmmColors.Surface2 else Color.Transparent, RoundedCornerShape(12.dp))
            .then(
                if (rowFocused) Modifier.border(4.dp, WmmColors.Accent, RoundedCornerShape(12.dp)) else Modifier,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, style = inter(21.sp, FontWeight.SemiBold), color = WmmColors.Text)
            if (note != null) Text(note, style = inter(17.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else Box(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(WmmColors.Surface2)
                    .border(1.dp, WmmColors.Line2, RoundedCornerShape(10.dp))
                    .focusProperties { onEnter = { selected.requestFocus() } }
                    .focusGroup(),
            ) {
                for (m in SkipMode.entries) {
                    val on = m == mode
                    FocusableBox(
                        onClick = { onSelect(m) },
                        modifier = if (on) Modifier.focusRequester(selected) else Modifier,
                        cornerRadius = 0.dp,
                        focusedScale = 1f,
                        ringOffset = (-4).dp,
                        // Gold on the gold selected segment would not show.
                        ringColor = if (on) WmmColors.OnAccent else WmmColors.Accent,
                    ) {
                        Box(Modifier.height(44.dp).background(if (on) WmmColors.Accent else Color.Transparent).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(m.label, style = inter(17.sp, FontWeight.SemiBold), color = if (on) WmmColors.OnAccent else WmmColors.TextSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceRow(d: DeviceSession, revoking: Boolean, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.name, style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(d.model ?: d.kindLabel, if (d.current) "now" else formatAgo(d.lastSeenAtMs)).joinToString(" · "),
                style = inter(15.sp),
                color = WmmColors.TextMuted,
                maxLines = 1,
            )
        }
        if (d.current) {
            Text(
                "THIS TV",
                modifier = Modifier.background(WmmColors.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = inter(13.sp, FontWeight.Bold, tracking = 0.05f),
                color = WmmColors.OnAccent,
            )
        } else {
            TvButton(if (revoking) "Signing out…" else "Sign out", onRevoke, size = TvButtonSize.Compact, enabled = !revoking)
        }
    }
}

@Composable
internal fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = inter(19.sp, FontWeight.SemiBold), color = WmmColors.Text)
        Box(Modifier.weight(1f))
        Text(value, style = inter(17.sp), color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
