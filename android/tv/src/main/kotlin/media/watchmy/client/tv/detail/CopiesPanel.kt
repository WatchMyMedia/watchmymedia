package media.watchmy.client.tv.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.AWAY_FROM_HOME_NOTE
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.formatClock
import media.watchmy.client.model.formatRemaining
import media.watchmy.client.model.shortServerName
import media.watchmy.client.model.stillAsking
import media.watchmy.client.model.versionTitle
import media.watchmy.client.presentation.detail.Copies
import media.watchmy.client.presentation.detail.ServerCopy
import media.watchmy.client.presentation.detail.inChooserOrder
import media.watchmy.client.tv.components.OptionRow
import media.watchmy.client.tv.components.PanelGroup
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvSidePanel
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.tryFocus
import media.watchmy.client.ui.WmmColors

/** Where the person is with a copy, as the chooser's rows say it: "52 min left", "Watched", "Not started". */
internal fun ServerCopy.placeLine(): String = when {
    watch?.completed == true -> "Watched"
    place != null -> formatRemaining(place!!.durationMs - place!!.positionMs)
    else -> "Not started"
}

/** What the button after Play says of the copy Play uses: "Home · 4K HDR", short enough for six buttons in a row. */
internal fun Copies.buttonLabel(): String? = play?.let { p ->
    val quality = listOfNotNull(media.watchmy.client.model.resolutionLabel(p.version.width, p.version.height), "HDR".takeIf { p.version.hdr != null }).joinToString(" ")
    "${p.copy.shortName} · ${quality.ifEmpty { versionTitle(p.version) }}"
}

/**
 * What Resume says on a title on several servers: "Resume · from Cabin" when
 * Play's copy is the one with the latest place on another server, "Resume
 * from 1:06:12" when another copy is chosen but the place is that one's;
 * null when the page's own place stands, which says it as before.
 */
internal fun Copies.resumeLabel(own: String?): String? {
    val at = latest?.takeIf { it.registeredId != own } ?: return null
    val play = play ?: return null
    return if (play.copy.registeredId == at.registeredId) "Resume · from ${at.shortName}" else at.place?.let { "Resume from ${formatClock(it.positionMs)}" }
}

/** "Resume starts at 1:06:12, where you got to on Cabin, whichever you choose.": when the latest place is another copy's. */
internal fun Copies.resumeNote(own: String?): String? {
    val at = latest ?: return null
    if (at.registeredId == own) return null
    val place = at.place ?: return null
    return "Resume starts at ${formatClock(place.positionMs)}, where you got to on ${shortServerName(at.serverName)}, whichever you choose."
}

/**
 * The version chooser for a title on several servers, from the right: each
 * server's versions under its heading ("Home server", "Cabin server"), with
 * where the person is there, those away from home last with their note.
 * Focus opens on the copy Play uses. OK on a version chooses it and closes
 * the panel; it never plays by itself ([onChoose]). A server still being
 * asked ends the list with a line that takes no focus.
 */
@Composable
internal fun CopiesPanel(
    title: String,
    copies: Copies,
    note: String?,
    onChoose: (ServerCopy, MediaVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(onDismiss) {
        val focus = remember { FocusRequester() }
        val play = copies.play
        TvSidePanel("Versions", kicker = title) {
            if (note != null) Text(note, style = TvType.Second, color = WmmColors.TextSoft)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(10.dp)) {
                copies.all.inChooserOrder().forEach { copy ->
                    item(key = "head:${copy.registeredId}") { PanelGroup(copy.heading) }
                    if (copy.away) item(key = "away:${copy.registeredId}") {
                        Text(AWAY_FROM_HOME_NOTE, style = TvType.Caption, color = TvPalette.Muted, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
                    }
                    copy.versions.forEach { v ->
                        item(key = "v:${copy.registeredId}:${v.fileId}") {
                            val chosen = play?.copy?.registeredId == copy.registeredId && play.version.fileId == v.fileId
                            OptionRow(
                                versionTitle(v),
                                selected = chosen,
                                onClick = { onChoose(copy, v) },
                                modifier = if (chosen) Modifier.focusRequester(focus) else Modifier,
                                description = copy.placeLine(),
                            )
                        }
                    }
                }
                if (copies.asking.isNotEmpty()) item(key = "asking") {
                    Text(stillAsking(copies.asking), style = TvType.Second, color = TvPalette.Muted, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus.tryFocus()
        }
    }
}
