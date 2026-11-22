package media.watchmy.client.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.HeldRemoval
import media.watchmy.client.model.NEW_WATCHLIST_ID
import media.watchmy.client.model.OnePress
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.WATCHLISTS_API_LEVEL
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistLedger
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.WatchlistWords

/**
 * The person's watchlists on the fakes, from [WATCHLISTS_API_LEVEL], kept in
 * [data] with the other lists so their rows and pages are the lists'. The
 * person starts with Watchlist, Halloween (which keeps watched titles) and
 * With the kids. Saving shows at once through the same [WatchlistLedger] the real
 * repository uses.
 */
class FakeWatchlistRepository(
    private val data: FakeRowsData,
    private val level: () -> Int = { FAKE_SERVER_API_LEVEL },
    /** Where held removals are sent; the main thread's by default, made only once one is. */
    scope: CoroutineScope? = null,
    private val latencyMs: () -> Long = { fakeLatencyMs },
) : WatchlistRepository {
    override val supported: Boolean get() = level() >= WATCHLISTS_API_LEVEL

    private val list = MutableStateFlow<List<Watchlist>?>(null)
    override val watchlists: StateFlow<List<Watchlist>?> = list.asStateFlow()

    // Made on first use: a plain JVM test of other fakes has no main thread to make it on.
    private val ledger by lazy { WatchlistLedger(scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    override val held: StateFlow<Map<WatchlistKey, List<Long>>> get() = ledger.held
    override val revision: Long get() = ledger.revision
    override val refusals: Flow<String> get() = ledger.refusals

    /** Saves and removals refuse with this status while set: the button's and the picker's failures (422 is a limit). */
    var refuse: Int? = null

    /** How many calls reached the "server": a held removal is counted only once sent. */
    var calls: Int = 0
        private set

    override val changes: Flow<Unit> = data.changes
        .filter { it is RowsChange.Layout && it.screen == RowScreens.HOME || it is RowsChange.Lists && data.watchlists().any { w -> w.id == it.listId } }
        .onEach { if (supported) list.value = data.watchlists() }
        .map { }

    private suspend fun <T> call(write: Boolean = false, block: () -> T): T {
        latencyMs().takeIf { it > 0 }?.let { delay(it) }
        if (!supported) throw ApiException(404, WatchlistWords.UPDATE_SERVER)
        calls++
        if (write) refuse?.let { throw ApiException(it, "The server couldn’t save that.") }
        return block()
    }

    private fun read(): List<Watchlist> = data.watchlists().also { list.value = it }

    override suspend fun refresh(): List<Watchlist> = call { read() }

    override suspend fun holding(key: WatchlistKey): List<Watchlist> {
        val since = ledger.revision
        val found = call { read(); data.watchlists(key) }
        ledger.known(key, found.filter { it.contains }.map { it.id }, since)
        return found
    }

    override suspend fun create(name: String): Watchlist = call(write = true) { data.createWatchlist(name).also { read() } }

    override suspend fun update(id: Long, name: String?, removeWatched: Boolean?): Watchlist =
        call(write = true) { data.updateWatchlist(id, name, removeWatched).also { read() } }

    override suspend fun delete(id: Long) {
        call(write = true) { data.delete(id) }
        ledger.keepOnly(read().map { it.id }.toSet())
    }

    override suspend fun saveToOnly(title: WatchlistTitle): OnePress {
        val only = list.value?.singleOrNull()?.id ?: NEW_WATCHLIST_ID
        val press = ledger.change<OnePress>(title.key, { (it + only).distinct() }, { (it as? OnePress.Saved)?.held }) {
            call(write = true) {
                val mine = data.watchlists()
                when {
                    mine.size >= 2 -> OnePress.Choose(data.watchlists(title.key))
                    else -> {
                        val target = mine.singleOrNull() ?: data.createWatchlist(WatchlistWords.FIRST)
                        val saved = data.saveTitle(target.id, title)
                        OnePress.Saved(saved, holdingIds(title.key))
                    }
                }
            }
        }
        read()
        if (press is OnePress.Choose) ledger.place(title.key, press.watchlists.filter { it.contains }.map { it.id })
        return press
    }

    override suspend fun save(id: Long, title: WatchlistTitle): List<Long> = ledger.change(title.key, { (it + id).distinct() }, { it }) {
        call(write = true) { data.saveTitle(id, title); read(); holdingIds(title.key) }
    }

    override suspend fun remove(id: Long, key: WatchlistKey): List<Long> = ledger.change(key, { it - id }, { it }) {
        call(write = true) { data.removeTitle(id, key); read(); holdingIds(key) }
    }

    override fun removeLater(key: WatchlistKey, from: Long?): HeldRemoval = ledger.removeLater(key, from) {
        call(write = true) {
            (if (from == null) holdingIds(key) else listOf(from)).forEach { data.removeTitle(it, key) }
            read()
            holdingIds(key)
        }
    }

    override fun known(key: WatchlistKey, ids: List<Long>, since: Long) = ledger.known(key, ids, since)

    /** The watchlists that hold [key] on the fakes now. */
    fun holdingIds(key: WatchlistKey): List<Long> = data.holding(key).orEmpty()
}
