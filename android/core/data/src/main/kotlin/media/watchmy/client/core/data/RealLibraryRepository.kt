package media.watchmy.client.core.data

import kotlinx.coroutines.flow.Flow
import media.watchmy.client.core.api.apis.BrowseApi
import media.watchmy.client.core.api.apis.FilesApi
import media.watchmy.client.core.api.apis.LibrariesApi
import media.watchmy.client.core.api.apis.WatchApi
import media.watchmy.client.core.api.models.Guess
import media.watchmy.client.core.api.models.Library as ApiLibrary
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.Library
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
    events: ServerEvents,
) : LibraryRepository {
    private fun browse(base: String, client: okhttp3.OkHttpClient) = BrowseApi(base, client)

    override suspend fun home(): HomeData = http.signedIn { base, client -> browse(base, client).getHome() }.toModel()

    /** arranged=true only from [ROWS_API_LEVEL]: an older server sends its fixed rows. */
    override suspend fun arrangedHome(): HomeData {
        if (http.apiLevel() < ROWS_API_LEVEL) return home()
        return http.signedIn { base, client -> browse(base, client).getHome(arranged = true) }.toModel(arranged = true)
    }

    override suspend fun libraryRows(libraryId: Long): List<ScreenRow> {
        if (http.apiLevel() < ROWS_API_LEVEL) return emptyList()
        return http.signedIn { base, client -> browse(base, client).getLibraryRows(libraryId, arranged = true) }.rows.toRows()
    }

    override suspend fun continueWatching(): List<ContinueItem> =
        http.signedIn { base, client -> WatchApi(base, client).listContinueWatching() }.items.mapNotNull { it.toModel() }

    override suspend fun libraries(): List<Library> = http.signedIn { base, client -> LibrariesApi(base, client).listLibraries() }.items.mapNotNull { lib ->
        when (lib.type) {
            ApiLibrary.Type.movie -> Library(lib.id, lib.name, LibraryType.Movie, lib.scan != null, lib.lastScannedAt, lib.lastScanError, lib.lastScanErrorAt)
            ApiLibrary.Type.tv -> Library(lib.id, lib.name, LibraryType.Tv, lib.scan != null, lib.lastScannedAt, lib.lastScanError, lib.lastScanErrorAt)
            ApiLibrary.Type.unknown_default_open_api -> null
        }
    }

    override suspend fun items(query: ItemQuery): ItemPage {
        val list = http.signedIn { base, client ->
            browse(base, client).listLibraryItems(
                id = query.libraryId,
                sort = query.sort.toApi(),
                filter = query.filter.toApi(),
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
        )
    }

    override suspend fun show(id: Long): ShowDetail = http.signedIn { base, client -> browse(base, client).getShow(id) }.toModel()

    override suspend fun season(showId: Long, number: Int): SeasonDetail = http.signedIn { base, client -> browse(base, client).getSeason(showId, number.toLong()) }.toModel()

    override suspend fun movie(id: Long): MovieDetail = http.signedIn { base, client -> browse(base, client).getMovie(id) }.toModel()

    override suspend fun search(text: String): SearchResults {
        val q = text.trim().take(MAX_SEARCH)
        if (q.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        val r = http.signedIn { base, client -> browse(base, client).search(q) }
        return SearchResults(r.movies.map { it.toBrowseItem() }, r.shows.map { it.toBrowseItem() }, r.episodes.map { it.toCard() })
    }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        http.signedIn { base, client ->
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
        http.signedIn { base, client ->
            val api = WatchApi(base, client)
            if (watched) api.markSeasonWatched(showId, season.toLong()) else api.markSeasonUnwatched(showId, season.toLong())
        }
    }

    /** One call for the whole show; servers without it get one per season. */
    override suspend fun setShowWatched(showId: Long, watched: Boolean) {
        try {
            http.signedIn { base, client ->
                val api = WatchApi(base, client)
                if (watched) api.markShowWatched(showId) else api.markShowUnwatched(showId)
            }
        } catch (e: ApiException) {
            if (!e.unknownRoute()) throw e
            super.setShowWatched(showId, watched)
        }
    }

    /**
     * The file names its movie, or its episode and show; then the episode is
     * found in its season. Servers that do not name a file's episode get the
     * show found by the title guessed from the file name and the episode by
     * its file in that season. Failing that, the guess itself.
     */
    override suspend fun playRequest(fileId: Long): PlayRequest {
        val file = http.signedIn { base, client -> FilesApi(base, client).getFile(fileId) }
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
        val file = http.signedIn { base, client -> FilesApi(base, client).getFile(fileId) }
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

    override fun imageUrl(path: String?, size: ImageSize): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = store.current.value?.server?.baseUrl ?: return null
        return "$base/api/images/${size.path}${if (p.startsWith('/')) p else "/$p"}"
    }

    override val changes: Flow<Unit> = events.changes

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
