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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.model.AddServerStep
import media.watchmy.client.presentation.servers.ServerSwitchViewModel
import media.watchmy.client.tv.servers.SwitchBanner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.tv.live.CastLivePlayer
import media.watchmy.client.tv.navigation.TvNavHost
import media.watchmy.client.tv.servers.ServerPickerScreen
import media.watchmy.client.tv.signin.SignInScreen
import media.watchmy.client.tv.player.PlayerScreen
import media.watchmy.client.ui.SessionViewModels
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TestTags
import media.watchmy.client.ui.WmmColors

/**
 * The TV app: the server picker until a server is chosen, sign-in until
 * someone is signed in, then the signed-in screens. [deepLink] is a
 * watchmymedia:// link the app was opened with (Watch Next); one that
 * arrives before sign-in opens once signed in.
 * [deepLinkKey] changes with every link, so the same link opened twice
 * is handled twice.
 *
 * The signed-in screens are rebuilt for each server, so what follows a
 * change of server is kept here, above them ([ServerSwitchViewModel]): the
 * banner that says which server the TV is on now, Add a server, the way
 * back from a sign-in the TV switched to, and a search result or link that
 * belongs to another server this TV is signed in to.
 */
@Composable
fun TvApp(deepLink: String? = null, deepLinkKey: Any? = deepLink) {
    val services = LocalAppServices.current
    val server by services.servers.current.collectAsStateWithLifecycle()
    val user by services.auth.user.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    // A TV has no last step for Add a server: the pairing code, then the new server's Home with the banner.
    val switcher = viewModel { ServerSwitchViewModel(services.servers, addedStep = false) }
    val moves by switcher.state.collectAsStateWithLifecycle()
    // A link for another account signed in on this TV (its Watch Next row) switches to its server first.
    LaunchedEffect(deepLink, deepLinkKey) { if (deepLink != null && switcher.arrive(deepLink)) pendingLink = deepLink }

    val cast by services.castReceiver.incoming.collectAsStateWithLifecycle()
    val castLive by services.castReceiver.incomingLive.collectAsStateWithLifecycle()

    TvTheme {
        Box(Modifier.fillMaxSize().background(WmmColors.Ground).testTag(TestTags.TV)) {
            // A play cast to this TV (Cast Connect) takes the screen, signed in or not: it plays as whoever cast it.
            cast?.let { play ->
                key(play.id) {
                    PlayerScreen(play.request, onExit = { services.castReceiver.finished(play) }, controller = play.controller)
                }
                return@Box
            }
            // A channel cast to this TV plays in the TV's own live player, as whoever cast it.
            castLive?.let { live ->
                key(live.id) {
                    CastLivePlayer(live, onExit = { services.castReceiver.finished(live) })
                }
                return@Box
            }
            when {
                // Add a server's first step: the servers this TV isn't signed in to yet.
                moves.adding == AddServerStep.Choose -> ServerPickerScreen(onServerChosen = {}, onBack = switcher::doneAdding, adding = true)
                server == null || picking -> ServerPickerScreen(
                    onServerChosen = { picking = false },
                    onBack = if (server != null) ({ picking = false }) else null,
                )
                // A sign-in the TV switched to (a server being added, or one whose sign-in ended): Cancel goes back.
                user == null && moves.returnTo != null -> key(server?.server?.id) { SignInScreen(onSwitchServer = switcher::cancelSignIn, cancel = true) }
                user == null -> SignInScreen(onSwitchServer = { picking = true })
                // Each sign-in's screens keep their ViewModels to themselves, cleared when it ends.
                else -> key(server?.server?.id, user) {
                    SessionViewModels {
                        TvNavHost(
                            onAddServer = switcher::startAdding,
                            onSignedOut = {},
                            deepLink = pendingLink,
                            onDeepLinkHandled = { pendingLink = null },
                            landing = moves.landingFor(server?.server?.id),
                            onLandingShown = switcher::landingShown,
                            onOpenElsewhere = switcher::openFound,
                        )
                    }
                }
            }
            // "Now on Cabin server", at the top right: it takes no focus and goes by itself.
            moves.note?.let { SwitchBanner(it, Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 58.dp)) }
        }
    }
}
