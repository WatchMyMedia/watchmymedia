package media.watchmy.client.mobile.detail

import media.watchmy.client.mobile.components.GoldTextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.TextUnit
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.WATCHED_IT_ALL_NOTE
import media.watchmy.client.ui.components.rememberLiveClockFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.downloads.ItemDownload
import media.watchmy.client.mobile.requests.DownloadBar
import media.watchmy.client.mobile.requests.OffPill
import media.watchmy.client.mobile.requests.tone
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.episodeKicker
import media.watchmy.client.model.playable
import media.watchmy.client.model.episodePlayLabel
import media.watchmy.client.model.episodeTags
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.formatRating
import media.watchmy.client.model.codecLabel
import media.watchmy.client.model.hdrLabel
import media.watchmy.client.model.resolutionLabel
import media.watchmy.client.model.partway
import media.watchmy.client.model.requestSeasonLabel
import media.watchmy.client.model.subtitleLanguages
import media.watchmy.client.model.watchToggleLabel
import media.watchmy.client.model.watched
import media.watchmy.client.presentation.detail.ShowPageActions
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.Badge
import media.watchmy.client.ui.components.BadgeStyle
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WatchedCheck
import media.watchmy.client.ui.components.WmmIcons

/** "HARBOR LIGHTS · S03 E07" with why it is there ("Up next"), then the title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeHeading(show: ShowDetail, ep: EpisodeDetail, state: ShowPageViewModel.State, titleSize: TextUnit) {
    Column {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            episodeKicker(show.title, ep.season, ep.number).uppercase(),
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.Accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.reason?.let { ReasonChip(it.label) }
    }
    Text(
        ep.title.ifBlank { "Episode ${ep.number}" },
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = titleSize, lineHeight = titleSize * 1.15f, letterSpacing = (-0.24).sp, color = WmmColors.Text),
        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
    }
}

/** "Up next", "Watched it all": outlined in gold beside the eyebrow. */
@Composable
private fun ReasonChip(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = WmmColors.Accent),
        modifier = Modifier.height(22.dp).border(1.dp, WmmColors.Accent, RoundedCornerShape(11.dp)).padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** Aired, runtime and rating with Watched; what a missing one waits for; Watched it all's note. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeFacts(ep: EpisodeDetail, state: ShowPageViewModel.State) {
    val missing = state.missing
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val meta = if (missing != null) missingMeta(ep) else listOfNotNull(episodeMeta(ep).ifEmpty { null }, formatRating(ep.rating)).joinToString(" · ")
        Text(meta, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
        if (ep.watched) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchedCheck(size = 16.dp)
                Text("Watched", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Accent))
            }
        }
    }
    ep.versions.firstOrNull()?.recording?.let { Text(it.label(rememberLiveClockFormat()), color = WmmColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    if (missing != null) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(missing.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, color = missing.kind.tone()))
            missing.progress?.let { DownloadBar(it, Modifier.fillMaxWidth()) }
        }
    }
    if (state.reason == OnTopReason.WatchedItAll) {
        Text(WATCHED_IT_ALL_NOTE, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(top = 8.dp))
    }
}

/** The file's tags, subtitles and size, and its name under File (the chosen episode's file is enough). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpisodeFile(ep: EpisodeDetail) {
    val version = ep.versions.firstOrNull() ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            episodeTags(version).forEach { Tag(it) }
            val rest = listOfNotNull(subtitleLanguages(version)?.let { "Subtitles: $it" }, formatBytes(version.sizeBytes)).joinToString(" · ")
            Text(rest, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(start = 2.dp))
        }
        Row(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = if (open) "Hide the file" else "Show the file") { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(if (open) WmmIcons.ChevronDown else WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(16.dp))
            Text("File", style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted))
        }
        if (open) Text(version.label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = WmmColors.TextMuted))
    }
}

/**
 * Play (or Resume) across, then From the start, Mark watched and the
 * episode's ⋮; for a missing episode what can be done about it; then the
 * episode's download.
 */
@Composable
internal fun Actions(show: ShowDetail, ep: EpisodeDetail, state: ShowPageViewModel.State, vm: ShowPageActions, page: PageActions, modifier: Modifier = Modifier) {
    val play = episodePlayLabel(ep)
    val missing = state.missing
    val fromStart = play != null && ep.partway != null
    val menu: @Composable () -> Unit = {
        EpisodeMenu(
            onReport = if (state.canReport) page.onReport else null,
            onReplace = if (state.canReplace) page.onReplace else null,
            onDelete = if (state.canDelete) vm::askDelete else null,
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Where it is, when it matters: opened in place from another server, an episode only another has, or on others too.
        val copies = state.copies
        val where = when {
            state.hereName != null -> listOfNotNull("On ${media.watchmy.client.model.serverHeading(state.hereName!!)}", "away from home".takeIf { state.hereAway }).joinToString(" · ")
            ep.where.serverName != null -> "On ${media.watchmy.client.model.serverHeading(ep.where.serverName!!)}"
            copies != null && copies.several -> "Also on " + media.watchmy.client.model.serverList(copies.all.drop(1).map { it.serverName })
            else -> null
        }
        if (where != null) Text(where, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextSoft))
        when {
            play != null -> FilledButton(play, { vm.playRequest()?.let(page.onPlay) }, Modifier.fillMaxWidth().testTag(TestTags.PLAY), large = true, icon = WmmIcons.Play)
            missing != null && missing.actions -> {
                if (missing.request) FilledButton(requestSeasonLabel(ep.season), { page.onAsk(ep.season) }, Modifier.fillMaxWidth(), large = true)
                if (missing.requested && missing.cancel == null) OffPill("Requested", Modifier.fillMaxWidth(), large = true)
                if (missing.search || missing.monitor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (missing.search) TonalButton("Search now", vm::searchMissing, Modifier.weight(1f), busy = state.acting)
                        if (missing.monitor) TonalButton("Monitor", vm::monitor, Modifier.weight(1f), enabled = !state.acting)
                    }
                }
                missing.cancel?.let { r -> TonalButton("Cancel request", { vm.cancelRequest(r.id) }, Modifier.fillMaxWidth(), enabled = !state.acting) }
            }
            !ep.aired -> AirsLine(ep)
            else -> FilledButton("Not in library", {}, Modifier.fillMaxWidth(), large = true, enabled = false)
        }
        if (fromStart || ep.aired || state.hasMore) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromStart) TonalButton("From the start", { vm.playRequest(startMs = 0)?.let(page.onPlay) }, Modifier.weight(1f), compact = true)
                if (ep.aired) {
                    TonalButton(
                        watchToggleLabel(ep.watched),
                        vm::toggleWatched,
                        Modifier.weight(1f),
                        busy = state.marking,
                        // Beside From the start and ⋮ the label needs the room its icon would take.
                        icon = if (fromStart) null else if (ep.watched) WmmIcons.Undo else WmmIcons.Check,
                        compact = fromStart,
                    )
                }
                if (state.hasMore) menu() else if (!fromStart && !ep.aired) Spacer(Modifier.weight(1f))
            }
        }
        // Every server's copies of the episode, when it is on two servers or more: Play this plays it and keeps it for Play.
        state.copies?.takeIf { it.several }?.let { copies -> EpisodeVersions(copies, vm, page) }
        // Only the server in use's copy downloads: an episode only another server has is downloaded by switching to it.
        if (ep.playable && ep.where.serverId == null) {
            val version = ep.versions.firstOrNull()
            ItemDownload(
                fileId = version?.fileId,
                kicker = episodeKicker(show.title, ep.season, ep.number),
                from = downloadFrom(ep),
                onPlayCopy = { vm.playRequest()?.let(page.onPlay) },
                onOpenSettings = page.onOpenSettings,
                onMessage = vm::say,
                tablet = rememberTabletLayout().isTablet,
            )
        }
    }
}

/** "Airs Tue, Nov 18", where Play goes for an episode still to air. */
@Composable
private fun AirsLine(ep: EpisodeDetail) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(WmmColors.TextFaint))
        Text(episodeMeta(ep), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.TextMuted))
    }
}

/**
 * The episode's ⋮ beside Mark watched:
 * Report a problem, Replace file (admins) and, last, Delete
 * episode… in the danger colour. The show's actions are in About the show.
 */
@Composable
internal fun EpisodeMenu(onReport: (() -> Unit)?, onReplace: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = WmmColors.Line2, contentColor = WmmColors.Text),
        ) {
            Icon(PhoneIcons.MoreVert, contentDescription = "More for this episode")
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(240.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MenuGround,
            border = BorderStroke(1.dp, WmmColors.Line2),
        ) {
            fun pick(action: () -> Unit) {
                open = false
                action()
            }
            onReport?.let { MenuItem("Report a problem", WmmIcons.Flag) { pick(it) } }
            onReplace?.let { MenuItem("Replace file", WmmIcons.Replace) { pick(it) } }
            onDelete?.let {
                if (onReport != null || onReplace != null) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = WmmColors.Line2)
                MenuItem("Delete episode…", WmmIcons.Trash, color = DangerText) { pick(it) }
            }
        }
    }
}

/** The download sheet's line under its title: "The Long Dark · 52 min · 4K HEVC HDR". */
internal fun downloadFrom(ep: EpisodeDetail): String {
    val v = ep.versions.firstOrNull()
    val video = v?.let { listOfNotNull(resolutionLabel(it.width, it.height), codecLabel(it.videoCodec), hdrLabel(it.hdr)).joinToString(" ") }?.ifEmpty { null }
    return listOfNotNull(ep.title, ep.runtimeMin?.let { "$it min" }, video).joinToString(" · ")
}

/** The still edge to edge (`.still`), with NEW while unwatched, the runtime, and progress while part watched. */
@Composable
internal fun Still(ep: EpisodeDetail, url: String?, seed: String, missing: MissingEpisode?) {
    // 16:9 across a phone; a tablet's width would make it most of the screen, so it is cropped to StillMax.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        StillFrame(ep, url, seed, missing, Modifier.fillMaxWidth().height(minOf(maxWidth * 9 / 16, StillMax)).clipToBounds())
    }
}

@Composable
internal fun StillFrame(ep: EpisodeDetail, url: String?, seed: String, missing: MissingEpisode?, modifier: Modifier = Modifier, shade: Boolean = true) {
    Box(modifier) {
        if (!ep.aired || (missing != null && !ep.hasFile)) {
            Box(
                Modifier.fillMaxSize().background(WmmColors.Surface).drawBehind {
                    drawRect(WmmColors.Line3, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (!ep.aired) "Not aired yet" else missing?.label.orEmpty(),
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = missing?.kind?.tone() ?: WmmColors.TextMuted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    if (ep.isNew && !ep.watched) Badge("New", Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp), style = BadgeStyle.Gold)
                    ep.runtimeMin?.let { Badge("$it min", Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp)) }
                    ep.partway?.let { ProgressStrip(it.positionMs.toFloat() / it.durationMs, Modifier.align(Alignment.BottomStart), height = 5.dp) }
                }
            }
        }
        // Darkens the top so the bar's buttons read over a bright still.
        if (shade) Box(Modifier.fillMaxWidth().height(128.dp).background(Brush.verticalGradient(listOf(WmmColors.Ground.copy(alpha = 0.55f), Color.Transparent))))
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, color: Color = Color(0xFFE5E5E5), onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp)) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color),
        contentPadding = PaddingValues(horizontal = 16.dp),
    )
}

private val MenuGround = Color(0xFF1C1C1F)
private val DangerText = Color(0xFFFCA5A5)
internal val Danger = Color(0xFFB42318)
internal val DangerRim = Color(0xFFE5484D)


/** The episode's copies under their servers, those away from home last with their note, each with where the person is there. */
@Composable
private fun EpisodeVersions(copies: media.watchmy.client.presentation.detail.Copies, vm: ShowPageActions, page: PageActions) {
    val play = copies.play
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Versions", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = WmmColors.Text))
        copies.all.sortedBy { it.away }.forEach { copy ->
            Text(copy.heading, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.TextSoft))
            if (copy.away) Text(media.watchmy.client.model.AWAY_FROM_HOME_NOTE, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted))
            copy.versions.forEach { v ->
                val chosen = play?.copy?.registeredId == copy.registeredId && play.version.fileId == v.fileId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WmmColors.Surface).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(media.watchmy.client.model.versionTitle(v) + if (chosen) " · Play uses this" else "", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (chosen) WmmColors.Accent else WmmColors.Text))
                        val place = when {
                            copy.watch?.completed == true -> "Watched"
                            copy.place != null -> media.watchmy.client.model.formatRemaining(copy.place!!.durationMs - copy.place!!.positionMs)
                            else -> "Not started"
                        }
                        Text(place, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted))
                    }
                    GoldTextButton("Play this", {
                        vm.choose(copy, v)
                        vm.playCopy(copy, v)?.let(page.onPlay)
                    })
                }
            }
        }
    }
}
