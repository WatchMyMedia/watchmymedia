package media.watchmy.client.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import media.watchmy.client.core.api.apis.FollowsApi
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.FOLLOWS_UPDATE_SERVER
import media.watchmy.client.model.FollowLatest
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.NewEpisode
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.NewEpisodeOpen
import media.watchmy.client.model.NewEpisodes
import media.watchmy.client.core.api.models.Follow as ApiFollow
import media.watchmy.client.core.api.models.NewEpisodeGroup as ApiNewEpisodeGroup

/**
 * /api/shows/{id}/follow, /api/me/follows and /api/me/new-episodes on the
 * server in use. Below [FOLLOWS_API_LEVEL]
 * [supported] is false and every call fails without asking the server. The
 * list read last is kept for the sign-in it was read for, and dropped when
 * another server or account is used. This device's switch for the
 * notifications is kept on the device for each server and account
 * ([StoredServer.newEpisodesOff]), on by default.
 */
internal class RealFollowsRepository(
    private val store: LocalStore,
    private val http: Http,
    events: ServerEvents,
) : FollowsRepository {
    override val supported: Boolean get() = http.features().follows

    private val list = MutableStateFlow<List<FollowedShow>?>(null)
    override val follows: StateFlow<List<FollowedShow>?> = list.asStateFlow()

    private val switch = MutableStateFlow(on(store.value))
    override val notifyHere: StateFlow<Boolean> = switch.asStateFlow()

    init {
        // Another server or account: its list isn't this one's, and its switch is its own.
        store.onSessionChange {
            list.value = null
            switch.value = on(store.value)
        }
    }

    private fun on(s: Stored): Boolean {
        val server = s.current ?: return true
        val user = server.account?.userId ?: return true
        return user !in server.newEpisodesOff
    }

    private suspend fun <T> follows(block: suspend (FollowsApi) -> T): T {
        if (!supported) throw ApiException(404, FOLLOWS_UPDATE_SERVER)
        return http.signedIn { base, client -> block(FollowsApi(base, client)) }
    }

    override suspend fun refresh(): List<FollowedShow> {
        val revision = store.revision
        val read = follows { it.listFollows() }.follows.map { it.toModel() }
        if (store.revision == revision) list.value = read
        return read
    }

    override suspend fun setFollowing(showId: Long, following: Boolean) {
        val revision = store.revision
        if (following) {
            follows { it.followShow(showId) }
        } else {
            ignoring404 { follows { it.unfollowShow(showId) } }
            if (store.revision == revision) list.update { it?.filter { f -> f.showId != showId } }
        }
        // The list names the show by its TMDB id and title, which the server knows: read it again.
        runCatching { refresh() }
    }

    override suspend fun remove(tmdbId: Long) {
        val revision = store.revision
        ignoring404 { follows { it.removeFollow(tmdbId) } }
        if (store.revision == revision) list.update { it?.filter { f -> f.tmdbId != tmdbId } }
    }

    override suspend fun newEpisodes(after: String?): NewEpisodes {
        val reply = follows { it.listNewEpisodes(after?.takeIf { a -> a.isNotBlank() }) }
        return NewEpisodes(reply.cursor, reply.shows.map { it.toModel() })
    }

    override val changes: Flow<Unit> = events.followChanges.map { }

    override val libraryChanged: Flow<Unit> = events.contentsChanged

    override fun setNotifyHere(on: Boolean) {
        val next = store.update { s ->
            val server = s.current ?: return@update s
            val user = server.account?.userId ?: return@update s
            s.withServer(server.id) { it.copy(newEpisodesOff = if (on) it.newEpisodesOff - user else (it.newEpisodesOff + user).distinct()) }
        }
        switch.value = on(next)
    }
}

internal fun ApiFollow.toModel() = FollowedShow(
    tmdbId = tmdbId,
    title = title,
    showId = show?.id,
    posterPath = show?.posterPath.blankToNull(),
    latest = latest?.let { FollowLatest(it.season.toInt(), it.number.toInt(), it.title.blankToNull(), epochMs(it.arrivedAt)) },
    followedAtMs = epochMs(followedAt),
)

internal fun ApiNewEpisodeGroup.toModel() = NewEpisodeGroup(
    showId = show.id,
    tmdbId = show.tmdbId,
    title = show.title,
    count = count.toInt().coerceAtLeast(episodes.size),
    episodes = episodes.map { NewEpisode(it.id, it.season.toInt(), it.number.toInt(), it.title.blankToNull(), it.stillPath.blankToNull()) },
    open = NewEpisodeOpen(open.episodeId, open.season.toInt(), open.number.toInt()),
    posterPath = show.posterPath.blankToNull(),
)

private fun String?.blankToNull() = this?.takeIf { it.isNotBlank() }
