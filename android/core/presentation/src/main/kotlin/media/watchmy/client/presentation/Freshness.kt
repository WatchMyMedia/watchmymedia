package media.watchmy.client.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import media.watchmy.client.model.LibraryChange

/**
 * A page's ViewModel that reloads for server changes only while it is on
 * screen. The page reports it with `ReportShown` (core:ui), from its
 * navigation entry's lifecycle: shown while started, not while another page
 * covers it or the app is in the background.
 */
interface ShownPage {
    fun setShown(shown: Boolean)
}

/**
 * When a page reloads for server changes: a
 * change that touches the page reloads it at once while it is shown, and
 * marks it stale while it isn't, so it reloads once when it is next shown.
 * Pausing one episode used to reload every page in the back stack. A page
 * that never says whether it is shown counts as shown, as before.
 *
 * Runs on the ViewModel's scope, on the main thread, like the rest of it.
 */
class Freshness(private val scope: CoroutineScope, private val reload: () -> Unit) {
    private var shown = true
    private var stale = false

    /** Library changes [touches] says are this page's. */
    constructor(scope: CoroutineScope, changes: Flow<LibraryChange>, touches: (LibraryChange) -> Boolean, reload: () -> Unit) : this(scope, reload) {
        follow(changes, touches)
    }

    /** Follows another kind of change too; [touches] picks this page's. */
    fun <T> follow(changes: Flow<T>, touches: (T) -> Boolean = { true }): Freshness {
        scope.launch { changes.collect { if (touches(it)) changed() } }
        return this
    }

    /** Something this page shows changed: reloads now, or when it is next shown. */
    fun changed() {
        if (shown) reload() else stale = true
    }

    fun setShown(shown: Boolean) {
        this.shown = shown
        if (shown && stale) {
            stale = false
            reload()
        }
    }
}
