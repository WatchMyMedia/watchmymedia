package media.watchmy.client.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REPORT_NOTE
import media.watchmy.client.model.RequestLine
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.attempt
import media.watchmy.client.model.choice
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.initialSeasonPicks
import media.watchmy.client.model.requestButtonLabel
import media.watchmy.client.model.subtitle
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/** A missing episode's colour, as ShowMissing.dc.html gives each state. */
internal fun MissingEpisode.Kind.tone(): Color = when (this) {
    MissingEpisode.Kind.Downloading, MissingEpisode.Kind.Importing -> WmmColors.Accent
    MissingEpisode.Kind.DownloadFailed -> WmmColors.Warn
    MissingEpisode.Kind.NotMonitored, MissingEpisode.Kind.NotInLibrary -> WmmColors.TextMuted
    else -> WmmColors.TextSoft
}

internal fun MissingEpisode.Kind.icon(): ImageVector = when (this) {
    MissingEpisode.Kind.Queued, MissingEpisode.Kind.Importing -> WmmIcons.Clock
    MissingEpisode.Kind.Downloading -> WmmIcons.Download
    MissingEpisode.Kind.DownloadFailed -> WmmIcons.Alert
    MissingEpisode.Kind.Requested -> WmmIcons.Person
    MissingEpisode.Kind.Approved -> WmmIcons.CheckCircle
    MissingEpisode.Kind.Missing -> WmmIcons.SearchCircle
    MissingEpisode.Kind.NotMonitored -> WmmIcons.EyeOff
    MissingEpisode.Kind.Upcoming -> WmmIcons.Calendar
    MissingEpisode.Kind.NotInLibrary -> WmmIcons.PlusCircle
}

/** A request's colour and icon in Your requests (MyRequests.dc.html). */
internal fun RequestLine.Tone.tone(): Color = when (this) {
    RequestLine.Tone.Waiting -> WmmColors.TextSoft
    RequestLine.Tone.Declined, RequestLine.Tone.Failed -> WmmColors.Warn
    else -> WmmColors.Accent
}

internal fun RequestLine.Tone.icon(): ImageVector = when (this) {
    RequestLine.Tone.Waiting -> WmmIcons.Clock
    RequestLine.Tone.Approved -> WmmIcons.CheckCircle
    RequestLine.Tone.Declined -> WmmIcons.XCircle
    RequestLine.Tone.Downloading -> WmmIcons.Download
    RequestLine.Tone.Available -> WmmIcons.Check
    RequestLine.Tone.Failed -> WmmIcons.Alert
}

/** The thin gold download bar (`.dl`). */
@Composable
internal fun DownloadBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(WmmColors.Line2)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(WmmColors.Accent))
    }
}

/** Where a missing episode's still would be (`.gap`): a dashed frame with the state's icon. */
@Composable
internal fun MissingFrame(kind: MissingEpisode.Kind, modifier: Modifier, radius: Dp = 6.dp, iconSize: Dp = 22.dp) {
    Box(
        modifier.background(Color(0xFF141416), RoundedCornerShape(radius)).drawBehind {
            drawRoundRect(
                color = WmmColors.Line3,
                cornerRadius = CornerRadius(radius.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon(), contentDescription = null, tint = kind.tone(), modifier = Modifier.size(iconSize))
    }
}

/** A quiet pill in place of a button ("Requested", `.btn.off`). */
@Composable
internal fun OffPill(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        modifier.height(if (large) 52.dp else 40.dp).clip(CircleShape).background(Color(0xFF26262A)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (large) 16.sp else 14.sp, color = WmmColors.TextMuted))
    }
}

/** A state chip in a list row (`.st`): Requested, Downloading, In your library. */
@Composable
internal fun StateChip(text: String, icon: ImageVector, gold: Boolean) {
    val color = if (gold) WmmColors.Accent else WmmColors.TextSoft
    Row(
        Modifier.height(28.dp).border(1.dp, if (gold) WmmColors.Accent.copy(alpha = 0.55f) else WmmColors.Line3, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = color), maxLines = 1)
    }
}

/** The boards' bottom sheet: #1C1C1F, 28 dp top corners, the grab handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = SheetColor,
        contentColor = WmmColors.Text,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(32.dp, 4.dp).background(Color(0xFF5A5A5F), RoundedCornerShape(2.dp))) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

private val SheetColor = Color(0xFF1C1C1F)

@Composable
internal fun SheetClose(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = WmmColors.Line2, contentColor = WmmColors.Text)) {
        Icon(WmmIcons.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun Kicker(text: String) {
    Text(text.uppercase(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.Accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A line with an info icon under a form ("An admin approves requests…"). */
@Composable
internal fun NoteLine(text: String, icon: ImageVector = WmmIcons.Info) {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(18.dp))
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp, color = WmmColors.TextMuted))
    }
}

/**
 * Asking for a movie or some of a show's seasons (the request sheet on
 * SearchRequest.dc.html and ShowMissing.dc.html): poster, kind, title and
 * overview, a show's seasons to tick, the approval line for members, and
 * Request. [only] ticks just that season (Request season N); otherwise the
 * missing and partly-there seasons start ticked. [onSent] gets the message
 * to show once it is asked for.
 */
@Composable
internal fun RequestSheet(subject: Requestable, options: RequestOptions, onSent: (String) -> Unit, onDismiss: () -> Unit, only: Int? = null) {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var item by remember(subject.tmdbId) { mutableStateOf(subject) }
    var lookupError by remember(subject.tmdbId) { mutableStateOf<String?>(null) }
    var picked by rememberSaveable(subject.tmdbId, only) { mutableStateOf<Set<Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isShow = subject.mediaType == MediaType.Tv
    LaunchedEffect(subject.tmdbId) {
        if (!isShow && subject.overview.isNotBlank()) return@LaunchedEffect
        attempt { services.requests.lookup(subject.mediaType, subject.tmdbId) }
            .onSuccess { found ->
                item = found
                if (picked == null) picked = initialSeasonPicks(found.seasons.map { it.choice() }, only)
            }
            .onFailure { e -> lookupError = errorMessage(e) }
    }
    val choices = item.seasons.map { it.choice() }
    val chosen = picked.orEmpty().filter { n -> choices.any { it.number == n && !it.locked } }
    val count = if (isShow) chosen.size else 1

    RequestBottomSheet(onDismiss) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(
                services.library.imageUrl(item.posterPath, ImageSize.W185),
                seed = item.title,
                contentDescription = null,
                modifier = Modifier.size(72.dp, 108.dp),
                shape = RoundedCornerShape(8.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kicker(if (isShow) "TV show" else "Movie")
                Text(
                    item.title,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 25.sp, color = WmmColors.Text),
                    modifier = Modifier.semantics { heading() },
                )
                val seasons = item.seasons.count { it.number > 0 }
                val meta = listOfNotNull(item.year?.toString(), if (isShow && seasons > 0) "$seasons season${if (seasons == 1) "" else "s"}" else null).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted))
                if (item.overview.isNotBlank()) {
                    Text(
                        item.overview,
                        style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            SheetClose(onDismiss)
        }
        if (isShow) {
            Column {
                Text(
                    "SEASONS",
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.88.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
                when {
                    lookupError != null -> Text(lookupError!!, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(8.dp))
                    choices.isEmpty() -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
                    }
                    else -> choices.forEach { c ->
                        val checked = c.locked || c.number in chosen
                        SeasonOption(c.name, c.detail, c.state, checked, c.locked) {
                            val now = picked.orEmpty()
                            picked = if (c.number in now) now - c.number else now + c.number
                        }
                    }
                }
            }
        }
        options.approvalNote?.let { NoteLine(it) }
        error?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.Error), modifier = Modifier.padding(horizontal = 8.dp)) }
        FilledButton(
            requestButtonLabel(subject.mediaType, count),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    attempt { services.requests.request(subject.mediaType, subject.tmdbId, if (isShow) chosen.sorted() else emptyList()) }
                        .onSuccess { onSent(options.requestedMessage) }
                        .onFailure { e -> error = errorMessage(e) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            large = true,
            enabled = count > 0 && (!isShow || choices.isNotEmpty()),
            busy = busy,
        )
    }
}

/** A season with a checkbox (`.opt`): locked seasons are ticked and greyed. */
@Composable
private fun SeasonOption(name: String, detail: String, state: String?, checked: Boolean, locked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, enabled = !locked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier.size(20.dp).then(
                when {
                    locked -> Modifier.background(WmmColors.Line3, shape)
                    checked -> Modifier.background(WmmColors.Accent, shape)
                    else -> Modifier.border(2.dp, WmmColors.TextMuted, shape)
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(WmmIcons.Check, contentDescription = null, tint = if (locked) WmmColors.TextMuted else WmmColors.OnAccent, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = if (locked) WmmColors.TextMuted else WmmColors.Text))
            Text(detail, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted))
        }
        if (state != null) Text(state, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = WmmColors.TextMuted))
    }
}

/**
 * Report a problem (ReportProblem.dc.html): the kind, a note, and what the
 * server adds. [kicker] names the movie or "Show · S03 E07".
 */
@Composable
internal fun ReportProblemSheet(kicker: String, episode: Boolean, itemId: Long, fileId: Long?, onSent: (String) -> Unit, onDismiss: () -> Unit) {
    val requests = LocalAppServices.current.requests
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf<ProblemKind?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    RequestBottomSheet(onDismiss) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kicker(kicker)
                Text(
                    "Report a problem",
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 28.sp, color = WmmColors.Text),
                    modifier = Modifier.semantics { heading() },
                )
            }
            SheetClose(onDismiss)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProblemKind.entries.forEach { k ->
                val on = kind == k
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(shape)
                        .background(if (on) WmmColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (on) WmmColors.Accent else WmmColors.Line3, shape)
                        .selectable(selected = on, role = Role.RadioButton) { kind = k },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    val color = if (on) WmmColors.Accent else Color(0xFFE5E5E5)
                    Icon(k.icon(), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    Text(k.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color), textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(1000) },
            label = { Text("Note") },
            minLines = 3,
            textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 22.sp, color = WmmColors.Text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WmmColors.Accent,
                unfocusedBorderColor = WmmColors.FieldOutline,
                focusedLabelColor = WmmColors.Accent,
                unfocusedLabelColor = WmmColors.TextMuted,
                cursorColor = WmmColors.Accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        NoteLine(REPORT_NOTE, WmmIcons.Paperclip)
        error?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.Error), modifier = Modifier.padding(horizontal = 4.dp)) }
        FilledButton(
            "Send report",
            onClick = {
                val k = kind ?: return@FilledButton
                busy = true
                error = null
                scope.launch {
                    attempt { requests.report(episode, itemId, fileId, k, note) }
                        .onSuccess { onSent(it.sentMessage) }
                        .onFailure { e -> error = errorMessage(e) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            large = true,
            enabled = kind != null,
            busy = busy,
        )
    }
}

internal fun ProblemKind.icon(): ImageVector = when (this) {
    ProblemKind.Video -> WmmIcons.Screen
    ProblemKind.Audio -> WmmIcons.Speaker
    ProblemKind.Subtitles -> WmmIcons.Captions
    ProblemKind.Other -> WmmIcons.Question
}

/** A list row (`.li`): poster, title and line, and what is on the right. */
@Composable
internal fun RequestableRow(item: Requestable, onClick: (() -> Unit)?, sidePadding: Dp = 16.dp, trailing: @Composable () -> Unit) {
    val library = LocalAppServices.current.library
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = sidePadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(library.imageUrl(item.posterPath, ImageSize.W154), seed = item.title, contentDescription = null, modifier = Modifier.size(48.dp, 72.dp), shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle(), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
