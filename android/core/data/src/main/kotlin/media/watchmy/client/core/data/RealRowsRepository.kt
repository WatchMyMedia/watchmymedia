package media.watchmy.client.core.data

import kotlinx.coroutines.flow.Flow
import media.watchmy.client.core.api.apis.BrowseApi
import media.watchmy.client.core.api.apis.ListsApi
import media.watchmy.client.core.api.models.UpdateRowLayoutInputBody
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ListSort
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.MediaList
import media.watchmy.client.model.MediaListEntries
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RowLayout
import media.watchmy.client.model.RowPlacement
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.RowsRepository.Companion.MAX_LIST_ENTRIES
import media.watchmy.client.core.api.models.RowPlacement as ApiRowPlacement

/**
 * /api/me/rows and /api/lists on the server in use.
 * Below [ROWS_API_LEVEL] [supported] is false and every call fails
 * without asking the server, so no screen offers what it can't do. The
 * household defaults and making lists are the web's: nothing here calls them.
 */
internal class RealRowsRepository(
    private val http: Http,
    events: ServerEvents,
) : RowsRepository {
    override val supported: Boolean get() = http.features().rows

    private suspend fun <T> browse(block: suspend (BrowseApi) -> T): T {
        if (!supported) throw ApiException(404, "Update the server to arrange rows.")
        return http.signedIn { base, client -> block(BrowseApi(base, client)) }
    }

    private suspend fun <T> listsApi(block: suspend (ListsApi) -> T): T {
        if (!supported) throw ApiException(404, "Update the server to see lists.")
        return http.signedIn { base, client -> block(ListsApi(base, client)) }
    }

    override suspend fun layout(screen: String): RowLayout = browse { it.getRowLayout(screen) }.toModel()

    override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout =
        browse { it.updateRowLayout(screen, UpdateRowLayoutInputBody(rows.map { r -> ApiRowPlacement(r.id, hidden = r.hidden.takeIf { h -> h }) })) }.toModel()

    override suspend fun resetLayout(screen: String): RowLayout = browse { it.resetRowLayout(screen) }.toModel()

    override suspend fun lists(): List<MediaList> {
        if (!supported) return emptyList()
        return listsApi { it.listMediaLists() }.items.map { it.toModel() }
    }

    override suspend fun list(id: Long): MediaList = listsApi { it.getMediaList(id) }.toModel()

    override suspend fun entries(id: Long, offset: Int, limit: Int, sort: ListSort?, type: MediaType?): MediaListEntries {
        // The sorts and chips are a watchlist's page's, from the level that brought them; older servers aren't sent them.
        val newer = http.features().watchlists
        val order = sort?.takeIf { newer }?.let {
            when (it) {
                ListSort.Added -> ListsApi.SortListMediaListEntries.added
                ListSort.Title -> ListsApi.SortListMediaListEntries.title
                ListSort.Released -> ListsApi.SortListMediaListEntries.released
            }
        }
        val only = type?.takeIf { newer }?.let { if (it == MediaType.Movie) ListsApi.MediaTypeListMediaListEntries.movie else ListsApi.MediaTypeListMediaListEntries.tv }
        return listsApi {
            it.listMediaListEntries(id, limit = limit.coerceIn(1, MAX_LIST_ENTRIES).toLong(), offset = offset.coerceAtLeast(0).toLong(), sort = order, mediaType = only)
        }.toModel()
    }

    override suspend fun refreshList(id: Long): MediaList = listsApi { it.refreshMediaList(id) }.toModel()

    override suspend fun deleteList(id: Long) = listsApi { it.deleteMediaList(id) }

    override val changes: Flow<RowsChange> = events.rowChanges
}
