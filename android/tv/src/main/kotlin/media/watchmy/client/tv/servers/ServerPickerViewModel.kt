package media.watchmy.client.tv.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.DiscoveredServer
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.ServerTooOldException
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.formatAgo
import media.watchmy.client.model.isNotEncrypted
import media.watchmy.client.model.needsHttpsNudge
import media.watchmy.client.model.versionLabel

/**
 * The server picker: servers announcing themselves on this network, ones
 * used before, and a typed address. Choosing one checks it answers and is
 * new enough, then makes it the server in use.
 */
class ServerPickerViewModel(
    private val servers: ServerRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class Row(
        val id: String,
        val name: String,
        val detail: String,
        val baseUrl: String,
        /** Announcing itself on this network (the "On this network" badge). */
        val onNetwork: Boolean,
        val current: Boolean,
        val tooOld: Boolean,
        /** Plain http beyond this network ([Server.notEncrypted]): the Not encrypted tag. */
        val notEncrypted: Boolean = isNotEncrypted(baseUrl),
    )

    data class Form(
        val address: String = "",
        /** The row ID being connected to, or [ADDRESS] for the typed address. */
        val connecting: String? = null,
        val rowError: String? = null,
        val addressError: String? = null,
    )

    /** Looks for servers on the network only while the list is on screen (and for 5 s after). */
    val rows: StateFlow<List<Row>?> = combine(
        servers.discovered.onStart { emit(emptyList()) }.catch { emit(emptyList()) },
        servers.saved,
        servers.current,
    ) { found, saved, current -> rowsOf(found, saved, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val chosenEvents = Channel<Unit>(Channel.BUFFERED)

    /** Emits once each time a server has been chosen and is now in use. */
    val chosen: Flow<Unit> = chosenEvents.receiveAsFlow()

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    /** True while an address typed with http:// is a host name, which needs https. */
    val addressNeedsHttps: StateFlow<Boolean> = _form.map { needsHttpsNudge(it.address) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun rowsOf(found: List<DiscoveredServer>, saved: List<SavedServer>, current: SavedServer?): List<Row> {
        val byId = saved.associateBy { it.server.id }
        val discovered = found.map { d ->
            val s = byId[d.id]
            val account = when {
                s?.signedIn == true && s.userName != null -> "signed in as ${s.userName}"
                else -> "not signed in"
            }
            val tooOld = Server(d.id, d.name, d.baseUrl, d.version, d.apiLevel).tooOld
            Row(
                id = d.id,
                name = d.name,
                detail = listOf(displayAddress(d.baseUrl), versionLabel(d.version), if (tooOld) "too old for this app" else account).joinToString(" · "),
                baseUrl = d.baseUrl,
                onNetwork = true,
                current = current?.server?.id == d.id,
                tooOld = tooOld,
            )
        }
        val foundIds = found.map { it.id }.toSet()
        val remembered = saved.filter { it.server.id !in foundIds }.map { s ->
            val parts = mutableListOf("Remembered", "last used ${formatAgo(s.lastUsedAtMs, now())}")
            if (s.signedIn && s.userName != null) parts += "signed in as ${s.userName}"
            if (s.server.tooOld) parts += "too old for this app"
            Row(s.server.id, s.server.name, parts.joinToString(" · "), s.server.baseUrl, onNetwork = false, current = current?.server?.id == s.server.id, tooOld = s.server.tooOld)
        }
        return discovered + remembered
    }

    fun setAddress(address: String) = _form.update { it.copy(address = address, addressError = null) }

    fun choose(row: Row) {
        if (_form.value.connecting != null) return
        _form.update { it.copy(connecting = row.id, rowError = null, addressError = null) }
        viewModelScope.launch {
            val error = connect(row.baseUrl)
            _form.update { it.copy(connecting = null, rowError = error) }
            if (error == null) chosenEvents.send(Unit)
        }
    }

    fun connectAddress() {
        val address = _form.value.address.trim()
        if (_form.value.connecting != null) return
        if (address.isEmpty()) {
            _form.update { it.copy(addressError = "Enter the server's address, such as 192.168.1.20 or nas.local.") }
            return
        }
        _form.update { it.copy(connecting = ADDRESS, rowError = null, addressError = null) }
        viewModelScope.launch {
            val error = connect(address)
            _form.update { it.copy(connecting = null, addressError = error) }
            if (error == null) chosenEvents.send(Unit)
        }
    }

    /** Probes and selects; returns the message to show, or null when the server is now in use. */
    private suspend fun connect(address: String): String? = try {
        val server = servers.probe(address)
        if (server.tooOld) throw ServerTooOldException(server)
        servers.select(server)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServerTooOldException) {
        errorMessage(e)
    } catch (e: Exception) {
        errorMessage(e)
    }

    companion object {
        const val ADDRESS = "address"
    }
}
