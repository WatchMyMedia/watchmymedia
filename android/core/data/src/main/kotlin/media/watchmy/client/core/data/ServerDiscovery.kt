package media.watchmy.client.core.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.DiscoveredServer

/** Servers announcing themselves on the local network. */
internal fun interface ServerDiscovery {
    fun servers(): Flow<List<DiscoveredServer>>

    companion object {
        val None = ServerDiscovery { flowOf(emptyList()) }
    }
}

/**
 * mDNS through [NsdManager]: browses `_watchmymedia._tcp` while collected,
 * resolves each service to an address and its TXT records (`id`, `version`,
 * `api`, `path`), and drops services when they say goodbye. Resolves run one
 * at a time, since older Android refuses concurrent ones.
 */
internal class NsdDiscovery(context: Context) : ServerDiscovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val resolving = Mutex()

    override fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredServer>()
        fun publish() = trySend(found.values.toList())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val server = resolve(info)?.let(::toDiscovered) ?: return@launch
                    synchronized(found) {
                        found[info.serviceName] = server
                        publish()
                    }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(info.serviceName) != null) publish()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }

        publish()
        val started = try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        } catch (e: RuntimeException) {
            // No network permission or no NSD on this device: nothing to list, typing an address still works.
            Log.w(TAG, "mDNS discovery unavailable", e)
            false
        }
        awaitClose {
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = resolving.withLock {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(info) else resolveLegacy(info)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(resolved)
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveWithCallback(info: NsdServiceInfo): NsdServiceInfo? {
        var registered: NsdManager.ServiceInfoCallback? = null
        try {
            return suspendCancellableCoroutine { cont ->
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                        if (resolved.hostAddresses.isNotEmpty() && cont.isActive) cont.resume(resolved)
                    }

                    override fun onServiceLost() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                registered = callback
                nsd.registerServiceInfoCallback(info, executor, callback)
            }
        } finally {
            // One answer is enough; stop following the service's updates.
            registered?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    private fun toDiscovered(info: NsdServiceInfo): DiscoveredServer? {
        val address = addresses(info).sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull() ?: return null
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
        return discoveredServer(info.serviceName, address, info.port, txt)
    }

    @Suppress("DEPRECATION")
    private fun addresses(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)

    companion object {
        private const val TAG = "WatchMyMedia"
        const val SERVICE_TYPE = "_watchmymedia._tcp"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
        private val executor = Executors.newSingleThreadExecutor()

        /** A resolved service as the server picker lists it. */
        fun discoveredServer(serviceName: String, address: InetAddress, port: Int, txt: Map<String, String?>): DiscoveredServer {
            val host = address.hostAddress.orEmpty().substringBefore('%').let { if (address is Inet4Address) it else "[$it]" }
            val path = txt["path"].orEmpty().trim('/').let { if (it.isEmpty()) "" else "/$it" }
            return DiscoveredServer(
                id = txt["id"]?.takeIf { it.isNotBlank() } ?: serviceName,
                name = serviceName,
                baseUrl = "http://$host:$port$path",
                version = txt["version"].orEmpty(),
                apiLevel = txt["api"]?.toIntOrNull() ?: 0,
            )
        }
    }
}
