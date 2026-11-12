package media.watchmy.client.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.wordmark
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WmmColors
import java.time.ZoneId
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * The time now, moving on at each minute, for progress, minutes left and the
 * move to next at a programme's end (PLAN-LIVE-TV-GUIDE.md §6.3: no polling).
 * It waits off the main thread so a test's clock never has to run it.
 */
@Composable
fun rememberLiveNow(clock: () -> Long = System::currentTimeMillis): Long {
    val now by produceState(clock()) {
        withContext(Dispatchers.Default) {
            while (true) {
                delay(60_000 - value % 60_000 + 50)
                value = clock()
            }
        }
    }
    return now
}

/** Programme times in the device's zone, with its 12- or 24-hour setting. */
@Composable
fun rememberLiveClockFormat(): LiveClockFormat {
    val context = LocalContext.current
    val twentyFour = DateFormat.is24HourFormat(context)
    return remember(twentyFour) { LiveClockFormat(ZoneId.systemDefault(), twentyFour, Locale.getDefault()) }
}

/** The colours the wordmarks take while a logo loads, or on the fakes, which have none to load. */
private val WordmarkColors = listOf(
    Color(0xFF5CC8F0), Color(0xFF7DD3FC), Color(0xFFF5A65B), Color(0xFFF87171), Color(0xFFC4B5FD),
    Color(0xFFFDBA74), Color(0xFF60A5FA), Color(0xFFE2B07A), Color(0xFFF9A8D4), Color(0xFF6EE7B7), Color(0xFFFCD34D),
)

/**
 * The channel's 4:3 logo from the listings provider. Its wordmark (the
 * callsign in the channel's colour) shows while it loads and when it can't.
 * Call only for a channel with a [LiveChannel.logo]; one without shows its
 * number and name as text instead.
 */
@Composable
fun ChannelLogo(channel: LiveChannel, modifier: Modifier = Modifier) {
    val url = LocalAppServices.current.live.artUrl(channel.logo)
    var loaded by remember(url) { mutableStateOf(false) }
    BoxWithConstraints(modifier.aspectRatio(4f / 3f), contentAlignment = Alignment.Center) {
        if (!loaded) Wordmark(channel, maxWidth)
        if (url != null) AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onState = { loaded = it is AsyncImagePainter.State.Success },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Wordmark(channel: LiveChannel, width: Dp) {
    val mark = channel.wordmark()
    val color = WordmarkColors[channel.name.hashCode().absoluteValue % WordmarkColors.size]
    val size = (width.value * when (mark.length) { 1, 2 -> .34f; 3 -> .28f; 4 -> .22f; else -> .18f }).sp
    Text(
        mark, color = color, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = size, maxLines = 1, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().border((width.value / 26).coerceAtLeast(1f).dp, color, RoundedCornerShape(width * .08f)).padding(vertical = width * .06f),
    )
}

/** A channel without a logo, or a picture's fallback without one: the number over the name. */
@Composable
fun ChannelNumberMark(channel: LiveChannel, modifier: Modifier = Modifier, numberSize: TextUnit = 15.sp, nameSize: TextUnit = 10.sp) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(channel.number, color = WmmColors.Text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = numberSize, maxLines = 1)
        Text(channel.name, color = WmmColors.TextSoft, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = nameSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A programme's 16:9 picture (PLAN-LIVE-TV-GUIDE.md §4.1, §6.1): the
 * provider's series card, or with [preferStill] (the wide details panel and
 * the TVs' hero) a matched TMDB still first, captioned "Picture: TMDB". With
 * no picture it falls back to the channel's logo, then its number and name.
 * Pictures are decorative: the title beside them is the text.
 */
@Composable
fun ProgrammePicture(
    programme: LiveProgramme?,
    channel: LiveChannel,
    modifier: Modifier = Modifier,
    preferStill: Boolean = false,
    caption: Boolean = preferStill,
    captionSize: TextUnit = 11.sp,
    titleLettering: Boolean = true,
) {
    val live = LocalAppServices.current.live
    val still = programme?.still?.takeIf { preferStill }
    val path = still ?: programme?.picture
    BoxWithConstraints(modifier.aspectRatio(16f / 9f).background(WmmColors.Surface2)) {
        val width = maxWidth
        if (programme != null && path != null) {
            val url = live.artUrl(path)
            Box(Modifier.fillMaxSize().background(placeholderBrush(programme.title))) {
                // Nothing to load (the fakes): the placeholder carries the title, as a title card does.
                if (url == null && titleLettering) {
                    Text(
                        programme.title, color = WmmColors.Text.copy(alpha = .9f), fontFamily = Inter, fontWeight = FontWeight.ExtraBold,
                        fontSize = (width.value * .11f).sp, lineHeight = (width.value * .12f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = width * .07f, vertical = width * .06f),
                    )
                }
                if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (caption && (still != null || programme.pictureFromTmdb)) {
                    Text(
                        "Picture: TMDB", color = WmmColors.TextSoft, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = captionSize,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (channel.logo != null) ChannelLogo(channel, Modifier.fillMaxWidth(if (preferStill) .34f else .44f))
                else ChannelNumberMark(channel, numberSize = (width.value * .12f).coerceAtLeast(15f).sp, nameSize = (width.value * .05f).coerceAtLeast(10f).sp)
            }
        }
    }
}

/** A cell's New, Live or HD tag. */
@Composable
fun ProgrammeTag(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 11.sp, height: Dp = 18.dp) {
    val color = when (text) {
        "New" -> WmmColors.Accent
        "Live" -> Color(0xFFFCA5A5)
        else -> WmmColors.TextSoft
    }
    val border = when (text) {
        "New" -> WmmColors.Accent.copy(alpha = .6f)
        "Live" -> Color(0xFFFCA5A5).copy(alpha = .6f)
        else -> Color(0xFF5A5A60)
    }
    Box(modifier.height(height).border(1.dp, border, RoundedCornerShape(50)).padding(horizontal = height / 3), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = fontSize, maxLines = 1)
    }
}

/** A thin progress bar for what is on now. */
@Composable
fun LiveProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = WmmColors.Line2) {
    Box(modifier.height(height).background(track, RoundedCornerShape(height))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(WmmColors.Accent, RoundedCornerShape(height)))
    }
}

/** The fade a TV hero's picture takes into the page on its left and bottom. */
val HeroFade: Brush = Brush.horizontalGradient(0f to WmmColors.Ground, .35f to WmmColors.Ground.copy(alpha = .6f), 1f to Color.Transparent)
