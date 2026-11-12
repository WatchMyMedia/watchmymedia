package media.watchmy.client.model

import kotlinx.coroutines.flow.Flow

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
)

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
)

data class Person(val name: String, val role: String?, val profilePath: String?)

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
data class WatchProgress(val positionMs: Long, val durationMs: Long, val completed: Boolean)

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

    suspend fun search(text: String): SearchResults

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
     * Emits when watch state or the library changed on the server (the
     * watch.changed and library.changed events), so screens refetch.
     */
    val changes: Flow<Unit>
}
