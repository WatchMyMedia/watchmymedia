package media.watchmy.client.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.tv.live.CastLivePlayer
import media.watchmy.client.tv.navigation.TvNavHost
import media.watchmy.client.tv.servers.ServerPickerScreen
import media.watchmy.client.tv.signin.SignInScreen
import media.watchmy.client.tv.player.PlayerScreen
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors

/**
 * The TV app: the server picker until a server is chosen, sign-in until
 * someone is signed in, then the signed-in screens. [deepLink] is a
 * watchmymedia:// link the app was opened with (Watch Next); one that
 * arrives before sign-in opens once signed in.
 * [deepLinkKey] changes with every link, so the same link opened twice
 * is handled twice.
 */
@Composable
fun TvApp(deepLink: String? = null, deepLinkKey: Any? = deepLink) {
    val services = LocalAppServices.current
    val server by services.servers.current.collectAsStateWithLifecycle()
    val user by services.auth.user.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deepLink, deepLinkKey) { if (deepLink != null) pendingLink = deepLink }

    val cast by services.castReceiver.incoming.collectAsStateWithLifecycle()
    val castLive by services.castReceiver.incomingLive.collectAsStateWithLifecycle()

    TvTheme {
        Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
            // A play cast to this TV (Cast Connect) takes the screen, signed in or not: it plays as whoever cast it.
            cast?.let { play ->
                key(play.id) {
                    PlayerScreen(play.request, onExit = { services.castReceiver.finished(play) }, controller = play.controller)
                }
                return@Box
            }
            // A channel cast to this TV plays in the TV's own live player, as whoever cast it (PLAN-LIVE-TV.md §4.1).
            castLive?.let { live ->
                key(live.id) {
                    CastLivePlayer(live, onExit = { services.castReceiver.finished(live) })
                }
                return@Box
            }
            when {
                server == null || picking -> ServerPickerScreen(
                    onServerChosen = { picking = false },
                    onBack = if (server != null) ({ picking = false }) else null,
                )
                user == null -> SignInScreen(onSwitchServer = { picking = true })
                else -> key(server?.server?.id, user) {
                    TvNavHost(
                        onSwitchServer = { picking = true },
                        onSignedOut = {},
                        deepLink = pendingLink,
                        onDeepLinkHandled = { pendingLink = null },
                    )
                }
            }
        }
    }
}
