package media.watchmy.client.mobile.settings

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.mobile.downloads.DangerText
import media.watchmy.client.model.SaveFailure
import media.watchmy.client.model.rowLine
import media.watchmy.client.presentation.settings.SettingKey
import media.watchmy.client.presentation.settings.SettingsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

// The pieces every Settings page draws (PLAN-ANDROID-SETTINGS.md §4.4–§4.7):
// whose settings a heading is over, a row's Saving… and its failure, the
// cards at the top of a page, and the account's rows held until they load.

/** The one Settings model, from the Settings route's entry, so the overview and every page share it. */
@Composable
internal fun settingsModel(): SettingsViewModel {
    val services = LocalAppServices.current
    return viewModel { SettingsViewModel(services.auth, services.servers, services.preferences) }
}

/** "phone" or "tablet", for "On this phone" and the dialogs. */
@Composable
@ReadOnlyComposable
internal fun deviceWord(): String = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"

internal val LedeStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted)
internal val FootStyle = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint)
private val FailText = Color(0xFFFCA5A5)

/** The window's width, which decides whether a tablet's page beside the list is wide. */
@Composable
@ReadOnlyComposable
internal fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** A page's heading over one kind of setting: "On this phone", "Your account · on every device". */
@Composable
internal fun PageHeading(text: String, tablet: Boolean, modifier: Modifier = Modifier) {
    if (!tablet) SectionLabel(text, modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() })
    else Text(
        text.uppercase(),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted),
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A heading inside a kind ("Skipping", "Live TV reminders"). */
@Composable
internal fun SubHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = WmmColors.Text),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp).semantics { heading() },
    )
}

/**
 * A run of rows: straight on the page on a phone, in a rounded panel at most
 * 720 wide on a tablet (TabSettingsOverview.dc.html's `.panel`).
 */
@Composable
internal fun SettingsRows(tablet: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (!tablet) Column(modifier, content = content)
    else Column(
        modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(WmmColors.Surface)
            .border(1.dp, WmmColors.Line, RoundedCornerShape(16.dp)).padding(vertical = 2.dp),
        content = content,
    )
}

/**
 * The rows under Your account, dimmed and inert until the account's settings
 * have loaded (§4.5), so a default is never saved over someone's choice. The
 * rows pass [ready] on to their controls.
 */
@Composable
internal fun AccountRows(ready: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.alpha(if (ready) 1f else 0.45f).semantics { if (!ready) contentDescription = "Your account’s settings haven’t loaded" },
        content = content,
    )
}

/** What a card at the top of a page says about. */
internal enum class NoteTone { Info, Warn, Error, Ok }

/**
 * A card at the top of the overview or a page (`.note`): what is wrong or
 * what was done, and the button that fixes it. Errors are announced at once,
 * the rest politely.
 */
@Composable
internal fun StatusCard(
    title: String,
    body: String?,
    tone: NoteTone,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val (ground, rim, icon, tint) = when (tone) {
        NoteTone.Info -> Quad(WmmColors.Surface, WmmColors.Line2, WmmIcons.Info, WmmColors.TextSoft)
        NoteTone.Warn -> Quad(Color(0xFF1D160C), Color(0xFF7A4A12), WmmIcons.Alert, WmmColors.Warn)
        NoteTone.Error -> Quad(Color(0xFF1F1010), Color(0xFF7F1D1D), WmmIcons.Alert, WmmColors.Error)
        NoteTone.Ok -> Quad(Color(0xFF0F1A13), Color(0xFF1F5132), WmmIcons.CheckCircle, Color(0xFF4ADE80))
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(ground).border(1.dp, rim, shape).padding(start = 14.dp, end = if (onDismiss != null) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(Modifier.weight(1f)) {
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = if (tone == NoteTone.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite }) {
                Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Text))
                if (body != null) Text(body, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft), modifier = Modifier.padding(top = 2.dp))
            }
            if (action != null) GoldTextButton(action, onAction, Modifier.padding(top = 4.dp).padding(start = 0.dp))
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(WmmIcons.Close, contentDescription = "Put away", tint = WmmColors.TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private data class Quad(val ground: Color, val rim: Color, val icon: ImageVector, val tint: Color)

/** The cards' place on a page: inset on a phone, flush with the panels on a tablet. */
internal fun Modifier.cardInset(tablet: Boolean): Modifier =
    if (tablet) this.widthIn(max = 720.dp).padding(top = 10.dp) else this.padding(horizontal = 16.dp, vertical = 8.dp)

/** A row's line while its change is on its way: a small spinner and Saving…. */
@Composable
internal fun SavingLine(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(Modifier.size(12.dp), color = WmmColors.TextMuted, strokeWidth = 1.5.dp)
        Text("Saving…", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
    }
}

/**
 * Under a row whose change didn't save (§4.5): what it is back to, and Try
 * again. It stays until it is tried again or changed.
 */
@Composable
internal fun FailureLine(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Error, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = FailText),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Assertive },
        )
        GoldTextButton("Try again", onRetry)
    }
}

/**
 * Says "Saved" to TalkBack, politely, when [key]'s change has saved: the
 * row's value is the confirmation for everyone else (§4.5).
 */
@Composable
internal fun SavedAnnouncement(state: SettingsViewModel.State, key: SettingKey) {
    val saving = key in state.saving
    val failed = key in state.failed
    var wasSaving by remember(key) { mutableStateOf(saving) }
    var said by remember(key) { mutableStateOf(false) }
    LaunchedEffect(saving, failed) {
        if (wasSaving && !saving && !failed) {
            said = true
            delay(2_000)
            said = false
        }
        wasSaving = saving
    }
    // A point, not nothing: TalkBack leaves out nodes with no size.
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; if (said) contentDescription = "Saved" })
}

/** The line under a setting's name: Saving… while it goes, else [normal]. */
@Composable
internal fun SettingLine(saving: Boolean, normal: String?) {
    if (saving) SavingLine()
    else if (normal != null) Text(normal, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 17.5.sp, color = WmmColors.TextMuted))
}

/** [key]'s failure under its row, with Try again, when there is one. [still] is what the control is back to. */
@Composable
internal fun RowFailure(failure: SaveFailure?, still: String, onRetry: () -> Unit) {
    if (failure != null) FailureLine(failure.rowLine(still), onRetry)
}

/** A remove, apart at the bottom of its page in the danger colour, its label ending in an ellipsis (§4.4). */
@Composable
internal fun DangerRow(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = DangerText, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = DangerText))
            if (subtitle != null) Text(subtitle, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = DangerText.copy(alpha = 0.8f)))
        }
    }
}

/** The thin line that sets removes, and the account's groups, apart. */
@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(1.dp).background(WmmColors.Line))
}

/** A footnote under a run of rows. */
@Composable
internal fun FootNote(text: String, modifier: Modifier = Modifier, tablet: Boolean = false) {
    Text(text, style = FootStyle, modifier = modifier.padding(horizontal = if (tablet) 4.dp else 16.dp).padding(top = if (tablet) 10.dp else 4.dp).widthIn(max = 700.dp))
}

/**
 * Whether the device has a network now, from Android's default network,
 * kept up as it changes: "You're offline" in Settings is the device with
 * none, not a server that didn't answer.
 */
@Composable
internal fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember(connectivity) { mutableStateOf(connectivity?.activeNetwork != null || connectivity == null) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = connectivity?.activeNetwork != null
            }
        }
        val registered = connectivity != null && runCatching { connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }.isSuccess
        onDispose { if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }
    return online
}
