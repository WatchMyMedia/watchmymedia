package media.watchmy.client.core.playback

import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.UpNext

/**
 * What the player looks up beside the decision, which names neither the
 * file nor the next episode's length: a title for a bare
 * `watchmymedia://play/{fileId}` link, and the next episode's runtime and
 * whether it ends its season. Methods may throw; the player carries on
 * without the answer.
 */
interface PlaybackLookups {
    /** [request] named from the server's records; null leaves it as it is. */
    suspend fun name(request: PlayRequest): PlayRequest?

    /** [next] with [UpNext.runtimeMs] and [UpNext.seasonFinale] filled in. */
    suspend fun describe(next: UpNext): UpNext

    /** The URL of the picture at [path] (a backdrop) for the media notification and lock screen; null for none. */
    fun artworkUrl(path: String?): String? = null

    object None : PlaybackLookups {
        override suspend fun name(request: PlayRequest): PlayRequest? = null

        override suspend fun describe(next: UpNext): UpNext = next
    }
}

/** [PlaybackLookups] through the library: [LibraryRepository.playRequest], and the next episode's season. */
class LibraryLookups(private val library: LibraryRepository) : PlaybackLookups {
    override fun artworkUrl(path: String?): String? = library.imageUrl(path, ImageSize.W780)

    override suspend fun name(request: PlayRequest): PlayRequest? =
        library.playRequest(request.fileId).takeIf { it.title.isNotBlank() }

    override suspend fun describe(next: UpNext): UpNext {
        // Newer servers send both with the decision.
        if (next.runtimeMs != null) return next
        val season = library.season(next.showId, next.season)
        val episode = season.episodes.firstOrNull { it.id == next.episodeId } ?: season.episodes.firstOrNull { it.number == next.episode }
        val runtime = episode?.versions?.firstOrNull { it.fileId == next.fileId }?.durationMs
            ?: episode?.versions?.firstNotNullOfOrNull { it.durationMs }
            ?: episode?.runtimeMin?.let { it * 60_000L }
        // The season's TMDB size, or its last known episode; specials have no finale.
        val last = maxOf(season.season.episodeCount, season.episodes.maxOfOrNull { it.number } ?: 0)
        return next.copy(runtimeMs = runtime ?: next.runtimeMs, seasonFinale = next.season > 0 && last > 0 && next.episode >= last)
    }
}
