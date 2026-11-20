package media.watchmy.client.mobile.link

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
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
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.RowValue
import media.watchmy.client.mobile.settings.NoteTone
import media.watchmy.client.mobile.settings.StatusCard
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.rememberModelTextField

private val Body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted)
private val StepTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text)
private val FieldProblem = Color(0xFFFCA5A5)

/**
 * Link a TV (the web's path):
 * a task in the phone's frame, with Close, a step bar and one
 * named button pinned at the bottom. Enter the code → Check it's yours →
 * Linked, which says what changed and what happens next. A code no TV is
 * showing, or one that ran out, is said under the field; a server that
 * didn't answer, above the button. System Back from the second step goes
 * back to the first.
 *
 * [initialCode] comes from a watchmymedia://link?code= link and is looked up
 * at once, so the link opens on the second step; anyone can send such a
 * link, so that step says to approve only if the TV shows the code now
 * (LinkTvFromLink).
 */
@Composable
fun LinkTvScreen(initialCode: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalAppServices.current
    val vm = viewModel { LinkTvViewModel(services.auth, initialCode) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val server by services.servers.current.collectAsStateWithLifecycle()
    val serverName = server?.server?.name ?: "The server"
    val who = user?.name ?: "you"
    val step = state.step
    BackHandler(enabled = step is LinkTvViewModel.Step.Found && !state.busy) { vm.startOver() }

    Box(modifier.fillMaxSize().background(WmmColors.Ground), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BarIconButton(WmmIcons.Close, "Close", onDone)
                Text("Link a TV", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text), modifier = Modifier.semantics { heading() })
            }
            if (step !is LinkTvViewModel.Step.Approved) StepBar(second = step is LinkTvViewModel.Step.Found)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                when (step) {
                    LinkTvViewModel.Step.Enter -> EnterCode(state, onCode = vm::setCode, onGo = vm::lookUp)
                    is LinkTvViewModel.Step.Found -> CheckDevice(step.device, who)
                    is LinkTvViewModel.Step.Approved -> Linked(step.device, who)
                }
            }
            Pinned {
                when (step) {
                    LinkTvViewModel.Step.Enter -> {
                        val unreachable = state.problem == LinkProblem.Unreachable
                        if (unreachable) StatusCard("Couldn’t check that code", "$serverName didn’t answer. Nothing was linked. Try again.", NoteTone.Error)
                        FilledButton(if (unreachable) "Try again" else "Check this code", vm::lookUp, Modifier.fillMaxWidth().height(52.dp), large = true, busy = state.busy, enabled = state.complete)
                    }
                    is LinkTvViewModel.Step.Found -> {
                        approveProblem(state, serverName)?.let { (title, body) -> StatusCard(title, body, NoteTone.Error) }
                        FilledButton("Approve this TV", vm::approve, Modifier.fillMaxWidth().height(52.dp), large = true, busy = state.busy)
                        GoldTextButton("Not this TV", vm::startOver, enabled = !state.busy)
                    }
                    is LinkTvViewModel.Step.Approved -> {
                        FilledButton("Back to Account & devices", onDone, Modifier.fillMaxWidth().height(52.dp), large = true)
                        GoldTextButton("Link another TV", vm::startOver)
                    }
                }
            }
        }
    }
}

/** What went wrong approving, above the button: the code ran out, the server didn't answer, or the server's words. */
private fun approveProblem(state: LinkTvViewModel.State, serverName: String): Pair<String, String>? = when (state.problem) {
    null -> null
    LinkProblem.Unreachable -> "Couldn’t approve this TV" to "$serverName didn’t answer. Nothing was linked. Try again."
    LinkProblem.ExpiredCode -> "That code has run out" to "Nothing was linked. Choose Not this TV, then type the new code your TV is showing."
    LinkProblem.UnknownCode -> "No TV is showing that code now" to "Nothing was linked. Choose Not this TV and check the code."
    else -> "Couldn’t approve this TV" to (state.error ?: "Nothing was linked. Try again.")
}

/** Two segments and "Step 1 of 2 · Enter the code · next: Check it's yours" (`.steps`). */
@Composable
private fun StepBar(second: Boolean) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().semantics { invisibleToUser() }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(true, second).forEach { on ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (on) WmmColors.Accent else WmmColors.Line2))
            }
        }
        Text(
            if (second) "Step 2 of 2 · Check it’s yours" else "Step 1 of 2 · Enter the code · next: Check it’s yours",
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The task's button, pinned at the bottom and riding above the keyboard, with what stopped it above it. */
@Composable
private fun Pinned(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WmmColors.Line))
        Column(
            Modifier.fillMaxWidth().background(WmmColors.Ground).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Step 1: the six characters in six boxes, and what is wrong with them under the boxes. */
@Composable
private fun ColumnScope.EnterCode(state: LinkTvViewModel.State, onCode: (String) -> Unit, onGo: () -> Unit) {
    val field = rememberModelTextField(state.code, state.revision, onCode)
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.revision) { focus.requestFocus() }
    Text("Enter the code on your TV", style = StepTitle, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
    Text("It has six letters and numbers. Capitals and spaces don’t matter.", style = Body, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
    val problem = when (state.problem) {
        LinkProblem.UnknownCode -> "No TV is showing that code right now. Check it and type it again."
        LinkProblem.ExpiredCode -> "That code has run out. Type the new one your TV is showing."
        LinkProblem.Short, LinkProblem.Other -> state.error
        else -> null
    }
    CodeBoxes(field, bad = problem != null, enabled = !state.busy, onGo = onGo, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).focusRequester(focus))
    if (problem != null) {
        Text(problem, style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 17.sp, color = FieldProblem), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive })
    }
    Text(
        "No code on the TV? Open WatchMyMedia on it and choose your server. It shows a code and keeps it fresh.",
        style = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp),
    )
}

/**
 * The code field, drawn as the TV shows the code: six boxes, a gap after the
 * third. One field under them takes the typing, in any case, with spaces or
 * dashes; it keeps letters and digits, upper-cased, six at most.
 */
@Composable
private fun CodeBoxes(field: TextFieldState, bad: Boolean, enabled: Boolean, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        state = field,
        modifier = modifier.semantics { contentDescription = "Code, six letters and numbers" },
        enabled = enabled,
        inputTransformation = CodeInput,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
        onKeyboardAction = { onGo() },
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        decorator = { inner ->
            val text = field.text.toString()
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(LinkTvViewModel.CODE_LENGTH) { i ->
                    val current = focused && (i == text.length || (i == LinkTvViewModel.CODE_LENGTH - 1 && text.length == LinkTvViewModel.CODE_LENGTH))
                    val rim = when {
                        bad -> WmmColors.Error
                        current -> WmmColors.Accent
                        else -> WmmColors.FieldOutline
                    }
                    Box(
                        Modifier.size(width = 46.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).border(if (current) 2.dp else 1.dp, rim, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text.getOrNull(i)?.toString().orEmpty(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text, textAlign = TextAlign.Center))
                    }
                    if (i < LinkTvViewModel.CODE_LENGTH - 1) Spacer(Modifier.width(if (i == 2) 16.dp else 6.dp))
                }
                // The field itself takes the typing and the cursor, out of sight behind the boxes.
                Box(Modifier.size(1.dp).alpha(0f)) { inner() }
            }
        },
    )
}

/** Letters and digits only, upper-cased, six at most: "kq7-f2w" → "KQ7F2W". */
private val CodeInput = InputTransformation {
    val typed = asCharSequence().toString()
    val code = normalizeCode(typed)
    if (code != typed) replace(0, length, code)
}

/** Step 2: the TV behind the code, so the person can check it is theirs before approving. */
@Composable
private fun ColumnScope.CheckDevice(device: PendingDevice, who: String) {
    Text("Is this your TV?", style = StepTitle, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
    ListRow(
        title = device.name,
        subtitle = listOfNotNull(device.model ?: device.product, device.version?.let { "app $it" }).joinToString(" · ").ifEmpty { null },
        minHeight = 72.dp,
        modifier = Modifier.padding(top = 6.dp),
        leading = { IconTile(WmmIcons.Tv, background = WmmColors.Line2, tint = WmmColors.Accent) },
    )
    ListRow("Address", minHeight = 44.dp, titleSize = 15, trailing = { RowValue(device.ip ?: "Unknown", chevron = false, size = 13) })
    ListRow("Code", minHeight = 44.dp, titleSize = 15, trailing = {
        // As the TV shows it, in two threes ("K7M 4PQ"), read out a character at a time.
        Text(
            device.code.chunked(3).joinToString(" "),
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.04.sp, color = WmmColors.TextSoft),
            modifier = Modifier.semantics { contentDescription = device.code.toList().joinToString(" ") },
        )
    })
    StatusCard(
        "Approve only if this code is on your TV now",
        "Approving signs this TV in as $who.",
        NoteTone.Warn,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The finished screen: what changed, and what happens next. */
@Composable
private fun ColumnScope.Linked(device: PendingDevice, who: String) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Done.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(WmmIcons.Check, contentDescription = null, tint = Done, modifier = Modifier.size(32.dp))
        }
        Text("${device.name} is linked", style = StepTitle, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text("It shows Home in a moment, signed in as $who.", style = Body, textAlign = TextAlign.Center)
    }
    Outcome("What changed", "${device.name} is signed in as $who, and is in your signed-in devices.")
    Outcome("What happens next", "It stays signed in until you sign it out in Account & devices.", Modifier.padding(top = 10.dp))
}

/** A box on the finished screen (`.box`). */
@Composable
private fun Outcome(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(title.uppercase(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(bottom = 6.dp))
        Text(body, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft))
    }
}

private val Done = Color(0xFF4ADE80)
