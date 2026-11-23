package media.watchmy.client.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import media.watchmy.client.core.api.apis.BrowseApi
import media.watchmy.client.core.api.apis.FilesApi
import media.watchmy.client.core.api.apis.LibrariesApi
import media.watchmy.client.core.api.apis.WatchApi
import media.watchmy.client.core.api.models.Guess
import media.watchmy.client.core.api.models.Library as ApiLibrary
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.PersonPage
import media.watchmy.client.model.PersonTitle
import media.watchmy.client.model.ServerFeatures
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.Library
import media.watchmy.client.model.LibraryChange
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.episodeCode

internal class RealLibraryRepository(
    private val store: LocalStore,
    private val http: Http,
    private val events: ServerEvents,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val clock: () -> Long,
    /**
     * The registered server every call goes to, as the person signed in
     * there ([on]); null is the server in use, whichever it is.
     */
    private val target: String? = null,
) : LibraryRepository {
    /** The library list, asked again after a scan or a change to a library, or after a while ([SessionMemo]). */
    private val libraryList = SessionMemo(store, scope, clock) { listLibraries() }

    init {
        if (target == null) events.onEvent { if (it == ServerEvents.RECONNECTED || it in ServerEvents.LIBRARY_EVENTS) libraryList.clear() }
    }

    /**
     * Runs [block] as the person signed in on [target], or on the server in
     * use without one. Another server's address is asked which server it is
     * first, with no sign-in ([Http.askFirst], through its access), and the
     * answer is kept so its pictures are signed ([Http.answered]); a call that
     * fails forgets it, so the next asks again.
     */
    private suspend fun <T> signed(block: suspend (baseUrl: String, client: okhttp3.Call.Factory) -> T): T {
        val id = target?.takeIf { it != store.value.currentId } ?: return http.signedIn(block)
        val server = store.value.servers.firstOrNull { it.id == id }
        val access = server?.account?.let { http.bindTo(id, it.userId, keepAnswer = true) } ?: throw SignedOutException()
        return try {
            access.call(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            http.forgetAnswers(id)
            throw e
        }
    }

    /** The repositories for other servers ([on]), one each. */
    private val bound = java.util.concurrent.ConcurrentHashMap<String, RealLibraryRepository>()

    override fun on(serverId: String?): LibraryRepository? {
        if (target != null) return if (serverId == target) this else null
        if (serverId == null || serverId == store.value.currentId) return this
        val server = store.value.servers.firstOrNull { it.id == serverId } ?: return null
        if (server.account == null) return null
        return bound.getOrPut(serverId) { RealLibraryRepository(store, http, events, scope, clock, target = serverId) }
    }

    override fun features(): ServerFeatures {
        val id = target ?: return http.features()
        return ServerFeatures(store.value.servers.firstOrNull { it.id == id }?.apiLevel ?: 0)
    }

    private fun browse(base: String, client: okhttp3.Call.Factory) = BrowseApi(base, client)

    override suspend fun home(): HomeData = signed { base, client -> browse(base, client).getHome() }.toModel()

    /** arranged=true only from [ROWS_API_LEVEL]: an older server sends its fixed rows. */
    override suspend fun arrangedHome(): HomeData {
        if (!features().rows) return home()
        return signed { base, client -> browse(base, client).getHome(arranged = true) }.toModel(arranged = true)
    }

    override suspend fun libraryRows(libraryId: Long): List<ScreenRow> {
        if (!features().rows) return emptyList()
        return signed { base, client -> browse(base, client).getLibraryRows(libraryId, arranged = true) }.rows.toRows()
    }

    override suspend fun continueWatching(): List<ContinueItem> =
        signed { base, client -> WatchApi(base, client).listContinueWatching() }.items.mapNotNull { it.toModel() }

    override suspend fun libraries(): List<Library> = if (target == null) libraryList.get() else listLibraries()

    private suspend fun listLibraries(): List<Library> = signed { base, client -> LibrariesApi(base, client).listLibraries() }.items.mapNotNull { lib ->
        when (lib.type) {
            ApiLibrary.Type.movie -> Library(lib.id, lib.name, LibraryType.Movie, lib.scan != null, lib.lastScannedAt, lib.lastScanError, lib.lastScanErrorAt)
            ApiLibrary.Type.tv -> Library(lib.id, lib.name, LibraryType.Tv, lib.scan != null, lib.lastScannedAt, lib.lastScanError, lib.lastScanErrorAt)
            ApiLibrary.Type.unknown_default_open_api -> null
        }
    }

    override suspend fun items(query: ItemQuery): ItemPage {
        val ratings = query.contentRatings.takeIf { it.isNotEmpty() && features().ratingFilter }?.sorted()
        val list = signed { base, client ->
            browse(base, client).listLibraryItems(
                id = query.libraryId,
                sort = query.sort.toApi(),
                filter = query.filter.toApi(),
                contentRating = ratings,
                genre = query.genre?.takeIf { g -> g.isNotBlank() },
                decade = query.decade?.toLong(),
                q = query.text?.trim()?.takeIf { t -> t.isNotEmpty() },
                limit = query.limit.coerceIn(1, MAX_PAGE).toLong(),
                offset = query.offset.coerceAtLeast(0).toLong(),
            )
        }
        return ItemPage(
            items = list.items.mapNotNull { it.toModel() },
            total = list.total.toInt(),
            offset = list.offset.toInt(),
            genres = list.facets.genres,
            decades = list.facets.decades.map { it.toInt() },
            summary = list.totals?.toModel(),
            letters = list.letters?.map { media.watchmy.client.model.LetterOffset(it.letter, it.offset.toInt()) },
            genreCounts = list.genreCounts?.mapValues { (_, total) -> total.toInt() },
            contentRatings = list.facets.contentRatings,
            contentRatingCounts = list.contentRatingCounts?.mapValues { (_, total) -> total.toInt() },
        )
    }

    override suspend fun show(id: Long): ShowDetail = signed { base, client -> browse(base, client).getShow(id) }.toModel()

    override suspend fun season(showId: Long, number: Int): SeasonDetail = signed { base, client -> browse(base, client).getSeason(showId, number.toLong()) }.toModel()

    override suspend fun movie(id: Long): MovieDetail = signed { base, client -> browse(base, client).getMovie(id) }.toModel()

    /** Asked only from [media.watchmy.client.model.RELATED_API_LEVEL]: an older server has no such route. */
    override suspend fun movieRelated(id: Long): List<ScreenRow> {
        if (!features().related) return emptyList()
        return signed { base, client -> browse(base, client).getMovieRelated(id) }.rows.toRows()
    }

    override suspend fun showRelated(id: Long): List<ScreenRow> {
        if (!features().related) return emptyList()
        return signed { base, client -> browse(base, client).getShowRelated(id) }.rows.toRows()
    }

    /** Asked only from [media.watchmy.client.model.REDESIGN_API_LEVEL]: an older server has no such route, and its credits no person ids. */
    override suspend fun person(personId: Long): PersonPage {
        if (!features().people) throw ApiException(404, "Not found.")
        val p = signed { base, client -> browse(base, client).getPerson(personId) }
        return PersonPage(
            personId = p.personId,
            name = p.name,
            profilePath = p.profilePath?.takeIf { it.isNotBlank() },
            movies = p.movies.mapNotNull { it.toTitle() },
            shows = p.shows.mapNotNull { it.toTitle() },
        )
    }

    override suspend fun hideFromContinue(kind: ContinueItem.Kind, id: Long) {
        if (!features().hideFromContinue) throw ApiException(404, "This server can't do that.")
        signed { base, client ->
            val type = if (kind == ContinueItem.Kind.Movie) WatchApi.TypeHideFromContinueWatching.movie else WatchApi.TypeHideFromContinueWatching.episode
            WatchApi(base, client).hideFromContinueWatching(type, id)
        }
    }

    override suspend fun showInContinue(kind: ContinueItem.Kind, id: Long) {
        if (!features().hideFromContinue) throw ApiException(404, "This server can't do that.")
        signed { base, client ->
            val type = if (kind == ContinueItem.Kind.Movie) WatchApi.TypeShowInContinueWatching.movie else WatchApi.TypeShowInContinueWatching.episode
            WatchApi(base, client).showInContinueWatching(type, id)
        }
    }

    /** Asked only from [media.watchmy.client.model.REDESIGN_API_LEVEL]: an older server has no such route. */
    override suspend fun copies(kind: ItemKind, tmdbId: Long): List<BrowseItem> {
        if (!features().copies || tmdbId <= 0) return emptyList()
        return when (kind) {
            ItemKind.Movie -> signed { base, client -> browse(base, client).findMoviesByTMDB(tmdbId) }.items.map { it.toBrowseItem() }
            ItemKind.Show -> signed { base, client -> browse(base, client).findShowsByTMDB(tmdbId) }.items.map { it.toBrowseItem() }
            ItemKind.Unmatched -> emptyList()
        }
    }

    override suspend fun search(text: String): SearchResults {
        val q = text.trim().take(MAX_SEARCH)
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val r = signed { base, client -> browse(base, client).search(q) }
        return SearchResults(r.movies.map { it.toBrowseItem() }, r.shows.map { it.toBrowseItem() }, r.episodes.map { it.toCard() })
    }

    /**
     * The call is built for [serverId] alone: its address and its sign-in,
     * so what is typed goes to each server as its own person and no sign-in
     * is sent to another server's address. Its address is asked which
     * server it is first, with no sign-in ([Http.askFirst], which the
     * access does): the sign-in goes only to the server remembered, and to
     * nothing else that answers there. The answer is kept for this visit to
     * Search; a call that fails forgets it, so the next asks again.
     */
    override suspend fun searchOn(serverId: String, text: String): SearchResults {
        val q = text.trim().take(MAX_SEARCH)
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val server = store.value.servers.firstOrNull { it.id == serverId }
        val access = server?.account?.let { http.bindTo(serverId, it.userId, keepAnswer = true) } ?: throw SignedOutException()
        val r = try {
            access.call { base, client -> browse(base, client).search(q) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            http.forgetAnswers(serverId)
            throw e
        }
        return SearchResults(r.movies.map { it.toBrowseItem() }, r.shows.map { it.toBrowseItem() }, r.episodes.map { it.toCard() })
    }

    /**
     * What every other server said is forgotten, and those in [showing] are
     * asked again now, each on its own: one that doesn't answer is found out
     * by the next search, which says so.
     */
    override suspend fun searchVisit(showing: Collection<String>) {
        http.forgetAnswers()
        coroutineScope {
            showing.distinct().forEach { id ->
                val server = store.value.servers.firstOrNull { it.id == id } ?: return@forEach
                launch {
                    try {
                        http.askFirst(server, keep = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        signed { base, client ->
            val api = WatchApi(base, client)
            when {
                watched && kind == ContinueItem.Kind.Movie -> api.markWatched(WatchApi.TypeMarkWatched.movie, id)
                watched -> api.markWatched(WatchApi.TypeMarkWatched.episode, id)
                kind == ContinueItem.Kind.Movie -> api.markUnwatched(WatchApi.TypeMarkUnwatched.movie, id)
                else -> api.markUnwatched(WatchApi.TypeMarkUnwatched.episode, id)
            }
        }
    }

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) {
        signed { base, client ->
            val api = WatchApi(base, client)
            if (watched) api.markSeasonWatched(showId, season.toLong()) else api.markSeasonUnwatched(showId, season.toLong())
        }
    }

    /** One call for the whole show; servers without it get one per season. */
    override suspend fun setShowWatched(showId: Long, watched: Boolean) {
        orIfUnknownRoute({ super.setShowWatched(showId, watched) }) {
            signed { base, client ->
                val api = WatchApi(base, client)
                if (watched) api.markShowWatched(showId) else api.markShowUnwatched(showId)
            }
        }
    }

    /**
     * The file names its movie, or its episode and show; then the episode is
     * found in its season. Servers that do not name a file's episode get the
     * show found by the title guessed from the file name and the episode by
     * its file in that season. Failing that, the guess itself.
     */
    override suspend fun playRequest(fileId: Long): PlayRequest {
        val file = signed { base, client -> FilesApi(base, client).getFile(fileId) }
        file.movieId?.let { id ->
            val movie = movie(id)
            return PlayRequest(fileId, title = movie.title, subtitle = movie.year?.toString(), backdropPath = movie.backdropPath)
        }
        val showId = file.showId
        val seasonNumber = file.seasonNumber?.toInt()
        if (showId != null && seasonNumber != null) {
            val detail = season(showId, seasonNumber)
            val episode = detail.episodes.firstOrNull { it.id == file.episodeId }
            if (episode != null) {
                return PlayRequest(
                    fileId,
                    title = detail.showTitle,
                    subtitle = "${episodeCode(episode.season, episode.number)} · ${episode.title}",
                    backdropPath = detail.showBackdropPath ?: episode.stillPath,
                    episodeContext = media.watchmy.client.model.EpisodeContext(showId, episode.season),
                )
            }
        }
        val guess = file.parsed
        val title = guess.title.ifBlank { fileName(file.relativePath) }
        val season = guess.season?.toInt()
        if (guess.kind != Guess.Kind.episode || season == null) return PlayRequest(fileId, title = title)
        findEpisode(fileId, guess.title, season)?.let { return it }
        val code = guess.episodes?.firstOrNull()?.let { episodeCode(season, it.toInt()) }
        val subtitle = listOfNotNull(code, guess.episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
        return PlayRequest(fileId, title = title, subtitle = subtitle)
    }

    override suspend fun filePage(fileId: Long): DeepLink? {
        val file = signed { base, client -> FilesApi(base, client).getFile(fileId) }
        file.movieId?.let { return DeepLink.Movie(it) }
        val showId = file.showId ?: return null
        val season = file.seasonNumber?.toInt() ?: return DeepLink.Show(showId)
        return DeepLink.Show(showId, season, file.episodeId)
    }

    private suspend fun findEpisode(fileId: Long, showTitle: String, season: Int): PlayRequest? {
        if (showTitle.isBlank()) return null
        val shows = try {
            search(showTitle).shows
        } catch (e: SignedOutException) {
            throw e
        } catch (_: ApiException) {
            return null
        }
        for (show in shows.take(MAX_SHOW_GUESSES)) {
            val detail = try {
                season(show.id, season)
            } catch (e: SignedOutException) {
                throw e
            } catch (_: ApiException) {
                continue
            }
            val episode = detail.episodes.firstOrNull { e -> e.versions.any { it.fileId == fileId } } ?: continue
            return PlayRequest(
                fileId,
                title = detail.showTitle,
                subtitle = "${episodeCode(episode.season, episode.number)} · ${episode.title}",
                backdropPath = show.backdropPath ?: episode.stillPath,
                episodeContext = media.watchmy.client.model.EpisodeContext(show.id, episode.season),
            )
        }
        return null
    }

    override fun imageUrl(path: String?, size: ImageSize): String? =
        if (target != null) imageUrlOn(target, path, size) else imageUrlAt(store.current.value?.server?.baseUrl, path, size)

    override fun imageUrlOn(serverId: String, path: String?, size: ImageSize): String? =
        imageUrlAt(store.value.servers.firstOrNull { it.id == serverId }?.baseUrl, path, size)

    private fun imageUrlAt(base: String?, path: String?, size: ImageSize): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (base == null) return null
        return "$base/api/images/${size.path}${if (p.startsWith('/')) p else "/$p"}"
    }

    /** Another server's events don't reach this device: its pages reload when shown again. */
    override val changes: Flow<Unit> = if (target == null) events.changes else kotlinx.coroutines.flow.emptyFlow()

    override val libraryChanges: Flow<LibraryChange> = if (target == null) events.libraryChanges else kotlinx.coroutines.flow.emptyFlow()

    private fun ItemSort.toApi() = when (this) {
        ItemSort.Title -> BrowseApi.SortListLibraryItems.title
        ItemSort.Added -> BrowseApi.SortListLibraryItems.added
        ItemSort.Year -> BrowseApi.SortListLibraryItems.year
        ItemSort.Rating -> BrowseApi.SortListLibraryItems.rating
        ItemSort.Released -> BrowseApi.SortListLibraryItems.released
        ItemSort.Runtime -> BrowseApi.SortListLibraryItems.runtime
        ItemSort.Aired -> BrowseApi.SortListLibraryItems.aired
    }

    private fun ItemFilter.toApi() = when (this) {
        ItemFilter.All -> null
        ItemFilter.Continuing -> BrowseApi.FilterListLibraryItems.continuing
        ItemFilter.Ended -> BrowseApi.FilterListLibraryItems.ended
        ItemFilter.FourK -> BrowseApi.FilterListLibraryItems._4k
        ItemFilter.Hdr -> BrowseApi.FilterListLibraryItems.hdr
        ItemFilter.Unwatched -> BrowseApi.FilterListLibraryItems.unwatched
    }

    private companion object {
        const val MAX_PAGE = 1000
        const val MAX_SEARCH = 100
        const val MAX_SHOW_GUESSES = 3
    }
}
