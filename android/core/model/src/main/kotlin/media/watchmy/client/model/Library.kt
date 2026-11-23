package media.watchmy.client.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class LibraryType { Movie, Tv }

data class Library(
    val id: Long,
    val name: String,
    val type: LibraryType,
    val scanning: Boolean = false,
    val lastScannedAt: String? = null,
    val lastScanError: String? = null,
    val lastScanErrorAt: String? = null,
)

enum class ItemKind { Movie, Show, Unmatched }

/** "4K", "1080p", "720p" or "SD"; null when unknown. */
typealias QualityLabel = String

/** A movie or show card in a grid or row. */
data class BrowseItem(
    val kind: ItemKind,
    val id: Long,
    val libraryId: Long,
    val title: String,
    val year: Int?,
    val overview: String,
    val genres: List<String>,
    val rating: Double?,
    val posterPath: String?,
    val backdropPath: String?,
    val quality: QualityLabel?,
    val hdr: Boolean,
    val isNew: Boolean,
    /** A row-specific line such as "S03E05" or "added today". */
    val subtitle: String?,
    val watched: Boolean,
    /** 0–1 for a movie partway through, else null. */
    val progress: Float?,
    /** Shows: episodes on disk. */
    val episodeCount: Int?,
    val runtimeMin: Int?,
    val releaseDate: String?,
    /** Unmatched: the file or folder name. */
    val fileName: String? = null,
    /** Movies: versions on disk. Unmatched: the files in the group (a show folder has several). */
    val fileCount: Int? = null,
    /** Recently added shows: the one episode added, whose page the card opens; null otherwise or from older servers. */
    val episodeId: Long? = null,
    /** That episode's season. */
    val episodeSeason: Int? = null,
    /** The title's TMDB id, which saving it to a watchlist takes; null when unmatched, or from servers below [WATCHLISTS_API_LEVEL]. */
    val tmdbId: Long? = null,
    /** On one of the person's watchlists, as the server read it ([WatchlistRepository.held] has what changed since). */
    val watchlisted: Boolean = false,
    /** Its certification ("PG-13", "TV-14"); null when it has none. */
    val contentRating: String? = null,
    /** Shows: episodes on disk not watched yet ("4 episodes left"); null from servers below [REDESIGN_API_LEVEL]. */
    val unwatchedCount: Int? = null,
    /** Shows: episodes new since the person last watched ("2 new"); null as [unwatchedCount]. */
    val newEpisodeCount: Int? = null,
    /** Shows: seasons with an episode on disk, specials left out; null as [unwatchedCount]. */
    val seasonCount: Int? = null,
    /** Shows: when the next episode airs, an ISO date; null when none is known. */
    val nextAirDate: String? = null,
    /** When it came into the library, in epoch milliseconds; what a row filled from several servers is ordered by. */
    val addedAtMs: Long? = null,
    /** Which servers the title is on, when the device is signed in to several ([Where]). */
    val where: Where = Where.Here,
)

/**
 * Where a card's title is, for one card a title across the servers this
 * device is signed in to. [serverId] is the registered server the card's ids
 * are for, which it opens and plays from: null is the server in use, as every
 * card was before. [serverName] names it when it isn't. [copies] are the same
 * title on other servers, merged into this card, each with its own id there.
 */
data class Where(
    val serverId: String? = null,
    val serverName: String? = null,
    val copies: List<Copy> = emptyList(),
) {
    /** The title on another server: [id] is its movie, show or episode id there; [serverId] null is the server in use. */
    data class Copy(val serverId: String?, val serverName: String?, val id: Long)

    /** The server in use has no copy: the card names where it is ("On Cabin"). */
    val onlyElsewhere: Boolean get() = serverId != null && copies.none { it.serverId == null }

    /** On more than one server. */
    val several: Boolean get() = copies.isNotEmpty()

    /** Every server that has the title, its own first: (server id or null, name or null). */
    val all: List<Copy> get() = listOf(Copy(serverId, serverName, -1)) + copies

    companion object {
        /** On the server in use alone. */
        val Here = Where()
    }
}

/** An episode card (Recently aired, search results). */
data class EpisodeCard(
    val episodeId: Long,
    val showId: Long,
    val showTitle: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    /** ISO date, such as 2026-10-28. */
    val airDate: String?,
    val stillPath: String?,
    val showPosterPath: String?,
    val showBackdropPath: String?,
    /** False shows "Not in library". */
    val hasFile: Boolean,
    val fileId: Long?,
    val isNew: Boolean,
    /** The signed-in user has watched it; false on servers that do not say. */
    val watched: Boolean = false,
    /** Its length in minutes; null when unknown, or from servers below [REDESIGN_API_LEVEL]. */
    val runtimeMin: Int? = null,
    /** The show's TMDB id, which matches the episode on other servers with its season and number; null as [runtimeMin]. */
    val showTmdbId: Long? = null,
    /** Which servers the episode is on ([Where]); a copy's id is the episode's there. */
    val where: Where = Where.Here,
)

/** An item partway through, for Continue watching. */
data class ContinueItem(
    val kind: Kind,
    val title: String,
    val subtitle: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val stillPath: String?,
    val fileId: Long,
    val movieId: Long?,
    val showId: Long?,
    val episodeId: Long?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    /** The movie's or show's certification; null when it has none, or from servers below [REDESIGN_API_LEVEL]. */
    val contentRating: String? = null,
    /** The movie's or the episode's overview; null as [contentRating]. */
    val overview: String? = null,
    /** The movie's TMDB id, or for an episode its show's; null as [contentRating]. */
    val tmdbId: Long? = null,
    /** When it was last played, in epoch milliseconds: the order of Continue watching filled from several servers. */
    val updatedAtMs: Long? = null,
    /** Which servers the title is on ([Where]): the card is the copy played last, and a copy's id is its movie's or episode's there. */
    val where: Where = Where.Here,
) {
    enum class Kind { Movie, Episode }

    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/** The big item at the top of Home. */
data class Hero(
    val kind: Kind,
    /** "New episode · Harbor Lights". */
    val kicker: String,
    val title: String,
    /** "S02E01 · Aired 3 days ago · 47 min". */
    val facts: String,
    val overview: String,
    val backdropPath: String?,
    val posterPath: String?,
    val showId: Long?,
    val movieId: Long?,
    val episodeId: Long?,
    val fileId: Long?,
    /** The show's or movie's certification ("TV-14", "R"); null when it has none. */
    val contentRating: String? = null,
    /** The episode's file, or the movie's or show's best. */
    val quality: QualityLabel? = null,
    val hdr: Boolean = false,
) {
    enum class Kind { Episode, Movie, Show }
}

data class LibraryTotals(val movies: Int, val shows: Int, val episodes: Int, val files: Int, val totalSizeBytes: Long)

data class HomeData(
    val hero: Hero?,
    val continueWatching: List<ContinueItem>,
    val recentlyAddedShows: List<BrowseItem>,
    val recentlyAired: List<EpisodeCard>,
    val recentlyAddedMovies: List<BrowseItem>,
    val recentlyReleased: List<BrowseItem>,
    val totals: LibraryTotals,
    val lastScannedAtMs: Long?,
    val movieLibraryId: Long?,
    val tvLibraryId: Long?,
    /**
     * Home in the person's order, from servers at [ROWS_API_LEVEL] asked
     * for it ([LibraryRepository.arrangedHome]); then [continueWatching] and
     * the four arrays above are empty. Null: the fixed rows above.
     */
    val rows: List<ScreenRow>? = null,
)

enum class ItemSort { Title, Added, Year, Rating, Released, Runtime, Aired }

enum class ItemFilter { All, Continuing, Ended, FourK, Hdr, Unwatched }

/** [ItemQuery.contentRatings]'s value for titles with no certification. */
const val UNRATED = "unrated"

data class ItemQuery(
    val libraryId: Long,
    val sort: ItemSort = ItemSort.Title,
    val filter: ItemFilter = ItemFilter.All,
    val genre: String? = null,
    /** Such as 1990. */
    val decade: Int? = null,
    /** Title contains. */
    val text: String? = null,
    val offset: Int = 0,
    val limit: Int = 100,
    /** Certifications to keep ("PG-13", [UNRATED] for none); empty keeps every one. Sent from [REDESIGN_API_LEVEL] only. */
    val contentRatings: Set<String> = emptySet(),
)

/** A title initial and its first offset across the whole filtered result. */
data class LetterOffset(val letter: String, val offset: Int)

data class ItemPage(
    val items: List<BrowseItem>,
    /** Items matching the query. */
    val total: Int,
    val offset: Int,
    /** Genres and decades present in the library, for the filter chips. */
    val genres: List<String>,
    val decades: List<Int>,
    /** The whole library whatever the filter; null from servers that do not count it. */
    val summary: LibrarySummary? = null,
    /** Null means this server does not provide the title index. */
    val letters: List<LetterOffset>? = null,
    /** Genre totals with the active status/quality, title and decade filters, excluding selected genre.
     * Null on older servers; an empty map is an authoritative empty result. */
    val genreCounts: Map<String, Int>? = null,
    /** The certifications in the library, in the US order with [UNRATED] last; null below [REDESIGN_API_LEVEL], where there is no rating filter. */
    val contentRatings: List<String>? = null,
    /** Titles of each certification with the other filters on; null as [contentRatings]. */
    val contentRatingCounts: Map<String, Int>? = null,
)

/** A library's counts for its header ("142 shows · 3,418 episodes · 12 continuing"). */
data class LibrarySummary(
    /** Matched movies or shows. */
    val items: Int,
    /** TV: episodes on disk. */
    val episodes: Int,
    /** TV: shows that have not ended. */
    val continuing: Int,
    /** Files (or show folders) not matched yet. */
    val unmatched: Int,
    val totalSizeBytes: Long,
    /** Titles the person hasn't watched; null from servers below [REDESIGN_API_LEVEL]. */
    val unwatched: Int? = null,
    /** TV: shows with new episodes for the person; null as [unwatched]. */
    val newEpisodes: Int? = null,
)

/** A cast member. [personId] is TMDB's, which opens their titles; null from servers below [REDESIGN_API_LEVEL] and on titles not fetched again since. */
data class Person(val name: String, val role: String?, val profilePath: String?, val personId: Long? = null)

/** A person's titles in the library, newest first ([LibraryRepository.person]). */
data class PersonPage(
    val personId: Long,
    val name: String,
    val profilePath: String?,
    val movies: List<PersonTitle>,
    val shows: List<PersonTitle>,
) {
    val count: Int get() = movies.size + shows.size
}

/** One of a person's titles, and the part they play in it ("Tess Arden"; two joined with " / "). */
data class PersonTitle(val item: BrowseItem, val character: String?)

data class SeasonSummary(
    val id: Long,
    val number: Int,
    val name: String,
    val overview: String,
    val airDate: String?,
    val posterPath: String?,
    val episodeCount: Int,
    val availableCount: Int,
    val watchedCount: Int,
    /** Sonarr monitors the season; null when no Sonarr manages the show. */
    val monitored: Boolean? = null,
    /** The season's open request, from servers at [REQUESTS_API_LEVEL]. */
    val request: RequestState? = null,
)

/** The first unwatched episode on disk, for "Play next". */
data class NextUp(val episodeId: Long, val season: Int, val episode: Int, val title: String, val fileId: Long)

data class ShowDetail(
    val id: Long,
    val libraryId: Long,
    val title: String,
    val year: Int?,
    val overview: String,
    /** "Returning Series", "Ended". */
    val status: String?,
    val genres: List<String>,
    val rating: Double?,
    val posterPath: String?,
    val backdropPath: String?,
    val quality: QualityLabel?,
    val hdr: Boolean,
    val seasons: List<SeasonSummary>,
    val totalEpisodes: Int,
    val episodesOnDisk: Int,
    val totalSizeBytes: Long,
    val nextAirDate: String?,
    val nextUp: NextUp?,
    val cast: List<Person>,
    /** "TV-14"; null when TMDB has none. */
    val contentRating: String? = null,
    val tmdbId: Long? = null,
    /** The Sonarr connection that manages the show's files, by name. */
    val managedBy: String? = null,
    /** The person follows the show (Notify me); null from a server below [FOLLOWS_API_LEVEL]. */
    val following: Boolean? = null,
    /** The person's watchlists that hold the show; null from a server below [WATCHLISTS_API_LEVEL], where the page has no Watchlist button. */
    val watchlists: List<Long>? = null,
)

/** One version (file) of a movie or episode. */
data class MediaVersion(
    val fileId: Long,
    /** File name, for telling versions apart. */
    val label: String,
    val sizeBytes: Long,
    /** "MKV", "MP4". */
    val container: String?,
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val hdr: String?,
    /** "TrueHD 7.1 · English", the default audio track. */
    val audioSummary: String?,
    val durationMs: Long?,
    /** Every audio track in the file; empty when the server has not probed it. */
    val audio: List<AudioOption> = emptyList(),
    /** The file's own subtitle tracks (not sidecar files); [SubtitleOption.burnInOnly] marks image ones. */
    val subtitles: List<SubtitleOption> = emptyList(),
    /** The Dolby Vision profile (5, 7, 8); null for other files, or when the server has not read it. */
    val dvProfile: Int? = null,
    val recording: RecordingProvenance? = null,
    /** When the file came into the library, in epoch milliseconds; null when the server doesn't say. */
    val addedAtMs: Long? = null,
) {
    /** [hdr] as a tag: "DV P8" when the profile is known, else "DV", "HDR10", "HLG"; null for SDR. */
    val hdrTag: String? get() = hdrLabel(hdr)?.let { if (it == DOLBY_VISION) "DV" + profileSuffix(" ") else it }

    /** [hdrTag] with Dolby Vision written out, for the TV: "Dolby Vision · P8", "Dolby Vision". */
    val hdrTagSpelledOut: String? get() = hdrLabel(hdr)?.let { if (it == DOLBY_VISION) it + profileSuffix(" · ") else it }

    private fun profileSuffix(sep: String): String = dvProfile?.takeIf { it > 0 }?.let { "${sep}P$it" }.orEmpty()
}

/** [MediaVersion.hdr] and [StreamVideo.hdr] for Dolby Vision. */
const val DOLBY_VISION = "Dolby Vision"

/** Where the signed-in user is in a movie or episode. */
data class WatchProgress(
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    /** When it was last played or marked, in epoch milliseconds: when it was watched, once [completed]. */
    val updatedAtMs: Long? = null,
)

data class EpisodeDetail(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String,
    val airDate: String?,
    val stillPath: String?,
    val runtimeMin: Int?,
    val rating: Double?,
    val hasFile: Boolean,
    /** False before its air date. */
    val aired: Boolean,
    val isNew: Boolean,
    val versions: List<MediaVersion>,
    val watch: WatchProgress?,
    /** Sonarr monitors the episode; null when no Sonarr manages the show. */
    val monitored: Boolean? = null,
    /** In Sonarr's download queue. */
    val download: Download? = null,
    /** The season's open request covering this episode. */
    val request: RequestState? = null,
)

/** The show with only the seasons that have episodes on disk, and [keep] (the one asked for). */
fun ShowDetail.seasonsOnDisk(keep: Int? = null): ShowDetail = copy(seasons = seasons.filter { it.availableCount > 0 || it.number == keep })

/** The season with only the episodes on disk, and [keep] (the one opened from Home, which may be missing). */
fun SeasonDetail.episodesOnDisk(keep: Long? = null): SeasonDetail = copy(episodes = episodes.filter { it.hasFile || it.id == keep })

data class SeasonDetail(
    val showId: Long,
    val showTitle: String,
    val season: SeasonSummary,
    val episodes: List<EpisodeDetail>,
    val showBackdropPath: String? = null,
)

data class MovieDetail(
    val id: Long,
    val libraryId: Long,
    val title: String,
    val year: Int?,
    val tagline: String?,
    val overview: String,
    val releaseDate: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val rating: Double?,
    val posterPath: String?,
    val backdropPath: String?,
    val quality: QualityLabel?,
    val hdr: Boolean,
    val versions: List<MediaVersion>,
    val cast: List<Person>,
    val watch: WatchProgress?,
    /** "R", "PG-13"; null when TMDB has none. */
    val contentRating: String? = null,
    val tmdbId: Long? = null,
    /** In Radarr's download queue (a new movie, or an upgrade). */
    val download: Download? = null,
    /** The Radarr connection that manages the movie's files, by name. */
    val managedBy: String? = null,
    /** Best first; empty on servers below [TRAILERS_API_LEVEL] and for movies with none. */
    val trailers: List<Trailer> = emptyList(),
    /** The person's watchlists that hold the movie; null from a server below [WATCHLISTS_API_LEVEL], where the page has no Watchlist button. */
    val watchlists: List<Long>? = null,
)

data class SearchResults(val movies: List<BrowseItem>, val shows: List<BrowseItem>, val episodes: List<EpisodeCard>)

/** TMDB image sizes the server's /api/images route serves. */
enum class ImageSize(val path: String) { W92("w92"), W154("w154"), W185("w185"), W300("w300"), W342("w342"), W500("w500"), W780("w780"), W1280("w1280"), Original("original") }

/**
 * Browsing the current server's libraries. Methods throw [ApiException]; a
 * revoked or expired sign-in throws [SignedOutException].
 */
interface LibraryRepository {
    /** Home with its fixed rows, as every server sends it. */
    suspend fun home(): HomeData

    /**
     * Home in the person's order ([HomeData.rows]) from a server at
     * [ROWS_API_LEVEL]; from older ones the fixed rows, as [home].
     */
    suspend fun arrangedHome(): HomeData = home()

    /** A library's Movies or TV rows, above its grid, in the person's order; empty below [ROWS_API_LEVEL]. */
    suspend fun libraryRows(libraryId: Long): List<ScreenRow> = emptyList()

    /** Continue watching on its own (Watch Next, when an arranged Home has no Continue row). */
    suspend fun continueWatching(): List<ContinueItem> = home().continueWatching

    suspend fun libraries(): List<Library>

    suspend fun items(query: ItemQuery): ItemPage

    suspend fun show(id: Long): ShowDetail

    suspend fun season(showId: Long, number: Int): SeasonDetail

    suspend fun movie(id: Long): MovieDetail

    /**
     * The rows at the end of a movie's page: its
     * collection, then More like this, as the server sends them; empty when
     * neither has anything, and below [RELATED_API_LEVEL], which is not asked.
     */
    suspend fun movieRelated(id: Long): List<ScreenRow> = emptyList()

    /** The row at the end of a show's page: More like this; empty as [movieRelated]'s. */
    suspend fun showRelated(id: Long): List<ScreenRow> = emptyList()

    /**
     * A cast member's titles in the library, newest first, from
     * [REDESIGN_API_LEVEL]. Throws [ApiException] with 404 when no title here
     * has them, and on older servers, which have no such call.
     */
    suspend fun person(personId: Long): PersonPage = throw ApiException(404, "Not found.")

    /**
     * Takes the movie or episode off Continue watching, keeping its place, from
     * [REDESIGN_API_LEVEL] ([ServerFeatures.hideFromContinue]); playing it
     * again brings it back. [showInContinue] undoes it.
     */
    suspend fun hideFromContinue(kind: ContinueItem.Kind, id: Long): Unit = throw ApiException(404, "This server can't do that.")

    suspend fun showInContinue(kind: ContinueItem.Kind, id: Long): Unit = throw ApiException(404, "This server can't do that.")

    /**
     * This server's copies of the movie or show with TMDB id [tmdbId], every
     * library's with a file, marked for the person as search's results are;
     * empty when it has none, and below [REDESIGN_API_LEVEL], which is not asked.
     */
    suspend fun copies(kind: ItemKind, tmdbId: Long): List<BrowseItem> = emptyList()

    /** What the server this repository talks to offers, by its level as last read. */
    fun features(): ServerFeatures = ServerFeatures(0)

    /**
     * This library on the registered server [serverId], as the person signed
     * in there, whichever server is in use: every call goes to that server's
     * own address with its own sign-in, and every id is that server's. Null
     * for a server no one is signed in to here. With null, or the server in
     * use, this repository itself.
     *
     * A server that isn't in use is asked which server it is first, with no
     * sign-in, and gets its sign-in only when it is the one remembered; its
     * pictures ([imageUrl]) are signed while that answer stands, and a call
     * that fails forgets it, so the next asks again.
     */
    fun on(serverId: String?): LibraryRepository? = if (serverId == null) this else null

    suspend fun search(text: String): SearchResults

    /**
     * [search] on the registered server [serverId], as the person signed in
     * there, whichever server is in use: the call goes to that server's own
     * address with its own sign-in. Every id in the reply is that server's.
     * Throws [SignedOutException] when no one is signed in there.
     *
     * A server that isn't the one in use is first asked which server it is,
     * with no sign-in, and gets its sign-in only when it is the one
     * remembered: its address may be another machine's on another network.
     * It is asked once a visit to Search ([searchVisit]), and again after a
     * call to it fails. When it doesn't say so, this throws as a server
     * that can't be reached and nothing is sent.
     */
    suspend fun searchOn(serverId: String, text: String): SearchResults = search(text)

    /**
     * A visit to Search begins: Search is on screen, for the first time or
     * after being away (under another page, or with the app in the
     * background, which is when a device changes network). Each other server
     * is asked which server it is again before a sign-in next goes to it,
     * for a search or a picture; those in [showing], whose results are on
     * screen, are asked now, so their pictures go on loading.
     */
    suspend fun searchVisit(showing: Collection<String> = emptyList()) {}

    suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean)

    suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean)

    /** Marks every episode of the show watched or unwatched; this default goes a season at a time. */
    suspend fun setShowWatched(showId: Long, watched: Boolean) {
        show(showId).seasons.forEach { setSeasonWatched(showId, it.number, watched) }
    }

    /**
     * A request to play [fileId], named from the server's records: the movie,
     * or the episode and its show. For a `watchmymedia://play/{fileId}` link
     * that carries no title.
     */
    suspend fun playRequest(fileId: Long): PlayRequest = PlayRequest(fileId, title = "")

    /**
     * The page that shows [fileId], as the link that opens it: its movie
     * ([DeepLink.Movie]), or its episode's page ([DeepLink.Show] with the
     * season and episode); null when the file is matched to neither. Where a
     * play link that may not play goes ([DeepLink.Details]).
     */
    suspend fun filePage(fileId: Long): DeepLink? = null

    /**
     * The full URL of a TMDB image path at [size], or null for no image. The
     * image loader adds the sign-in, so the URL carries no token.
     */
    fun imageUrl(path: String?, size: ImageSize): String?

    /**
     * [imageUrl] for a picture on the registered server [serverId]: its own
     * address, which the image loader signs with that server's sign-in while
     * the server has said, on this visit to Search, that it is the one
     * remembered ([searchOn]); otherwise the picture is asked for with no
     * sign-in, and doesn't load.
     */
    fun imageUrlOn(serverId: String, path: String?, size: ImageSize): String? = imageUrl(path, size)

    /**
     * Emits when watch state or the library changed on the server (the
     * watch.changed and library.changed events), so screens refetch; once
     * for a burst of events.
     */
    val changes: Flow<Unit>

    /**
     * What changed, as [changes] says that something did: which movies,
     * episodes and libraries, so a page can
     * leave other items' changes alone. A repository that can't tell says
     * [LibraryChange.Everything] each time.
     */
    val libraryChanges: Flow<LibraryChange> get() = changes.map { LibraryChange.Everything }
}

/**
 * This library on [serverId] ([LibraryRepository.on]), and this very one for
 * the server in use (null): a wrapper's own calls (Watch Next's, a test's)
 * stay in the way for the server in use.
 */
fun LibraryRepository.at(serverId: String?): LibraryRepository? = if (serverId == null) this else on(serverId)
