package media.watchmy.client.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.EmptyState
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.Problem
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.SEERR_APPROVAL_NOTE
import media.watchmy.client.model.line
import media.watchmy.client.model.requestAge
import media.watchmy.client.model.requestedWhat
import media.watchmy.client.model.seasonName
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.requests.InboxViewModel
import media.watchmy.client.presentation.requests.MyRequestsViewModel
import media.watchmy.client.presentation.value
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.rememberTabletLayout
import media.watchmy.client.ui.components.Artwork

/**
 * Your requests (MyRequests.dc.html), reached from Settings: what this
 * person asked for, newest first, each with where it stands; one still
 * waiting can be cancelled.
 */
@Composable
fun MyRequestsScreen(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val vm = viewModel { MyRequestsViewModel(services.requests) }
    val state by vm.state.collectAsStateWithLifecycle()
    val items = state.mine.value
    Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar("Your requests", onBack)
            if (state.options.viaSeerr) {
                Text(SEERR_APPROVAL_NOTE, modifier = Modifier.padding(16.dp), style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextSoft))
            }
            when {
                items == null && state.mine is Load.Failed -> ErrorState((state.mine as Load.Failed).message, vm::retry)
                items == null -> LoadingState()
                items.isEmpty() -> EmptyState("No requests yet", "Ask for a movie or a show from Search, or a missing season from its show.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                    item { HorizontalDivider(color = WmmColors.Line) }
                    items(items, key = { it.id }) { r -> MyRequestRow(r, cancelling = state.cancelling == r.id) { vm.cancel(r) } }
                }
            }
        }
        MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter).padding(WindowInsets.navigationBars.asPaddingValues()))
    }
}

@Composable
private fun MyRequestRow(r: MediaRequest, cancelling: Boolean, onCancel: () -> Unit) {
    val library = LocalAppServices.current.library
    val line = r.line()
    val tone = line.tone.tone()
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(library.imageUrl(r.posterPath, ImageSize.W154), seed = r.title, contentDescription = null, modifier = Modifier.size(48.dp, 72.dp), shape = RoundedCornerShape(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(requestedWhat(r.mediaType, r.seasons), requestAge(r.createdAtMs)).joinToString(" · "),
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                    maxLines = 1,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(line.tone.icon(), contentDescription = null, tint = tone, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = tone, fontWeight = FontWeight.SemiBold)) { append(line.label) }
                            line.note?.let { withStyle(SpanStyle(color = WmmColors.TextSoft, fontWeight = FontWeight.Medium)) { append(" · $it") } }
                        },
                        style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),
                    )
                }
                line.progress?.let { DownloadBar(it, Modifier.fillMaxWidth().padding(top = 4.dp)) }
                if (r.status == RequestStatus.Pending) {
                    GoldTextButton(if (cancelling) "Cancelling…" else "Cancel request", onCancel, Modifier.offset(x = (-12).dp), enabled = !cancelling)
                }
            }
        }
        HorizontalDivider(color = WmmColors.Line)
    }
}

/**
 * The admins' Inbox (Inbox.dc.html), reached from Settings: requests
 * waiting for approval (Decline asks why, optionally), failed ones to send
 * again, and problems people reported, to mark resolved.
 */
@Composable
fun InboxScreen(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val vm = viewModel { InboxViewModel(services.requests) }
    val state by vm.state.collectAsStateWithLifecycle()
    val inbox = state.inbox.value
    val uriHandler = LocalUriHandler.current
    val twoPane = rememberTabletLayout().twoPane
    var selectedRequest by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedProblem by rememberSaveable { mutableStateOf<Long?>(null) }
    Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar("Inbox", onBack)
            Row(Modifier.fillMaxWidth().height(48.dp)) {
                InboxTab("Requests", state.requestCount, state.tab == InboxViewModel.Tab.Requests, Modifier.weight(1f)) { vm.select(InboxViewModel.Tab.Requests) }
                InboxTab("Problems", state.problemCount, state.tab == InboxViewModel.Tab.Problems, Modifier.weight(1f)) { vm.select(InboxViewModel.Tab.Problems) }
            }
            HorizontalDivider(color = WmmColors.Line)
            when {
                inbox == null && state.inbox is Load.Failed -> ErrorState((state.inbox as Load.Failed).message, vm::retry)
                inbox == null -> LoadingState()
                state.tab == InboxViewModel.Tab.Requests && inbox.requests.isEmpty() && inbox.seerr == null -> EmptyState("No requests waiting", "Requests from people who need your approval show up here.")
                state.tab == InboxViewModel.Tab.Problems && inbox.problems.isEmpty() -> EmptyState("No problems reported", "When someone reports a problem with a file, it shows up here.")
                twoPane -> {
                    val request = inbox.requests.firstOrNull { it.id == selectedRequest } ?: inbox.requests.firstOrNull()
                    val problem = inbox.problems.firstOrNull { it.id == selectedProblem } ?: inbox.problems.firstOrNull()
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.width(360.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.tab == InboxViewModel.Tab.Requests) {
                                inbox.seerr?.let { seerr ->
                                    item(key = "seerr") {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Requests are approved in Seerr", color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold)
                                            seerr.pending?.let { Text("$it waiting in Seerr", color = WmmColors.TextMuted) }
                                            seerr.safeRequestsUrl?.let { url -> TonalButton("Open Seerr", { runCatching { uriHandler.openUri(url) }.onFailure { vm.openSeerrFailed() } }) }
                                            if (inbox.requests.isNotEmpty()) Text("Asked for before Seerr was connected", color = WmmColors.TextMuted)
                                        }
                                    }
                                }
                                items(inbox.requests, key = { "r${it.id}" }) { r ->
                                    InboxSelection(r.title, "${r.requestedBy} · ${requestedWhat(r.mediaType, r.seasons)}", request?.id == r.id) {
                                        selectedRequest = r.id
                                        vm.cancelDecline()
                                    }
                                }
                            } else {
                                items(inbox.problems, key = { "p${it.id}" }) { p ->
                                    InboxSelection(p.title, "${p.reportedBy} · ${p.kind.label}", problem?.id == p.id) { selectedProblem = p.id }
                                }
                            }
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp)) {
                            if (state.tab == InboxViewModel.Tab.Requests) {
                                if (request != null) item(key = "r${request.id}") {
                                    Text(request.title, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = WmmColors.Text)
                                    InboxRequest(request, state.declining == request.id, state.busy == "r${request.id}", { vm.approve(request) }, { vm.askWhy(request) }, { vm.decline(request, it) }, vm::cancelDecline)
                                }
                            } else if (problem != null) item(key = "p${problem.id}") {
                                Text(problem.title, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = WmmColors.Text)
                                InboxProblem(problem, state.busy == "p${problem.id}") { vm.resolve(problem.id) }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp)) {
                    if (state.tab == InboxViewModel.Tab.Requests) {
                        inbox.seerr?.let { seerr ->
                            item(key = "seerr") {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Requests are approved in Seerr", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Text))
                                    seerr.pending?.let { Text("$it waiting in Seerr", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextSoft)) }
                                    seerr.safeRequestsUrl?.let { url ->
                                        TonalButton("Open Seerr", { runCatching { uriHandler.openUri(url) }.onFailure { vm.openSeerrFailed() } })
                                    }
                                    if (inbox.requests.isNotEmpty()) {
                                        Text("Asked for before Seerr was connected", style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextSoft))
                                    }
                                }
                            }
                        }
                        items(inbox.requests, key = { "r${it.id}" }) { r ->
                            InboxRequest(
                                r,
                                declining = state.declining == r.id,
                                busy = state.busy == "r${r.id}",
                                onApprove = { vm.approve(r) },
                                onAskWhy = { vm.askWhy(r) },
                                onDecline = { note -> vm.decline(r, note) },
                                onCancel = vm::cancelDecline,
                            )
                        }
                    } else {
                        items(inbox.problems, key = { "p${it.id}" }) { p -> InboxProblem(p, busy = state.busy == "p${p.id}") { vm.resolve(p.id) } }
                    }
                }
            }
        }
        MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter).padding(WindowInsets.navigationBars.asPaddingValues()))
    }
}

@Composable
private fun InboxTab(label: String, count: Int, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxSize().selectable(selected = on, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) WmmColors.Accent else WmmColors.TextMuted))
            if (count > 0) {
                Box(
                    Modifier.heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) WmmColors.Accent.copy(alpha = 0.18f) else WmmColors.Line2).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (on) WmmColors.Accent else WmmColors.TextSoft))
                }
            }
        }
        if (on) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(WmmColors.Accent))
        }
    }
}

private val Say = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 22.sp, color = WmmColors.TextSoft)
private val Strong = SpanStyle(color = WmmColors.Text, fontWeight = FontWeight.Bold)
private val Titled = SpanStyle(color = WmmColors.Text, fontStyle = FontStyle.Italic)
private val Ago = SpanStyle(color = WmmColors.TextMuted)

@Composable
private fun InboxRequest(
    r: MediaRequest,
    declining: Boolean,
    busy: Boolean,
    onApprove: () -> Unit,
    onAskWhy: () -> Unit,
    onDecline: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val library = LocalAppServices.current.library
    var note by rememberSaveable(r.id) { mutableStateOf("") }
    Column {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(library.imageUrl(r.posterPath, ImageSize.W154), seed = r.title, contentDescription = null, modifier = Modifier.size(40.dp, 60.dp), shape = RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        buildAnnotatedString {
                            withStyle(Strong) { append(r.requestedBy) }
                            append(" asked for ")
                            if (r.mediaType == MediaType.Tv && r.seasons.isNotEmpty()) {
                                withStyle(Strong) { append(if (r.seasons.size == 1) seasonName(r.seasons.single()) else requestedWhat(r.mediaType, r.seasons)) }
                                append(" of ")
                            }
                            withStyle(Titled) { append(r.title) }
                            requestAge(r.createdAtMs)?.let { withStyle(Ago) { append(" · $it") } }
                        },
                        style = Say,
                    )
                    if (r.status != RequestStatus.Pending) {
                        val line = r.line()
                        Text(
                            listOfNotNull(line.label, line.note).joinToString(" · "),
                            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = line.tone.tone()),
                        )
                    }
                }
            }
            val decidable = r.status == RequestStatus.Pending || r.status == RequestStatus.Failed
            when {
                !decidable -> Unit
                declining -> {
                    val field = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        label = { Text("Tell ${r.requestedBy} why (optional)") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = WmmColors.Text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WmmColors.Accent,
                            unfocusedBorderColor = WmmColors.FieldOutline,
                            focusedLabelColor = WmmColors.Accent,
                            unfocusedLabelColor = WmmColors.TextMuted,
                            cursorColor = WmmColors.Accent,
                        ),
                        modifier = Modifier.padding(start = 52.dp).fillMaxWidth().focusRequester(field),
                    )
                    Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledButton("Decline", { onDecline(note) }, Modifier.weight(1f), busy = busy)
                        TonalButton("Cancel", onCancel, Modifier.weight(1f))
                    }
                }
                else -> Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledButton(if (r.status == RequestStatus.Failed) "Retry" else "Approve", onApprove, Modifier.weight(1f), busy = busy)
                    TonalButton("Decline", onAskWhy, Modifier.weight(1f), enabled = !busy)
                }
            }
        }
        HorizontalDivider(color = WmmColors.Line)
    }
}

@Composable
private fun InboxProblem(p: Problem, busy: Boolean, onResolve: () -> Unit) {
    val indent = if (p.episode) 108.dp else 52.dp
    Column {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(null, seed = p.title, contentDescription = null, modifier = if (p.episode) Modifier.size(96.dp, 54.dp) else Modifier.size(40.dp, 60.dp), shape = RoundedCornerShape(6.dp))
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
                        style = Say,
                    )
                    if (p.note.isNotBlank()) Text("“${p.note}”", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 21.sp, color = WmmColors.Text))
                    if (p.details.isNotBlank()) Text(p.details, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, lineHeight = 15.sp, color = WmmColors.TextMuted))
                }
            }
            TonalButton("Mark resolved", onResolve, Modifier.padding(start = indent), busy = busy)
        }
        HorizontalDivider(color = WmmColors.Line)
    }
}

@Composable
private fun InboxSelection(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) WmmColors.Accent.copy(alpha = 0.12f) else WmmColors.Surface)
        .selectable(selected, role = Role.Tab, onClick = onSelect).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = if (selected) WmmColors.Accent else WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(subtitle, color = WmmColors.TextMuted, fontFamily = Inter, fontSize = 13.sp)
    }
}
