package media.watchmy.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.tv.TvApp
import media.watchmy.client.ui.ProvideAppServices

class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<String?>(null)

    /** Bumped on every link, so the same link opened twice is handled twice. */
    private var deepLinkCount by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super: the launch screen stays until the first frame, so it goes straight to the page or its skeleton.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WatchMyMediaApp
        if (savedInstanceState == null) receive(intent)
        setContent {
            LocalNetworkGate(isTv = app.isTv, question = app.localNetwork) {
                ProvideAppServices(app.services) {
                    if (app.isTv) TvApp(deepLink, deepLinkKey = deepLinkCount) else MobileApp(deepLink, deepLinkKey = deepLinkCount)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        // A cast to this TV (Cast Connect): the TV UI opens the player from AppServices.castReceiver.
        if ((application as WatchMyMediaApp).castConnect?.onNewIntent(intent) == true) return
        val link = intent?.dataString ?: return
        deepLink = link
        deepLinkCount++
    }
}
