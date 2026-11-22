package media.watchmy.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.holding
import media.watchmy.client.model.namesOf
import media.watchmy.client.model.watchlistMenuLabel
import media.watchmy.client.model.watchlistTitle

/** A card menu's watchlist item: its words, what it does, and whether the title is [on] a watchlist (the TV's filled bookmark). */
@Immutable
data class WatchlistMenuItem(val label: String, val on: Boolean = false, val onClick: () -> Unit)

/**
 * Watchlists as the session's screens use them, on the phone, tablet and
 * TV: the person's [watchlists], where titles saved or removed here now are
 * ([held]), and a press of the Watchlist button or a card's menu item, which
 * the session's saver takes (it toggles, or opens Save to watchlist). A new
 * one is provided as either changes, so every button and card menu says
 * Add or Remove rightly. A watchlist's own page provides one whose card item
 * takes the title off that watchlist ([withItem]).
 */
@Immutable
class WatchlistControls(
    val watchlists: List<Watchlist>?,
    val held: Map<WatchlistKey, List<Long>>,
    private val onPress: (WatchlistTitle, Boolean, List<Long>) -> Unit,
    private val itemFor: ((WatchlistTitle, Boolean) -> WatchlistMenuItem?)? = null,
) {
    /** The watchlists holding [key]: what this device changed lately, else what the screen [read]. */
    fun holding(key: WatchlistKey?, read: List<Long>): List<Long> = held.holding(key, read)

    /** The button's names for [ids]. */
    fun names(ids: List<Long>): List<String> = watchlists.namesOf(ids)

    /** The Watchlist button: [title] is on [ids] now. */
    fun press(title: WatchlistTitle, ids: List<Long>) = onPress(title, ids.isNotEmpty(), ids)

    /** A card's item for [title] (null when it can't be saved), which the screen read as [watchlisted]. */
    fun item(title: WatchlistTitle?, watchlisted: Boolean): WatchlistMenuItem? {
        title ?: return null
        val on = held[title.key]?.isNotEmpty() ?: watchlisted
        itemFor?.let { return it(title, on) }
        return WatchlistMenuItem(watchlistMenuLabel(watchlists, on), on) { onPress(title, on, held[title.key].orEmpty()) }
    }

    /** The same, with cards' items made by [item] instead. */
    fun withItem(item: (WatchlistTitle, Boolean) -> WatchlistMenuItem?) = WatchlistControls(watchlists, held, onPress, item)
}

/**
 * The session's [WatchlistControls]; null where nothing saves (an older
 * server, a preview), where the button and the cards' item are left out. A
 * local rather than parameters, since a card's menu anywhere in a session
 * has the item, and threading it through every row and grid would change
 * every screen's signature.
 */
@Suppress("ComposeCompositionLocalUsage")
val LocalWatchlistControls = compositionLocalOf<WatchlistControls?> { null }

/** The card's watchlist item, as [LocalWatchlistControls] offers it. */
@Composable
@ReadOnlyComposable
fun watchlistItem(item: BrowseItem): WatchlistMenuItem? = LocalWatchlistControls.current?.item(item.watchlistTitle(), item.watchlisted)

/** The session's controls from its saver's state; null when the server has no watchlists. */
@Composable
fun rememberWatchlistControls(
    supported: Boolean,
    watchlists: List<Watchlist>?,
    held: Map<WatchlistKey, List<Long>>,
    onPress: (WatchlistTitle, Boolean, List<Long>) -> Unit,
): WatchlistControls? {
    val press by rememberUpdatedState(onPress)
    return remember(supported, watchlists, held) {
        if (supported) WatchlistControls(watchlists, held, { t, on, ids -> press(t, on, ids) }) else null
    }
}
