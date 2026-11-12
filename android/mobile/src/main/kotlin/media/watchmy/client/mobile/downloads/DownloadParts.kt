package media.watchmy.client.mobile.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.GoldTextButton
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.requests.Kicker
import media.watchmy.client.mobile.requests.RequestBottomSheet
import media.watchmy.client.mobile.requests.SheetClose
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.DownloadOption
import media.watchmy.client.model.DownloadPlan
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.DownloadStorage
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.downloadHelp
import media.watchmy.client.model.downloadLabel
import media.watchmy.client.model.downloadMenuLine
import media.watchmy.client.model.downloadMenuTitle
import media.watchmy.client.model.downloadSize
import media.watchmy.client.model.freeSize
import media.watchmy.client.model.neededSize
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.downloads.ItemDownloadViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.WmmIcons

/** The boards' orange: Removed from the server, and a sheet's "Needs … free". */
internal val Orange = Color(0xFFFB923C)

/** The boards' danger red on dark: Cancel and Remove download. */
internal val DangerText = Color(0xFFFCA5A5)

/**
 * A movie's or an episode's download (MovieDownload.dc.html and
 * EpisodePageDownload.dc.html): the button under Play and Mark watched in
 * each of its states, the line that says why it waits, the server's marks,
 * and the sheet and the menu it opens. [fileId] is the version the page
 * plays first, and a download of any of [otherFiles] is shown as its own;
 * [kicker] and [from] head the sheet. [onPlayCopy] plays the downloaded copy; [onOpenSettings] is Settings › Downloads; [onMessage]
 * says a failure on the page's snackbar.
 */
@Composable
internal fun ItemDownload(
    fileId: Long?,
    kicker: String,
    from: String,
    onPlayCopy: (fileId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onMessage: (String) -> Unit,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    otherFiles: List<Long> = emptyList(),
) {
    val services = LocalAppServices.current
    val vm = viewModel(key = "download-$fileId") { ItemDownloadViewModel(services.downloads, fileId, otherFiles) }
    val state by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberNotificationAsk()
    LaunchedEffect(state.message) {
        state.message?.let {
            onMessage(it)
            vm.messageShown()
        }
    }
    LaunchedEffect(state.download == null) {
        if (state.download == null) menu = false
    }
    if (!state.offered) return
    val openSheet = {
        vm.openSheet()
        sheet = true
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadButton(state.download, state.defaultSize, onDownload = openSheet, onMenu = { menu = true }, onRetry = vm::retry)
        state.download?.let { d -> DownloadMarkLine(d, onDownloadAgain = openSheet) }
    }
    if (sheet) {
        LaunchedEffect(state.started) {
            if (state.started) sheet = false
        }
        DownloadSheet(
            kicker = kicker,
            from = from,
            state = state,
            tablet = tablet,
            onRetryPlan = vm::loadPlan,
            onDownload = { q, audio, burn ->
                askNotifications()
                vm.download(q, audio, burn)
            },
            onOpenSettings = {
                sheet = false
                onOpenSettings()
            },
            onDismiss = { sheet = false },
        )
    }
    val held = state.download
    if (menu && held != null) {
        DownloadMenu(
            kicker = kicker,
            download = held,
            wifiOnly = state.settings.wifiOnly,
            device = if (tablet) "tablet" else "phone",
            onPlay = {
                menu = false
                onPlayCopy(held.fileId)
            },
            onDownloadAgain = {
                menu = false
                openSheet()
            },
            onPause = vm::pause,
            onResume = vm::resume,
            onOpenSettings = {
                menu = false
                onOpenSettings()
            },
            onRemove = {
                menu = false
                vm.remove()
            },
            onDismiss = { menu = false },
        )
    }
}

/**
 * Asks once, on Android 13 and later, to post the downloads' notifications,
 * when the first download starts (PLAN-DOWNLOADS.md §10.3); downloads run
 * whatever the answer.
 */
@Composable
internal fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context, launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33 && !asked &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                asked = true
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}

/** Asked once a process at most: Android itself stops asking after two refusals. */
private var asked = false

/** The tonal button with the download's state (`.btn.tonal.dl`), with progress along its bottom while it comes. */
@Composable
internal fun DownloadButton(download: OfflineDownload?, size: String?, onDownload: () -> Unit, onMenu: () -> Unit, onRetry: () -> Unit) {
    val status = download?.status
    if (status == DownloadStatus.Failed) {
        DlButton("Couldn’t download · Try again", WmmIcons.Replace, WmmColors.Text, onRetry)
        return
    }
    if (status == null) {
        DlButton(listOfNotNull("Download", size).joinToString(" · "), WmmIcons.Download, WmmColors.Text, onDownload)
        return
    }
    val gold = status is DownloadStatus.Downloading || status == DownloadStatus.Downloaded
    val progress = (status as? DownloadStatus.Downloading)?.percent ?: (status as? DownloadStatus.Paused)?.percent
    DlButton(downloadLabel(status), statusIcon(status), if (gold) WmmColors.Accent else WmmColors.Text, onMenu, progress = progress?.takeIf { status is DownloadStatus.Downloading })
    downloadHelp(status)?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.padding(start = 4.dp)) }
}

@Composable
private fun DlButton(text: String, icon: ImageVector?, iconColor: Color, onClick: () -> Unit, progress: Int? = null) {
    Box(
        Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(WmmColors.Line2).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon == null) {
                CircularProgressIndicator(Modifier.size(18.dp), color = WmmColors.Text, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress / 100f).background(WmmColors.Accent))
            }
        }
    }
}

/** The state's icon; null is Preparing's spinner. */
internal fun statusIcon(status: DownloadStatus): ImageVector? = when (status) {
    is DownloadStatus.Waiting -> if (status.reason == DownloadStatus.Reason.Wifi) PhoneIcons.Wifi else WmmIcons.Clock
    DownloadStatus.Preparing -> null
    is DownloadStatus.Downloading, is DownloadStatus.Paused -> WmmIcons.Download
    DownloadStatus.Downloaded -> WmmIcons.CheckCircle
    DownloadStatus.Failed -> WmmIcons.Alert
}

/** The server's mark under the button (`.mark`): Removed from the server, or A new version is available with Download again. */
@Composable
internal fun DownloadMarkLine(download: OfflineDownload, onDownloadAgain: () -> Unit) {
    when (download.mark) {
        DownloadMark.None -> Unit
        DownloadMark.RemovedFromServer -> Row(Modifier.padding(start = 4.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(Orange)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Orange)) { append("Removed from the server") }
                    withStyle(SpanStyle(color = WmmColors.TextMuted)) { append(" · this copy plays until you remove it") }
                },
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.sp),
            )
        }
        DownloadMark.NewVersion -> Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(WmmColors.Accent)
            Text("A new version is available", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Accent), modifier = Modifier.weight(1f))
            GoldTextButton("Download again", onDownloadAgain)
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

/** What the download sheet says of an option (DownloadSheet.dc.html). */
internal fun optionLine(o: DownloadOption, tablet: Boolean): String = when (o.mode) {
    PlaybackMode.Direct -> "The file as it is"
    PlaybackMode.Remux, PlaybackMode.AudioTranscode ->
        if (o.audioConverted) "Video as it is, audio converted" + if (tablet) " for this tablet" else "" else "Video as it is"
    PlaybackMode.Transcode -> "Converted on the server"
}

/** "English and Spanish subtitles come with it.": the text subtitles an HLS download carries; null when it carries none. */
internal fun subtitlesLine(plan: DownloadPlan): String? {
    val names = plan.textSubtitles.map { it.label.substringBefore(" (") }.distinct()
    if (names.isEmpty()) return null
    val list = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "$list subtitles come with it."
}

/**
 * The download sheet (DownloadSheet.dc.html; TabDownloadSheet.dc.html as a
 * centred dialog on a tablet): each quality with its size, the Settings
 * quality picked; the audio track and an image subtitle to burn in for a
 * converted download; what comes with it, Wi-Fi only and the space free.
 * An option that would leave under 1 GB free says how much it needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadSheet(
    kicker: String,
    from: String,
    state: ItemDownloadViewModel.State,
    tablet: Boolean,
    onRetryPlan: () -> Unit,
    onDownload: (DownloadQuality, Int?, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = (state.plan as? Load.Ready)?.data
    var picked by rememberSaveable(plan?.fileId) { mutableStateOf(state.settings.quality) }
    var audio by rememberSaveable(plan?.fileId) { mutableStateOf<Int?>(null) }
    var burn by rememberSaveable(plan?.fileId) { mutableStateOf<String?>(null) }
    val device = if (tablet) "tablet" else "phone"
    val body: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (tablet) 3.dp else 4.dp)) {
                Kicker(kicker)
                Text("Download", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = if (tablet) 26.sp else 24.sp, letterSpacing = (-0.24).sp, color = WmmColors.Text))
                Text(from, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
            }
            SheetClose(onDismiss)
        }
        when {
            plan == null && state.plan is Load.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((state.plan as Load.Failed).message, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = WmmColors.Warn))
                GoldTextButton("Try again", onRetryPlan)
            }
            plan == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = WmmColors.Accent, strokeWidth = 2.dp)
            }
            else -> {
                val current = plan.option(picked) ?: plan.options.first()
                val fits = state.storage.fits(current.sizeBytes)
                val hls = current.protocol == StreamProtocol.Hls || burn != null
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(if (tablet) 2.dp else 0.dp)) {
                    Label("Quality", Modifier.padding(bottom = if (tablet) 4.dp else 2.dp))
                    plan.options.forEach { o -> OptionRow(o, o.quality == current.quality, state.storage, tablet) { picked = o.quality } }
                }
                val audioPick = hls && plan.audio.size > 1
                val burnPick = plan.imageSubtitles.isNotEmpty()
                val audioChips: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Audio")
                        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val chosen = audio ?: plan.startingAudio(current)
                            plan.audio.forEach { a -> PickChip(a.label, a.index == chosen) { audio = a.index } }
                        }
                    }
                }
                val burnChips: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Subtitles burned into video")
                        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PickChip("None", burn == null) { burn = null }
                            plan.imageSubtitles.forEach { s -> PickChip(listOfNotNull(s.label, s.format).joinToString(" · "), burn == s.id) { burn = s.id } }
                        }
                    }
                }
                if (tablet && audioPick && burnPick) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        audioChips(Modifier.weight(1f))
                        burnChips(Modifier.weight(1f))
                    }
                } else {
                    if (audioPick) audioChips(Modifier)
                    if (burnPick) burnChips(Modifier)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (burn != null) SheetLine(PhoneIcons.Tracks, "The server prepares a copy with these subtitles always on. Its size may differ from this estimate.")
                    val tracks = if (!hls) "Every audio track and the supported text subtitles come with it. Image and styled subtitles need a prepared copy." else subtitlesLine(plan)
                    tracks?.let { SheetLine(PhoneIcons.Tracks, it) }
                    if (state.settings.wifiOnly) {
                        Row(Modifier.clickable(onClick = onOpenSettings), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(PhoneIcons.Wifi, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                            Text(
                                buildAnnotatedString {
                                    append("Downloads on Wi-Fi only. ")
                                    withStyle(SpanStyle(color = Color(0xFFE5E5E5), textDecoration = TextDecoration.Underline)) { append("Change in Settings") }
                                },
                                style = LineText,
                            )
                        }
                    }
                    if (fits) {
                        SheetLine(PhoneIcons.Storage, "${freeSize(state.storage.freeBytes)} free on this $device")
                    } else {
                        SheetLine(WmmIcons.Alert, "Needs ${neededSize(current.sizeBytes)} free. This $device has ${freeSize(state.storage.freeBytes)}. Pick a lower quality or free up space.", Orange)
                    }
                }
                val label = "Download · ${downloadSize(current.sizeBytes, current.estimated)}"
                val download = { onDownload(current.quality, audio.takeIf { audioPick }, burn.takeIf { burnPick }) }
                if (tablet) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        media.watchmy.client.mobile.components.TonalButton("Cancel", onDismiss)
                        SheetDownloadButton(label, fits, state.starting, large = false, onClick = download)
                    }
                } else {
                    SheetDownloadButton(label, fits, state.starting, large = true, onClick = download, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (tablet) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.width(600.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1A1A1D)).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { body() }
        }
    } else {
        RequestBottomSheet(onDismiss) { body() }
    }
}

private val LineText = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 18.8.sp, color = WmmColors.TextMuted)

@Composable
private fun SheetLine(icon: ImageVector, text: String, color: Color = WmmColors.TextMuted) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = LineText.copy(color = color))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted), modifier = modifier)

/** A quality (`.opt`): radio, name and what it is, size; one that won't fit is dimmed and says what it needs. */
@Composable
private fun OptionRow(o: DownloadOption, on: Boolean, storage: DownloadStorage, tablet: Boolean, onPick: () -> Unit) {
    val room = storage.fits(o.sizeBytes)
    val color = if (room) WmmColors.Text else Color(0xFF8C8C8C)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (tablet && on) WmmColors.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = on, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(20.dp).border(2.dp, if (on) WmmColors.Accent else if (room) WmmColors.TextMuted else Color(0xFF5A5A5F), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(WmmColors.Accent)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(o.quality.label, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = color))
            Text(
                if (room) optionLine(o, tablet) else "Needs ${neededSize(o.sizeBytes)} free",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = if (room) WmmColors.TextMuted else Orange),
            )
        }
        Text(downloadSize(o.sizeBytes, o.estimated), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (tablet) 15.sp else 14.sp, color = color), maxLines = 1)
    }
}

/** A choice chip (`.pick`): gold when picked. */
@Composable
private fun PickChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) WmmColors.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (on) WmmColors.Accent else WmmColors.Line3, RoundedCornerShape(8.dp))
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (on) WmmColors.Accent else Color(0xFFE5E5E5)), maxLines = 1)
    }
}

/** The sheet's Download · size: burgundy, or off (`.btn.off`) when it won't fit. */
@Composable
private fun SheetDownloadButton(text: String, enabled: Boolean, busy: Boolean, large: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.height(if (large) 52.dp else 40.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = WmmColors.Brand,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF26262A),
            disabledContentColor = WmmColors.TextMuted,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        } else if (enabled) {
            Icon(WmmIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (large) 16.sp else 14.sp), modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

/**
 * A held download's options (DownloadMenu.dc.html): what it is and where it
 * stands, the server's mark explained, then Play (Play this copy beside a new
 * version), Download again, Pause or Resume, Download settings while it waits
 * for Wi-Fi, and Cancel download or Remove download.
 */
@Composable
internal fun DownloadMenu(
    kicker: String,
    download: OfflineDownload,
    wifiOnly: Boolean,
    device: String,
    onPlay: () -> Unit,
    onDownloadAgain: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status = download.status
    val done = download.downloaded
    RequestBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Kicker(kicker)
            Text(downloadMenuTitle(status), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.24).sp, color = WmmColors.Text))
            downloadMenuLine(download, device, wifiOnly)?.let { Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted)) }
        }
        val pct = (status as? DownloadStatus.Downloading)?.percent ?: (status as? DownloadStatus.Paused)?.percent
        if (pct != null) {
            Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(WmmColors.Line2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(WmmColors.Accent))
            }
        }
        when (download.mark) {
            DownloadMark.NewVersion -> MarkBox(WmmColors.Accent, "A new version is available", "The server has a different file for this ${if (download.item.kind == media.watchmy.client.model.OfflineItem.Kind.Episode) "episode" else "movie"} now. This copy still plays until you remove it.")
            DownloadMark.RemovedFromServer -> MarkBox(Orange, "Removed from the server", "This copy plays until you remove it. What you watch in it isn’t sent to the server.")
            DownloadMark.None -> Unit
        }
        Column {
            if (done) MenuRow(WmmIcons.Play, if (download.mark == DownloadMark.NewVersion) "Play this copy" else "Play", onClick = onPlay)
            if (done && download.mark == DownloadMark.NewVersion) MenuRow(WmmIcons.Replace, "Download again", onClick = onDownloadAgain)
            if (status is DownloadStatus.Downloading || status == DownloadStatus.Preparing) MenuRow(PhoneIcons.Pause, "Pause download", onClick = onPause)
            if (status is DownloadStatus.Paused) MenuRow(WmmIcons.Download, "Resume download", onClick = onResume)
            if (status is DownloadStatus.Waiting && status.reason == DownloadStatus.Reason.Wifi) MenuRow(PhoneIcons.Wifi, "Download settings", onClick = onOpenSettings)
            if (done) MenuRow(WmmIcons.Trash, "Remove download", DangerText, onRemove) else MenuRow(WmmIcons.Close, "Cancel download", DangerText, onRemove)
        }
    }
}

@Composable
private fun MarkBox(color: Color, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Column {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, color = color))
            Text(body, style = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, color = WmmColors.TextSoft))
        }
    }
}

/** A menu item (`.mi`): 56 dp, icon and words. */
@Composable
internal fun MenuRow(icon: ImageVector, text: String, color: Color = Color(0xFFE5E5E5), onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(text, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = color))
    }
}
