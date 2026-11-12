package media.watchmy.client.mobile.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.ErrorState
import media.watchmy.client.mobile.components.LoadingState
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.LISTS_ON_THE_WEB
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.addLine
import media.watchmy.client.model.editorLine
import media.watchmy.client.model.notShown
import media.watchmy.client.model.overLimit
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.rows.RowLayoutViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

private val Intro = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextMuted)
private val Foot = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp, color = WmmColors.TextFaint)
private val RowTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp)
private val RowLine = TextStyle(fontFamily = Inter, fontSize = 12.sp)

/**
 * Edit Home, or a library's Edit rows (HomeEditRows.dc.html on the phone,
 * TabHomeEditRows.dc.html as a dialog on the tablet): the screen's rows with
 * a drag handle, ⋮ for Move up, Move down, Hide or Show and a list's Open
 * list, Show on a hidden row, Add a row and Reset. Done saves the changes;
 * Close leaves without saving. [name] names the screen: "Home", "Movies".
 */
@Composable
fun RowEditorScreen(
    screen: String,
    name: String,
    onClose: () -> Unit,
    onOpenList: (Long) -> Unit,
    modifier: Modifier = Modifier,
    tablet: Boolean = false,
) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "rows-editor-$screen") { RowLayoutViewModel(services.rows, screen, name) }
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.events.collect { if (it == RowLayoutViewModel.Event.Done) onClose() } }

    val content: @Composable ColumnScope.() -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = if (tablet) 12.dp else 0.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close without saving" }) {
                Icon(WmmIcons.Close, contentDescription = null, tint = WmmColors.Text)
            }
            Text(
                "Edit $name",
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text),
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
            if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
            TextButton(onClick = vm::done, enabled = state.layout is Load.Ready && !state.saving) {
                Text("Done", color = WmmColors.Accent, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        Text(
            "Drag a row by its handle, or use ⋮ to move it. Your rows are the same on every device you sign in on.",
            style = Intro,
            modifier = Modifier.padding(start = if (tablet) 24.dp else 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val layout = state.layout) {
                Load.Loading -> LoadingState()
                is Load.Failed -> ErrorState(layout.message, vm::retry, title = "Couldn't load the rows")
                is Load.Ready -> EditorList(state, vm, name, tablet, onOpenList)
            }
            MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
        }
        HorizontalDivider(color = WmmColors.Line)
        if (tablet) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalButton("Add a row", { adding = true }, icon = WmmIcons.Plus, enabled = state.layout is Load.Ready)
                Text(RESET_NOTE, style = Foot, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                TextButton(onClick = vm::reset, enabled = state.layout is Load.Ready && !state.saving) { Text("Reset", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold) }
            }
        } else {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TonalButton("Add a row", { adding = true }, icon = WmmIcons.Plus, enabled = state.layout is Load.Ready)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::reset, enabled = state.layout is Load.Ready && !state.saving) { Text("Reset", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold) }
                }
                Text(RESET_NOTE, style = Foot)
            }
        }
    }

    if (tablet) {
        Surface(shape = RoundedCornerShape(28.dp), color = WmmColors.Surface2, modifier = modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 760.dp)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    } else {
        Column(modifier.fillMaxSize().background(WmmColors.Ground).statusBarsPadding(), content = content)
    }

    if (adding) AddRow(state, name, tablet, onAdd = { vm.add(it); adding = false }, onDismiss = { adding = false })
}

private const val RESET_NOTE = "Reset goes back to the rows everyone on this server starts with."

/** The rows, each with its handle, line and ⋮; the one being dragged lifted above the others. */
@Composable
private fun EditorList(state: RowLayoutViewModel.State, vm: RowLayoutViewModel, name: String, tablet: Boolean, onOpenList: (Long) -> Unit) {
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val entries by rememberUpdatedState(state.entries)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = if (tablet) 12.dp else 0.dp, end = if (tablet) 12.dp else 0.dp, bottom = 16.dp)) {
        if (state.entries.overLimit) {
            item(key = "limit", contentType = "notice") {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .background(WmmColors.Accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .border(1.dp, WmmColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(WmmIcons.Info, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(18.dp))
                    Text("$name shows 30 rows at most. Hide a row to make room for the ones marked Not shown.", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.Text))
                }
            }
        }
        itemsIndexed(state.entries, key = { _, e -> e.id }, contentType = { _, _ -> "row" }) { index, entry ->
            val lifted = dragging == entry.id
            val handle: Modifier.() -> Modifier = { pointerInput(entry.id) {
                detectDragGestures(
                    onDragStart = {
                        dragging = entry.id
                        offset = 0f
                    },
                    onDragEnd = {
                        dragging = null
                        offset = 0f
                        vm.dropped()
                    },
                    onDragCancel = {
                        dragging = null
                        offset = 0f
                        vm.dropped()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        offset += amount.y
                        val visible = list.layoutInfo.visibleItemsInfo
                        val me = visible.firstOrNull { it.key == entry.id } ?: return@detectDragGestures
                        val middle = me.offset + offset + me.size / 2f
                        val target = visible.firstOrNull { it.key != entry.id && it.key is String && it.key != "limit" && middle.toInt() in it.offset..(it.offset + it.size) }
                            ?: return@detectDragGestures
                        val from = entries.indexOfFirst { it.id == entry.id }
                        val to = entries.indexOfFirst { it.id == target.key }
                        if (from >= 0 && to >= 0) {
                            // The row moves to the slot it is over; it stays under the finger.
                            offset += me.offset - target.offset
                            vm.drag(from, to)
                        }
                    },
                )
            } }
            EditorRow(
                entry = entry,
                line = entry.editorLine(state.listFor(entry)),
                index = index,
                count = state.entries.size,
                lifted = lifted,
                dragHandle = handle,
                onShow = { vm.show(entry.id) },
                onHide = { vm.hide(entry.id) },
                onMoveUp = { vm.moveUp(entry.id) },
                onMoveDown = { vm.moveDown(entry.id) },
                onOpenList = entry.listId?.let { id -> { onOpenList(id) } },
                modifier = if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = offset } else Modifier.animateItem(),
            )
        }
    }
}

/**
 * A row in the editor (`.er`): the handle, the title and its line (and why
 * it isn't shown), Show when hidden, and ⋮. A hidden row is greyed.
 */
@Composable
private fun EditorRow(
    entry: RowLayoutEntry,
    line: String,
    index: Int,
    count: Int,
    lifted: Boolean,
    dragHandle: Modifier.() -> Modifier,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOpenList: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val off = entry.hidden
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (lifted) Modifier.padding(horizontal = 4.dp).shadow(14.dp, shape).background(WmmColors.Line2, shape).border(1.dp, WmmColors.Line3, shape) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = 4.dp)
            .semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Move up") { onMoveUp(); true }.takeIf { index > 0 },
                    CustomAccessibilityAction("Move down") { onMoveDown(); true }.takeIf { index < count - 1 },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.dragHandle().size(44.dp, 48.dp).semantics { contentDescription = "Drag to move ${entry.title}" }, contentAlignment = Alignment.Center) {
            Icon(WmmIcons.DragHandle, contentDescription = null, tint = if (lifted) WmmColors.Accent else if (off) Color(0xFF5A5A5F) else WmmColors.TextMuted)
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = RowTitle, color = if (off) WmmColors.TextFaint else WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = RowLine, color = if (off) WmmColors.TextFaint else WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.notShown?.let { note ->
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(WmmIcons.Info, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(14.dp))
                    Text(note, style = RowLine.copy(fontWeight = FontWeight.Medium), color = WmmColors.TextSoft)
                }
            }
        }
        if (lifted) {
            Text("Row ${index + 1} of $count", style = RowLine.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Accent, modifier = Modifier.padding(end = 16.dp))
        } else {
            if (off) {
                TextButton(onClick = onShow, modifier = Modifier.semantics { contentDescription = "Show ${entry.title}" }) {
                    Text("Show", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "${entry.title}, options" }) {
                    Icon(PhoneIcons.MoreVert, contentDescription = null, tint = WmmColors.Text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = WmmColors.Surface3, modifier = Modifier.width(208.dp)) {
                    if (index > 0) MenuItem("Move up", WmmIcons.ArrowUp) { menu = false; onMoveUp() }
                    if (index < count - 1) MenuItem("Move down", WmmIcons.ArrowDown) { menu = false; onMoveDown() }
                    if (off) MenuItem("Show", WmmIcons.Eye) { menu = false; onShow() } else MenuItem("Hide", WmmIcons.EyeOff) { menu = false; onHide() }
                    if (onOpenList != null) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = WmmColors.Line3)
                        MenuItem("Open list", WmmIcons.ListBullets) { menu = false; onOpenList() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/**
 * Add a row (AddRow.dc.html; a dialog on the tablet): the hidden built-in
 * rows, the household's lists and the person's own, each coming back where
 * it was, and that lists are made on the web, with Open the web app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRow(state: RowLayoutViewModel.State, name: String, tablet: Boolean, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val services = LocalAppServices.current
    val server by services.servers.current.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val body: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Add a row", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text), modifier = Modifier.semantics { heading() })
                Text("Rows you hid, and lists you can show on $name. A row comes back where it was.", style = Intro, modifier = Modifier.padding(top = 4.dp))
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(WmmColors.Line2).clickable(onClickLabel = "Close", onClick = onDismiss).semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(WmmIcons.Close, contentDescription = null, tint = WmmColors.Text, modifier = Modifier.size(20.dp))
            }
        }
        val groups = state.addGroups
        groups.forEach { group ->
            Text(
                group.name.uppercase(),
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted),
                modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
            )
            group.rows.forEach { entry ->
                val list = state.listFor(entry)
                val icon = when {
                    entry.kind != RowLayoutEntry.Kind.List -> WmmIcons.Rows
                    list?.sourceKind == "library" -> WmmIcons.Filter
                    else -> WmmIcons.ListBullets
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .background(Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = "Add ${entry.title}") { onAdd(entry.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(44.dp).background(WmmColors.Line2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.title, style = RowTitle, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.addLine(list), style = RowLine, color = WmmColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(40.dp).background(WmmColors.Accent.copy(alpha = 0.16f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        Icon(WmmIcons.Plus, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            group.empty?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        }
        if (groups.isEmpty()) {
            Text(
                "Every row is on $name. Hide one in Edit $name and it will wait here.",
                style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextSoft),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp),
            )
        }
        HorizontalDivider(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), color = WmmColors.Line3)
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(WmmIcons.Globe, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
            Text(LISTS_ON_THE_WEB, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextSoft), modifier = Modifier.weight(1f))
        }
        server?.server?.baseUrl?.let { base ->
            TextButton(onClick = { runCatching { uri.openUri(base) } }, modifier = Modifier.padding(start = 26.dp)) {
                Text("Open the web app", color = WmmColors.Accent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Icon(PhoneIcons.External, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(16.dp))
            }
        }
    }
    if (tablet) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(28.dp), color = WmmColors.Surface3, modifier = Modifier.widthIn(max = 480.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp), content = body)
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WmmColors.Surface3) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 28.dp), content = body)
        }
    }
}
