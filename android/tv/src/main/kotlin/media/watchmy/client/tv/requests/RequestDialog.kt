package media.watchmy.client.tv.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.RequestLine
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.SeasonAvailability
import media.watchmy.client.model.choice
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.initialSeasonPicks
import media.watchmy.client.model.requestButtonLabel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons

/** A missing episode's colour on the TV, as ShowMissing.dc.html (Android TV) gives each state. */
internal fun MissingEpisode.Kind.tvTone(): Color = when (this) {
    MissingEpisode.Kind.Downloading -> WmmColors.Accent
    MissingEpisode.Kind.DownloadFailed -> WmmColors.Error
    MissingEpisode.Kind.NotInLibrary -> WmmColors.Warn
    MissingEpisode.Kind.Missing, MissingEpisode.Kind.NotMonitored, MissingEpisode.Kind.Upcoming -> WmmColors.TextMuted
    else -> WmmColors.TextSoft
}

internal fun MissingEpisode.Kind.tvIcon(): ImageVector = when (this) {
    MissingEpisode.Kind.Queued, MissingEpisode.Kind.Requested -> WmmIcons.Clock
    MissingEpisode.Kind.Downloading -> WmmIcons.Download
    MissingEpisode.Kind.Importing -> WmmIcons.Tray
    MissingEpisode.Kind.DownloadFailed -> WmmIcons.Alert
    MissingEpisode.Kind.Approved -> WmmIcons.Check
    MissingEpisode.Kind.Missing -> WmmIcons.SearchCircle
    MissingEpisode.Kind.NotMonitored -> WmmIcons.EyeOff
    MissingEpisode.Kind.Upcoming -> WmmIcons.Calendar
    MissingEpisode.Kind.NotInLibrary -> WmmIcons.PlusCircle
}

/** A request's colour and icon in Your requests (MyRequests.dc.html, Android TV). */
internal fun RequestLine.Tone.tvTone(): Color = when (this) {
    RequestLine.Tone.Waiting -> WmmColors.TextSoft
    RequestLine.Tone.Approved -> TextBright
    RequestLine.Tone.Declined -> WmmColors.TextMuted
    RequestLine.Tone.Downloading -> WmmColors.Accent
    RequestLine.Tone.Available -> WmmColors.Text
    RequestLine.Tone.Failed -> WmmColors.Error
}

internal fun RequestLine.Tone.tvIcon(): ImageVector = when (this) {
    RequestLine.Tone.Waiting -> WmmIcons.Clock
    RequestLine.Tone.Approved -> WmmIcons.Check
    RequestLine.Tone.Declined -> WmmIcons.XCircle
    RequestLine.Tone.Downloading -> WmmIcons.Download
    RequestLine.Tone.Available -> WmmIcons.CheckCircle
    RequestLine.Tone.Failed -> WmmIcons.Alert
}

/** The gold download bar (`.bar`). */
@Composable
internal fun TvDownloadBar(progress: Float, width: androidx.compose.ui.unit.Dp = 300.dp) {
    Box(Modifier.width(width).height(5.dp).background(WmmColors.Line2, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp).background(WmmColors.Accent, RoundedCornerShape(3.dp)))
    }
}

/**
 * Asking for a movie or some of a show's seasons on the TV (the request
 * dialog on SearchRequest.dc.html): poster, title, overview and Request
 * with Cancel on the left, a show's seasons to tick on the right. [only]
 * ticks that season alone (Request season N). [onSent] gets the message
 * to show once it is asked for.
 */
@Composable
internal fun RequestDialog(subject: Requestable, options: RequestOptions, onSent: (String) -> Unit, onDismiss: () -> Unit, only: Int? = null) {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var item by remember(subject.tmdbId) { mutableStateOf(subject) }
    var picked by remember(subject.tmdbId, only) { mutableStateOf<Set<Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val isShow = subject.mediaType == MediaType.Tv
    val primary = remember { FocusRequester() }
    LaunchedEffect(subject.tmdbId) {
        try {
            val found = services.requests.lookup(subject.mediaType, subject.tmdbId)
            item = found
            if (picked == null) picked = initialSeasonPicks(found.seasons.map { it.choice() }, only)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorMessage(e)
        }
    }
    val choices = item.seasons.map { it.choice() }
    val chosen = picked.orEmpty().filter { n -> choices.any { it.number == n && !it.locked } }
    val count = if (isShow) chosen.size else 1

    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
            Box(
                Modifier.fillMaxWidth().height(640.dp).background(
                    Brush.verticalGradient(0f to Color(0xFF1F3A4D), 0.55f to Color(0xFF12161C), 1f to WmmColors.Ground),
                ),
            )
            Box(
                Modifier.fillMaxWidth().height(640.dp).background(
                    Brush.horizontalGradient(0f to WmmColors.Ground.copy(alpha = 0.94f), 0.45f to WmmColors.Ground.copy(alpha = 0.6f), 0.8f to WmmColors.Ground.copy(alpha = 0.2f)),
                ),
            )
            Artwork(
                services.library.imageUrl(item.posterPath, ImageSize.W342),
                seed = item.title,
                contentDescription = null,
                modifier = Modifier.offset(160.dp, 150.dp).size(240.dp, 360.dp),
                shape = RoundedCornerShape(14.dp),
            )
            Column(Modifier.offset(440.dp, 150.dp).width(580.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Kicker("Request")
                Text(item.title, style = inter(56.sp, FontWeight.ExtraBold, lineHeight = 59.sp, tracking = -0.025f), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val seasons = item.seasons.count { it.number > 0 }
                val meta = listOfNotNull(
                    item.year?.toString(),
                    if (isShow && seasons > 0) "$seasons season${if (seasons == 1) "" else "s"}" else null,
                    if (isShow) "TV show" else "Movie",
                ).joinToString(" · ")
                Text(meta, style = inter(21.sp), color = TextBright)
                if (item.overview.isNotBlank()) {
                    Text(item.overview, style = inter(20.sp, lineHeight = 30.sp), color = WmmColors.TextSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton(
                        if (busy) "Requesting…" else requestButtonLabel(subject.mediaType, count),
                        onClick = {
                            if (busy || count == 0 || (isShow && choices.isEmpty())) return@TvButton
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    services.requests.request(subject.mediaType, subject.tmdbId, if (isShow) chosen.sorted() else emptyList())
                                    onSent(options.requestedMessage)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = errorMessage(e)
                                }
                                busy = false
                            }
                        },
                        // Stays focusable with nothing ticked (it reads "Request" and does nothing), so focus
                        // starts here while the seasons load and doesn't jump when the last one is unticked.
                        modifier = Modifier.focusRequester(primary),
                        style = TvButtonStyle.Primary,
                        icon = WmmIcons.Plus,
                    )
                    TvButton("Cancel", onDismiss)
                }
                options.approvalNote?.let { approvalNote ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(WmmIcons.Clock, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
                        Text(approvalNote, style = inter(19.sp), color = WmmColors.TextMuted)
                    }
                }
                error?.let { Text(it, style = inter(19.sp), color = WmmColors.Error) }
            }
            if (isShow) {
                Column(
                    Modifier.offset(1120.dp, 150.dp).width(680.dp).heightIn(max = 860.dp).verticalScroll(rememberScrollState()).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("SEASONS", style = inter(17.sp, FontWeight.Bold, tracking = 0.1f), color = WmmColors.TextMuted, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
                    if (choices.isEmpty() && error == null) Text("Loading…", style = inter(19.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(start = 18.dp))
                    for ((c, s) in choices.zip(item.seasons)) {
                        val ticked = c.number in chosen
                        if (c.locked) {
                            SeasonRow(c.name, c.detail, c.state, icon = if (s.availability == SeasonAvailability.Available) WmmIcons.CheckCircle else WmmIcons.Clock, ticked = false, onClick = null)
                        } else {
                            SeasonRow(c.name, c.detail, c.state, icon = null, ticked = ticked) {
                                val now = picked.orEmpty()
                                picked = if (c.number in now) now - c.number else now + c.number
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { primary.requestFocus() }
        }
    }
}

/** A season in the dialog (`.srow`): a box to tick, or for one in the library or asked for, its icon, greyed. */
@Composable
private fun SeasonRow(name: String, detail: String, note: String?, icon: ImageVector?, ticked: Boolean, onClick: (() -> Unit)?) {
    val body = @Composable { focused: Boolean ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .background(if (focused) WmmColors.Surface3 else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.size(32.dp).then(
                    when {
                        icon != null -> Modifier
                        ticked -> Modifier.background(WmmColors.Accent, RoundedCornerShape(8.dp))
                        else -> Modifier.border(2.dp, WmmColors.TextFaint, RoundedCornerShape(8.dp))
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    icon != null -> Icon(icon, contentDescription = null, tint = WmmColors.TextFaint, modifier = Modifier.size(26.dp))
                    ticked -> Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.OnAccent, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = inter(22.sp, FontWeight.SemiBold), color = if (onClick == null) WmmColors.TextMuted else WmmColors.Text)
                Text(detail, style = inter(17.sp), color = WmmColors.TextMuted)
            }
            if (note != null) Text(note, style = inter(18.sp, FontWeight.SemiBold), color = if (onClick == null) WmmColors.TextMuted else WmmColors.TextSoft)
        }
    }
    if (onClick == null) {
        body(false)
    } else {
        FocusableBox(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$name, ${if (ticked) "selected" else "not selected"}" },
            cornerRadius = 12.dp,
            focusedScale = 1f,
            ringOffset = 0.dp,
        ) { focused -> body(focused) }
    }
}
