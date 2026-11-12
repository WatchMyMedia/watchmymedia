package media.watchmy.client.core.playback

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Mobile data, as the system tells it: metered unless the active network says it isn't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemMeteredNetworkTest {
    private val context = RuntimeEnvironment.getApplication()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun active(vararg capabilities: Int) {
        val caps = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, caps)
    }

    @Test
    fun `Wi-Fi is not metered, mobile data and a metered hotspot are`() {
        val network = SystemMeteredNetwork(context)
        active(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        assertFalse(network.metered())
        active(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertTrue(network.metered())
    }
}
