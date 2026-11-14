package media.watchmy.client.core.playback

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.StreamQuality

/**
 * The quality a player asks for, the same for files and Live TV: the
 * viewer's pick in this player once there is one, else the device's default
 * for the network in use ([qualityOn]), read at each request so a change of
 * network shows at the next one.
 */
internal class QualityChoice(private val preferences: PreferencesRepository, private val network: MeteredNetwork) {
    private var pick: StreamQuality? = null

    /** The viewer picked a quality in this player. */
    val picked: Boolean get() = pick != null

    /** What the next request asks for. */
    fun current(): StreamQuality = pick ?: preferences.qualityOn(network)

    /**
     * The viewer picked [quality]: it holds for this player whatever the
     * network does, and becomes the device's default, saved on [scope]: the
     * On mobile data quality when that is the one in force, else the
     * Default quality.
     */
    fun pick(quality: StreamQuality, scope: CoroutineScope) {
        pick = quality
        val mobile = preferences.device.value.mobileQuality != null && network.metered()
        scope.launch {
            try {
                if (mobile) preferences.setMobileQuality(quality) else preferences.setQuality(quality)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the quality", e)
            }
        }
    }

    private companion object {
        const val TAG = "WmmPlayback"
    }
}
