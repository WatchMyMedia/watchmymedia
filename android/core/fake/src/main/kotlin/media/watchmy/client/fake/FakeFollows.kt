package media.watchmy.client.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.FOLLOWS_UPDATE_SERVER
import media.watchmy.client.model.FollowLatest
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.NewEpisode
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.NewEpisodeOpen
import media.watchmy.client.model.NewEpisodes

/**
 * Following shows on the fakes (PLAN-NEW-EPISODES.md), from
 * [FOLLOWS_API_LEVEL], as with a real server. The person starts out
 * following Harbor Lights and Copperline, and Ashgrove, which has left the
 * library; the feed has Harbor Lights' S3 E7 once, after the first check.
 */
class FakeFollowsRepository(
    private val level: () -> Int = { FAKE_SERVER_API_LEVEL },
    private val latencyMs: () -> Long = { fakeLatencyMs },
) : FollowsRepository {
    override val supported: Boolean get() = level() >= FOLLOWS_API_LEVEL

    private val kept = MutableStateFlow(seed())
    private val list = MutableStateFlow<List<FollowedShow>?>(null)
    override val follows: StateFlow<List<FollowedShow>?> = list.asStateFlow()

    private val switch = MutableStateFlow(true)
    override val notifyHere: StateFlow<Boolean> = switch.asStateFlow()

    /** Follow, unfollow and remove calls refuse with this status while set: the bell's and the list's failures (409 is the limit). */
    var refuse: Int? = null

    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val changes: Flow<Unit> = events

    private val library = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val libraryChanged: Flow<Unit> = library

    /** An episode of [showId] arrived: its follow's latest is now it, and library.changed comes. */
    fun arrived(showId: Long, season: Int, number: Int, title: String? = null) {
        kept.value = kept.value.map { if (it.showId == showId) it.copy(latest = FollowLatest(season, number, title, System.currentTimeMillis())) else it }
        library.tryEmit(Unit)
    }

    private suspend fun <T> call(block: () -> T): T {
        latencyMs().takeIf { it > 0 }?.let { delay(it) }
        if (!supported) throw ApiException(404, FOLLOWS_UPDATE_SERVER)
        return block()
    }

    override suspend fun refresh(): List<FollowedShow> = call { kept.value }.also { list.value = it }

    override suspend fun setFollowing(showId: Long, following: Boolean) {
        call {
            refuse?.let { throw ApiException(it, "The server couldn’t save that.") }
            val show = FakeData.shows.firstOrNull { it.id == showId } ?: throw ApiException(404, "No such show.")
            val tmdbId = FakeData.tmdbId(showId)
            kept.value = kept.value.filter { it.tmdbId != tmdbId }.let {
                if (following) listOf(FollowedShow(tmdbId, show.title, showId, show.posterPath, followedAtMs = System.currentTimeMillis())) + it else it
            }
        }
        list.value = kept.value
    }

    override suspend fun remove(tmdbId: Long) {
        call {
            refuse?.let { throw ApiException(it, "The server couldn’t save that.") }
            kept.value = kept.value.filter { it.tmdbId != tmdbId }
        }
        list.value = kept.value
    }

    /** Whether the show [showId] is followed now, for the fake show page. */
    fun following(showId: Long): Boolean = kept.value.any { it.showId == showId }

    override suspend fun newEpisodes(after: String?): NewEpisodes = call {
        when {
            after == null -> NewEpisodes(CURSOR, emptyList())
            after == CURSOR && following(HARBOR_LIGHTS) -> NewEpisodes(
                "${CURSOR.toInt() + 1}",
                listOf(
                    NewEpisodeGroup(
                        HARBOR_LIGHTS, FakeData.tmdbId(HARBOR_LIGHTS), "Harbor Lights", 1,
                        listOf(NewEpisode(100037, 3, 7, "The Long Dark")), NewEpisodeOpen(100037, 3, 7),
                    ),
                ),
            )
            else -> NewEpisodes(after, emptyList())
        }
    }

    override fun setNotifyHere(on: Boolean) {
        switch.value = on
    }

    /** Another device followed or stopped following [showId]. */
    fun changedElsewhere(showId: Long, following: Boolean) {
        val show = FakeData.shows.firstOrNull { it.id == showId } ?: return
        val tmdbId = FakeData.tmdbId(showId)
        kept.value = kept.value.filter { it.tmdbId != tmdbId }.let { if (following) listOf(FollowedShow(tmdbId, show.title, showId)) + it else it }
        events.tryEmit(Unit)
    }

    /** Home's New from shows you follow: the recently aired episodes of the shows followed, as the server lists them, with a file. */
    fun row(cards: List<EpisodeCard>): List<EpisodeCard> =
        if (!supported) emptyList() else cards.filter { c -> kept.value.any { it.showId == c.showId } && c.hasFile && !c.watched }

    private fun seed(): List<FollowedShow> {
        val now = System.currentTimeMillis()
        return listOf(
            FollowedShow(FakeData.tmdbId(HARBOR_LIGHTS), "Harbor Lights", HARBOR_LIGHTS, latest = FollowLatest(3, 7, "The Long Dark", now - DAY), followedAtMs = now - 2 * DAY),
            FollowedShow(FakeData.tmdbId(COPPERLINE), "Copperline", COPPERLINE, latest = FollowLatest(4, 2, "Slag", now - 4 * DAY), followedAtMs = now - 9 * DAY),
            FollowedShow(ASHGROVE_TMDB, "Ashgrove", null, followedAtMs = now - 40 * DAY),
        )
    }

    private companion object {
        const val HARBOR_LIGHTS = 100L
        const val COPPERLINE = 105L
        const val ASHGROVE_TMDB = 70_412L
        const val CURSOR = "1841"
        const val DAY = 24 * 3_600_000L
    }
}
