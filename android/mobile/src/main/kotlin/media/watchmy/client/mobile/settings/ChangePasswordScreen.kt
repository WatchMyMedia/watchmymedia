package media.watchmy.client.mobile.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.BarIconButton
import media.watchmy.client.mobile.components.FieldText
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.IconTile
import media.watchmy.client.mobile.components.ListRow
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.SectionLabel
import media.watchmy.client.mobile.components.fieldColors
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.kindLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.CHANGE_PASSWORD_NO_OTHERS
import media.watchmy.client.presentation.settings.ChangePasswordRefusal
import media.watchmy.client.presentation.settings.ChangePasswordViewModel
import media.watchmy.client.presentation.settings.changePasswordLede
import media.watchmy.client.presentation.settings.changePasswordSignsOut
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.components.loadingSemantics
import media.watchmy.client.ui.components.rememberSheen
import media.watchmy.client.ui.components.skeletonBlock
import media.watchmy.client.ui.components.skeletonLine

private val Lede = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted)
private val Body = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, color = WmmColors.TextSoft)
private val Foot = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 18.sp, color = WmmColors.TextFaint)
private val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = WmmColors.Text)

/** The red of a refusal's note, as the boards' `.note.err` draws it. */
private val ErrorGround = Color(0xFF1F1010)
private val ErrorRim = Color(0xFF7F1D1D)

/**
 * Change password: the current password and a new one, each
 * typed once with the eye to check it, the other devices the change signs
 * out by name, and one button that says how many.
 *
 * - On a phone, a full-screen task: Close at the left of the top bar and the
 *   button pinned at the bottom, riding above the keyboard.
 * - On a tablet ([asDialog]), the same in a centred surface about 560 wide,
 *   with Cancel beside the button at its foot. The caller puts it in its
 *   dialog window (a dialog destination); this draws only the surface.
 *
 * Close, Cancel and the system's Back ask first when something is typed
 * ("Leave without changing your password?"); with nothing typed they just
 * close. [onClose] leaves without changing anything. [onChanged] reports a
 * changed password and how many other devices it signed out, after which the
 * caller closes the task and calls `SettingsViewModel.passwordChanged`.
 *
 * A refusal moves focus to its message (the field it is about, or the note
 * above the button), and TalkBack announces it. The passwords are never kept
 * in saved state: after the process is killed the task comes back empty.
 */
@Composable
internal fun ChangePasswordScreen(
    onClose: () -> Unit,
    onChanged: (signedOut: Int) -> Unit,
    asDialog: Boolean,
    modifier: Modifier = Modifier,
) {
    val services = LocalAppServices.current
    val vm = viewModel { ChangePasswordViewModel(services.auth, services.servers) }
    val state by vm.state.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val changed by rememberUpdatedState(onChanged)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChangePasswordViewModel.Event.Close -> close()
                is ChangePasswordViewModel.Event.Changed -> changed(event.signedOut)
            }
        }
    }
    // With nothing typed, Back closes as it always does, and predictive back shows Settings behind it.
    BackHandler(enabled = state.typed && !state.changed, onBack = vm::requestClose)

    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    val currentField = rememberPasswordField(state.current, vm::setCurrent)
    val newField = rememberPasswordField(state.new, vm::setNew)
    val focus = remember { PasswordFocus() }
    LaunchedEffect(Unit) { focus.current.requestFocus() }
    LaunchedEffect(state.refusal) {
        when (state.refusal?.place) {
            ChangePasswordRefusal.Place.Current -> focus.current.requestFocus()
            ChangePasswordRefusal.Place.New -> focus.new.requestFocus()
            ChangePasswordRefusal.Place.Button -> focus.note.requestFocus()
            null -> Unit
        }
    }
    val submit: () -> Unit = {
        // The last field's Done: with the current password still empty, go back to it.
        if (currentField.text.isEmpty()) focus.current.requestFocus() else vm.submit()
    }
    val fields = PasswordFields(
        current = currentField,
        new = newField,
        showCurrent = state.showCurrent,
        showNew = state.showNew,
        enabled = !state.working && !state.changed,
        refusal = state.refusal,
        focus = focus,
        eyeVerb = if (asDialog) "use" else "tap",
        onToggleCurrent = vm::toggleShowCurrent,
        onToggleNew = vm::toggleShowNew,
        onSubmit = submit,
    )
    val foot = PasswordFoot(
        button = state.button,
        enabled = state.canSubmit,
        working = state.working,
        refusal = state.refusal?.takeIf { it.place == ChangePasswordRefusal.Place.Button },
        needsBoth = currentField.text.isEmpty() || newField.text.isEmpty(),
        noteFocus = focus.note,
        onSubmit = vm::submit,
    )

    if (asDialog) {
        PasswordSurface(device, fields, foot, state.others, vm::loadOthers, onCancel = vm::requestClose, modifier = modifier)
    } else {
        PasswordPage(device, fields, foot, state.others, vm::loadOthers, onClose = vm::requestClose, modifier = modifier)
    }

    if (state.leaving) {
        AlertDialog(
            onDismissRequest = vm::keepEditing,
            containerColor = WmmColors.Surface2,
            title = { Text("Leave without changing your password?") },
            text = { Text("What you typed isn’t saved.") },
            confirmButton = { GoldTextButton("Keep editing", vm::keepEditing) },
            dismissButton = { GoldTextButton("Leave", vm::leave, color = WmmColors.TextSoft) },
        )
    }
}

/**
 * A password field's text, held where it is typed, as `rememberModelTextField`
 * holds a field's (android/AGENTS.md, Text fields), and told to the model as
 * it is typed. Unlike that one it is not saved with the screen's instance
 * state, which would keep a password on disk across the process being
 * killed. It starts from the model's [value], so it lasts as long as the
 * model (the activity handles rotation itself) and comes back empty after
 * process death. The model never sets the text, so it needs no revision.
 */
@Composable
private fun rememberPasswordField(value: String, onChange: (String) -> Unit): TextFieldState {
    val field = remember { TextFieldState(value) }
    val report by rememberUpdatedState(onChange)
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { report(it) } }
    return field
}

/** Where focus goes: the current password on opening, and the message when a try is refused. */
private class PasswordFocus {
    val current = FocusRequester()
    val new = FocusRequester()
    val note = FocusRequester()
}

/** The two fields and what they need, for the phone's page and the tablet's surface alike. */
private class PasswordFields(
    val current: TextFieldState,
    val new: TextFieldState,
    val showCurrent: Boolean,
    val showNew: Boolean,
    val enabled: Boolean,
    val refusal: ChangePasswordRefusal?,
    val focus: PasswordFocus,
    /** "tap" on a phone, "use" on a tablet, which may have a pointer. */
    val eyeVerb: String,
    val onToggleCurrent: () -> Unit,
    val onToggleNew: () -> Unit,
    val onSubmit: () -> Unit,
)

/** The foot: a refusal above the button, or what the button waits for, then the button. */
private class PasswordFoot(
    val button: String,
    val enabled: Boolean,
    val working: Boolean,
    val refusal: ChangePasswordRefusal?,
    val needsBoth: Boolean,
    val noteFocus: FocusRequester,
    val onSubmit: () -> Unit,
)

/** The phone: a full-screen task with Close, and the button pinned at the bottom above the keyboard. */
@Composable
private fun PasswordPage(
    device: String,
    fields: PasswordFields,
    foot: PasswordFoot,
    others: Load<List<DeviceSession>>,
    onRetryOthers: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(WmmColors.Ground).navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BarIconButton(WmmIcons.Close, "Close", onClose)
            Text("Change password", style = Title, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(changePasswordLede(device), style = Lede, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
            PasswordInputs(fields)
            DeviceList(others, onRetryOthers, Modifier.padding(top = 22.dp))
        }
        Column(Modifier.fillMaxWidth().background(WmmColors.Ground)) {
            HorizontalDivider(thickness = 1.dp, color = WmmColors.Line)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    foot.refusal != null -> RefusalNote(foot.refusal, Modifier.focusRequester(foot.noteFocus))
                    foot.needsBoth -> Text("Enter your current password and a new one.", style = Foot)
                }
                ChangeButton(foot, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The tablet: the same task in a centred surface, with Close at its head and Cancel beside the button at its foot. */
@Composable
private fun PasswordSurface(
    device: String,
    fields: PasswordFields,
    foot: PasswordFoot,
    others: Load<List<DeviceSession>>,
    onRetryOthers: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(WmmColors.Surface2)
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 20.dp)
            .semantics { paneTitle = "Change password" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BarIconButton(WmmIcons.Close, "Close", onCancel)
            Text("Change password", style = Title, modifier = Modifier.semantics { heading() })
        }
        // The middle scrolls at the largest font, and the buttons stay in view below it.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(changePasswordLede(device), style = Lede, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
            PasswordInputs(fields)
            DeviceSentence(others, onRetryOthers, Modifier.padding(top = 18.dp))
            if (foot.refusal != null) {
                RefusalNote(foot.refusal, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).focusRequester(foot.noteFocus))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoldTextButton("Cancel", onCancel, color = WmmColors.TextSoft)
            ChangeButton(foot, Modifier.weight(1f, fill = false))
        }
    }
}

/** Current password and New password, the label above each, the eye at the end and the refusal or help under it. */
@Composable
private fun PasswordInputs(fields: PasswordFields, modifier: Modifier = Modifier) {
    val refusal = fields.refusal
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PasswordField(
            field = fields.current,
            label = "Current password",
            shown = fields.showCurrent,
            onToggle = fields.onToggleCurrent,
            enabled = fields.enabled,
            error = refusal?.takeIf { it.place == ChangePasswordRefusal.Place.Current }?.message,
            help = null,
            contentType = ContentType.Password,
            imeAction = ImeAction.Next,
            onDone = null,
            modifier = Modifier.focusRequester(fields.focus.current),
        )
        PasswordField(
            field = fields.new,
            label = "New password",
            shown = fields.showNew,
            onToggle = fields.onToggleNew,
            enabled = fields.enabled,
            error = refusal?.takeIf { it.place == ChangePasswordRefusal.Place.New }?.message,
            help = "8 to 256 characters. Typed once: ${fields.eyeVerb} the eye to check it.",
            contentType = ContentType.NewPassword,
            imeAction = ImeAction.Done,
            onDone = fields.onSubmit,
            modifier = Modifier.focusRequester(fields.focus.new),
        )
    }
}

@Composable
private fun PasswordField(
    field: TextFieldState,
    label: String,
    shown: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    error: String?,
    help: String?,
    contentType: ContentType,
    imeAction: ImeAction,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val below = error ?: help
    OutlinedSecureTextField(
        state = field,
        label = { Text(label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) },
        labelPosition = TextFieldLabelPosition.Above(),
        enabled = enabled,
        isError = error != null,
        supportingText = below?.let { text ->
            {
                // A refusal is announced as it appears; the help is read with the field.
                Text(text, modifier = if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Assertive } else Modifier)
            }
        },
        textStyle = FieldText,
        colors = fieldColors(),
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        onKeyboardAction = if (onDone != null) KeyboardActionHandler { onDone() } else null,
        trailingIcon = {
            IconButton(onClick = onToggle, enabled = enabled) {
                Icon(
                    if (shown) PhoneIcons.EyeOff else WmmIcons.Eye,
                    contentDescription = (if (shown) "Hide " else "Show ") + label.lowercase(),
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        modifier = modifier.fillMaxWidth().semantics { this.contentType = contentType },
    )
}

/** The phone's list of the devices the change signs out, by name and kind. */
@Composable
private fun DeviceList(others: Load<List<DeviceSession>>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val count = (others as? Load.Ready)?.data?.size
    Column(modifier.fillMaxWidth()) {
        SectionLabel(changePasswordSignsOut(count), Modifier.padding(bottom = 4.dp).semantics { heading() })
        when (others) {
            Load.Loading -> DeviceSkeleton()
            is Load.Failed -> OthersFailed(others.message, onRetry)
            is Load.Ready -> if (others.data.isEmpty()) {
                Text(CHANGE_PASSWORD_NO_OTHERS, style = Body, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            } else {
                others.data.forEach { d ->
                    ListRow(
                        d.name,
                        subtitle = d.model ?: d.kindLabel,
                        titleSize = 15,
                        subtitleSize = 12,
                        minHeight = 52.dp,
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                        leading = { IconTile(d.icon, background = WmmColors.Surface2, tint = WmmColors.TextSoft) },
                    )
                }
            }
        }
    }
}

/** The tablet's: the same devices in a sentence, "Living room TV, Kitchen iPad and Bedroom Apple TV." */
@Composable
private fun DeviceSentence(others: Load<List<DeviceSession>>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val count = (others as? Load.Ready)?.data?.size
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(changePasswordSignsOut(count), Modifier.semantics { heading() })
        when (others) {
            Load.Loading -> Box(Modifier.padding(horizontal = 16.dp).loadingSemantics("Listing your other devices")) {
                Box(Modifier.skeletonLine(rememberSheen(1_600), 280.dp, 12.dp))
            }
            is Load.Failed -> OthersFailed(others.message, onRetry)
            is Load.Ready -> Text(
                if (others.data.isEmpty()) CHANGE_PASSWORD_NO_OTHERS else namesSentence(others.data.map { it.name }),
                style = Body,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** Three rows in the list's places while it loads. */
@Composable
private fun DeviceSkeleton(modifier: Modifier = Modifier) {
    val sheen = rememberSheen(1_600)
    Column(modifier.fillMaxWidth().loadingSemantics("Listing your other devices")) {
        repeat(3) { i ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).skeletonBlock(sheen, RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.skeletonLine(sheen, if (i == 1) 96.dp else 128.dp, 12.dp))
                    Box(Modifier.skeletonLine(sheen, 80.dp, 10.dp))
                }
            }
        }
    }
}

/** The devices couldn't be listed: why, and Try again. The change itself lists them again first. */
@Composable
private fun OthersFailed(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = Body, modifier = Modifier.weight(1f))
        GoldTextButton("Try again", onRetry)
    }
}

/** Why the last try didn't work, above the button, in the boards' red note; focusable, so focus can move to it. */
@Composable
private fun RefusalNote(refusal: ChangePasswordRefusal, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorGround)
            .border(1.dp, ErrorRim, RoundedCornerShape(12.dp))
            .focusable()
            .clearAndSetSemantics {
                contentDescription = refusal.spoken
                liveRegion = LiveRegionMode.Assertive
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(WmmIcons.Alert, contentDescription = null, tint = WmmColors.Error, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            refusal.title?.let { Text(it, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.Text)) }
            Text(refusal.message, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.sp, color = WmmColors.TextSoft))
        }
    }
}

/**
 * The burgundy button, named for what it does. Unlike the shared pill it
 * wraps onto a second line at a large font size, since "Change password and
 * sign out 3 devices" cut short would lose the count.
 */
@Composable
private fun ChangeButton(foot: PasswordFoot, modifier: Modifier = Modifier) {
    Button(
        onClick = foot.onSubmit,
        enabled = foot.enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = WmmColors.Brand,
            contentColor = Color.White,
            disabledContainerColor = WmmColors.Brand.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
    ) {
        if (foot.working) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(foot.button, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp), textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** "A", "A and B", "A, B and C." */
internal fun namesSentence(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0] + "."
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last() + "."
}

/** What a device looks like in the list: a TV, a phone or tablet, or a browser. */
private val DeviceSession.icon: ImageVector
    get() = when {
        kind == DeviceSession.Kind.Browser -> WmmIcons.Globe
        platform == "androidtv" || platform == "tvos" -> WmmIcons.Tv
        platform == "android" || platform == "ios" -> PasswordIcons.Handheld
        else -> WmmIcons.Screen
    }

/** The board's tablet outline, for a phone or tablet in the list. */
private object PasswordIcons {
    val Handheld: ImageVector = ImageVector.Builder("handheld", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM11 17h2").toNodes(),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
