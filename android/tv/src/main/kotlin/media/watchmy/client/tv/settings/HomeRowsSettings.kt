package media.watchmy.client.tv.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.attempt
import media.watchmy.client.model.editorLine
import media.watchmy.client.model.notShown
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.rows.RowLayoutViewModel
import media.watchmy.client.tv.components.BackClosesNote
import media.watchmy.client.tv.components.OptionsDialog
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors

/** A screen the person arranges: Home, or a library's Movies or TV screen, by its name. */
private data class RowsScreen(val screen: String, val name: String)

private const val TV_LISTS_NOTE = "Lists are made in WatchMyMedia on the web. Lists for everyone, and your own, show up here."

/**
 * Settings › Home rows on the TV (android-tv/SettingsHomeRows.dc.html), as
 * the language lists are done: the Screen it arranges (Home, Movies, TV
 * Shows), then its rows numbered in order with their lines, Options (or
 * Hidden) on each, which opens Move up, Move down, Hide or Show and Cancel
 * in the side panel. Every change is saved as it is made. Hidden rows stay
 * in the list; there is no Add a row on the TV. [initialScreen] is the
 * screen a library's Edit rows opened it on; [focus] then takes focus to
 * its Screen row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.HomeRowsSettings(initialScreen: String?, focus: FocusRequester) {
    val services = LocalAppServices.current
    var screen by rememberSaveable { mutableStateOf(initialScreen ?: RowScreens.HOME) }
    val screens by produceState(listOf(RowsScreen(RowScreens.HOME, "Home"))) {
        attempt { services.library.libraries() }.onSuccess { libs ->
            value = listOf(RowsScreen(RowScreens.HOME, "Home")) + libs.map { RowsScreen(RowScreens.library(it.id), if (it.type == LibraryType.Tv) it.name.ifBlank { "TV Shows" } else it.name.ifBlank { "Movies" }) }
        }
    }
    val current = screens.firstOrNull { it.screen == screen } ?: RowsScreen(screen, if (screen == RowScreens.HOME) "Home" else "Library")
    val vm = viewModel(key = "tv-rows-$screen") { RowLayoutViewModel(services.rows, screen, current.name, autosave = true) }
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    var refocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refocus, state.entries) {
        val id = refocus ?: return@LaunchedEffect
        if (runCatching { requesters[id]?.requestFocus() }.getOrNull() == true) refocus = null
    }

    // Opened from a library's Edit rows: once its rows are in, bring the Screen row (which has focus) into view again,
    // as the section grew under it while they loaded.
    var shown by rememberSaveable { mutableStateOf(initialScreen == null) }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(state.layout is Load.Ready) {
        if (shown || state.layout !is Load.Ready) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { bring.bringIntoView() }
        shown = true
    }

    Section("Home rows") {
        Note("The rows on ${current.name}, in your order. They are the same on every device you sign in on.")
        SettingRow(
            "Screen",
            onClick = { picking = true },
            description = "Movies and TV Shows have rows of their own",
            modifier = Modifier.focusRequester(focus).bringIntoViewRequester(bring),
        ) { Value(current.name, chevron = true) }
        when (val layout = state.layout) {
            Load.Loading -> Note("Loading…")
            is Load.Failed -> SettingRow("Couldn't load the rows", onClick = vm::retry, description = layout.message) { Value("Try again") }
            is Load.Ready -> state.entries.forEachIndexed { i, entry -> key(entry.id) {
                val line = entry.copy(hidden = false).editorLine(state.listFor(entry))
                val note = entry.notShown
                val requester = remember { FocusRequester() }
                DisposableEffect(requester) {
                    requesters[entry.id] = requester
                    onDispose { requesters.remove(entry.id) }
                }
                SettingRow(
                    "${i + 1}. ${entry.title}",
                    onClick = { options = entry.id },
                    description = if (note == null) line else "$line\n$note",
                    stacked = note != null,
                    labelColor = if (entry.hidden) WmmColors.TextFaint else WmmColors.Text,
                    modifier = Modifier.focusRequester(requester),
                ) { Value(if (entry.hidden) "Hidden" else "Options", chevron = true) }
            } }
        }
        Note(TV_LISTS_NOTE, Modifier.padding(top = 4.dp), color = WmmColors.TextFaint)
        SettingRow("Reset ${current.name} rows", onClick = vm::reset, description = "Back to the rows everyone on this server starts with") { Box {} }
        if (state.saving) Text("Saving…", style = inter(17.sp), color = WmmColors.TextMuted, modifier = Modifier.padding(horizontal = 18.dp))
        state.message?.let { message ->
            Text(message, style = inter(17.sp), color = WmmColors.TextSoft, modifier = Modifier.padding(horizontal = 18.dp))
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2600)
                vm.messageShown()
            }
        }
    }

    if (picking) {
        OptionsDialog(
            title = "Screen",
            context = "Which screen's rows to arrange",
            options = screens,
            selected = current,
            label = { it.name },
            onSelect = {
                picking = false
                screen = it.screen
            },
            onDismiss = { picking = false },
            footer = {
                Box(Modifier.weight(1f))
                BackClosesNote()
            },
        )
    }
    options?.let { id ->
        val index = state.entries.indexOfFirst { it.id == id }
        val entry = state.entries.getOrNull(index)
        if (entry == null) {
            options = null
        } else {
            val actions = buildList {
                if (index > 0) add(RowAction.Up)
                if (index < state.entries.lastIndex) add(RowAction.Down)
                add(if (entry.hidden) RowAction.Show else RowAction.Hide)
                add(RowAction.Cancel)
            }
            OptionsDialog<RowAction>(
                title = entry.title,
                context = "${current.name} rows · row ${index + 1} of ${state.entries.size}" + if (entry.hidden) " · hidden" else "",
                options = actions,
                selected = null,
                label = { it.label(current.name) },
                onSelect = {
                    when (it) {
                        RowAction.Up -> vm.moveUp(id)
                        RowAction.Down -> vm.moveDown(id)
                        RowAction.Hide -> vm.hide(id)
                        RowAction.Show -> vm.show(id)
                        RowAction.Cancel -> Unit
                    }
                    options = null
                    refocus = id
                },
                onDismiss = {
                    options = null
                    refocus = id
                },
                footer = {
                    Box(Modifier.weight(1f))
                    BackClosesNote()
                },
            )
        }
    }
}

/** What the side panel offers for a row. */
private enum class RowAction {
    Up, Down, Hide, Show, Cancel;

    fun label(screen: String): String = when (this) {
        Up -> "Move up"
        Down -> "Move down"
        Hide -> "Hide from $screen"
        Show -> "Show on $screen"
        Cancel -> "Cancel"
    }
}
