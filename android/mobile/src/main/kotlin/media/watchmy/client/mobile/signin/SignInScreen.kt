package media.watchmy.client.mobile.signin

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.FieldText
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.mobile.components.panel
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.MIN_SERVER_VERSION
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.displayAddress
import media.watchmy.client.model.versionLabel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.BrandMark
import media.watchmy.client.ui.components.WmmIcons

/**
 * Password sign-in (Main.dc.html). Linking a TV needs a signed-in phone, so
 * the Link a TV row (or a watchmymedia://link deep link, [linkTv]) marks the
 * sign-in as one that goes on to Link a TV; [onLinkTv] records that.
 */
@Composable
fun SignInScreen(onSwitchServer: () -> Unit, linkTv: DeepLink.LinkTv? = null, onLinkTv: () -> Unit = {}, showServerChange: Boolean = true, showBrand: Boolean = true) {
    val services = LocalAppServices.current
    val vm = viewModel { SignInViewModel(services.auth, services.servers) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val nameFocus = remember { FocusRequester() }
    // Back from the server's web page: see whether its first account is there now.
    LifecycleResumeEffect(state.setupRequired) {
        if (state.setupRequired) vm.checkSetup(quiet = true)
        onPauseOrDispose {}
    }

    Box(
        Modifier.fillMaxSize().background(WmmColors.Ground).drawBehind {
            drawRect(
                Brush.radialGradient(
                    0f to WmmColors.Brand.copy(alpha = 0.45f),
                    0.7f to Color.Transparent,
                    center = Offset(size.width * 0.9f, 0f),
                    radius = 340.dp.toPx(),
                ),
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column(
                Modifier.align(Alignment.TopCenter).widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 16.dp),
            ) {
                if (showBrand) {
                    Spacer(Modifier.height(72.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandMark(64.dp)
                        Text(
                            buildAnnotatedString {
                                append("WatchMy")
                                withStyle(SpanStyle(color = WmmColors.TextMuted, fontWeight = FontWeight.SemiBold)) { append("Media") }
                            },
                            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.52).sp, color = WmmColors.Text),
                        )
                    }
                    Spacer(Modifier.height(34.dp))
                } else {
                    Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Sign in", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.56).sp, color = WmmColors.Text))
                        Text(
                            when {
                                state.setupRequired -> "once this server has its first account"
                                state.codeSignIn -> "with a code, approved where you're signed in"
                                else -> "with your account on this server"
                            },
                            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.TextMuted),
                        )
                    }
                    if (state.setupRequired) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ServerCard(server, onSwitchServer.takeIf { showServerChange })
                            SetupPanel(server, state.checking, state.error, onCheck = { vm.checkSetup() })
                        }
                    } else if (state.codeSignIn) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ServerCard(server, onSwitchServer.takeIf { showServerChange })
                            CodePanel(state.pairing, vm::newCode)
                        }
                    } else {
                    // The fields keep 8 dp above their rim for the floating label, so 12 here reads as the mockup's 20.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ServerCard(server, onSwitchServer.takeIf { showServerChange }, warnNotEncrypted = true)
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = vm::setName,
                            label = { Text("Username") },
                            singleLine = true,
                            enabled = !state.busy,
                            textStyle = FieldText,
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).semantics { contentType = ContentType.Username },
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = vm::setPassword,
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !state.busy,
                            isError = state.error != null,
                            supportingText = state.error?.let { { Text(it) } },
                            textStyle = FieldText,
                            colors = fieldColors(),
                            visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.signIn() }),
                            trailingIcon = {
                                IconButton(onClick = vm::toggleShowPassword) {
                                    Icon(
                                        if (state.showPassword) PhoneIcons.EyeOff else WmmIcons.Eye,
                                        contentDescription = if (state.showPassword) "Hide password" else "Show password",
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                        )
                    }
                    FilledButton(
                        text = if (linkTv != null) "Sign in and link a TV" else "Sign in",
                        onClick = vm::signIn,
                        large = true,
                        busy = state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    }
                    // Linking a TV needs an account, which a server that isn't set up has none of.
                    if (!state.setupRequired) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(Modifier.weight(1f), color = WmmColors.Line)
                        Text("or", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextFaint))
                        HorizontalDivider(Modifier.weight(1f), color = WmmColors.Line)
                    }
                    LinkTvRow(
                        pending = linkTv,
                        onClick = {
                            onLinkTv()
                            when {
                                // Signed in by the code, the phone goes on to Link a TV.
                                state.codeSignIn -> Unit
                                state.canSubmit -> vm.signIn()
                                state.name.isBlank() -> nameFocus.requestFocus()
                            }
                        },
                    )
                    }
                }
                Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
                Text(
                    "Needs server $MIN_SERVER_VERSION or newer · ${Build.MODEL ?: "Android"} · app ${services.client.version}",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextFaint),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }
        }
    }
}

/**
 * The server being signed in to, with Change (the server picker). Over plain
 * http beyond this network, [warnNotEncrypted] adds that the password can be
 * read on the way (ServerNotEncrypted.dc.html).
 */
@Composable
private fun ServerCard(server: SavedServer?, onChange: (() -> Unit)?, warnNotEncrypted: Boolean = false) {
    val notEncrypted = server?.server?.notEncrypted == true
    Column(Modifier.fillMaxWidth().panel()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notEncrypted) IconTile(WmmIcons.Globe, background = WmmColors.Line2) else IconTile(WmmIcons.Server)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(server?.server?.name ?: "No server", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (server != null) {
                    Text(
                        "${displayAddress(server.server.baseUrl)} · ${versionLabel(server.server.version)}",
                        style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            onChange?.let { GoldTextButton("Change", it) }
        }
        if (warnNotEncrypted && notEncrypted) {
            HorizontalDivider(color = WmmColors.Line)
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(WmmIcons.LockOpen, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = WmmColors.Warn)) { append("Not encrypted.") }
                        append(" Your password and what you watch can be read on the way to this server.")
                    },
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft),
                )
            }
        }
    }
}

/**
 * Sign-in by code, for a server that wants a browser check before a password
 * sign-in: the code, and the link page to approve it from a browser that is
 * signed in (on this phone or anywhere else). The phone signs itself in once
 * it is approved.
 */
@Composable
private fun CodePanel(pairing: PairingState?, onNewCode: () -> Unit) {
    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "This server checks for a browser before a password sign-in, which the app can't show. Approve this $device with a code instead.",
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextMuted),
        )
        when (pairing) {
            null, PairingState.Starting -> Text("Getting a code…", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.TextMuted))
            is PairingState.Waiting -> {
                Text(
                    pairing.code.code.chunked(3).joinToString(" "),
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = 4.sp, color = WmmColors.Accent),
                    modifier = Modifier.semantics { contentDescription = "Code ${pairing.code.code.toList().joinToString(" ")}" },
                )
                Text(
                    "Open ${displayAddress(pairing.code.linkUrl.substringBefore('?'))} where you're signed in and enter the code. This $device signs in by itself.",
                    style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Text),
                )
                FilledButton("Open the link page", onClick = { uriHandler.openUri(pairing.code.linkUrl) }, modifier = Modifier.fillMaxWidth())
            }
            is PairingState.Approved -> Text("Code accepted. Signing in…", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Accent))
            PairingState.Expired -> {
                Text("The code ran out.", style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Text))
                FilledButton("Get a new code", onClick = onNewCode, modifier = Modifier.fillMaxWidth())
            }
            is PairingState.Failed -> {
                Text(pairing.message.ifBlank { "Couldn't get a code." }, style = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = WmmColors.Error))
                FilledButton("Try again", onClick = onNewCode, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A server with no accounts yet: its first account (the admin) and its
 * libraries are made on its web page, which an app can't do for it. Coming
 * back to the app, or Check again, asks the server whether that's done.
 */
@Composable
private fun SetupPanel(server: SavedServer?, checking: Boolean, error: String?, onCheck: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().panel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This server isn't set up yet", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = WmmColors.Text))
        Text(
            "It has no accounts. Open its web page to make the first one and add your libraries, then come back here and sign in with it.",
            style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextMuted),
        )
        if (server != null) {
            FilledButton("Open the server's web page", onClick = { runCatching { uriHandler.openUri(server.server.baseUrl) } }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Error)) }
        GoldTextButton(if (checking) "Checking…" else "Check again", onCheck, enabled = !checking)
    }
}

/** "Link a TV": signs in first, then goes on to approve the TV's code. */
@Composable
private fun LinkTvRow(pending: DeepLink.LinkTv?, onClick: () -> Unit) {
    val subtitle = when {
        pending?.code != null -> "Sign in to approve the code ${pending.code}"
        pending != null -> "Sign in first, then enter the TV's code"
        else -> "Enter the code shown on your TV"
    }
    Row(
        Modifier.fillMaxWidth().panel(selected = pending != null).clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconTile(WmmIcons.Tv, background = WmmColors.Line2, tint = WmmColors.Accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Link a TV", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = WmmColors.Text))
            Text(subtitle, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = if (pending != null) WmmColors.Accent else WmmColors.TextMuted))
        }
        Icon(WmmIcons.ChevronRight, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}
