package media.watchmy.client.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * Following a show, and a notification when a new episode of it reaches the
 * library (PLAN-NEW-EPISODES.md), from [FOLLOWS_API_LEVEL]. A person follows
 * a show on the server, so the list is the same on every device; each phone
 * and tablet checks the server's feed for itself (§6.1) and notifies. TVs
 * follow for the person's phone and show the Home row instead.
 */

/** The level that brought following shows, the feed of new episodes and the `followed` Home row. */
const val FOLLOWS_API_LEVEL = 29

/** The notification channel new episodes come on (phones and tablets; TVs don't notify). */
const val NEW_EPISODES_CHANNEL = "new-episodes"
const val NEW_EPISODES_CHANNEL_NAME = "New episodes"

/** One show the person follows, as the server lists it. */
data class FollowedShow(
    /** What [FollowsRepository.remove] takes: a follow is kept by TMDB id. */
    val tmdbId: Long,
    val title: String,
    /** The show, while it is in a library; null once it has left every library. */
    val showId: Long? = null,
    val posterPath: String? = null,
    /** The newest episode that arrived for it, announced or not. */
    val latest: FollowLatest? = null,
    val followedAtMs: Long? = null,
) {
    /** The show has left every library: listed by its title, and said so. */
    val gone: Boolean get() = showId == null
}

/** The newest episode that arrived for a followed show. */
data class FollowLatest(val season: Int, val number: Int, val title: String? = null, val arrivedAtMs: Long? = null)

/** An episode that arrived for a followed show. */
data class NewEpisode(val id: Long, val season: Int, val number: Int, val title: String? = null, val stillPath: String? = null)

/** The episode a notification opens: the earliest of its group. */
data class NewEpisodeOpen(val episodeId: Long, val season: Int, val number: Int)

/** One show's new episodes: one notification. [episodes] are the first few, earliest first; [count] is all of them. */
data class NewEpisodeGroup(
    val showId: Long,
    val tmdbId: Long,
    val title: String,
    val count: Int,
    val episodes: List<NewEpisode>,
    val open: NewEpisodeOpen,
    val posterPath: String? = null,
)

/** What arrived since a device's cursor, and the cursor to keep. */
data class NewEpisodes(val cursor: String, val shows: List<NewEpisodeGroup>)

/**
 * The shows the person follows and the feed of what arrived for them, on the
 * server in use, and this device's switch for the notifications. Below
 * [FOLLOWS_API_LEVEL] [supported] is false and the calls fail without asking
 * the server.
 */
interface FollowsRepository {
    /** The server in use can follow shows: Notify me shows. */
    val supported: Boolean

    /** The person's follows, newest first, as last read; null before the first read for this sign-in. */
    val follows: StateFlow<List<FollowedShow>?>

    /** Reads the list again. Throws [ApiException]. */
    suspend fun refresh(): List<FollowedShow>

    /** Follows the show [showId], or stops. Throws [ApiException]; the list is read again after. */
    suspend fun setFollowing(showId: Long, following: Boolean)

    /** Stops following by TMDB id, which works for a show no longer in the library. */
    suspend fun remove(tmdbId: Long)

    /** What arrived since [after]; without it, only the cursor (a device starts from now). */
    suspend fun newEpisodes(after: String?): NewEpisodes

    /** follows.changed, from the person's other devices, and a reconnection, after which the list may differ. */
    val changes: Flow<Unit>

    /**
     * This device's switch for new-episode notifications (§7.2), kept on the
     * device and on by default; the system's own permission is apart from it.
     */
    val notifyHere: StateFlow<Boolean>

    fun setNotifyHere(on: Boolean)

    /** No server, or one too old: nothing to follow. */
    object Unavailable : FollowsRepository {
        override val supported: Boolean get() = false
        override val follows: StateFlow<List<FollowedShow>?> = MutableStateFlow<List<FollowedShow>?>(null).asStateFlow()
        override suspend fun refresh(): List<FollowedShow> = throw ApiException(404, FOLLOWS_UPDATE_SERVER)
        override suspend fun setFollowing(showId: Long, following: Boolean) = throw ApiException(404, FOLLOWS_UPDATE_SERVER)
        override suspend fun remove(tmdbId: Long) = throw ApiException(404, FOLLOWS_UPDATE_SERVER)
        override suspend fun newEpisodes(after: String?): NewEpisodes = throw ApiException(404, FOLLOWS_UPDATE_SERVER)
        override val changes: Flow<Unit> = emptyFlow()
        override val notifyHere: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override fun setNotifyHere(on: Boolean) = Unit
    }
}

const val FOLLOWS_UPDATE_SERVER = "Update the server to follow shows."

// --- the check (§6.1) ---

/**
 * Whether this device checks for new episodes: the server can, the person
 * follows at least one show, this device's switch is on and the system lets
 * the notifications show. [follows] null (not read yet) is not a reason to
 * stop, since a process started for the check itself hasn't read it.
 */
fun newEpisodeChecks(supported: Boolean, follows: List<FollowedShow>?, notifyHere: Boolean, allowed: Boolean): NewEpisodeChecks = when {
    !supported || !notifyHere || !allowed -> NewEpisodeChecks.Stop
    follows == null -> NewEpisodeChecks.Unknown
    follows.isEmpty() -> NewEpisodeChecks.Stop
    else -> NewEpisodeChecks.Run
}

enum class NewEpisodeChecks {
    /** Schedule the check. */
    Run,

    /** Cancel it: nothing to do. */
    Stop,

    /** Leave it as it is until the list is read. */
    Unknown,
}

/** The cursor a device keeps, and the account ([watchNextAccount]) it is for: one server and person. */
data class NewEpisodesCursor(val account: String, val cursor: String)

/** Where a device keeps its [NewEpisodesCursor]; one at a time, for the account signed in. */
interface NewEpisodesCursors {
    fun read(): NewEpisodesCursor?

    /** Keeps [cursor]; null forgets it (signed out, or another server or person). */
    fun keep(cursor: NewEpisodesCursor?)
}

/** How one check ended. */
sealed interface NewEpisodeCheck {
    /** No one is signed in: the cursor is forgotten. */
    data object SignedOut : NewEpisodeCheck

    /** The first check for this server and person: the cursor is kept and nothing is shown, as the device starts from now. */
    data object Started : NewEpisodeCheck

    /** [shows] were handed over to be shown (none, when nothing arrived), and the new cursor kept. */
    data class Shown(val shows: List<NewEpisodeGroup>) : NewEpisodeCheck

    /** The server couldn't be asked: the cursor stays, and the next check catches up. */
    data class Unreachable(val error: Throwable) : NewEpisodeCheck

    /** Someone else signed in while it asked: nothing shown or kept. */
    data object Moved : NewEpisodeCheck
}

/**
 * One check (PLAN-NEW-EPISODES.md §6.1), the same on every trigger: ask the
 * feed with the cursor kept for this server and person, [show] a
 * notification for each show in the reply, then keep the new cursor. The
 * first check for an account asks without a cursor and only keeps the one it
 * gets. A cursor kept for another account is not used. [account] is asked
 * again before anything is shown or kept, so a sign-out or switch while the
 * server answers leaves nothing behind.
 */
suspend fun checkNewEpisodes(
    account: () -> String?,
    cursors: NewEpisodesCursors,
    fetch: suspend (after: String?) -> NewEpisodes,
    show: (List<NewEpisodeGroup>) -> Unit,
): NewEpisodeCheck {
    val who = account()
    if (who == null) {
        cursors.keep(null)
        return NewEpisodeCheck.SignedOut
    }
    val after = cursors.read()?.takeIf { it.account == who }?.cursor
    val reply = try {
        fetch(after)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return NewEpisodeCheck.Unreachable(e)
    }
    if (account() != who) return NewEpisodeCheck.Moved
    if (after == null) {
        cursors.keep(NewEpisodesCursor(who, reply.cursor))
        return NewEpisodeCheck.Started
    }
    if (reply.shows.isNotEmpty()) show(reply.shows)
    cursors.keep(NewEpisodesCursor(who, reply.cursor))
    return NewEpisodeCheck.Shown(reply.shows)
}

/** At most this many shows get a notification of their own from one check; the rest share one (§6.2). */
const val NEW_EPISODES_MAX_SHOWN = 5

/** What one check posts: a notification for each of [shows], and one for [more] shows past them when there are. */
data class NewEpisodeNotices(val shows: List<NewEpisodeGroup>, val more: Int)

fun List<NewEpisodeGroup>.notices(): NewEpisodeNotices =
    if (size <= NEW_EPISODES_MAX_SHOWN) NewEpisodeNotices(this, 0) else NewEpisodeNotices(take(NEW_EPISODES_MAX_SHOWN), size - NEW_EPISODES_MAX_SHOWN)

/**
 * The link a notification opens: the show's page with [NewEpisodeGroup.open]
 * on top, for [account] alone ([DeepLink.open] opens nothing for another).
 */
fun NewEpisodeGroup.link(account: String): String = DeepLink.newEpisode(showId, open.season, open.episodeId, account)
