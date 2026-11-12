package media.watchmy.client.tv.signin

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.versionLabel
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.KeyboardField
import media.watchmy.client.tv.components.OnScreenKeyboard
import media.watchmy.client.tv.components.QrCode
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.radialGlow
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BrandLockup

/**
 * Sign in on the TV (mockup: design/mockups/android-tv/Main.dc.html): a
 * pairing code and QR code for the link page, renewed when it runs out, or a
 * name and password typed on the on-screen keyboard.
 */
@Composable
fun SignInScreen(onSwitchServer: () -> Unit) {
    val services = LocalAppServices.current
    // Its own for each visit, not the Activity's: after a sign-out or another server, a new code for the server in use.
    val vm = viewModel(rememberViewModelStoreOwner()) { SignInViewModel(services.auth, services.servers) }
    val server by vm.server.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    var passwordMode by rememberSaveable { mutableStateOf(false) }
    val serverName = server?.server?.name ?: "your server"

    Box(
        Modifier
            .fillMaxSize()
            .background(WmmColors.Ground)
            .radialGlow(WmmColors.Brand.copy(alpha = 0.42f), 0.86f, 0.14f, 1000.dp, 640.dp)
            .radialGlow(WmmColors.Accent.copy(alpha = 0.07f), 0.06f, 0.96f, 760.dp, 520.dp),
    ) {
        BrandLockup(markSize = 44.dp, fontSize = 26.sp, modifier = Modifier.padding(start = 58.dp, top = 48.dp))
        if (passwordMode) {
            BackHandler {
                passwordMode = false
                vm.startPairing()
            }
            PasswordSignIn(
                vm = vm,
                serverName = serverName,
                onUseCode = {
                    passwordMode = false
                    vm.startPairing()
                },
            )
        } else {
            PairingSignIn(
                vm = vm,
                serverName = serverName,
                onPassword = {
                    vm.stopPairing()
                    passwordMode = true
                },
                onSwitchServer = onSwitchServer,
                // A server that wants a browser check first refuses password sign-in from apps.
                passwordAllowed = server?.server?.turnstile != true,
            )
        }
        Footer(server, deviceName, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PairingSignIn(vm: SignInViewModel, serverName: String, onPassword: () -> Unit, onSwitchServer: () -> Unit, passwordAllowed: Boolean = true) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val waiting = pairing as? SignInViewModel.Pairing.Waiting

    Column(Modifier.padding(start = 160.dp, top = 236.dp).width(960.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Kicker("Sign in")
        Text("Link this TV to\n$serverName", style = inter(60.sp, FontWeight.Bold, lineHeight = 65.sp, tracking = -0.02f), color = WmmColors.Text)
        Text(
            buildAnnotatedString {
                append("On your phone or computer, open ")
                withStyle(SpanStyle(color = WmmColors.Text, fontWeight = FontWeight.SemiBold)) { append(waiting?.linkLabel ?: "the link page") }
                append(" and enter this code.")
            },
            style = inter(26.sp, lineHeight = 38.sp),
            color = WmmColors.TextSoft,
            modifier = Modifier.widthIn(max = 820.dp),
        )
        CodeBoxes(waiting?.code, Modifier.padding(top = 6.dp))
        StatusLine(pairing)
        Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (pairing is SignInViewModel.Pairing.Failed) {
                TvButton("Try again", vm::startPairing, style = TvButtonStyle.Primary)
            }
            if (passwordAllowed) TvButton("Sign in with a password instead", onPassword, Modifier.focusRequester(first))
            TvButton("Switch server", onSwitchServer, if (passwordAllowed) Modifier else Modifier.focusRequester(first))
        }
    }

    Box(Modifier.fillMaxSize().padding(end = 200.dp, top = 258.dp), contentAlignment = Alignment.TopEnd) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(
                Modifier.size(392.dp).background(Color.White, RoundedCornerShape(22.dp)).padding(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (waiting != null) {
                    QrCode(waiting.linkUrl, contentDescription = "QR code for the link page", modifier = Modifier.fillMaxSize())
                }
            }
            Text("Or scan to open the link page", style = inter(20.sp), color = WmmColors.TextMuted)
        }
    }
    InitialFocus(first)
}

/** The code in two groups of three with a dash between, as the link page asks for it. */
@Composable
private fun CodeBoxes(code: String?, modifier: Modifier = Modifier) {
    val chars = (code ?: "").padEnd(6).take(6)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        CodeGroup(chars.substring(0, 3))
        Box(Modifier.size(22.dp, 6.dp).background(WmmColors.Line3, RoundedCornerShape(3.dp)))
        CodeGroup(chars.substring(3))
    }
}

@Composable
private fun CodeGroup(chars: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (c in chars) {
            Box(
                Modifier
                    .size(92.dp, 116.dp)
                    .background(WmmColors.Surface2, RoundedCornerShape(16.dp))
                    .border(1.dp, WmmColors.Line2, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(c.toString().trim(), style = inter(72.sp, FontWeight.ExtraBold), color = WmmColors.Text)
            }
        }
    }
}

@Composable
private fun StatusLine(pairing: SignInViewModel.Pairing) {
    val (text, color) = when (pairing) {
        SignInViewModel.Pairing.Starting -> "Getting a code…" to WmmColors.TextMuted
        is SignInViewModel.Pairing.Waiting ->
            "Waiting for the code · refreshes in ${countdownLabel(pairing.remainingMs)} · this TV signs in by itself once it is entered" to WmmColors.TextMuted
        SignInViewModel.Pairing.Approved -> "Code accepted. Signing in…" to WmmColors.Accent
        is SignInViewModel.Pairing.Failed -> pairing.message to WmmColors.Error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).background(if (pairing is SignInViewModel.Pairing.Failed) WmmColors.Error else WmmColors.Accent, CircleShape))
        Text(text, style = inter(20.sp), color = color)
    }
}

private enum class Field { Name, Password }

@Composable
private fun PasswordSignIn(vm: SignInViewModel, serverName: String, onUseCode: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    var target by rememberSaveable { mutableStateOf(if (form.name.isBlank()) Field.Name else Field.Password) }
    val firstKey = remember { FocusRequester() }

    Column(Modifier.padding(start = 160.dp, top = 200.dp).width(760.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Kicker("Sign in")
        Text("Sign in to\n$serverName", style = inter(60.sp, FontWeight.Bold, lineHeight = 65.sp, tracking = -0.02f), color = WmmColors.Text)
        Text("Use the name and password you sign in with on the web app.", style = inter(24.sp, lineHeight = 34.sp), color = WmmColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        FormField("Name", form.name, masked = false, active = target == Field.Name) {
            target = Field.Name
            firstKey.requestFocus()
        }
        FormField("Password", form.password, masked = true, active = target == Field.Password) {
            target = Field.Password
            firstKey.requestFocus()
        }
        Text(form.error ?: " ", style = inter(20.sp), color = WmmColors.Error)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TvButton(if (form.busy) "Signing in…" else "Sign in", vm::signIn, style = TvButtonStyle.Primary, enabled = !form.busy)
            TvButton("Use a code instead", onUseCode)
        }
    }

    Box(Modifier.fillMaxSize().padding(end = 160.dp, top = 330.dp), contentAlignment = Alignment.TopEnd) {
        OnScreenKeyboard(
            onText = { t -> if (target == Field.Name) vm.setName(form.name + t) else vm.setPassword(form.password + t) },
            onBackspace = { if (target == Field.Name) vm.setName(form.name.dropLast(1)) else vm.setPassword(form.password.dropLast(1)) },
            onDone = { if (target == Field.Name) target = Field.Password else vm.signIn() },
            doneLabel = if (target == Field.Name) "Next" else "Sign in",
            firstKey = firstKey,
        )
    }
    InitialFocus(firstKey)
}

@Composable
private fun FormField(label: String, value: String, masked: Boolean, active: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = inter(20.sp), color = WmmColors.TextMuted)
        FocusableBox(onClick = onClick, cornerRadius = 14.dp, focusedScale = 1.02f) {
            KeyboardField(value, placeholder = if (masked) "Password" else "Your name", masked = masked, active = active)
        }
    }
}

@Composable
private fun Footer(server: SavedServer?, deviceName: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 58.dp, end = 58.dp, bottom = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val s = server?.server
        Text(
            if (s != null) "${s.name} · ${displayAddress(s.baseUrl)} · ${versionLabel(s.version)}" else "",
            style = inter(18.sp),
            color = WmmColors.TextFaint,
        )
        Text("This device: $deviceName · ${Build.MODEL}", style = inter(18.sp), color = WmmColors.TextFaint)
    }
}
