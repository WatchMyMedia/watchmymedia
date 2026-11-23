package media.watchmy.client.tv.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.Person
import media.watchmy.client.tv.components.CardRow
import media.watchmy.client.tv.components.ContentEnd
import media.watchmy.client.tv.components.ContentStart
import media.watchmy.client.tv.components.FocusMemory
import media.watchmy.client.tv.components.FocusableBox
import media.watchmy.client.tv.components.FocusedArt
import media.watchmy.client.tv.components.SafeY
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.focusMemory
import media.watchmy.client.ui.WmmColors
import media.watchmy.client.ui.components.Artwork
import media.watchmy.client.ui.components.placeholderColor

/** One fact in a title's facts line: words, words in bold (the runtime), or an outlined tag (a rating, "4K"). */
internal sealed interface Fact {
    data class Words(val text: String, val bold: Boolean = false) : Fact
    data class Tag(val text: String, val gold: Boolean = false) : Fact
}

/**
 * A title's facts in a line, a grey dot between them (`.facts`): "2025 ·
 * PG-13 · 1 h 58 min · Thriller · ★ 7.8 · 4K HDR 5.1". Tags that follow one
 * another sit together, without dots.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FactsLine(facts: List<Fact>, modifier: Modifier = Modifier) {
    if (facts.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        facts.forEachIndexed { i, f ->
            val tagRun = i > 0 && f is Fact.Tag && facts[i - 1] is Fact.Tag
            if (i > 0 && !tagRun) Text("·", style = TvType.Body, color = TvPalette.Muted)
            when (f) {
                is Fact.Words -> Text(
                    f.text,
                    style = if (f.bold) TvType.BodyStrong else TvType.Body,
                    color = if (f.bold) WmmColors.Text else WmmColors.TextSoft,
                    maxLines = 1,
                )
                is Fact.Tag -> TvTag(f.text, gold = f.gold, modifier = if (tagRun) Modifier.padding(start = 0.dp) else Modifier)
            }
        }
    }
}

/**
 * When it ends, in the viewer's words: [lead] in bold ("46 min in", "29 min
 * left", "Not watched yet") and [rest] after it (" · ends 9:24 PM").
 */
@Composable
internal fun WhenLine(lead: String, rest: String?, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = WmmColors.Text)) { append(lead) }
            if (!rest.isNullOrEmpty()) withStyle(SpanStyle(color = WmmColors.TextSoft)) { append(rest) }
        },
        style = TvType.Body,
        modifier = modifier,
        maxLines = 2,
    )
}

/** How far through it is (`.meter`): a gold bar on a grey track, [width] wide and 8 high. */
@Composable
internal fun Meter(progress: Float, modifier: Modifier = Modifier, width: Dp = 560.dp, spoken: String? = null) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .width(width)
            .height(8.dp)
            .background(Color.White.copy(alpha = 0.22f), shape)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(WmmColors.Accent, shape))
    }
}

/** An overview of [lines] lines at most (three on a movie, two on an episode), short enough to read across a room. */
@Composable
internal fun Overview(text: String, lines: Int, modifier: Modifier = Modifier, width: Dp = 780.dp) {
    if (text.isBlank()) return
    Text(text, style = TvType.Body, color = WmmColors.TextSoft, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = modifier.widthIn(max = width))
}

/**
 * A title page's picture: the backdrop across the whole screen, faded into
 * the ground from the left and towards the foot ([solidFrom] of the height
 * is plain ground), so the words over it read and the rows under it sit on
 * the ground.
 */
@Composable
internal fun PageBackdrop(url: String?, seed: String, modifier: Modifier = Modifier, solidFrom: Float = 0.62f) {
    Box(modifier) {
        Artwork(url, seed = seed, contentDescription = null, modifier = Modifier.matchParentSize(), shape = RoundedCornerShape(0.dp))
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to WmmColors.Ground,
                        0.3f to WmmColors.Ground.copy(alpha = 0.94f),
                        0.62f to WmmColors.Ground.copy(alpha = 0.5f),
                        1f to Color.Transparent,
                    ),
                )
                .background(
                    Brush.verticalGradient(
                        (solidFrom - 0.28f).coerceAtLeast(0f) to Color.Transparent,
                        (solidFrom - 0.08f).coerceAtLeast(0f) to WmmColors.Ground.copy(alpha = 0.92f),
                        solidFrom to WmmColors.Ground,
                    ),
                ),
        )
    }
}

/**
 * A cast member (`.who`): a round picture 150 across, the name and the part
 * they play. It takes focus with the picture growing in the gold ring, and
 * TalkBack reads the name and the part as one (the clickable merges them;
 * clearing its semantics would clear its focused state with them). [onClick] is for opening the
 * person's titles; until the server can say which titles a person is in, a
 * press only shows that it was taken.
 */
@Composable
internal fun PersonCard(person: Person, imageUrl: String?, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    FocusableBox(
        onClick = onClick,
        modifier = modifier.width(PersonWidth),
        cornerRadius = 75.dp,
        ring = false,
    ) { focused ->
        Column(Modifier.width(PersonWidth), horizontalAlignment = Alignment.CenterHorizontally) {
            FocusedArt(focused, cornerRadius = 75.dp) {
                val face = Modifier.size(150.dp)
                if (imageUrl != null) {
                    Artwork(imageUrl, seed = person.name, contentDescription = null, modifier = face, shape = CircleShape)
                } else {
                    Box(face.background(placeholderColor(person.name), CircleShape).border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape))
                }
            }
            // The picture grows 6 dp into the gap when focused; the name moves down with it.
            Spacer(Modifier.height(if (focused) 26.dp else 18.dp))
            Text(person.name, style = TvType.Second.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            person.role?.let { Text(it, style = TvType.Caption, color = TvPalette.Muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private val PersonWidth = 200.dp

/**
 * The cast as a row of people (`Cast` and its cards), up to twelve, each
 * registered with [memory] as "cast:i". [trailing] is a line at the
 * heading's right ("Imogen Vale as Tess Arden").
 */
@Composable
internal fun CastRow(
    cast: List<Person>,
    library: LibraryRepository,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
    enter: FocusRequester = FocusRequester.Default,
    onFocusPerson: (Int) -> Unit = {},
) {
    if (cast.isEmpty()) return
    Column(modifier) {
        Text("Cast", style = TvType.CardTitle, color = WmmColors.Text, modifier = Modifier.padding(start = ContentStart, end = ContentEnd, bottom = 12.dp))
        CardRow(enter = enter) {
            items(cast.take(12).size) { i ->
                val p = cast[i]
                PersonCard(
                    p,
                    library.imageUrl(p.profilePath, ImageSize.W185),
                    Modifier.focusMemory(memory, "cast:$i").onFocused { onFocusPerson(i) },
                )
            }
        }
    }
}

/** Calls [action] when this element takes focus. */
internal fun Modifier.onFocused(action: () -> Unit): Modifier = onFocusChanged { if (it.isFocused) action() }

/** A key and what it does, at the foot of a panel or a screen (`.hints`): only where a key does what no button shows. */
@Composable
internal fun KeyHints(hints: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((key, what) in hints) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .widthIn(min = 44.dp)
                        .border(2.dp, TvPalette.Line3, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(key, style = TvType.Caption.copy(fontWeight = FontWeight.Bold), color = WmmColors.TextSoft, maxLines = 1)
                }
                Text(what, style = TvType.Caption, color = TvPalette.Muted, maxLines = 1)
            }
        }
    }
}


/**
 * Once a title page has scrolled past its top, a line stays at the top of
 * the screen saying whose rows these are ([content], in a row), with what
 * Back does, since Back leaves the title rather than scrolling up.
 */
@Composable
internal fun ScrolledHeading(scroll: ScrollState, back: String, content: @Composable RowScope.() -> Unit) {
    AnimatedVisibility(scroll.value > ScrolledFrom, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to WmmColors.Ground, 0.75f to WmmColors.Ground, 1f to Color.Transparent))
                .padding(start = ContentStart, end = ContentEnd, top = SafeY, bottom = 40.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                content()
                Spacer(Modifier.weight(1f))
                // Clear of the clock at the top right.
                KeyHints(listOf("Back" to "Leaves $back"), Modifier.padding(end = 170.dp))
            }
        }
    }
}

/** How far a title page scrolls before [ScrolledHeading] shows. */
private const val ScrolledFrom = 420

/**
 * ▲ goes to the first of [keys] still on screen (the button focus was last
 * on, then the others in order), else on as a plain focus search. A key
 * handler rather than `focusProperties { up = … }`, since a button can leave
 * the page (From the start, once watched) and a requester whose button is
 * gone can't take focus.
 */
internal fun Modifier.upTo(memory: FocusMemory, keys: () -> List<String>): Modifier = onPreviewKeyEvent { e ->
    if (e.key != Key.DirectionUp || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    keys().distinct().any { memory.focus(it) }
}
