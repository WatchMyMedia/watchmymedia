package media.watchmy.client.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.CastService
import media.watchmy.client.model.CastState
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.DownloadTotals
import media.watchmy.client.model.MAX_SERVERS
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerSwitch
import media.watchmy.client.model.attempt
import media.watchmy.client.model.couldntReach
import media.watchmy.client.model.couldntReachBody
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.registered
import media.watchmy.client.model.serverLine
import media.watchmy.client.model.serverLineWithVersion
import media.watchmy.client.model.serversSummary

/** What Servers' rows and buttons do: [ServersViewModel], which the screens' parts take as this. */
interface ServersActions {
    /** Switches to [serverId], asking it first. */
    fun choose(serverId: String)

    /** Asks the server that didn't answer again. */
    fun retry()

    /** Switches to the server that didn't answer all the same, for what was downloaded from it. */
    fun watchDownloads()

    fun dismissFailure()

    /** Removes [serverId] from this device. */
    fun remove(serverId: String)
}

/**
 * Servers, on the phone, the tablet and the TV: the servers this device is
 * signed in to, the one in use first, and switching between them. It draws
 * the switcher the account button opens and Settings' Servers pages alike.
 *
 * Choosing a server asks it which server it is first, so one that is off or
 * away leaves the app where it was, with [State.failure] over the list: Try
 * again ([retry]), and on a device holding downloads from it, Watch its
 * downloads ([watchDownloads]), which switches without asking. A switch
 * that works needs nothing more here: the app rebuilds its screens on the
 * new server and says where it is. One whose sign-in ended becomes the
 * server in use, and the app shows its sign-in.
 */
class ServersViewModel(
    private val servers: ServerRepository,
    downloads: DownloadRepository = DownloadRepository.Unavailable,
    cast: CastService = CastService.None,
) : ViewModel(), ServersActions {
    enum class RowState { Idle, Switching, SignedOut, TooOld }

    /** One registered server. */
    data class Row(
        val serverId: String,
        val name: String,
        /** Who is signed in there and its address: "Sam · home.example.net". */
        val line: String,
        /** [line] with its version, for Settings' list: "Sam · home.example.net · v1.4.5". */
        val detail: String,
        val current: Boolean,
        val state: RowState,
        val address: String,
        val version: String,
        val userName: String?,
        /** What this device holds from it; null when nothing. */
        val downloads: DownloadTotals? = null,
    ) {
        /** Choosing it does something: not the one in use, and not one this app is too new for. */
        val canChoose: Boolean get() = !current && state != RowState.TooOld && state != RowState.Switching
    }

    /** A switch that didn't happen: which server, in the words over the list, and what it holds here. */
    data class Failure(
        val serverId: String,
        val serverName: String,
        val title: String,
        val body: String,
        /** Its downloads on this device: more than none offers Watch its downloads. */
        val downloads: Int = 0,
    )

    data class State(
        /** The registered servers, the one in use first, then by last use. */
        val rows: List<Row> = emptyList(),
        val failure: Failure? = null,
        /** The TV this device is casting to: switching stops it. */
        val casting: String? = null,
        /** [MAX_SERVERS] are registered: one goes before another is added. */
        val full: Boolean = false,
        /** The server being removed. */
        val removing: String? = null,
    ) {
        val current: Row? get() = rows.firstOrNull { it.current }

        /** Two or more: where the app says which one it is on. */
        val several: Boolean get() = rows.size > 1

        /** Settings' row: "Living room and 2 more". */
        val summary: String get() = serversSummary(current?.name, rows.size)

        fun row(serverId: String?): Row? = rows.firstOrNull { it.serverId == serverId }
    }

    sealed interface Event {
        /** The app is on another server now, signed in: Servers closes. */
        data object Switched : Event

        /** The app is on a server whose sign-in has ended, and shows its sign-in. */
        data object SignIn : Event

        /** [name] is off this device. */
        data class Removed(val name: String) : Event
    }

    private data class Local(val switching: String? = null, val failure: Failure? = null, val removing: String? = null)

    private val local = MutableStateFlow(Local())
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    val state: StateFlow<State> = combine(servers.saved, servers.current, downloads.byServer, cast.state, local) { saved, current, held, casting, l ->
        val registered = saved.registered(current?.server?.id)
        State(
            rows = registered.map { it.toRow(current, l.switching, held[it.server.id]) },
            failure = l.failure?.copy(downloads = held[l.failure.serverId]?.count ?: 0),
            casting = (casting as? CastState.Connected)?.deviceName,
            full = registered.size >= MAX_SERVERS,
            removing = l.removing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    private fun SavedServer.toRow(current: SavedServer?, switching: String?, held: DownloadTotals?) = Row(
        serverId = server.id,
        name = server.name,
        line = serverLine(userName, server.baseUrl),
        detail = serverLineWithVersion(userName, server.baseUrl, server.version),
        current = current?.server?.id == server.id,
        state = when {
            switching == server.id -> RowState.Switching
            server.tooOld -> RowState.TooOld
            !signedIn -> RowState.SignedOut
            else -> RowState.Idle
        },
        address = displayAddress(server.baseUrl),
        version = server.version.removePrefix("v"),
        userName = userName,
        downloads = held?.takeIf { it.count > 0 },
    )

    override fun choose(serverId: String) = switch(serverId, ask = true)

    override fun retry() {
        local.value.failure?.let { switch(it.serverId, ask = true) }
    }

    /** The app opens as it does offline, on what was downloaded from that server. */
    override fun watchDownloads() {
        local.value.failure?.let { switch(it.serverId, ask = false) }
    }

    override fun dismissFailure() = local.update { it.copy(failure = null) }

    private fun switch(serverId: String, ask: Boolean) {
        if (local.value.switching != null) return
        val current = servers.current.value?.server
        if (current?.id == serverId) return
        local.update { it.copy(switching = serverId, failure = null) }
        viewModelScope.launch {
            val saved = state.value.row(serverId)
            val outcome = attempt { servers.switchTo(serverId, ask) }.getOrDefault(ServerSwitch.Unreachable)
            val name = saved?.name ?: "That server"
            val failure = when (outcome) {
                ServerSwitch.Unreachable -> Failure(serverId, name, couldntReach(name), couldntReachBody(current?.name))
                ServerSwitch.TooOld -> Failure(
                    serverId, name, "$name is too old for this app",
                    "Update it, then switch." + if (current != null) " You’re still on ${current.name}." else "",
                )
                else -> null
            }
            local.update { it.copy(switching = null, failure = failure) }
            when (outcome) {
                ServerSwitch.Switched -> eventChannel.send(Event.Switched)
                ServerSwitch.NeedsSignIn -> eventChannel.send(Event.SignIn)
                else -> Unit
            }
        }
    }

    /**
     * Removes [serverId] from this device: signed out of it, its downloads
     * here deleted, and nothing changed on the server. When it was the one
     * in use, the app moves to the server used last that is still signed in.
     */
    override fun remove(serverId: String) {
        if (local.value.removing != null) return
        val name = state.value.row(serverId)?.name ?: return
        local.update { it.copy(removing = serverId, failure = it.failure?.takeIf { f -> f.serverId != serverId }) }
        viewModelScope.launch {
            attempt { servers.forget(serverId) }
            local.update { it.copy(removing = null) }
            eventChannel.send(Event.Removed(name))
        }
    }
}
