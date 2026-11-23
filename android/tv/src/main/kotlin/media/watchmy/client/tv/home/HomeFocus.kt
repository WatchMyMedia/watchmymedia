package media.watchmy.client.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.formatDate
import media.watchmy.client.model.formatDay
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.heading
import media.watchmy.client.model.nextAt
import media.watchmy.client.model.onAt
import media.watchmy.client.model.plural
import media.watchmy.client.model.seeAllWords
import media.watchmy.client.tv.components.Kicker
import media.watchmy.client.tv.components.TvPalette
import media.watchmy.client.tv.components.TvTag
import media.watchmy.client.tv.components.TvType
import media.watchmy.client.tv.components.endsAt
import media.watchmy.client.tv.components.timeLeft
import media.watchmy.client.tv.components.wouldEndAt
import media.watchmy.client.ui.WmmColors

/**
 * What a Home row screen says about its focused card, in the order a viewer
 * asks: what it is, how long is left and when it ends, where they were, then
 * two lines about it. Quality (4K, HDR) isn't here: it is on the title's own
 * page, one press away.
 */
@Immutable
internal data class HomeFocus(
    val kicker: String,
    val title: String,
    /** "S3 E7 · The Long Dark", under the title. */
    val episode: String? = null,
    val facts: List<HomeFact> = emptyList(),
    /** 0–1: a progress bar under the facts. */
    val progress: Float? = null,
    /** How far in, for TalkBack ("23 of 52 minutes watched"). */
    val progressLabel: String? = null,
    /** A grey line under the facts: "Would end at 10:18 PM if you started now", "Next: Night Ferry at 9:00 PM". */
    val note: String? = null,
    val overview: String = "",
    /** A red dot before the kicker: a channel on now. */
    val live: Boolean = false,
    /** The card's 16:9 picture in the library, or a programme's picture. */
    val picturePath: String? = null,
    val livePicture: String? = null,
    val seed: String = title,
)

/** One fact in the line under the title: plain, white and bold (time left), or a tag. */
@Immutable
internal data class HomeFact(val text: String, val kind: Kind = Kind.Plain) {
    enum class Kind { Plain, Strong, Tag, New, Recording }
}

/**
 * The focus panel for the card at [index] of this row, or its See all card
 * after the last one. A Live row takes the channel at [index] of [channels]
 * (those available, in the row's order), and the Full guide card after them.
 */
internal fun ScreenRow.homeFocusAt(index: Int, channels: List<LiveChannel>, now: Long, format: LiveClockFormat, libraryType: LibraryType? = null): HomeFocus? {
    val (headingTitle, _) = heading()
    return when (style) {
        RowStyle.Continue -> continueItems.getOrNull(index)?.let { continueFocus(it, now, format) }
        RowStyle.Poster, RowStyle.Wide -> items.getOrNull(index)?.let { itemFocus(it, index, headingTitle, now, format) } ?: seeAllFocus(headingTitle, libraryType)
        RowStyle.Episode -> episodes.getOrNull(index)?.let { episodeFocus(it, headingTitle) } ?: seeAllFocus(headingTitle, libraryType)
        RowStyle.Live -> channels.getOrNull(index)?.let { liveFocus(it, now, format) } ?: HomeFocus(
            kicker = "Live TV",
            title = "Full guide",
            facts = listOf(HomeFact("Every channel, and what’s on next")),
        )
    }
}

private fun continueFocus(c: ContinueItem, now: Long, format: LiveClockFormat): HomeFocus {
    val code = if (c.season != null && c.episode != null) episodeCode(c.season!!, c.episode!!) else null
    val watchedMin = (c.positionMs / 60_000L).toInt()
    val totalMin = (c.durationMs / 60_000L).toInt()
    return HomeFocus(
        kicker = "Continue watching",
        title = c.title,
        episode = if (code != null) listOfNotNull(code, c.subtitle?.takeIf { it.isNotBlank() && !it.startsWith(code) }).joinToString(" · ") else null,
        facts = listOf(HomeFact(timeLeft(c.remainingMs), HomeFact.Kind.Strong), HomeFact(endsAt(c.remainingMs, now, format))),
        progress = c.progress.takeIf { c.durationMs > 0 },
        progressLabel = if (totalMin > 0) "$watchedMin of $totalMin minutes watched" else null,
        picturePath = c.stillPath ?: c.backdropPath,
    )
}

private fun ScreenRowItemKicker(item: BrowseItem, index: Int, headingTitle: String, listId: Long?, style: RowStyle): String = when {
    // The server sends a list row's items in the list's order without their ranks; the row's place stands in.
    listId != null -> "#${index + 1} on the list"
    item.isNew -> listOfNotNull("New", item.subtitle?.takeIf { it.startsWith("added") }).joinToString(" · ")
    style == RowStyle.Wide -> formatDate(item.releaseDate)?.let { "Released $it" } ?: headingTitle
    else -> headingTitle
}

private fun ScreenRow.itemFocus(item: BrowseItem, index: Int, headingTitle: String, now: Long, format: LiveClockFormat): HomeFocus {
    val genre = item.genres.firstOrNull()
    val runtimeMs = item.runtimeMin?.let { it * 60_000L }
    val partway = item.progress?.takeIf { it > 0f && it < 1f }
    val facts = mutableListOf<HomeFact>()
    var note: String? = null
    when (item.kind) {
        ItemKind.Movie -> {
            item.year?.let { facts += HomeFact(it.toString()) }
            if (partway != null && runtimeMs != null) {
                val left = (runtimeMs * (1f - partway)).toLong()
                facts += HomeFact(timeLeft(left), HomeFact.Kind.Strong)
                note = endsAt(left, now, format).replaceFirstChar { it.uppercase() } + " if you go on now"
            } else if (runtimeMs != null) {
                facts += HomeFact(formatDuration(runtimeMs), HomeFact.Kind.Strong)
                note = wouldEndAt(runtimeMs, now, format).replaceFirstChar { it.uppercase() } + " if you started now"
            }
            genre?.let { facts += HomeFact(it) }
        }
        ItemKind.Show -> {
            item.year?.let { facts += HomeFact(it.toString()) }
            item.episodeCount?.let { facts += HomeFact(plural(it, "episode"), HomeFact.Kind.Strong) }
            genre?.let { facts += HomeFact(it) }
        }
        ItemKind.Unmatched -> {
            item.fileName?.let { facts += HomeFact(it) }
            item.fileCount?.let { facts += HomeFact(plural(it, "file")) }
        }
    }
    return HomeFocus(
        kicker = ScreenRowItemKicker(item, index, headingTitle, listId, style),
        title = item.title,
        facts = facts,
        progress = partway,
        note = note,
        overview = item.overview,
        picturePath = item.backdropPath,
    )
}

private fun episodeFocus(e: EpisodeCard, headingTitle: String): HomeFocus = HomeFocus(
    kicker = listOfNotNull(if (e.isNew) "New episode" else headingTitle, formatDay(e.airDate)?.let { "aired $it" }).joinToString(" · "),
    title = e.showTitle,
    episode = "${episodeCode(e.season, e.episode)} · ${e.title}",
    facts = listOfNotNull(HomeFact("Not in your library").takeIf { !e.hasFile }),
    overview = e.overview,
    picturePath = e.stillPath ?: e.showBackdropPath,
    seed = e.showTitle,
)

private fun liveFocus(channel: LiveChannel, now: Long, format: LiveClockFormat): HomeFocus {
    val on = channel.onAt(now) ?: return HomeFocus(
        kicker = "Live TV · ${channel.number} ${channel.name}",
        title = channel.name,
        facts = listOf(HomeFact("No listings for this channel")),
        live = true,
    )
    val code = if (on.season != null && on.episode != null) "S${on.season} E${on.episode}" else null
    val next = channel.nextAt(now) ?: channel.next?.takeIf { it.startMs >= on.endMs }
    val length = (on.endMs - on.startMs).coerceAtLeast(1L)
    val minutesIn = ((now - on.startMs) / 60_000L).coerceAtLeast(0L)
    val minutesLeft = ((on.endMs - now + 59_999L) / 60_000L).coerceAtLeast(0L)
    return HomeFocus(
        kicker = "On now · ${channel.number} ${channel.name}",
        title = on.title,
        episode = listOfNotNull(code, on.episodeTitle).joinToString(" · ").ifEmpty { null },
        facts = listOfNotNull(
            HomeFact(format.span(on.startMs, on.endMs)),
            HomeFact(timeLeft(on.endMs - now), HomeFact.Kind.Strong),
            HomeFact("New", HomeFact.Kind.New).takeIf { on.new },
            HomeFact("Recording", HomeFact.Kind.Recording).takeIf { on.recording != null },
        ),
        progress = ((now - on.startMs).toFloat() / length).coerceIn(0f, 1f),
        progressLabel = "$minutesIn minutes in, $minutesLeft minutes left",
        note = next?.let { "Next: ${it.title} at ${format.time(it.startMs)}" },
        live = true,
        livePicture = on.picture,
        seed = on.title,
    )
}

private fun ScreenRow.seeAllFocus(headingTitle: String, libraryType: LibraryType?): HomeFocus? {
    if (listId != null) return HomeFocus(kicker = headingTitle, title = "See all", facts = listOf(HomeFact(subtitle ?: "With the ones you don’t have")))
    val link = seeAll as? RowLink.Library ?: return null
    val words = seeAllWords(link, libraryType, headingTitle) ?: return null
    return HomeFocus(kicker = headingTitle, title = words.title, facts = listOfNotNull(words.detail?.let(::HomeFact)))
}

/**
 * The focused card's panel over a row screen's picture: the kicker, the
 * title at 64, the episode at 34, the facts with time left in white, a
 * progress bar, a grey note, then two lines about it.
 */
@Composable
internal fun HomeFocusPanel(focus: HomeFocus, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (focus.live) {
                Box(Modifier.padding(end = 12.dp).size(16.dp).background(WmmColors.BrandLine, CircleShape))
            }
            Kicker(focus.kicker)
        }
        Text(
            focus.title,
            modifier = Modifier.padding(top = 14.dp),
            style = TvType.Display,
            color = WmmColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        focus.episode?.let {
            Text(it, modifier = Modifier.padding(top = 10.dp), style = TvType.Heading, color = WmmColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (focus.facts.isNotEmpty()) FactsLine(focus.facts, Modifier.padding(top = 18.dp))
        focus.progress?.let { p ->
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .width(560.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WmmColors.Text.copy(alpha = 0.22f))
                    .then(if (focus.progressLabel != null) Modifier.semantics { contentDescription = focus.progressLabel } else Modifier),
            ) {
                Box(Modifier.fillMaxWidth(p).height(8.dp).clip(RoundedCornerShape(4.dp)).background(WmmColors.Accent))
            }
        }
        focus.note?.let {
            Text(it, modifier = Modifier.padding(top = if (focus.progress != null) 16.dp else 10.dp), style = TvType.Second, color = if (focus.live) WmmColors.TextSoft else TvPalette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (focus.overview.isNotBlank()) {
            Text(
                focus.overview,
                modifier = Modifier.padding(top = 22.dp).widthIn(max = 760.dp),
                style = TvType.Body,
                color = WmmColors.TextSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "29 min left · ends 8:41 PM · TV-14": the facts with a dot between them, tags drawn as tags. */
@Composable
internal fun FactsLine(facts: List<HomeFact>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        facts.forEachIndexed { i, fact ->
            if (i > 0) Text("·", style = TvType.Body, color = TvPalette.Muted)
            when (fact.kind) {
                HomeFact.Kind.Plain -> Text(fact.text, style = TvType.Body, color = WmmColors.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                HomeFact.Kind.Strong -> Text(fact.text, style = TvType.Body.copy(fontWeight = FontWeight.SemiBold), color = WmmColors.Text, maxLines = 1)
                HomeFact.Kind.Tag -> TvTag(fact.text)
                HomeFact.Kind.New -> TvTag(fact.text, gold = true)
                HomeFact.Kind.Recording -> RecordingTag(fact.text)
            }
        }
    }
}

/** A Recording tag: burgundy with its rim, white words. */
@Composable
internal fun RecordingTag(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text.uppercase(),
        modifier = modifier
            .background(WmmColors.Brand, shape)
            .border(2.dp, WmmColors.BrandLine, shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TvType.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = TvType.Caption.fontSize * 0.03f),
        color = WmmColors.Text,
        maxLines = 1,
    )
}
