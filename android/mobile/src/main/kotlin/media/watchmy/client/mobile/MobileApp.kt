package media.watchmy.client.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import media.watchmy.client.mobile.navigation.MobileNavHost
import media.watchmy.client.mobile.servers.ServerPickerScreen
import media.watchmy.client.mobile.signin.SignInScreen
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.watchNextAccount
import media.watchmy.client.ui.LocalAppServices
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
 */
@Composable
fun MobileApp(deepLink: String? = null, deepLinkKey: Any? = deepLink) {
    val services = LocalAppServices.current
    val server by services.servers.current.collectAsStateWithLifecycle()
    val user by services.auth.user.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DeepLink?>(null) }
    // Only a launcher's link for who is signed in plays; this phone publishes none, so a play link opens its page.
    LaunchedEffect(deepLink, deepLinkKey) {
        DeepLink.open(deepLink, watchNextAccount(services.servers.current.value?.server, services.auth.user.value))?.let { pending = it }
    }
    val layout = rememberTabletLayout()
    LightSystemBarIcons()

    WatchMyMediaPhoneTheme {
        Box(Modifier.fillMaxSize().background(WmmColors.Ground)) {
            val current = server
            when {
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
                    // A different server or account starts the signed-in screens afresh, releasing its player.
                    key(current.server.id, user) {
                        MobileNavHost(
                            onSwitchServer = { picking = true },
                            onSignedOut = { pending = null },
                            deepLink = pending,
                            onDeepLinkHandled = { pending = null },
                        )
                    }
                    if (picking) {
                        Surface(Modifier.fillMaxSize(), color = WmmColors.Ground) {
                            ServerPickerScreen(onServerChosen = { picking = false }, onBack = { picking = false })
                        }
                    }
                }
            }
        }
    }
}

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
