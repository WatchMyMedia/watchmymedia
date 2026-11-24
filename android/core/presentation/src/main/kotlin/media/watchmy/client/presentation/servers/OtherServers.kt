package media.watchmy.client.presentation.servers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerTag
import media.watchmy.client.model.at
import media.watchmy.client.model.attempt
import media.watchmy.client.model.didntAnswer
import media.watchmy.client.model.isAwayFromHome
import media.watchmy.client.model.registered

/**
 * The servers this device is signed in to besides the one a page is on, and
 * a way to ask each of them at once, for one card a title across servers:
 * Home's rows, the libraries, a title's other copies. Each is asked as the
 * person signed in there, at its own address, through [LibraryRepository.on],
 * which asks the address which server it is before the sign-in goes out;
 * each gets [timeoutMs], and one that doesn't answer in time, or fails, is
 * left out with its name kept to say so.
 */
class OtherServers(
    private val servers: ServerRepository?,
    private val library: LibraryRepository,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** A signed-in server: [library] is the library there, [away] when it is reached away from home. */
    data class Other(val tag: ServerTag, val away: Boolean, val library: LibraryRepository, val registeredId: String = tag.id.orEmpty()) {
        val id: String? get() = tag.id
        val name: String get() = tag.name
    }

    /** The server in use, as [Other]: its own library. */
    fun inUse(): Other? {
        val current = servers?.current?.value ?: return null
        return Other(ServerTag(null, current.server.name), isAwayFromHome(current.server.baseUrl), library, current.server.id)
    }

    /**
     * Every signed-in server this app can talk to in Servers' order (the one
     * in use first), but [except]: the server a page is on (null for the one
     * in use, which is then left out). Empty with one server.
     */
    suspend fun others(except: String? = null): List<Other> {
        val all = servers ?: return emptyList()
        val current = all.current.value ?: return emptyList()
        val registered = all.saved.first().registered(current.server.id).filter { it.signedIn && !it.server.tooOld }
        if (registered.size < 2) return emptyList()
        return registered.mapNotNull { s ->
            val id = s.server.id.takeIf { it != current.server.id }
            if (id == except) return@mapNotNull null
            val there = library.at(id) ?: return@mapNotNull null
            Other(ServerTag(id, s.server.name), isAwayFromHome(s.server.baseUrl), there, s.server.id)
        }
    }

    /** The server [id] is (null: the one in use), as [Other]; null when no one is signed in there. */
    suspend fun server(id: String?): Other? = if (id == null) inUse() else others(except = null).firstOrNull { it.id == id }
        ?: servers?.saved?.first()?.firstOrNull { it.server.id == id && it.signedIn }?.let { s ->
            library.at(id)?.let { Other(ServerTag(id, s.server.name), isAwayFromHome(s.server.baseUrl), it, s.server.id) }
        }

    /** Whether the device is signed in to two servers or more, which is when cards merge. */
    suspend fun several(): Boolean = others(except = null).isNotEmpty()

    /**
     * Asks each of [others] at once with [call]; each answer, or its failure
     * (in [timeoutMs] or not at all), as it comes. A failure that isn't the
     * server's own words reads as [didntAnswer].
     */
    fun <T> ask(others: List<Other>, call: suspend (LibraryRepository) -> T): Flow<Pair<Other, Result<T>>> = channelFlow {
        for (other in others) {
            launch {
                val answer = attempt {
                    withTimeoutOrNull(timeoutMs) { call(other.library) } ?: throw ApiException(null, didntAnswer(other.name))
                }
                send(other to answer)
            }
        }
    }

    companion object {
        /** How long each server gets, as search gives it. */
        const val TIMEOUT_MS = 4_000L

        /** How long a server is out before a page says it is still asking. */
        const val STILL_ASKING_MS = 1_000L
    }
}
