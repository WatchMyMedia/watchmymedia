package media.watchmy.client.tv.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import media.watchmy.client.model.displayAddress
import media.watchmy.client.tv.components.FocusGrowth
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.KeyboardField
import media.watchmy.client.tv.components.OnScreenKeyboard
import media.watchmy.client.tv.components.QrCode
import media.watchmy.client.tv.components.SafeX
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.inter
import media.watchmy.client.tv.components.radialGlow
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BrandLockup
import media.watchmy.client.presentation.signin.SignInViewModel
import media.watchmy.client.presentation.signin.countdownLabel
import media.watchmy.client.model.PairingState
import media.watchmy.client.ui.rememberModelTextField

/**
 * Sign in on the TV: "Use your phone to sign in", with two numbered steps,
 * the code and the square to scan, renewed when it runs out; or a name and
 * password typed on the on-screen keyboard. The server's address, its
 * version and the TV's model stay off the screen: what the phone will call
 * this TV is what helps.
 */
/**
 * [cancel]: the TV switched to this server for its sign-in (one being added,
 * or one whose sign-in ended), so [onSwitchServer] is Cancel, which Back is
 * too: back on the server it was on.
 */
@Composable
fun SignInScreen(onSwitchServer: () -> Unit, cancel: Boolean = false) {
    val services = LocalAppServices.current
    // Its own for each visit, not the Activity's: after a sign-out or another server, a new code for the server in use.
    val vm = viewModel(rememberViewModelStoreOwner()) { SignInViewModel(services.auth, services.servers, codeFirst = true) }
    val server by vm.server.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val passwordMode = !state.codeSignIn
    val serverName = server?.server?.name ?: "your server"

    Box(
        Modifier
            .fillMaxSize()
            .background(WmmColors.Ground)
            .radialGlow(WmmColors.Brand.copy(alpha = 0.42f), 0.86f, 0.14f, 1000.dp, 640.dp)
            .radialGlow(WmmColors.Accent.copy(alpha = 0.07f), 0.06f, 0.96f, 760.dp, 520.dp),
    ) {
        BrandLockup(markSize = 36.dp, fontSize = 24.sp, modifier = Modifier.padding(start = SafeX, top = SafeY))
        if (passwordMode) {
            BackHandler(onBack = vm::useCode)
            PasswordSignIn(vm = vm, state = state, serverName = serverName, onUseCode = vm::useCode)
        } else {
            PairingSignIn(
                vm = vm,
                state = state,
                serverName = serverName,
                onPassword = vm::usePassword,
                onSwitchServer = onSwitchServer,
                // A server that wants a browser check first refuses password sign-in from apps.
                passwordAllowed = server?.server?.turnstile != true,
                switchLabel = if (cancel) "Cancel" else "Choose another server",
                deviceName = deviceName,
            )
            if (cancel) BackHandler(onBack = onSwitchServer)
        }
    }
}

@Composable
private fun PairingSignIn(
    vm: SignInViewModel,
    state: SignInViewModel.State,
    serverName: String,
    onPassword: () -> Unit,
    onSwitchServer: () -> Unit,
    passwordAllowed: Boolean = true,
    switchLabel: String = "Choose another server",
    deviceName: String = "",
) {
    val pairing = state.pairing ?: PairingState.Starting
    when (pairing) {
        is PairingState.Failed -> {
            // Focus follows the failure: Try again is the first button and takes it.
            val retry = remember { FocusRequester() }
            SignInMessage(serverName, "Can’t get a code from $serverName", "${pairing.message.trimEnd('.')}. Nothing was signed in.") {
                TvButton("Try again", vm::newCode, Modifier.focusRequester(retry), style = TvButtonStyle.Primary, growFrom = TransformOrigin(0f, 0.5f))
                TvButton(switchLabel, onSwitchServer)
                if (passwordAllowed) TvButton("Type a password instead", onPassword)
            }
            InitialFocus(retry)
            return
        }
        is PairingState.Approved -> {
            SignInMessage(serverName, "Signed in as ${pairing.user.name}", "Getting your rows from $serverName…") {}
            return
        }
        else -> Unit
    }
    val first = remember { FocusRequester() }
    val waiting = (pairing as? PairingState.Waiting)?.code
    Column(Modifier.padding(start = SafeX, top = 182.dp).width(1040.dp)) {
        Kicker("Sign in to $serverName")
        Text("Use your phone to sign in", modifier = Modifier.padding(top = 14.dp), style = TvType.Display, color = WmmColors.Text)
        Column(Modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Step(1) { append("Point your phone’s camera at the square on the right.") }
            Step(2) {
                append("Or open ")
                withStyle(SpanStyle(color = WmmColors.Text, fontWeight = FontWeight.SemiBold)) { append(waiting?.let { displayAddress(it.linkUrl) } ?: "the link page") }
                append(" on any phone or computer and type this code:")
            }
        }
        Column(Modifier.padding(start = 64.dp)) {
            CodeBoxes(waiting?.code, Modifier.padding(top = 28.dp))
            StatusLine(pairing, state.codeRemainingMs, Modifier.padding(top = 30.dp))
            Row(Modifier.padding(top = 44.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (passwordAllowed) TvButton("Type a password instead", onPassword, Modifier.focusRequester(first), growFrom = TransformOrigin(0f, 0.5f))
                TvButton(switchLabel, onSwitchServer, if (passwordAllowed) Modifier else Modifier.focusRequester(first), growFrom = if (passwordAllowed) TransformOrigin.Center else TransformOrigin(0f, 0.5f))
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(end = SafeX, top = 292.dp), contentAlignment = Alignment.TopEnd) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(
                Modifier.size(400.dp).background(Color.White, RoundedCornerShape(24.dp)).padding(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (waiting != null) {
                    QrCode(waiting.linkUrl, contentDescription = "A code to scan that opens the link page", modifier = Modifier.fillMaxSize())
                }
            }
            Text("Scan to open the link page", style = TvType.Second, color = WmmColors.TextSoft)
        }
    }
    if (deviceName.isNotBlank()) {
        Box(Modifier.fillMaxSize().padding(start = SafeX, bottom = SafeY), contentAlignment = Alignment.BottomStart) {
            Text("Your phone will show this TV as “$deviceName”, so you can check it’s yours.", style = TvType.Second, color = TvPalette.Muted)
        }
    }
    InitialFocus(first)
}

/** A numbered step: the number in a round chip, then its words at 28. */
@Composable
private fun Step(n: Int, words: AnnotatedString.Builder.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(44.dp).background(WmmColors.Surface3, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = TvType.Second.copy(fontWeight = FontWeight.Bold), color = WmmColors.Text)
        }
        Text(buildAnnotatedString(words), style = TvType.CardTitle.copy(fontWeight = FontWeight.Normal, lineHeight = 36.sp), color = WmmColors.TextSoft, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A sign-in state that replaces the code: the server's name, what happened, why, and its buttons (the first takes focus). */
@Composable
private fun SignInMessage(serverName: String, title: String, body: String, buttons: @Composable RowScope.() -> Unit) {
    Column(Modifier.padding(start = SafeX, top = 354.dp).widthIn(max = 1400.dp)) {
        Kicker(serverName)
        Text(title, modifier = Modifier.padding(top = 14.dp), style = TvType.Display, color = WmmColors.Text)
        Text(body, modifier = Modifier.padding(top = 20.dp).widthIn(max = 860.dp), style = TvType.Body, color = WmmColors.TextSoft)
        Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), content = buttons)
    }
}

/** The code in two groups of three, a gap between them, as the link page asks for it. */
@Composable
private fun CodeBoxes(code: String?, modifier: Modifier = Modifier) {
    val chars = (code ?: "").padEnd(6).take(6)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Code: ${chars.trim().toList().joinToString(" ")}" }, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        CodeGroup(chars.substring(0, 3))
        CodeGroup(chars.substring(3))
    }
}

@Composable
private fun CodeGroup(chars: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (c in chars) {
            Box(
                Modifier
                    .size(96.dp, 120.dp)
                    .background(WmmColors.Surface, RoundedCornerShape(16.dp))
                    .border(2.dp, TvPalette.Line3, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(c.toString().trim(), style = inter(72.sp, FontWeight.Bold), color = WmmColors.Text)
            }
        }
    }
}

@Composable
private fun StatusLine(pairing: PairingState, remainingMs: Long, modifier: Modifier = Modifier) {
    val text = when (pairing) {
        PairingState.Starting -> "Getting a code…"
        PairingState.Expired -> "That code ran out. Getting a new one…"
        is PairingState.Waiting -> "Waiting for your phone. This TV signs in by itself, and the code lasts another ${countdownLabel(remainingMs)}."
        is PairingState.Approved -> "Code accepted. Signing in…"
        is PairingState.Failed -> pairing.message
    }
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(16.dp).background(WmmColors.Accent, CircleShape))
        Text(text, style = TvType.Second, color = WmmColors.TextSoft)
    }
}

private enum class Field { Name, Password }

@Composable
private fun PasswordSignIn(vm: SignInViewModel, state: SignInViewModel.State, serverName: String, onUseCode: () -> Unit) {
    val form = state
    // The fields hold their text, which the keyboard edits; the model hears it as it is typed.
    val nameField = rememberModelTextField(state.name, state.revision, vm::setName)
    val passwordField = rememberModelTextField(state.password, state.revision, vm::setPassword)
    val submit = {
        vm.setName(nameField.text.toString())
        vm.setPassword(passwordField.text.toString())
        vm.signIn()
    }
    var target by rememberSaveable { mutableStateOf(if (form.name.isBlank()) Field.Name else Field.Password) }
    val firstKey = remember { FocusRequester() }

    Column(Modifier.padding(start = SafeX, top = 182.dp).width(760.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Kicker("Sign in to $serverName")
        Text("Type your name and password", style = TvType.Title, color = WmmColors.Text)
        Text("The ones you sign in with on the web.", style = TvType.Body, color = WmmColors.TextSoft)
        Spacer(Modifier.height(4.dp))
        FormField("Name", nameField.text.toString(), masked = false, active = target == Field.Name) {
            target = Field.Name
            firstKey.requestFocus()
        }
        FormField("Password", passwordField.text.toString(), masked = true, active = target == Field.Password) {
            target = Field.Password
            firstKey.requestFocus()
        }
        Text(form.error ?: " ", style = TvType.Second, color = WmmColors.Warn)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TvButton(if (form.busy) "Signing in…" else "Sign in", submit, style = TvButtonStyle.Primary, enabled = !form.busy)
            TvButton("Use a code instead", onUseCode)
        }
    }

    Box(Modifier.fillMaxSize().padding(end = SafeX, top = 292.dp), contentAlignment = Alignment.TopEnd) {
        OnScreenKeyboard(
            field = if (target == Field.Name) nameField else passwordField,
            masked = target == Field.Password,
            onDone = { if (target == Field.Name) target = Field.Password else submit() },
            doneLabel = if (target == Field.Name) "Next" else "Sign in",
            firstKey = firstKey,
        )
    }
    InitialFocus(firstKey)
}

@Composable
private fun FormField(label: String, value: String, masked: Boolean, active: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = TvType.Second, color = TvPalette.Muted)
        FocusableBox(onClick = onClick, cornerRadius = 14.dp, focusedScale = FocusGrowth.ROW) {
            KeyboardField(value, placeholder = if (masked) "Password" else "Your name", masked = masked, active = active)
        }
    }
}
