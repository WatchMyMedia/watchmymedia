package media.watchmy.client.tv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackControllerFactory
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.ProvideAppServices
import media.watchmy.client.ui.WmmColors

/**
 * Debug builds only: the TV player on the fakes, without going through the
 * browse screens. From android/:
 *
 * ```
 * ./gradlew :app:installDebug -PforceTv=true -PappIdSuffix=.tvplayer
 * adb shell am start -n media.watchmy.client.tvplayer/media.watchmy.client.tv.player.PlayerHarnessActivity \
 *     --el startMs 57000 --es scene plain --ef speed 1
 * ```
 *
 * - `startMs`: where to start. The fake starts at 0:50, ten seconds before
 *   its intro (Skip Intro shows 1:00–1:42); 2968000 is two seconds before the
 *   credits and the Next Episode card.
 * - `scene`: what else happens (HarnessScene): plain, autoskip, still,
 *   stopped, error, banner, auto, autorecovery.
 * - `speed`: how fast the fake's clock runs.
 * - `autoDelayMs`: how long autoskip's fill runs (3000, as the real one).
 */
class PlayerHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val scene = HarnessScene.from(intent.getStringExtra("scene"))
        val speed = intent.getFloatExtra("speed", 1f)
        val startMs = intent.getLongExtra("startMs", -1L).takeIf { it >= 0 }
        val autoDelayMs = intent.getLongExtra("autoDelayMs", 3_000L)
        val services = HarnessServices(scene, speed, autoDelayMs)
        val request = PlayRequest(fileId = 9101, startMs = startMs, title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark")
        setContent {
            ProvideAppServices(services) {
                TvTheme {
                    Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
                        PlayerScreen(request, onExit = { finish() })
                    }
                }
            }
        }
    }
}

private class HarnessServices(
    scene: HarnessScene,
    speed: Float,
    autoDelayMs: Long,
    base: AppServices = FakeAppServices(signedIn = true, tv = true),
) : AppServices by base {
    override val playback = PlaybackControllerFactory { HarnessPlaybackController(FakePlaybackController(speed = speed), scene, autoDelayMs) }
}
