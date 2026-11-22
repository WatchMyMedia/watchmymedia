package media.watchmy.client.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import media.watchmy.client.core.api.apis.WatchlistsApi
import media.watchmy.client.core.api.infrastructure.ClientError
import media.watchmy.client.core.api.infrastructure.ClientException
import media.watchmy.client.core.api.infrastructure.Serializer
import media.watchmy.client.core.api.models.UpdateWatchlistInputBody
import media.watchmy.client.core.api.models.WatchlistBody
import media.watchmy.client.core.api.models.WatchlistTitleChange
import media.watchmy.client.core.api.models.WatchlistTitleDetails
import media.watchmy.client.core.api.models.WatchlistsConflict
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.HeldRemoval
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.NEW_WATCHLIST_ID
import media.watchmy.client.model.OnePress
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.WATCHLISTS_API_LEVEL
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.WatchlistLedger
import media.watchmy.client.core.api.models.Watchlist as ApiWatchlist

/**
 * /api/me/watchlists and /api/me/watchlist on the server in use.
 * Below [WATCHLISTS_API_LEVEL] [supported] is false and every call fails
 * without asking the server.
 *
 * Every save and removal is put in [held] before it is sent and taken back
 * if the server refuses; the server's reply then says where the title is.
 * The watchlists are read again whenever one changes ([changes]: lists.changed
 * for a watchlist, rows.changed for Home, where a made, renamed or deleted
 * watchlist is announced), and both are dropped when another server or
 * account is used. A removal held for Undo ([removeLater]) is sent in the
 * data layer's [scope], so it reaches the server after its screen closes.
 */
internal class RealWatchlistRepository(
    private val store: LocalStore,
    private val http: Http,
    events: ServerEvents,
    private val scope: CoroutineScope,
) : WatchlistRepository {
    override val supported: Boolean get() = http.features().watchlists

    private val list = MutableStateFlow<List<Watchlist>?>(null)
    override val watchlists: StateFlow<List<Watchlist>?> = list.asStateFlow()

    private val ledger = WatchlistLedger(scope)
    override val held: StateFlow<Map<WatchlistKey, List<Long>>> get() = ledger.held
    override val revision: Long get() = ledger.revision
    override val refusals: Flow<String> get() = ledger.refusals

    init {
        // Another server or account: its watchlists aren't this one's.
        store.onSessionChange {
            list.value = null
            ledger.reset()
        }
    }

    /** A change that may be a watchlist's; one read again however many screens are listening. */
    override val changes: Flow<Unit> = events.rowChanges
        .filter { change ->
            when (change) {
                is RowsChange.Layout -> change.screen == null || change.screen == RowScreens.HOME
                is RowsChange.Lists -> change.listId == null || list.value?.any { it.id == change.listId } != false
            }
        }
        .onEach { if (supported) quietly { refresh() } }
        .map { }
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    private suspend fun <T> api(block: suspend (WatchlistsApi) -> T): T {
        if (!supported) throw ApiException(404, WatchlistWords.UPDATE_SERVER)
        return http.signedIn { base, client -> block(WatchlistsApi(base, client)) }
    }

    override suspend fun refresh(): List<Watchlist> {
        val session = store.revision
        val read = api { it.listWatchlists() }.items.map { it.toModel() }
        if (store.revision == session) {
            list.value = read
            ledger.keepOnly(read.map { it.id }.toSet())
        }
        return read
    }

    override suspend fun holding(key: WatchlistKey): List<Watchlist> {
        val since = ledger.revision
        val read = api { it.listWatchlists(key.type.listing(), key.tmdbId) }.items.map { it.toModel() }
        list.value = read.map { it.copy(contains = false) }
        ledger.known(key, read.filter { it.contains }.map { it.id }, since)
        return read
    }

    override suspend fun create(name: String): Watchlist {
        val made = api { it.createWatchlist(WatchlistBody(name.trim())) }.toModel()
        keep(made)
        return made
    }

    override suspend fun update(id: Long, name: String?, removeWatched: Boolean?): Watchlist {
        val before = list.value
        list.update { it?.map { w -> if (w.id == id) w.copy(name = name?.trim() ?: w.name, removeWatched = removeWatched ?: w.removeWatched) else w } }
        val changed = try {
            api { it.updateWatchlist(id, UpdateWatchlistInputBody(name = name?.trim(), removeWatched = removeWatched)) }.toModel()
        } catch (e: Exception) {
            if (e !is CancellationException) list.value = before
            throw e
        }
        keep(changed)
        return changed
    }

    override suspend fun delete(id: Long) {
        api { it.deleteWatchlist(id) }
        list.update { it?.filter { w -> w.id != id } }
        list.value?.let { ledger.keepOnly(it.map { w -> w.id }.toSet()) }
    }

    override suspend fun saveToOnly(title: WatchlistTitle): OnePress {
        val key = title.key
        val only = list.value?.singleOrNull()?.id ?: NEW_WATCHLIST_ID
        val press = ledger.change<OnePress>(key, { (it + only).distinct() }, { (it as? OnePress.Saved)?.held }) {
            api { api ->
                try {
                    val change = api.saveToOnlyWatchlist(key.type.saveOnly(), key.tmdbId, title.details())
                    val saved = change.watchlist?.toModel() ?: throw ApiException(null, "The server sent a reply this app doesn't understand.")
                    OnePress.Saved(saved, change.watchlists)
                } catch (e: ClientException) {
                    // Several watchlists: the reply lists them, saying which hold the title, for the picker.
                    if (e.statusCode != 409) throw e
                    val text = (e.response as? ClientError<*>)?.body as? String ?: throw e
                    val conflict = runCatching { Serializer.kotlinxSerializationJson.decodeFromString(WatchlistsConflict.serializer(), text) }.getOrNull() ?: throw e
                    OnePress.Choose(conflict.watchlists.map { it.toModel() })
                }
            }
        }
        when (press) {
            is OnePress.Saved -> keep(press.watchlist)
            is OnePress.Choose -> {
                list.value = press.watchlists.map { it.copy(contains = false) }
                ledger.place(key, press.watchlists.filter { it.contains }.map { it.id })
            }
        }
        return press
    }

    override suspend fun save(id: Long, title: WatchlistTitle): List<Long> =
        ledger.change(title.key, { (it + id).distinct() }, { it }) { api { it.saveToWatchlist(id, title.key.type.save(), title.key.tmdbId, title.details()) }.placed() }

    override suspend fun remove(id: Long, key: WatchlistKey): List<Long> =
        ledger.change(key, { it - id }, { it }) { api { it.removeFromWatchlist(id, key.type.removeOne(), key.tmdbId) }.placed() }

    override fun removeLater(key: WatchlistKey, from: Long?): HeldRemoval = ledger.removeLater(key, from) {
        if (from == null) {
            api { it.removeFromWatchlists(key.type.removeAll(), key.tmdbId) }.placed()
        } else {
            api { it.removeFromWatchlist(from, key.type.removeOne(), key.tmdbId) }.placed()
        }
    }

    override fun known(key: WatchlistKey, ids: List<Long>, since: Long) = ledger.known(key, ids, since)

    /** [changed] in the list, in its place, or at the end when it is new. */
    private fun keep(changed: Watchlist) = list.update { now ->
        val all = now.orEmpty()
        if (all.any { it.id == changed.id }) all.map { if (it.id == changed.id) changed else it } else all + changed
    }

    /** The reply's watchlist, with its counts after; where the title is now. */
    private fun WatchlistTitleChange.placed(): List<Long> {
        watchlist?.toModel()?.let(::keep)
        return watchlists
    }

    /** A read whose failure leaves things as they are; a cancellation still cancels. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

internal fun ApiWatchlist.toModel() = Watchlist(
    id = id,
    name = name,
    titles = titles.toInt(),
    inLibrary = inLibrary.toInt(),
    removeWatched = removeWatched,
    posters = posters.filter { it.isNotBlank() }.take(4),
    contains = contains == true,
)

/** What the server keeps for a title it has no record of: sent only when there is a title to keep. */
private fun WatchlistTitle.details(): WatchlistTitleDetails? = title.takeIf { it.isNotBlank() }?.let {
    WatchlistTitleDetails(posterPath = posterPath?.takeIf { p -> p.isNotBlank() }, title = it, year = year?.toLong())
}

private fun MediaType.listing() = if (this == MediaType.Movie) WatchlistsApi.MediaTypeListWatchlists.movie else WatchlistsApi.MediaTypeListWatchlists.tv
private fun MediaType.save() = if (this == MediaType.Movie) WatchlistsApi.MediaTypeSaveToWatchlist.movie else WatchlistsApi.MediaTypeSaveToWatchlist.tv
private fun MediaType.saveOnly() = if (this == MediaType.Movie) WatchlistsApi.MediaTypeSaveToOnlyWatchlist.movie else WatchlistsApi.MediaTypeSaveToOnlyWatchlist.tv
private fun MediaType.removeOne() = if (this == MediaType.Movie) WatchlistsApi.MediaTypeRemoveFromWatchlist.movie else WatchlistsApi.MediaTypeRemoveFromWatchlist.tv
private fun MediaType.removeAll() = if (this == MediaType.Movie) WatchlistsApi.MediaTypeRemoveFromWatchlists.movie else WatchlistsApi.MediaTypeRemoveFromWatchlists.tv
