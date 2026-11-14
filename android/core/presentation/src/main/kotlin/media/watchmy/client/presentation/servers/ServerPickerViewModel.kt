package media.watchmy.client.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.model.REQUIRED_API_LEVEL
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerTooOldException
import media.watchmy.client.model.attempt
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.formatAgo
import media.watchmy.client.model.isNotEncrypted
import media.watchmy.client.model.needsHttpsNudge
import media.watchmy.client.model.versionLabel

/**
 * Choosing a server, on the phone, the tablet and the TV: found on this
 * network, remembered, or typed. Choosing one checks it answers and is new
 * enough, then makes it the server in use ([chosen]). The network is
 * searched only while the list is on screen (and for 5 s after).
 */
class ServerPickerViewModel(
    private val servers: ServerRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** One server in a list. [key] is its address, which is also what connecting probes. */
    data class Row(
        val key: String,
        val name: String,
        val detail: String,
        val tooOld: Boolean = false,
        val current: Boolean = false,
        val savedId: String? = null,
        /** Announcing itself on this network (the TV's "On this network" badge). */
        val onNetwork: Boolean = false,
        /** Plain http beyond this network ([media.watchmy.client.model.Server.notEncrypted]): marked Not encrypted. */
        val notEncrypted: Boolean = false,
    )

    data class State(
        /** Null while still looking. */
        val discovered: List<Row>? = null,
        val remembered: List<Row> = emptyList(),
        /** The typed address, as the screen's field reports it ([setAddress]). */
        val address: String = "",
        /** The key of the row (or [ADDRESS]) being connected to. */
        val connecting: String? = null,
        /** Why connecting to a listed server failed. */
        val error: String? = null,
        /** Why connecting to the typed address failed. */
        val addressError: String? = null,
    ) {
        /** An address typed with http:// is a host name, which needs https. */
        val addressNeedsHttps: Boolean get() = needsHttpsNudge(address)
    }

    private data class Local(val address: String = "", val connecting: String? = null, val error: String? = null, val addressError: String? = null)

    private val local = MutableStateFlow(Local())
    private val chosenEvents = Channel<Unit>(Channel.BUFFERED)

    /** Emits once each time a server has been chosen and is now in use. */
    val chosen: Flow<Unit> = chosenEvents.receiveAsFlow()

    val state: StateFlow<State> = combine(
        servers.discovered.map<List<DiscoveredServer>, List<DiscoveredServer>?> { it }.onStart { emit(null) }.catch { emit(emptyList()) },
        servers.saved,
        servers.current,
        local,
    ) { discovered, saved, current, l ->
        val savedById = saved.associateBy { it.server.id }
        val found = discovered?.map { d ->
            val s = savedById[d.id]
            val account = if (s?.signedIn == true && s.userName != null) "signed in as ${s.userName}" else "not signed in"
            val tooOld = d.apiLevel < REQUIRED_API_LEVEL
            Row(
                key = d.baseUrl,
                name = d.name,
                detail = listOf(displayAddress(d.baseUrl), versionLabel(d.version), if (tooOld) "too old for this app" else account).joinToString(" · "),
                tooOld = tooOld,
                current = current?.server?.id == d.id,
                savedId = s?.server?.id,
                onNetwork = true,
                notEncrypted = isNotEncrypted(d.baseUrl),
            )
        }
        val foundIds = discovered.orEmpty().map { it.id }.toSet()
        State(
            discovered = found,
            remembered = saved.filter { it.server.id !in foundIds }.map { it.toRow(current) },
            address = l.address,
            connecting = l.connecting,
            error = l.error,
            addressError = l.addressError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    private fun SavedServer.toRow(current: SavedServer?) = Row(
        key = server.baseUrl,
        name = server.name,
        detail = listOfNotNull(
            "Last used ${formatAgo(lastUsedAtMs, now())}",
            if (signedIn && userName != null) "signed in as $userName" else "not signed in",
            "too old for this app".takeIf { server.tooOld },
        ).joinToString(" · "),
        tooOld = server.tooOld,
        current = current?.server?.id == server.id,
        savedId = server.id,
        notEncrypted = server.notEncrypted,
    )

    fun setAddress(address: String) = local.update { if (it.address == address) it else it.copy(address = address, addressError = null) }

    /** Connects to a listed server. */
    fun choose(row: Row) = connect(row.key, row.key, row.name)

    /** Connects to the typed address. */
    fun connectToAddress() {
        val address = local.value.address.trim()
        if (address.isEmpty()) {
            local.update { it.copy(addressError = "Enter the server's address, such as 192.168.1.20 or nas.local.") }
            return
        }
        connect(address, ADDRESS, address)
    }

    fun forget(row: Row) {
        val id = row.savedId ?: return
        viewModelScope.launch { attempt { servers.forget(id) } }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun connect(address: String, key: String, label: String) {
        if (local.value.connecting != null) return
        local.update { it.copy(connecting = key, error = null, addressError = null) }
        viewModelScope.launch {
            val result = attempt {
                val server = servers.probe(address)
                if (server.tooOld) throw ServerTooOldException(server)
                servers.select(server)
            }
            result.onSuccess {
                local.update { it.copy(connecting = null, address = if (key == ADDRESS) "" else it.address) }
                chosenEvents.send(Unit)
            }.onFailure { e ->
                val message = connectMessage(e, label)
                local.update { if (key == ADDRESS) it.copy(connecting = null, addressError = message) else it.copy(connecting = null, error = message) }
            }
        }
    }

    companion object {
        /** [State.connecting] while the typed address is. */
        const val ADDRESS = "address"
    }
}

/** Too old says which version and what to do; an unreachable server says so. */
fun connectMessage(e: Throwable, label: String): String = when {
    e is ServerTooOldException -> e.message.orEmpty()
    e is ApiException && !e.message.isNullOrBlank() -> e.message.orEmpty()
    else -> "Couldn't reach $label. Check the address, and that the server is running and on this network."
}
