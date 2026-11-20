package media.watchmy.client.mobile.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.FilledButton
import media.watchmy.client.mobile.signin.SignInScreen
import media.watchmy.client.model.ADD_A_SERVER
import media.watchmy.client.model.AddServerStep
import media.watchmy.client.model.SERVER_ADDED_NEXT
import media.watchmy.client.model.serverAdded
import media.watchmy.client.model.serverAddedChanged
import media.watchmy.client.model.serverAddedLine
import media.watchmy.client.presentation.servers.ServerSwitchViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

// Add a server: choose one (the server picker, without the ones already
// registered), sign in (the sign-in screen), then a last step that says what
// changed. Closing it at any step leaves the app on the server it was on.

private val BarTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text)

/** Add a server's bar: Close, which leaves the app where it was, and the title. */
@Composable
internal fun AddServerBar(onClose: () -> Unit, title: String = ADD_A_SERVER) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BarIconButton(WmmIcons.Close, "Close", onClose)
        Text(title, style = BarTitle, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Where Add a server is: three bars, gold up to this step, and "Step 1 of 3 · Choose a server · next: Sign in". */
@Composable
internal fun AddServerSteps(step: AddServerStep, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp).clearAndSetSemantics { contentDescription = step.line }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AddServerStep.entries.forEach { s ->
                Box(Modifier.weight(1f).height(4.dp).background(if (s.ordinal <= step.ordinal) WmmColors.Accent else WmmColors.Line2, RoundedCornerShape(2.dp)))
            }
        }
        Text(step.line, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * The sign-in of a server the app switched to, with a way back: Close (and
 * Back) leave the app on the server it was on. [step] is Add a server's
 * second step; null for a registered server whose sign-in ended.
 */
@Composable
internal fun SwitchedSignIn(step: AddServerStep?, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(WmmColors.Ground)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp)) {
                AddServerBar(onCancel, title = if (step != null) ADD_A_SERVER else "Sign in again")
                if (step != null) AddServerSteps(step)
            }
        }
        // The bar above has taken the status bar's room.
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) {
            SignInScreen(onSwitchServer = {}, showServerChange = false, showBrand = false)
        }
    }
}

/** Add a server's last step: the server is added and in use, what changed, and what happens next. */
@Composable
internal fun ServerAddedScreen(added: ServerSwitchViewModel.Added, onDone: () -> Unit) {
    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    BackHandler(onBack = onDone)
    Box(Modifier.fillMaxSize().background(WmmColors.Ground), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            AddServerBar(onDone)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                AddServerSteps(AddServerStep.Added)
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(64.dp).background(Color(0xFF4ADE80).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(WmmIcons.Check, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(32.dp))
                    }
                    Text(
                        serverAdded(added.serverName),
                        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, color = WmmColors.Text, textAlign = TextAlign.Center),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(serverAddedLine(added.userName), style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted, textAlign = TextAlign.Center))
                }
                AddedBox("What changed", serverAddedChanged(device, added.signedIn))
                AddedBox("What happens next", SERVER_ADDED_NEXT, Modifier.padding(top = 10.dp))
            }
            Column(Modifier.fillMaxWidth().background(WmmColors.Ground).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(WmmColors.Line))
                FilledButton("Done", onDone, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), large = true)
            }
        }
    }
}

@Composable
private fun AddedBox(heading: String, text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().background(WmmColors.Surface, shape).border(1.dp, WmmColors.Line, shape).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            heading.uppercase(),
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted),
            modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
        )
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft))
    }
}
