package media.watchmy.client.mobile.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.mobile.components.CardCaption
import media.watchmy.client.mobile.components.PhoneIcons
import media.watchmy.client.mobile.components.PlayGlyph
import media.watchmy.client.mobile.components.TonalButton
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.formatRemaining
import media.watchmy.client.model.plural
import media.watchmy.client.presentation.downloads.DownloadsViewModel
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.ProgressStrip
import media.watchmy.client.ui.components.WmmIcons

/**
 * Home with no server (HomeOffline.dc.html; TabHomeOffline.dc.html): You're
 * offline with Try again, then the downloads partway through to resume, and
 * what is on this device. [tryingAgain] shows Try again at work.
 */
@Composable
fun OfflineHome(
    downloads: DownloadsViewModel.State,
    tryingAgain: Boolean,
    onTryAgain: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onSeeAll: () -> Unit,
    tablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val services = LocalAppServices.current
    val server by services.servers.current.collectAsStateWithLifecycle()
    val device = if (tablet) "tablet" else "phone"
    val held = downloads.all.filter { it.downloaded }
    val nothing = held.isEmpty()
    val name = server?.server?.name ?: "the server"
    val lead = if (nothing) "Can’t reach $name." else "Can’t reach $name. Your downloads still play, and what you watch is sent to the server when you’re back online."
    val side = if (tablet) 24.dp else 16.dp
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = if (tablet) 8.dp else 4.dp, bottom = 24.dp)) {
        StatusCard(lead, tryingAgain, onTryAgain, tablet, Modifier.padding(horizontal = side))
        if (nothing) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = if (tablet) 96.dp else 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Nothing is downloaded on this $device", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = if (tablet) 20.sp else 18.sp, color = WmmColors.Text), textAlign = TextAlign.Center)
                Text(
                    "Downloads play without a connection. When you’re back online, download a movie or an episode from its page.",
                    style = TextStyle(fontFamily = Inter, fontSize = if (tablet) 15.sp else 14.sp, lineHeight = 21.sp, color = WmmColors.TextMuted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 460.dp),
                )
            }
            return@Column
        }
        val partway = downloads.partway
        if (partway.isNotEmpty()) {
            Heading("Continue watching", "Downloaded", null, Modifier.padding(start = side, end = side, top = if (tablet) 28.dp else 24.dp, bottom = 10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 12.dp)) {
                items(partway, key = { it.id }) { d -> ResumeCard(d, if (tablet) 280.dp else 232.dp, tablet) { onPlay(playRequest(d)) } }
            }
        }
        Heading(
            "On this $device",
            if (tablet) usedSize(downloads.usedBytes) else null,
            onSeeAll,
            Modifier.padding(start = side, end = side, top = if (tablet) 28.dp else 24.dp, bottom = 10.dp),
        )
        val tiles = tiles(held)
        LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 8.dp)) {
            items(tiles, key = { it.key }) { t ->
                // A movie opens its page, which works offline; a show's episodes are listed in Downloads.
                PosterTile(t, if (tablet) 150.dp else 116.dp) { if (t.movieId != null) onOpenMovie(t.movieId) else onSeeAll() }
            }
        }
    }
}

/** The status card (`section[role=status]`): the struck-through cloud, You're offline, what still works, Try again. */
@Composable
private fun StatusCard(lead: String, trying: Boolean, onTryAgain: () -> Unit, tablet: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val button: @Composable () -> Unit = {
        if (trying) {
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(Color(0xFF26262A)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), color = WmmColors.TextMuted, strokeWidth = 2.dp)
                Text("Trying again…", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.TextMuted))
            }
        } else {
            TonalButton("Try again", onTryAgain, icon = WmmIcons.Replace)
        }
    }
    val icon: @Composable (Dp) -> Unit = { size ->
        Box(Modifier.size(size).clip(CircleShape).background(WmmColors.Line2), contentAlignment = Alignment.Center) {
            Icon(PhoneIcons.CloudOff, contentDescription = null, tint = WmmColors.TextSoft, modifier = Modifier.size(size / 2))
        }
    }
    val words: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("You’re offline", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = if (tablet) 22.sp else 20.sp, color = WmmColors.Text), modifier = Modifier.semantics { heading() })
            Text(lead, style = TextStyle(fontFamily = Inter, fontSize = if (tablet) 15.sp else 14.sp, lineHeight = if (tablet) 21.75.sp else 20.3.sp, color = WmmColors.TextMuted))
        }
    }
    val card = modifier.fillMaxWidth().clip(shape).background(WmmColors.Surface).border(1.dp, WmmColors.Line, shape).semantics { liveRegion = LiveRegionMode.Polite }
    if (tablet) {
        Row(card.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            icon(52.dp)
            words(Modifier.weight(1f))
            button()
        }
    } else {
        Column(card.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                icon(44.dp)
                words(Modifier)
            }
            button()
        }
    }
}

@Composable
private fun Heading(title: String, small: String?, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = WmmColors.Text), modifier = Modifier.alignByBaseline().semantics { heading() })
        if (small != null) Text(small, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = WmmColors.TextMuted), modifier = Modifier.alignByBaseline())
        Box(Modifier.weight(1f))
        if (onSeeAll != null) {
            Text(
                "See all",
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = WmmColors.Accent),
                modifier = Modifier.alignByBaseline().clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** A download partway through: artwork with the play glyph and progress, then "S03 E06 · 31 min left". */
@Composable
private fun ResumeCard(d: OfflineDownload, width: Dp, tablet: Boolean, onClick: () -> Unit) {
    val library = LocalAppServices.current.library
    val watch = d.partway ?: return
    val item = d.item
    val left = formatRemaining(watch.durationMs - watch.positionMs)
    val subtitle = if (item.kind == OfflineItem.Kind.Episode && item.season != null && item.episode != null) {
        listOfNotNull(episodeCode(item.season!!, item.episode!!), item.title.takeIf { tablet }, left).joinToString(" · ")
    } else {
        left
    }
    Column(Modifier.width(width).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Artwork(library.imageUrl(item.artPath, ImageSize.W780), seed = item.title, contentDescription = null, modifier = Modifier.width(width).height(width * 9 / 16), shape = RoundedCornerShape(8.dp)) {
            Box(Modifier.fillMaxSize()) {
                PlayGlyph()
                ProgressStrip(watch.positionMs.toFloat() / watch.durationMs, Modifier.align(Alignment.BottomStart))
            }
        }
        CardCaption(if (item.kind == OfflineItem.Kind.Episode) item.showTitle ?: item.title else item.title, subtitle)
    }
}

/** A tile on "On this phone": a movie, or a show with how many of its episodes are here. */
private data class Tile(val key: String, val title: String, val subtitle: String, val posterPath: String?, val movieId: Long?, val showId: Long?)

private fun tiles(held: List<OfflineDownload>): List<Tile> {
    val movies = held.filter { it.item.kind != OfflineItem.Kind.Episode }.map { d ->
        Tile("m-${d.id}", d.item.title, "Movie", d.item.posterPath, d.item.movieId, null)
    }
    val shows = held.filter { it.item.kind == OfflineItem.Kind.Episode }.groupBy { it.item.showId }.map { (showId, eps) ->
        val first = eps.first().item
        Tile("s-$showId", first.showTitle ?: first.title, plural(eps.size, "episode"), first.posterPath, null, showId)
    }
    // As downloaded, newest first, movies and shows together.
    val order = held.map { it.id }
    return (movies + shows).sortedBy { t ->
        val ids = if (t.movieId != null) listOf(t.key.removePrefix("m-")) else held.filter { "s-${it.item.showId}" == t.key }.map { it.id }
        ids.minOf { order.indexOf(it) }
    }
}

@Composable
private fun PosterTile(t: Tile, width: Dp, onClick: () -> Unit) {
    val library = LocalAppServices.current.library
    Column(Modifier.width(width).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Artwork(library.imageUrl(t.posterPath, ImageSize.W342), seed = t.title, contentDescription = null, modifier = Modifier.width(width).height(width * 3 / 2), shape = RoundedCornerShape(8.dp)) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)).semantics { contentDescription = "Downloaded" },
                    contentAlignment = Alignment.Center,
                ) { Icon(WmmIcons.Download, contentDescription = null, tint = WmmColors.Accent, modifier = Modifier.size(14.dp)) }
            }
        }
        CardCaption(t.title, t.subtitle)
    }
}
