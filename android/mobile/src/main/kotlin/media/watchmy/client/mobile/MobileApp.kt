package media.watchmy.client.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.navigation.MobileNavHost
import media.watchmy.client.mobile.servers.ServerAddedScreen
import media.watchmy.client.mobile.servers.ServerPickerScreen
import media.watchmy.client.mobile.servers.SwitchedSignIn
import media.watchmy.client.model.AddServerStep
import media.watchmy.client.presentation.servers.ServerSwitchViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.mobile.signin.SignInScreen
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.watchNextAccount
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.SessionViewModels
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.rememberTabletLayout

/**
 * The phone app: the server picker until a server is chosen, sign-in until
 * someone is signed in, then the signed-in screens. [deepLink] is a
 * watchmymedia:// link the app was opened with; it waits through the
 * picker and sign-in and opens once someone is signed in.
 * [deepLinkKey] changes with every link, so the same link opened twice
 * is handled twice.
 *
 * The signed-in screens are rebuilt for each server, so what follows a
 * change of server is kept here, above them ([ServerSwitchViewModel]): the
 * note that says which server the app is on now, Add a server's steps, the
 * way back from a sign-in the app switched to, and a search result or link
 * that belongs to another server signed in on this device.
 */
@Composable
fun MobileApp(deepLink: String? = null, deepLinkKey: Any? = deepLink) {
    val services = LocalAppServices.current
    val server by services.servers.current.collectAsStateWithLifecycle()
    val user by services.auth.user.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DeepLink?>(null) }
    val switcher = viewModel { ServerSwitchViewModel(services.servers) }
    val moves by switcher.state.collectAsStateWithLifecycle()
    // Only a launcher's link for who is signed in plays; this phone publishes none, so a play link opens its page.
    // One for another account signed in on this phone switches to its server first.
    LaunchedEffect(deepLink, deepLinkKey) {
        if (deepLink == null || !switcher.arrive(deepLink)) return@LaunchedEffect
        DeepLink.open(deepLink, watchNextAccount(services.servers.current.value?.server, services.auth.user.value))?.let { pending = it }
    }
    val layout = rememberTabletLayout()
    LightSystemBarIcons()

    WatchMyMediaPhoneTheme {
        Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
            val current = server
            when {
                // Add a server's first step, over whatever the app was showing.
                moves.adding == AddServerStep.Choose -> ServerPickerScreen(onServerChosen = {}, onBack = switcher::doneAdding, adding = true)
                // A sign-in the app switched to: Add a server's second step, or a server whose sign-in ended. Close goes back.
                current != null && user == null && moves.returnTo != null -> key(current.server.baseUrl) {
                    SwitchedSignIn(moves.adding?.takeIf { it == AddServerStep.SignIn }, onCancel = switcher::cancelSignIn)
                }
                layout.twoPane && user == null -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(480.dp)) {
                        ServerPickerScreen(onServerChosen = { picking = false })
                    }
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (current != null) key(current.server.baseUrl) {
                            SignInScreen(
                                onSwitchServer = { picking = true },
                                showServerChange = false,
                                showBrand = false,
                                linkTv = pending as? DeepLink.LinkTv,
                                onLinkTv = { if (pending !is DeepLink.LinkTv) pending = DeepLink.LinkTv(null) },
                            )
                        } else {
                            Text("Choose a server to sign in", color = WmmColors.TextMuted, modifier = Modifier.padding(24.dp))
                        }
                    }
                }
                current == null -> ServerPickerScreen(onServerChosen = { picking = false })
                user == null && picking -> ServerPickerScreen(onServerChosen = { picking = false }, onBack = { picking = false })
                user == null -> SignInScreen(
                    onSwitchServer = { picking = true },
                    linkTv = pending as? DeepLink.LinkTv,
                    onLinkTv = { if (pending !is DeepLink.LinkTv) pending = DeepLink.LinkTv(null) },
                )
                else -> {
                    // A different server or account starts the signed-in screens afresh, with ViewModels of its own, releasing its player.
                    key(current.server.id, user) {
                        SessionViewModels {
                            MobileNavHost(
                                onAddServer = switcher::startAdding,
                                onSignedOut = { pending = null },
                                deepLink = pending,
                                onDeepLinkHandled = { pending = null },
                                landing = moves.landing,
                                onLandingShown = switcher::landingShown,
                                onOpenElsewhere = switcher::openFound,
                            )
                        }
                    }
                    // Add a server's last step, over the new server's Home.
                    moves.added?.takeIf { moves.adding == AddServerStep.Added }?.let { added ->
                        Surface(Modifier.fillMaxSize(), color = WmmColors.Ground) { ServerAddedScreen(added, onDone = switcher::doneAdding) }
                    }
                }
            }
            // "Now on Cabin server", over the bottom bar, gone by itself.
            moves.note?.let { note ->
                SwitchNote(
                    note,
                    onDismiss = switcher::noteShown,
                    // Across a phone, over its bottom bar; a centred strip on a tablet.
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = if (layout.isTablet) 24.dp else 96.dp)
                        .then(if (layout.isTablet) Modifier.widthIn(min = 344.dp, max = 560.dp) else Modifier.fillMaxWidth()),
                )
            }
        }
    }
}

/** The note after a switch, as the boards' snackbar: light on the dark app, read out when it comes, and put away by a tap. */
@Composable
private fun SwitchNote(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Ground),
        modifier = modifier.shadow(12.dp, shape).clip(shape).background(NoteGround)
            .clickable(onClickLabel = "Put away", onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private val NoteGround = Color(0xFFE5E5E5)

/** The phone UI is always dark, so the status and navigation bar icons are always light. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null && !view.isInEditMode) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
