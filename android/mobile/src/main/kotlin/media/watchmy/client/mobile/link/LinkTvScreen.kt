package media.watchmy.client.mobile.link

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BackTopBar
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.mobile.components.panel
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

private val Body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted)

/**
 * Link a TV (Settings' list style; LinkTvFromLink.dc.html): type the code
 * the TV shows, check the device, then Approve signs it in as this
 * account. [initialCode] comes from a watchmymedia://link?code= link and is
 * looked up at once; anyone can send such a link, so the page then asks
 * to approve only if the TV shows the code now.
 */
@Composable
fun LinkTvScreen(initialCode: String?, onDone: () -> Unit) {
    val services = LocalAppServices.current
    val vm = viewModel { LinkTvViewModel(services.auth, initialCode) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initialCode == null) codeFocus.requestFocus() }

    Box(Modifier.fillMaxSize().background(WmmColors.Ground), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            BackTopBar("Link a TV", onDone)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val step = state.step) {
                    LinkTvViewModel.Step.Enter -> {
                        Text(
                            "Enter the code shown on your TV. It has six letters and numbers; capitals and spaces don't matter.",
                            style = Body,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            OutlinedTextField(
                                value = state.code,
                                onValueChange = vm::setCode,
                                label = { Text("Code") },
                                singleLine = true,
                                enabled = !state.busy,
                                isError = state.error != null,
                                supportingText = state.error?.let { { Text(it) } },
                                textStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 8.sp, color = WmmColors.Text, textAlign = TextAlign.Center),
                                colors = fieldColors(),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { vm.lookUp() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                            )
                            FilledButton("Continue", onClick = vm::lookUp, large = true, busy = state.busy, enabled = state.complete, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is LinkTvViewModel.Step.Found -> {
                        DeviceRows(step.device)
                        Text(
                            "Approve signs this TV in as ${user?.name ?: "you"}. You can sign it out later from Settings › Signed-in devices.",
                            style = Body,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        state.error?.let { Text(it, style = Body.copy(color = WmmColors.Error), modifier = Modifier.padding(horizontal = 16.dp)) }
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.fromLink) FromLinkNote()
                            FilledButton("Approve", onClick = vm::approve, large = true, busy = state.busy, modifier = Modifier.padding(top = if (state.fromLink) 8.dp else 0.dp).fillMaxWidth())
                            GoldTextButton("Not this TV", vm::startOver, enabled = !state.busy)
                        }
                    }
                    is LinkTvViewModel.Step.Approved -> {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(WmmColors.Accent), contentAlignment = Alignment.Center) {
                                Icon(WmmIcons.Check, contentDescription = null, tint = WmmColors.OnAccent, modifier = Modifier.size(36.dp))
                            }
                            Text(
                                "${step.device.name} is signed in",
                                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = WmmColors.Text),
                                textAlign = TextAlign.Center,
                            )
                            Text("It shows Home in a moment, signed in as ${user?.name ?: "you"}.", style = Body, textAlign = TextAlign.Center)
                        }
                        FilledButton("Done", onClick = onDone, large = true, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * The caution over Approve for a code that came in a link (`role="note"`).
 * The board adds when the TV showed the code; the server's pairing reply
 * has no start time to say it from, so it is left out.
 */
@Composable
private fun FromLinkNote() {
    Row(
        Modifier.fillMaxWidth().panel().padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Warn, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Text(
            "Approve only if this code is on your TV now.",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, color = WmmColors.Text),
        )
    }
}

/** The TV behind the code, so the viewer can check it is theirs before approving. */
@Composable
private fun DeviceRows(device: PendingDevice) {
    SectionLabel("This TV", Modifier.padding(top = 8.dp))
    ListRow(
        title = device.name,
        subtitle = listOfNotNull(device.model, device.product, device.version?.let { "version $it" }).joinToString(" · ").ifEmpty { null },
        minHeight = 64.dp,
        leading = { IconTile(WmmIcons.Tv, background = WmmColors.Line2, tint = WmmColors.Accent) },
    )
    ListRow("Address", minHeight = 48.dp, titleSize = 15, trailing = { RowValue(device.ip ?: "Unknown", chevron = false, size = 13) })
    ListRow("Code", minHeight = 48.dp, titleSize = 15, trailing = {
        // As the TV shows it, in two threes ("K7M 4PQ"), read out a character at a time.
        Text(
            device.code.chunked(3).joinToString(" "),
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.04.sp, color = WmmColors.TextSoft),
            modifier = Modifier.semantics { contentDescription = device.code.toList().joinToString(" ") },
        )
    })
}
