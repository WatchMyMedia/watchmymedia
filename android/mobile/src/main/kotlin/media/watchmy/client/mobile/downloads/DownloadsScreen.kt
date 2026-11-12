package media.watchmy.client.mobile.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import media.watchmy.client.mobile.components.MainTopBar
import media.watchmy.client.mobile.components.MessageHost
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.mobile.navigation.MobileRoutes
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.downloadRowState
import media.watchmy.client.model.downloadSize
import media.watchmy.client.model.downloadTitle
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.freeSize
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.WmmIcons
import media.watchmy.client.ui.rememberTabletLayout

/**
 * Downloads (Downloads.dc.html; TabDownloads.dc.html on a tablet): the space
 * downloads take and what is free, then movies, and shows with their
 * episodes, each with its size, quality and state; ⋯ opens its menu, a swipe
 * removes it on the phone, and a tap opens its page.
 */
@Composable
fun DownloadsScreen(
    onPlay: (PlayRequest) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
    onOpenEpisode: (showId: Long, season: Int, episodeId: Long) -> Unit,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalAppServices.current
    val vm = viewModel { DownloadsViewModel(services.downloads) }
    val state by vm.state.collectAsStateWithLifecycle()
    val user by services.auth.user.collectAsStateWithLifecycle()
    val layout = rememberTabletLayout()
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshStorage() }
    val open = { d: OfflineDownload ->
        val item = d.item
        when {
            item.kind == OfflineItem.Kind.Movie && item.movieId != null -> onOpenMovie(item.movieId!!)
            item.showId != null && item.season != null && item.episodeId != null -> onOpenEpisode(item.showId!!, item.season!!, item.episodeId!!)
            item.showId != null -> onOpenShow(item.showId!!)
        }
    }

    Column(modifier.fillMaxSize().background(WmmColors.Ground)) {
        if (!layout.isTablet) MainTopBar("Downloads", user?.name, onSearch = { onNavigate(MobileRoutes.Search) }, onAvatar = { onNavigate(MobileRoutes.Settings) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (layout.isTablet) {
                TabletDownloads(state, open, { menuFor = it.id }, onOpenShow, onHome = { onNavigate(MobileRoutes.Home) })
            } else {
                PhoneDownloads(state, open, { menuFor = it.id }, onOpenShow, onRemove = { vm.remove(it.id) }, onHome = { onNavigate(MobileRoutes.Home) })
            }
            MessageHost(state.message, vm::messageShown, Modifier.align(Alignment.BottomCenter))
        }
    }

    val held = state.all.firstOrNull { it.id == menuFor }
    if (held != null) {
        DownloadMenu(
            kicker = held.item.showTitle?.takeIf { held.item.kind == OfflineItem.Kind.Episode }?.let { show ->
                listOfNotNull(show, held.item.season?.let { s -> held.item.episode?.let { e -> episodeCode(s, e) } }).joinToString(" · ")
            } ?: held.item.title,
            download = held,
            wifiOnly = state.settings.wifiOnly,
            device = if (layout.isTablet) "tablet" else "phone",
            onPlay = {
                menuFor = null
                onPlay(playRequest(held))
            },
            onDownloadAgain = {
                menuFor = null
                open(held)
            },
            onPause = { vm.pause(held.id) },
            onResume = { vm.resume(held.id) },
            onOpenSettings = {
                menuFor = null
                onNavigate(MobileRoutes.Settings)
            },
            onRemove = {
                menuFor = null
                vm.remove(held.id)
            },
            onDismiss = { menuFor = null },
        )
    }
}

/** Plays the copy on this device: the player plays a downloaded file from it, online or not. */
internal fun playRequest(d: OfflineDownload): PlayRequest {
    val item = d.item
    val episode = item.kind == OfflineItem.Kind.Episode
    return PlayRequest(
        fileId = d.fileId,
        title = if (episode) item.showTitle ?: item.title else item.title,
        subtitle = if (episode) downloadTitle(item) else null,
        backdropPath = item.artPath,
        episodeContext = item.showId?.let { show -> item.season?.let { EpisodeContext(show, it) } },
    )
}

/** The space bar (`section[aria-label=Space…]`): downloads in gold, other apps and files in grey. */
@Composable
private fun SpaceBar(state: DownloadsViewModel.State, legend: Boolean, modifier: Modifier = Modifier) {
    val storage = state.storage
    val total = storage.totalBytes.coerceAtLeast(1)
    val used = state.usedBytes.coerceAtLeast(0)
    val other = (total - storage.freeBytes - used).coerceAtLeast(0)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (legend) 8.dp else 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                Text(usedSize(used), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WmmColors.Text))
                Text(" used by downloads", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
            }
            Text("${freeSize(storage.freeBytes)} free", style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
        }
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(WmmColors.Line)) {
            val usedFraction = used.toFloat() / total
            val otherFraction = other.toFloat() / total
            if (usedFraction > 0) Box(Modifier.fillMaxHeight().weight(usedFraction.coerceAtLeast(0.001f)).background(WmmColors.Accent))
            if (otherFraction > 0) Box(Modifier.fillMaxHeight().weight(otherFraction.coerceAtLeast(0.001f)).background(Color(0xFF4A4A4F)))
            val rest = 1f - usedFraction - otherFraction
            if (rest > 0) Box(Modifier.fillMaxHeight().weight(rest))
        }
        if (legend) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendItem(WmmColors.Accent, "Downloads")
                LegendItem(Color(0xFF4A4A4F), "Other apps and files")
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = WmmColors.TextMuted))
    }
}

/** "66.1 GB", and "0 GB" for nothing. */
internal fun usedSize(bytes: Long): String = if (bytes <= 0) "0 GB" else freeSize(bytes)

/** Nothing downloaded yet, with Go to Home. */
@Composable
private fun EmptyDownloads(tablet: Boolean, onHome: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val size = if (tablet) 72.dp else 64.dp
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF1A1A1D)), contentAlignment = Alignment.Center) {
            Icon(PhoneIcons.Downloads, contentDescription = null, tint = WmmColors.TextMuted, modifier = Modifier.size(size / 2))
        }
        Text("Nothing downloaded yet", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = if (tablet) 22.sp else 20.sp, color = WmmColors.Text), textAlign = TextAlign.Center)
        Text(
            "Download a movie or an episode from its page to watch it without a connection.",
            style = TextStyle(fontFamily = Inter, fontSize = if (tablet) 15.sp else 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        TonalButton("Go to Home", onHome, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PhoneDownloads(
    state: DownloadsViewModel.State,
    onOpen: (OfflineDownload) -> Unit,
    onMore: (OfflineDownload) -> Unit,
    onOpenShow: (Long) -> Unit,
    onRemove: (OfflineDownload) -> Unit,
    onHome: () -> Unit,
) {
    val library = LocalAppServices.current.library
    Column(Modifier.fillMaxSize()) {
        SpaceBar(state, legend = true, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp))
        if (state.all.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyDownloads(tablet = false, onHome = onHome) }
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.movies.isNotEmpty()) {
                item(key = "movies") { Section("Movies") }
                items(state.movies, key = { it.id }) { d ->
                    Swipeable(d, onRemove) {
                        DownloadRow(d, library.imageUrl(d.item.posterPath, ImageSize.W154), artWidth = 40.dp, artHeight = 60.dp, onOpen = { onOpen(d) }, onMore = { onMore(d) })
                    }
                }
            }
            if (state.shows.isNotEmpty()) {
                item(key = "shows") { Section("TV shows", Modifier.padding(top = 6.dp)) }
                state.shows.forEach { g ->
                    item(key = "show-${g.showId}-${g.title}") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = g.showId != null) { g.showId?.let(onOpenShow) }.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Artwork(library.imageUrl(g.posterPath, ImageSize.W92), seed = g.title, contentDescription = null, modifier = Modifier.size(32.dp, 48.dp), shape = RoundedCornerShape(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(g.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(groupLine(g), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), maxLines = 1)
                            }
                        }
                    }
                    items(g.episodes, key = { it.id }) { d ->
                        Swipeable(d, onRemove) {
                            DownloadRow(d, library.imageUrl(d.item.artPath, ImageSize.W300), artWidth = 96.dp, artHeight = 54.dp, startPadding = 28.dp, titleSize = 14, subSize = 12, onOpen = { onOpen(d) }, onMore = { onMore(d) })
                        }
                    }
                }
            }
        }
    }
}

/** "3 episodes · 12.1 GB". */
private fun groupLine(g: DownloadsViewModel.ShowGroup) = "${plural(g.episodes.size, "episode")} · ${downloadSize(g.sizeBytes, g.estimated)}"

@Composable
private fun Section(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.96.sp, color = WmmColors.TextMuted),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Swipe left to remove (`r.swiped`): the red Remove under the row. */
@Composable
private fun Swipeable(d: OfflineDownload, onRemove: (OfflineDownload) -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) onRemove(d)
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(Color(0xFFB42318)).padding(end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(WmmIcons.Trash, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Remove", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White))
            }
        },
    ) { content() }
}

/** The state line's colour and icon (`.st`), as the Downloads board draws each. */
private fun stateLook(d: OfflineDownload): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector?> = when {
    d.mark == DownloadMark.RemovedFromServer -> Orange to PhoneIcons.RemovedRing
    d.mark == DownloadMark.NewVersion -> WmmColors.Accent to WmmIcons.Replace
    d.status is DownloadStatus.Downloading -> WmmColors.Accent to WmmIcons.Download
    d.status == DownloadStatus.Failed -> Orange to WmmIcons.Alert
    else -> WmmColors.TextMuted to statusIcon(d.status)
}

@Composable
private fun StateLine(d: OfflineDownload) {
    val (color, icon) = stateLook(d)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        else CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
        Text(downloadRowState(d), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgressLine(d: OfflineDownload, modifier: Modifier = Modifier) {
    val pct = (d.status as? DownloadStatus.Downloading)?.percent ?: return
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(WmmColors.Line2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(WmmColors.Accent))
    }
}

/** "Original · 46.1 GB". */
private fun sizeLine(d: OfflineDownload) = "${d.quality.short} · ${downloadSize(d.sizeBytes, d.sizeEstimated)}"

/** A download's row (`.row`): artwork, title, quality and size, state, and ⋯. */
@Composable
private fun DownloadRow(
    d: OfflineDownload,
    imageUrl: String?,
    artWidth: Dp,
    artHeight: Dp,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    startPadding: Dp = 16.dp,
    titleSize: Int = 15,
    subSize: Int = 13,
) {
    Row(
        Modifier.fillMaxWidth().background(WmmColors.Ground).heightIn(min = 68.dp).clickable(onClick = onOpen).padding(start = startPadding, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(imageUrl, seed = d.item.title, contentDescription = null, modifier = Modifier.size(artWidth, artHeight), shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(downloadTitle(d.item), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = titleSize.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sizeLine(d), style = TextStyle(fontFamily = Inter, fontSize = subSize.sp, color = WmmColors.TextMuted), maxLines = 1)
            StateLine(d)
            ProgressLine(d, Modifier.padding(top = 4.dp))
        }
        MoreButton(d, onMore)
    }
}

@Composable
private fun MoreButton(d: OfflineDownload, onMore: () -> Unit) {
    val label = "Options for ${downloadTitle(d.item)}"
    IconButton(onClick = onMore, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Icon(WmmIcons.MoreHoriz, contentDescription = null, tint = WmmColors.TextSoft)
    }
}

/** TabDownloads.dc.html: the header with the space bar, movies in a panel on the left, a panel per show on the right. */
@Composable
private fun TabletDownloads(
    state: DownloadsViewModel.State,
    onOpen: (OfflineDownload) -> Unit,
    onMore: (OfflineDownload) -> Unit,
    onOpenShow: (Long) -> Unit,
    onHome: () -> Unit,
) {
    val library = LocalAppServices.current.library
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 28.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Downloads", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = WmmColors.Text), modifier = Modifier.weight(1f).semantics { heading() })
            SpaceBar(state, legend = false, modifier = Modifier.width(360.dp))
        }
        if (state.all.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) { EmptyDownloads(tablet = true, onHome = onHome) }
            return
        }
        Row(Modifier.fillMaxSize().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.width(480.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.movies.isNotEmpty()) {
                    Heading("Movies", "${state.movies.size} · ${downloadSize(state.movies.sumOf { it.sizeBytes }, estimated = false)}")
                    Column(Modifier.panel().padding(vertical = 4.dp)) {
                        state.movies.forEach { d ->
                            TabletRow(d, library.imageUrl(d.item.posterPath, ImageSize.W154), 56.dp, 84.dp, onOpen = { onOpen(d) }, onMore = { onMore(d) })
                        }
                    }
                }
                Text(
                    "Downloads play from this tablet, online or not. What you watch offline is sent to the server when you’re back online.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp, lineHeight = 19.5.sp, color = WmmColors.TextMuted),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.shows.isNotEmpty()) {
                    val episodes = state.shows.flatMap { it.episodes }
                    Heading("TV shows", "${plural(episodes.size, "episode")} · ${downloadSize(episodes.sumOf { it.sizeBytes }, estimated = false)}")
                    state.shows.forEach { g ->
                        Column(Modifier.panel().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = g.showId != null) { g.showId?.let(onOpenShow) }.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Artwork(library.imageUrl(g.posterPath, ImageSize.W92), seed = g.title, contentDescription = null, modifier = Modifier.size(32.dp, 48.dp), shape = RoundedCornerShape(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(g.title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = WmmColors.Text))
                                    Text(groupLine(g), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted))
                                }
                            }
                            g.episodes.forEach { d ->
                                Box(Modifier.fillMaxWidth().height(1.dp).background(WmmColors.Line))
                                TabletRow(d, library.imageUrl(d.item.artPath, ImageSize.W300), 96.dp, 54.dp, episode = true, onOpen = { onOpen(d) }, onMore = { onMore(d) })
                            }
                        }
                    }
                }
                Box(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Heading(title: String, small: String) {
    Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), modifier = Modifier.alignByBaseline().semantics { heading() })
        Text(small, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.alignByBaseline())
    }
}

/** A row in a tablet panel; an episode puts its size and state side by side. */
@Composable
private fun TabletRow(d: OfflineDownload, imageUrl: String?, artWidth: Dp, artHeight: Dp, onOpen: () -> Unit, onMore: () -> Unit, episode: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (episode) 64.dp else 68.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(imageUrl, seed = d.item.title, contentDescription = null, modifier = Modifier.size(artWidth, artHeight), shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (episode) 2.dp else 3.dp)) {
            Text(downloadTitle(d.item), style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (episode) 14.sp else 15.sp, color = WmmColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (episode) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(sizeLine(d), style = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = WmmColors.TextMuted), maxLines = 1)
                    StateLine(d)
                }
            } else {
                Text(sizeLine(d), style = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = WmmColors.TextMuted), maxLines = 1)
                StateLine(d)
            }
            ProgressLine(d, Modifier.padding(top = 4.dp))
        }
        MoreButton(d, onMore)
    }
}

/** A tablet panel: the boards' 12 dp card on #151517 with a #222225 rim. */
private fun Modifier.panel(): Modifier = clip(RoundedCornerShape(12.dp)).background(WmmColors.Surface).border(1.dp, WmmColors.Line, RoundedCornerShape(12.dp))
