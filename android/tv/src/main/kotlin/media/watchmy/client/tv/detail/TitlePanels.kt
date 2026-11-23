package media.watchmy.client.tv.detail

import media.watchmy.client.model.addedLine
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.model.audioLabel
import media.watchmy.client.model.codecLabel
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.hdrLabel
import media.watchmy.client.model.versionTitle
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.PanelGroup
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.WidePanelWidth
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.rememberLiveClockFormat

/**
 * A row of a title's More: [label], a grey [detail] under it, and a chevron
 * when it opens another panel ([opens]). [key] names it, so focus comes back
 * to it from the panel it opened.
 */
internal class MoreRow(
    val key: String,
    val label: String,
    val onClick: () -> Unit,
    val detail: String? = null,
    val opens: Boolean = false,
    val icon: ImageVector? = null,
    val warn: Boolean = false,
)

/**
 * More, from the right: [context] (the title, or "S03 E07 · The Long Dark")
 * over "More", then [rows]; focus starts on [focusKey] (the row that opened
 * the panel just closed), else the first row. Back closes it.
 */
@Composable
internal fun TitleMorePanel(context: String, rows: List<MoreRow>, onDismiss: () -> Unit, focusKey: String? = null, title: String = "More", groups: Map<Int, String> = emptyMap()) {
    val first = remember { FocusRequester() }
    val start = rows.indexOfFirst { it.key == focusKey }.takeIf { it >= 0 } ?: 0
    TvDialog(onDismiss) {
        TitlePanel(context, title) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEachIndexed { i, r ->
                    groups[i]?.let { PanelGroup(it) }
                    LinkRow(r.label, r.onClick, if (i == start) Modifier.focusRequester(first) else Modifier, detail = r.detail, opens = r.opens, icon = r.icon, warn = r.warn)
                }
            }
            Spacer(Modifier.weight(1f))
            KeyHints(listOf("Back" to "Close"))
        }
        InitialFocus(first)
    }
}

/**
 * A title's panel from the right: a grey line saying what it is for
 * ([context]: "Ninety Fathoms", "Ninety Fathoms · More") over its [title].
 */
@Composable
internal fun TitlePanel(context: String, title: String, width: androidx.compose.ui.unit.Dp = media.watchmy.client.tv.components.PanelWidth, content: @Composable ColumnScope.() -> Unit) {
    TvSidePanel(null, width = width) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(context, style = TvType.Second, color = TvPalette.Muted, maxLines = 1)
            Text(title, style = TvType.Heading, color = WmmColors.Text, maxLines = 2)
        }
        content()
    }
}

/**
 * A row in a title's panels (`.opt`): its words, a grey second line, and a
 * chevron when it opens another panel, or a gold tick when [chosen].
 */
@Composable
internal fun LinkRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    opens: Boolean = false,
    chosen: Boolean = false,
    icon: ImageVector? = null,
    warn: Boolean = false,
    radio: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    FocusableBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().then(
            if (radio) Modifier.semantics {
                role = Role.RadioButton
                selected = chosen
            } else Modifier,
        ),
        cornerRadius = 16.dp,
        focusedScale = FocusGrowth.ROW,
        role = if (radio) null else Role.Button,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .background(if (focused) TvPalette.Lift else Color.Transparent, shape)
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (warn) WmmColors.Warn else WmmColors.TextSoft, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = TvType.Body.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium), color = WmmColors.Text, maxLines = 2)
                if (detail != null) Text(detail, style = TvType.Caption, color = TvPalette.Muted, maxLines = 3)
            }
            if (chosen) Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(30.dp))
            if (opens) Icon(WmmIcons.ChevronRight, contentDescription = null, tint = TvPalette.Muted, modifier = Modifier.size(30.dp))
        }
    }
}

/**
 * File details, 900 wide: one sentence on how the version plays on this TV,
 * the versions (the one Play and Resume use ticked; OK plays the one
 * focused), then what is in the focused version's file. Back goes back to
 * More.
 */
@Composable
internal fun FileDetailsPanel(
    context: String,
    versions: List<MediaVersion>,
    plans: Map<Long, PlaybackPlan>,
    onPlay: (MediaVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    var shown by remember { mutableStateOf(versions.firstOrNull()) }
    val format = rememberLiveClockFormat()
    TvDialog(onDismiss) {
        TitlePanel("$context · More", "File details", width = WidePanelWidth) {
            Text(planSentence(shown?.let { plans[it.fileId] }), style = TvType.Body, color = WmmColors.Text, modifier = Modifier.padding(top = 4.dp))
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 10.dp),
            ) {
                if (versions.isNotEmpty()) {
                    item("versions-head") { PanelGroup("Versions") }
                    item("versions") {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            versions.forEachIndexed { i, v ->
                                LinkRow(
                                    versionTitle(v),
                                    { onPlay(v) },
                                    (if (i == 0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) shown = v },
                                    detail = versionDetail(v, plans[v.fileId]),
                                    chosen = i == 0,
                                    radio = true,
                                )
                            }
                        }
                    }
                }
                shown?.let { v ->
                    item("file-head") { PanelGroup(if (versions.size > 1) "In this version" else "In the file") }
                    item("file") {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            fileFacts(v, v.recording?.label(format)).forEach { (label, value) -> FileFact(label, value) }
                        }
                    }
                }
            }
            KeyHints(listOf("OK" to "Play this version", "Back" to "More"))
        }
        InitialFocus(first)
    }
}

@Composable
private fun FileFact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(label, style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.width(150.dp))
        Text(value, style = TvType.Second, color = WmmColors.Text, modifier = Modifier.weight(1f))
    }
}

/** What File details says of a file, label by label: the picture, the sound, the subtitles, the file's name, and when it came into the library. */
internal fun fileFacts(v: MediaVersion, recorded: String?): List<Pair<String, String>> = listOfNotNull(
    videoLine(v)?.let { "Video" to it },
    soundLine(v)?.let { "Sound" to it },
    "Subtitles" to (subtitlesLine(v) ?: "None in the file"),
    "File" to v.label,
    formatBytes(v.sizeBytes).let { "Size" to it },
    recorded?.let { "Recorded" to it.removePrefix("Recorded from ") },
    v.addedLine()?.let { "Added" to it },
)

/** "3840 × 1608, HEVC, Dolby Vision profile 8". */
private fun videoLine(v: MediaVersion): String? {
    val size = if ((v.width ?: 0) > 0 && (v.height ?: 0) > 0) "${v.width} × ${v.height}" else null
    val hdr = hdrLabel(v.hdr)?.let { h -> v.dvProfile?.takeIf { it > 0 && h == media.watchmy.client.model.DOLBY_VISION }?.let { "$h profile $it" } ?: h }
    return listOfNotNull(size, codecLabel(v.videoCodec), hdr).joinToString(", ").ifEmpty { null }
}

/** "English TrueHD 7.1, English AC-3 5.1, Spanish E-AC-3 5.1", or the default track's summary. */
private fun soundLine(v: MediaVersion): String? {
    if (v.audio.isEmpty()) return v.audioSummary
    val names = v.audio.map { it.label.removeSuffix(" (Default)").replace(" · ", " ") }
    return names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else ""
}

/** "English, Spanish, French (pictures)". */
private fun subtitlesLine(v: MediaVersion): String? {
    if (v.subtitles.isEmpty()) return null
    val names = v.subtitles.map { s -> s.label + if (s.burnInOnly) " (pictures)" else "" }
    return names.take(4).joinToString(", ") + if (names.size > 4) " and ${names.size - 4} more" else ""
}

/** A version's second line in File details: "Dolby Vision · TrueHD 7.1 · 58.2 GB · plays as it is". */
internal fun versionDetail(v: MediaVersion, plan: PlaybackPlan?): String = listOfNotNull(
    v.hdrTagSpelledOut ?: codecLabel(v.videoCodec),
    audioLabel(v.audioSummary),
    formatBytes(v.sizeBytes),
    plan?.let(::planShort),
).joinToString(" · ")

/** How a version plays here, in a few words. */
internal fun planShort(plan: PlaybackPlan): String = when (plan.mode) {
    PlaybackMode.Direct, PlaybackMode.Remux -> "plays as it is"
    PlaybackMode.AudioTranscode -> "sound converted for this TV"
    PlaybackMode.Transcode -> listOfNotNull("converted to", plan.video?.quality).joinToString(" ") + " as it plays"
}

/** File details' first sentence: how the file plays on this TV, in words. */
internal fun planSentence(plan: PlaybackPlan?): String = when (plan?.mode) {
    null -> "How it plays on this TV is worked out when it starts."
    PlaybackMode.Direct -> "Plays on this TV as it is. Nothing is converted."
    PlaybackMode.Remux -> "Plays on this TV as it is. The server only repackages it as it plays."
    PlaybackMode.AudioTranscode -> "The picture plays as it is. The server converts the sound for this TV" + (plan.audioCodec?.let { " (to $it)" } ?: "") + "."
    PlaybackMode.Transcode -> "This TV can’t play the file as it is, so the server converts it" + (plan.video?.quality?.let { " to $it" } ?: "") + " as it plays."
}

/** Subtitles as the person chose them in Audio & subtitles: as Settings says, off, or one track by its id. */
internal const val SUBTITLES_AUTO = "auto"
internal const val SUBTITLES_OFF = "off"

/** [this] request with the sound and subtitles chosen on the page: [audio] null keeps the file's default. */
internal fun PlayRequest.withTracks(audio: Int?, subtitles: String): PlayRequest = when (subtitles) {
    SUBTITLES_AUTO -> copy(audioIndex = audio)
    SUBTITLES_OFF -> copy(audioIndex = audio, subtitleMode = SubtitleRequestMode.Off)
    else -> copy(audioIndex = audio, subtitleId = subtitles)
}

/** More's line under Audio & subtitles: "English · TrueHD 7.1 · subtitles off". */
internal fun tracksLine(v: MediaVersion, audio: Int?, subtitles: String): String {
    val sound = (v.audio.firstOrNull { it.index == audio } ?: v.audio.firstOrNull { it.isDefault } ?: v.audio.firstOrNull())?.label?.removeSuffix(" (Default)")
        ?: v.audioSummary
    val subs = when (subtitles) {
        SUBTITLES_AUTO -> "subtitles as in Settings"
        SUBTITLES_OFF -> "subtitles off"
        else -> v.subtitles.firstOrNull { it.id == subtitles }?.let { "subtitles: ${it.label}" } ?: "subtitles as in Settings"
    }
    return listOfNotNull(sound, subs).joinToString(" · ")
}

/**
 * Audio & subtitles for the next play from this page: the file's sound
 * tracks, then its subtitles (as Settings says, Off, or a track), each in
 * words. A choice applies at once, with its tick, and the panel stays;
 * Back goes back to More.
 */
@Composable
internal fun TracksPanel(context: String, version: MediaVersion, audio: Int?, subtitles: String, onAudio: (Int?) -> Unit, onSubtitles: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val defaultAudio = version.audio.firstOrNull { it.isDefault } ?: version.audio.firstOrNull()
    val chosenAudio = version.audio.firstOrNull { it.index == audio } ?: defaultAudio
    TvDialog(onDismiss) {
        TitlePanel("$context · More", "Audio & subtitles") {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 10.dp)) {
                if (version.audio.isNotEmpty()) {
                    item("audio-head") { PanelGroup("Sound") }
                    version.audio.forEach { a ->
                        item("a${a.index}") {
                            LinkRow(
                                a.label.removeSuffix(" (Default)"),
                                { onAudio(a.index.takeIf { a != defaultAudio }) },
                                if (a == chosenAudio) Modifier.focusRequester(first) else Modifier,
                                detail = a.title,
                                chosen = a == chosenAudio,
                                radio = true,
                            )
                        }
                    }
                }
                item("subs-head") { PanelGroup("Subtitles") }
                item("s-auto") {
                    LinkRow(
                        "As in Settings",
                        { onSubtitles(SUBTITLES_AUTO) },
                        if (version.audio.isEmpty()) Modifier.focusRequester(first) else Modifier,
                        detail = "Your subtitle languages choose",
                        chosen = subtitles == SUBTITLES_AUTO,
                        radio = true,
                    )
                }
                item("s-off") { LinkRow("Off", { onSubtitles(SUBTITLES_OFF) }, chosen = subtitles == SUBTITLES_OFF, radio = true) }
                version.subtitles.forEach { s ->
                    item("s${s.id}") {
                        LinkRow(
                            s.label,
                            { onSubtitles(s.id) },
                            detail = if (s.burnInOnly) "Drawn into the picture, so the video is converted" else s.format,
                            chosen = subtitles == s.id,
                            radio = true,
                        )
                    }
                }
            }
            KeyHints(listOf("Back" to "More"))
        }
        InitialFocus(first)
    }
}
