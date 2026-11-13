package media.watchmy.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import media.watchmy.client.mobile.MobileApp
import media.watchmy.client.tv.TvApp
import media.watchmy.client.ui.ProvideAppServices

class MainActivity : ComponentActivity() {
    /** A cast to this TV to Cast Connect (the TV UI then opens the player from AppServices.castReceiver); a link to the UI. */
    private val intents = IntentRouter { intent -> (application as WatchMyMediaApp).castConnect?.onNewIntent(intent) == true }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super: the launch screen stays until the first frame, so it goes straight to the page or its skeleton.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WatchMyMediaApp
        if (savedInstanceState == null) intents.receive(intent)
        setContent {
            // Test tags (TestTags) read as resource ids, for the baseline-profile journeys and benchmarks.
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                LocalNetworkGate(isTv = app.isTv, question = app.localNetwork) {
                    ProvideAppServices(app.services) {
                        if (app.isTv) TvApp(intents.deepLink, deepLinkKey = intents.deepLinkCount) else MobileApp(intents.deepLink, deepLinkKey = intents.deepLinkCount)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intents.receive(intent)
    }
}
