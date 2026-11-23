package media.watchmy.client.tv.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.airedLabel
import media.watchmy.client.model.formatBytes
import media.watchmy.client.model.monthDay
import media.watchmy.client.tv.requests.tvIcon
import media.watchmy.client.tv.requests.tvTone
import media.watchmy.client.ui.WmmColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import media.watchmy.client.model.Person
import media.watchmy.client.tv.components.InitialFocus
import media.watchmy.client.tv.components.TextBright
import media.watchmy.client.tv.components.TvButton
import media.watchmy.client.tv.components.TvButtonStyle
import media.watchmy.client.tv.components.TvDialog
import media.watchmy.client.tv.components.focusRing
import media.watchmy.client.tv.components.inter
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.placeholderColor

// The TV show page's parts shared with other screens.

/** Cast & details: the show's whole overview, its facts and cast, from the show page and the episode page's More. */
@Composable
internal fun ShowDetailsSheet(show: ShowDetail, library: LibraryRepository, onDismiss: () -> Unit) {
    DetailsSheet(
        title = show.title,
        overview = show.overview,
        facts = listOfNotNull(
            show.status?.let { "Status" to it },
            show.genres.takeIf { it.isNotEmpty() }?.let { "Genres" to it.joinToString(", ") },
            "Episodes" to "${show.episodesOnDisk} on disk",
            "Size" to formatBytes(show.totalSizeBytes),
            show.nextAirDate?.let { "Next episode" to (monthDay(it) ?: it) },
        ),
        cast = show.cast,
        castImage = { library.imageUrl(it.profilePath, ImageSize.W185) },
        onDismiss = onDismiss,
    )
}

/** Where a missing episode's still would be (`.still.miss`): dashed, with the state's icon. */
@Composable
internal fun MissingStill(missing: MissingEpisode, radius: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(WmmColors.Surface, RoundedCornerShape(radius))
            .drawBehind {
                drawRoundRect(
                    WmmColors.Line3,
                    cornerRadius = CornerRadius(radius.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(missing.kind.tvIcon(), contentDescription = null, tint = missing.kind.tvTone(), modifier = Modifier.size(iconSize))
    }
}

/** "Aired yesterday · 52 min" or "Airs Nov 4". */
internal fun episodeMeta(e: EpisodeDetail): String = listOfNotNull(
    airedLabel(e.airDate),
    e.runtimeMin?.takeIf { e.aired }?.let { "$it min" },
).joinToString(" · ")

/**
 * A cast member: a 72 dp circle, the name and the role. [focusable] lets
 * focus stop on it (the movie page's cast): the
 * circle takes the gold ring and grows, and TalkBack reads the name and the
 * role as one. OK does nothing.
 */
@Composable
private fun OldCastMember(person: Person, imageUrl: String?, modifier: Modifier = Modifier, focusable: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tween(140), label = "castFocus")
    val stop = if (focusable) Modifier.focusable(interactionSource = interaction).semantics(mergeDescendants = true) {} else Modifier
    Column(modifier.then(stop).width(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val face = Modifier
            .size(72.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(focused, cornerRadius = 36.dp, offset = 0.dp)
        if (imageUrl != null) {
            Artwork(imageUrl, seed = person.name, contentDescription = person.name.takeIf { !focusable }, modifier = face, shape = CircleShape)
        } else {
            Box(face.background(placeholderColor(person.name), CircleShape))
        }
        Text(person.name, style = inter(22.sp, FontWeight.SemiBold, lineHeight = 28.sp), color = WmmColors.Text, textAlign = TextAlign.Center, maxLines = 2)
        person.role?.let { Text(it, style = inter(22.sp, lineHeight = 28.sp), color = WmmColors.TextMuted, textAlign = TextAlign.Center, maxLines = 2) }
    }
}

/**
 * Cast & details: a side sheet with the full overview, [facts] as label and
 * value rows, and the cast. Back or Close dismisses it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsSheet(title: String, overview: String, facts: List<Pair<String, String>>, cast: List<Person>, castImage: (Person) -> String?, onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .width(760.dp)
                    .fillMaxHeight()
                    .background(WmmColors.Surface)
                    .border(1.dp, WmmColors.Line)
                    .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(title, style = inter(34.sp, FontWeight.Bold, tracking = -0.02f), color = WmmColors.Text, maxLines = 2)
                if (overview.isNotBlank()) Text(overview, style = inter(22.sp, lineHeight = 34.sp), color = WmmColors.TextSoft, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Column {
                    for ((label, value) in facts) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(label.uppercase(), style = inter(22.sp, FontWeight.SemiBold, tracking = 0.06f), color = WmmColors.TextMuted, modifier = Modifier.width(170.dp).padding(top = 3.dp))
                            Text(value, style = inter(22.sp), color = WmmColors.Text)
                        }
                    }
                }
                if (cast.isNotEmpty()) {
                    Text("Cast", style = inter(24.sp, FontWeight.SemiBold), color = WmmColors.Text)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), maxLines = 2) {
                        for (p in cast.take(12)) OldCastMember(p, castImage(p))
                    }
                }
                Box(Modifier.weight(1f))
                TvButton("Close", onDismiss, Modifier.focusRequester(close), style = TvButtonStyle.Ghost)
            }
        }
        InitialFocus(close)
    }
}
