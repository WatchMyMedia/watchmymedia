package media.watchmy.client.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The device's default network, for the API client (no waiting without one)
 * and the event stream (connected only on one, and again at once on a new
 * one): PLAN-ANDROID-REVIEW.md §4.1.5–6.
 */
internal interface NetworkMonitor {
    /** The default network, as a value that changes when the device moves to another; null with none. */
    val network: StateFlow<Long?>

    val available: Boolean get() = network.value != null

    companion object {
        /** Always on one network: tests, and wherever the system can't say. */
        val Always: NetworkMonitor = object : NetworkMonitor {
            override val network: StateFlow<Long?> = MutableStateFlow<Long?>(0L).asStateFlow()
        }
    }
}

/**
 * [NetworkMonitor] from ConnectivityManager's default-network callback,
 * registered off the main thread. Until the system has answered it counts
 * as on a network, so a slow answer never holds a request back.
 */
internal class SystemNetworkMonitor(context: Context, scope: CoroutineScope) : NetworkMonitor {
    private val state = MutableStateFlow<Long?>(UNKNOWN)
    override val network: StateFlow<Long?> = state.asStateFlow()

    init {
        val app = context.applicationContext
        scope.launch {
            val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return@launch
            try {
                connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        state.value = network.networkHandle
                    }

                    override fun onLost(network: Network) {
                        state.compareAndSet(network.networkHandle, null)
                    }
                })
                // With a default network the callback says so at once; with none it says nothing.
                if (connectivity.activeNetwork == null) state.compareAndSet(UNKNOWN, null)
            } catch (e: RuntimeException) {
                // No permission, or the system's callback limit: carry on as if always on a network.
                Log.w("WatchMyMedia", "can't follow the default network", e)
            }
        }
    }

    private companion object {
        const val UNKNOWN = Long.MIN_VALUE
    }
}

/** No default network: a request fails at once rather than waiting out a connect timeout. */
internal class NoNetworkException : java.io.IOException("no network")

/**
 * True for an address on this device or the local network, where a server
 * answers within a few milliseconds or not at all: loopback, private and
 * link-local IPs, `.local`, `.lan` and `.home.arpa` names, and single-label
 * hosts ("nas"). No lookup is made, so a name that resolves to a private
 * address elsewhere doesn't count.
 */
internal fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    if (isLoopbackHost(h)) return true
    if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
    if (':' in h) return h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb") || h.startsWith("fc") || h.startsWith("fd")
    val octets = h.split('.')
    if (octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }) {
        val (a, b) = octets[0].toInt() to octets[1].toInt()
        return a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254
    }
    return '.' !in h && h.isNotEmpty()
}

/** This device itself, which answers with no network at all. */
internal fun isLoopbackHost(host: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    return h == "localhost" || h == "::1" || h.startsWith("127.")
}
