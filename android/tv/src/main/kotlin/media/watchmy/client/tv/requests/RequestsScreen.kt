package media.watchmy.client.tv.requests

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.Problem
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.SEERR_APPROVAL_NOTE
import media.watchmy.client.model.line
import media.watchmy.client.model.requestAge
import media.watchmy.client.model.requestedWhat
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.requests.InboxViewModel
import media.watchmy.client.presentation.requests.MyRequestsViewModel
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.EmptyState
import media.watchmy.client.tv.components.ErrorState
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.KeepInViewSpec
import media.watchmy.client.tv.components.KeyboardDialog
import media.watchmy.client.tv.components.LoadingState
import media.watchmy.client.tv.components.Notice
import media.watchmy.client.tv.components.RailSection
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonSize
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvScaffold
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork

/**
 * Your requests (MyRequests.dc.html, Android TV), from Settings: each
 * request with where it stands, and Cancel request on one still waiting.
 * Admins get a second tab, the Inbox: requests to approve or decline (with
 * an optional note), failed ones to send again, and problems to mark
 * resolved. [inbox] opens on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RequestsScreen(onNavigate: (Any) -> Unit, onOpenShow: (Long) -> Unit, onOpenMovie: (Long) -> Unit, inbox: Boolean = false) {
    val services = LocalAppServices.current
    val vm = viewModel { MyRequestsViewModel(services.requests) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The Inbox loads only for those who may open it.
    val inboxVm = if (state.options.canOpenInbox) viewModel { InboxViewModel(services.requests) } else null
    val inboxState = inboxVm?.state?.collectAsStateWithLifecycle()?.value
    var inboxWanted by rememberSaveable { mutableStateOf(inbox) }
    val first = remember { FocusRequester() }
    var declining by remember { mutableStateOf<MediaRequest?>(null) }
    val density = LocalDensity.current
    val spec = remember(density) { with(density) { KeepInViewSpec(leading = 60.dp.toPx(), trailing = 60.dp.toPx()) } }
    // Opened on the Inbox, it stays there while what this user may do is loading.
    val showingInbox = inboxWanted && (inboxState != null || (state.mine is Load.Loading && state.options == RequestOptions.None))
    val uriHandler = LocalUriHandler.current

    TvScaffold(RailSection.Settings, onNavigate, sectionRoot = false) {
        Column(Modifier.padding(start = ContentStart, top = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings › ${if (showingInbox) "Inbox" else "Your requests"}", style = inter(18.sp), color = WmmColors.TextMuted)
            Text(if (showingInbox) "Inbox" else "Your requests", style = inter(46.sp, FontWeight.ExtraBold, tracking = -0.02f), color = WmmColors.Text)
            val sub = if (showingInbox) {
                (inboxState?.inbox as? Load.Ready)?.data?.let { i -> listOf("${i.requests.size} request${if (i.requests.size == 1) "" else "s"}", "${i.problems.size} problem${if (i.problems.size == 1) "" else "s"}").joinToString(" · ") }
            } else {
                (state.mine as? Load.Ready)?.data?.let { "${it.size} request${if (it.size == 1) "" else "s"} · newest first" }
            }
            Text(sub.orEmpty(), style = inter(20.sp), color = WmmColors.TextMuted)
        }
        if (state.options.canOpenInbox) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = ContentEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tab("Your requests", null, on = !showingInbox, modifier = if (!showingInbox) Modifier.focusRequester(first) else Modifier) { inboxWanted = false }
                val count = inboxState?.takeIf { it.inbox is Load.Ready }?.let { it.requestCount + it.problemCount } ?: state.options.inbox
                Tab("Inbox", count.takeIf { it > 0 }, on = showingInbox, modifier = if (showingInbox) Modifier.focusRequester(first) else Modifier) { inboxWanted = true }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            Box(Modifier.fillMaxSize().padding(top = 236.dp)) {
                if (showingInbox) {
                    when (val i = inboxState?.inbox) {
                        null, Load.Loading -> LoadingState()
                        is Load.Failed -> ErrorState(i.message, { inboxVm?.retry() }, title = "Couldn't load the Inbox")
                        is Load.Ready -> if (i.data.requests.isEmpty() && i.data.problems.isEmpty() && i.data.seerr == null) {
                            EmptyState("Nothing in the Inbox", "Requests that need your approval and problems people report show up here.")
                        } else {
                            LazyColumn(contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                i.data.seerr?.let { seerr ->
                                    item(key = "seerr") {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Requests are approved in Seerr", style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text)
                                            seerr.pending?.let { Text("$it waiting in Seerr", style = inter(20.sp), color = WmmColors.TextSoft) }
                                            seerr.safeRequestsUrl?.let { url ->
                                                TvButton("Open Seerr", { runCatching { uriHandler.openUri(url) }.onFailure { inboxVm?.openSeerrFailed() } })
                                            }
                                            if (i.data.requests.isNotEmpty()) Text("Asked for before Seerr was connected", style = inter(20.sp), color = WmmColors.TextSoft)
                                        }
                                    }
                                }
                                items(i.data.requests, key = { "r${it.id}" }) { r ->
                                    InboxRequestRow(r, busy = inboxState?.busy == "r${r.id}", onApprove = { inboxVm?.approve(r) }, onDecline = { declining = r })
                                }
                                items(i.data.problems, key = { "p${it.id}" }) { p -> ProblemRow(p, busy = inboxState?.busy == "p${p.id}") { inboxVm?.resolve(p.id) } }
                            }
                        }
                    }
                } else {
                    when (val m = state.mine) {
                        Load.Loading -> LoadingState()
                        is Load.Failed -> ErrorState(m.message, vm::retry, title = "Couldn't load your requests")
                        is Load.Ready -> if (m.data.isEmpty()) {
                            EmptyState("No requests yet", if (state.options.viaSeerr) SEERR_APPROVAL_NOTE else "Ask for a movie or a show from Search, or a missing season from its show.")
                        } else {
                            LazyColumn(contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.options.viaSeerr) item(key = "seerr-note") {
                                    Text(SEERR_APPROVAL_NOTE, style = inter(20.sp), color = WmmColors.TextSoft)
                                }
                                items(m.data, key = { it.id }) { r ->
                                    MyRequestRow(
                                        r,
                                        busy = state.cancelling == r.id,
                                        onCancel = { vm.cancel(r) },
                                        onOpen = when {
                                            r.status != RequestStatus.Available -> null
                                            r.showId != null -> ({ onOpenShow(r.showId!!) })
                                            r.movieId != null -> ({ onOpenMovie(r.movieId!!) })
                                            else -> null
                                        },
                                        modifier = if (!state.options.canOpenInbox && r == m.data.first()) Modifier.focusRequester(first) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Notice(state.message, vm::messageShown)
        inboxVm?.let { Notice(inboxState?.message, it::messageShown) }
    }
    // Focus starts on the tabs (admins) or the first request, once it is there.
    val ready = state.options.canOpenInbox || (state.mine as? Load.Ready)?.data?.isNotEmpty() == true
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    declining?.let { r ->
        KeyboardDialog(
            title = "Tell ${r.requestedBy} why (optional)",
            initial = "",
            submitLabel = "Decline",
            onDismiss = { declining = null },
            onSubmit = { note ->
                declining = null
                inboxVm?.decline(r, note)
            },
        )
    }
}

/** A tab at the top right (`.tab`): gold when on, with the Inbox's count. */
@Composable
private fun Tab(label: String, count: Int?, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableBox(onClick = onClick, modifier = modifier, cornerRadius = 12.dp, focusedScale = 1.04f) { focused ->
        Row(
            Modifier.height(50.dp).background(if (on) WmmColors.Accent else if (focused) WmmColors.Surface3 else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, style = inter(20.sp, FontWeight.SemiBold), color = if (on) WmmColors.OnAccent else WmmColors.TextSoft)
            if (count != null) {
                Box(Modifier.heightIn(min = 26.dp).background(if (on) WmmColors.OnAccent else WmmColors.Accent, RoundedCornerShape(13.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("$count", style = inter(15.sp, FontWeight.ExtraBold), color = if (on) WmmColors.Accent else WmmColors.OnAccent)
                }
            }
        }
    }
}

/** A request row (`.req`): poster, title and what, its state, and Cancel request while it waits. */
@Composable
private fun MyRequestRow(r: MediaRequest, busy: Boolean, onCancel: () -> Unit, onOpen: (() -> Unit)?, modifier: Modifier) {
    val library = LocalAppServices.current.library
    val line = r.line()
    val cancel = r.status == RequestStatus.Pending
    val content = @Composable { focused: Boolean ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) WmmColors.Surface2 else WmmColors.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, WmmColors.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Artwork(library.imageUrl(r.posterPath, ImageSize.W154), seed = r.title, contentDescription = null, modifier = Modifier.size(64.dp, 96.dp), shape = RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.title, style = inter(23.sp, FontWeight.Bold), color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(requestedWhat(r.mediaType, r.seasons), requestAge(r.createdAtMs)).joinToString(" · "), style = inter(18.sp), color = WmmColors.TextMuted)
            }
            Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StateLine(listOfNotNull(line.label, line.note).joinToString(" · "), line.tone.tvIcon(), line.tone.tvTone())
                line.progress?.let { TvDownloadBar(it, 320.dp) }
            }
            Box(Modifier.width(236.dp), contentAlignment = Alignment.CenterEnd) {
                if (cancel) TvButton(if (busy) "Cancelling…" else "Cancel request", onCancel, modifier, size = TvButtonSize.Settings, enabled = !busy)
            }
        }
    }
    if (cancel) {
        content(false)
    } else {
        FocusableBox(onClick = { onOpen?.invoke() }, modifier = modifier.fillMaxWidth(), cornerRadius = 14.dp, focusedScale = 1f, ringOffset = 0.dp) { focused -> content(focused) }
    }
}

@Composable
private fun StateLine(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(text, style = inter(20.sp, FontWeight.SemiBold), color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val Strong = SpanStyle(color = WmmColors.Text, fontWeight = FontWeight.Bold)
private val Titled = SpanStyle(color = WmmColors.Text, fontStyle = FontStyle.Italic)
private val Ago = SpanStyle(color = WmmColors.TextMuted)

/** Someone's request in the Inbox: who asked for what, then Approve (Retry when it failed) and Decline. */
@Composable
private fun InboxRequestRow(r: MediaRequest, busy: Boolean, onApprove: () -> Unit, onDecline: () -> Unit) {
    val library = LocalAppServices.current.library
    val decidable = r.status == RequestStatus.Pending || r.status == RequestStatus.Failed
    val content = @Composable { focused: Boolean ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) WmmColors.Surface2 else WmmColors.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, WmmColors.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Artwork(library.imageUrl(r.posterPath, ImageSize.W154), seed = r.title, contentDescription = null, modifier = Modifier.size(64.dp, 96.dp), shape = RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(Strong) { append(r.requestedBy) }
                        append(" asked for ")
                        if (r.mediaType == MediaType.Tv && r.seasons.isNotEmpty()) {
                            withStyle(Strong) { append(requestedWhat(r.mediaType, r.seasons)) }
                            append(" of ")
                        }
                        withStyle(Titled) { append(r.title) }
                        requestAge(r.createdAtMs)?.let { withStyle(Ago) { append(" · $it") } }
                    },
                    style = inter(22.sp, lineHeight = 30.sp),
                    color = WmmColors.TextSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (r.status != RequestStatus.Pending) {
                    val line = r.line()
                    StateLine(listOfNotNull(line.label, line.note).joinToString(" · "), line.tone.tvIcon(), line.tone.tvTone())
                }
            }
            if (decidable) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton(if (r.status == RequestStatus.Failed) "Retry" else "Approve", onApprove, style = TvButtonStyle.Primary, size = TvButtonSize.Settings, enabled = !busy)
                    TvButton("Decline", onDecline, size = TvButtonSize.Settings, enabled = !busy)
                }
            }
        }
    }
    if (decidable) content(false) else FocusableBox(onClick = {}, modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, focusedScale = 1f, ringOffset = 0.dp) { content(it) }
}

/** A problem someone reported: who, what kind, on what, their note and the file, then Mark resolved. */
@Composable
private fun ProblemRow(p: Problem, busy: Boolean, onResolve: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WmmColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, WmmColors.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Artwork(null, seed = p.title, contentDescription = null, modifier = if (p.episode) Modifier.size(171.dp, 96.dp) else Modifier.size(64.dp, 96.dp), shape = RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(Strong) { append(p.reportedBy) }
                    append(" reported ")
                    withStyle(Strong) { append(p.kind.label) }
                    append(" on ")
                    withStyle(Titled) { append(p.title) }
                    requestAge(p.createdAtMs)?.let { withStyle(Ago) { append(" · $it") } }
                },
                style = inter(22.sp, lineHeight = 30.sp),
                color = WmmColors.TextSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (p.note.isNotBlank()) Text("“${p.note}”", style = inter(20.sp), color = WmmColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.details.isNotBlank()) Text(p.details, style = inter(16.sp), color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TvButton(if (busy) "Resolving…" else "Mark resolved", onResolve, size = TvButtonSize.Settings, enabled = !busy)
    }
}
