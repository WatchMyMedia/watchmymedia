package media.watchmy.client.core.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.StreamQuality

/** Whether the network in use is paid for by the byte (mobile data, a metered hotspot), for the On mobile data quality. */
fun interface MeteredNetwork {
    fun metered(): Boolean

    companion object {
        val Never = MeteredNetwork { false }
    }
}

/**
 * [MeteredNetwork] from the system: metered unless the active network says
 * it isn't (NET_CAPABILITY_NOT_METERED, which Wi-Fi and Ethernet usually
 * have). No network at all counts as not metered: nothing plays then anyway.
 */
class SystemMeteredNetwork(context: Context) : MeteredNetwork {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun metered(): Boolean {
        val cm = connectivity ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * The device's default quality on the network in use: the On mobile data
 * quality on a metered network when one is set, else the Default quality.
 * Read when a decision is asked for, so a change of network shows at the
 * next one rather than restarting what plays.
 */
internal fun PreferencesRepository.qualityOn(network: MeteredNetwork): StreamQuality =
    device.value.mobileQuality?.takeIf { network.metered() } ?: quality.value
