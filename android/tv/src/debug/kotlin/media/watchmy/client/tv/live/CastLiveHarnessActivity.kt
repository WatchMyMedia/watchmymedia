package media.watchmy.client.tv.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeLivePlaybackController
import media.watchmy.client.model.CastedLive
import media.watchmy.client.model.LiveCastSender
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.ProvideAppServices
import media.watchmy.client.ui.WmmColors

/**
 * Debug builds only: a channel cast to this TV (Cast Connect, the
 * LiveCastConnect board) on the fakes, as [CastLivePlayer] shows it once
 * Cast Connect has opened it. From android/:
 *
 * ```
 * ./gradlew :app:installDebug -PforceTv=true -PappIdSuffix=.tvcast
 * adb shell am start -n media.watchmy.client.tvcast/media.watchmy.client.tv.live.CastLiveHarnessActivity --es scene playing
 * ```
 *
 * `scene`: playing; paused (Pixel 9 paused it); still (Still watching after
 * 4 hours); busy (all tuners in use); stopped (Pixel 9 stops casting after 6 s).
 */
class CastLiveHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val scene = intent.getStringExtra("scene") ?: "playing"
        val services = FakeAppServices(signedIn = true, tv = true)
        var offset = 0L
        val controller = FakeLivePlaybackController(clock = { System.currentTimeMillis() + offset }).apply {
            if (scene == "busy") nextFailure = "tuners_busy"
        }
        val sender = MutableStateFlow(LiveCastSender("Pixel 9"))
        setContent {
            ProvideAppServices(services) {
                TvTheme {
                    Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
                        var live by remember { mutableStateOf<CastedLive?>(null) }
                        LaunchedEffect(Unit) {
                            val channels = services.live.channels()
                            val target = channels.firstOrNull { it.number == "4.1" } ?: channels.first()
                            live = CastedLive(1, LiveTarget(target), controller, services.live, MutableStateFlow<List<LiveChannel>>(channels).asStateFlow(), listings = true, sender = sender.asStateFlow())
                            delay(2_000)
                            when (scene) {
                                "paused" -> {
                                    sender.value = sender.value.copy(pausedThere = true)
                                    controller.pause()
                                }
                                "still" -> {
                                    offset = 4 * 60 * 60_000L
                                    controller.refreshClock()
                                }
                                "stopped" -> {
                                    delay(4_000)
                                    controller.stop()
                                    sender.value = sender.value.copy(stopped = true)
                                }
                            }
                        }
                        live?.let { CastLivePlayer(it, onExit = { finish() }) }
                    }
                }
            }
        }
    }
}
