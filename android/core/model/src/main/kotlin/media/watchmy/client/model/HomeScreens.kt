package media.watchmy.client.model

/**
 * Home one row a screen (PLAN-HOME.md §7): what the panel over a row says
 * about its current card (the focused one on TV, the leading one on a
 * tablet) and what OK or the panel's button does with it. The wording is
 * the Apple apps' too.
 */
data class CardPanel(
    /** "Recently added · S03 E07 · added today", "New episode · aired yesterday", "On now · 7.1 KBAY · 7:00–8:00 PM". */
    val kicker: String,
    val title: String,
    /** "2022 · 27 episodes · Drama", joined with " · ". */
    val facts: List<String>,
    /** "4K", "HDR". */
    val tags: List<String> = emptyList(),
    /** "★ 7.8". */
    val rating: String? = null,
    val overview: String = "",
    val target: CardTarget,
    /** The card's 16:9 picture in the library ([LibraryRepository.imageUrl]), or null. */
    val picturePath: String? = null,
    /** A Live programme's picture ([LiveRepository.artUrl]) instead. */
    val livePicture: String? = null,
    /** The placeholder gradient's seed while the picture loads. */
    val seed: String = title,
)

/** What a row's current card opens or does. */
enum class CardTarget {
    /** A movie's page. */
    Film,

    /** A show's page. */
    Show,

    /** An episode's page (a Recently aired card, or a Recently added card for one episode). */
    Episode,

    /** Continue watching: play on from where it stopped. */
    Resume,

    /** Watch a live channel. */
    Channel,

    /** The Live TV guide (the Full guide card). */
    Guide,

    /** A list's page (the See all card). */
    List,

    /** A file that isn't matched to anything. */
    File,
}

/** How many cards the row draws before its See all card (a Live row's channels are the app's). */
val ScreenRow.cardCount: Int
    get() = when (style) {
        RowStyle.Continue -> continueItems.size
        RowStyle.Episode -> episodes.size
        RowStyle.Poster, RowStyle.Wide -> items.size
        RowStyle.Live -> 0
    }

/** "Recently added · TV Shows": the row's name along the bottom edge of the screen before it. */
fun ScreenRow.headingLine(liveListings: Boolean): String {
    if (style == RowStyle.Live) return if (liveListings) "Live TV · On now" else "Live TV"
    val (title, small) = heading()
    return if (small != null) "$title · $small" else title
}

/**
 * The panel for the card at [index] of this row: the card itself, or the
 * See all card after the last one. A Live row takes the channel at [index]
 * from [channels] (those available, in the row's order), and the Full
 * guide card after them.
 */
fun ScreenRow.cardPanelAt(index: Int, channels: List<LiveChannel>, now: Long, format: LiveClockFormat): CardPanel? {
    val (headingTitle, _) = heading()
    return when (style) {
        RowStyle.Poster, RowStyle.Wide -> items.getOrNull(index)?.let { itemPanel(it, index) } ?: seeAllPanel(headingTitle)
        RowStyle.Episode -> episodes.getOrNull(index)?.let { episodePanel(it, headingTitle) } ?: seeAllPanel(headingTitle)
        RowStyle.Continue -> continueItems.getOrNull(index)?.let(::continuePanel)
        RowStyle.Live -> channels.getOrNull(index)?.let { livePanel(it, now, format) } ?: CardPanel(
            kicker = "Live TV",
            title = "Full guide",
            facts = listOf("Every channel and what’s next"),
            target = CardTarget.Guide,
        )
    }
}

private fun ScreenRow.seeAllPanel(headingTitle: String): CardPanel? = if (listId == null) null else CardPanel(
    kicker = headingTitle,
    title = "See all",
    facts = listOf(subtitle ?: "With the ones you don’t have"),
    target = CardTarget.List,
)

private fun ScreenRow.itemPanel(item: BrowseItem, index: Int): CardPanel {
    val (headingTitle, _) = heading()
    val kicker = when {
        // The server sends a list row's items in the list's order without their ranks; the row's place stands in.
        listId != null -> "#${index + 1} on the list"
        style == RowStyle.Wide -> formatDate(item.releaseDate)?.let { "Released $it" } ?: headingTitle
        else -> listOfNotNull(headingTitle, item.subtitle ?: item.year?.toString()).joinToString(" · ")
    }
    val genres = item.genres.take(2).joinToString(", ").ifEmpty { null }
    val facts = when (item.kind) {
        ItemKind.Movie -> listOfNotNull(item.year?.toString(), item.runtimeMin?.let { formatDuration(it * 60_000L) }, genres)
        ItemKind.Show -> listOfNotNull(item.year?.toString(), item.episodeCount?.let { plural(it, "episode") }, genres)
        ItemKind.Unmatched -> listOfNotNull(item.fileName, item.fileCount?.let { plural(it, "file") })
    }
    return CardPanel(
        kicker = kicker,
        title = item.title,
        facts = facts,
        tags = listOfNotNull(item.quality, "HDR".takeIf { item.hdr }),
        rating = formatRating(item.rating),
        overview = item.overview,
        target = when (item.kind) {
            ItemKind.Movie -> CardTarget.Film
            ItemKind.Show -> if (item.episodeId != null) CardTarget.Episode else CardTarget.Show
            ItemKind.Unmatched -> CardTarget.File
        },
        picturePath = item.backdropPath,
    )
}

private fun episodePanel(e: EpisodeCard, headingTitle: String): CardPanel = CardPanel(
    kicker = listOfNotNull(if (e.isNew) "New episode" else headingTitle, formatDay(e.airDate)?.let { "aired $it" }).joinToString(" · "),
    title = e.title,
    facts = listOfNotNull(e.showTitle, episodeCode(e.season, e.episode), "Not in library".takeIf { !e.hasFile }),
    overview = e.overview,
    target = CardTarget.Episode,
    picturePath = e.stillPath ?: e.showBackdropPath,
    seed = e.showTitle,
)

private fun continuePanel(c: ContinueItem): CardPanel = CardPanel(
    kicker = "Continue watching · ${formatRemaining(c.remainingMs)}",
    title = c.title,
    facts = listOfNotNull(c.subtitle, formatDuration(c.durationMs).takeIf { c.durationMs > 0 }),
    target = CardTarget.Resume,
    picturePath = c.stillPath ?: c.backdropPath,
)

private fun livePanel(channel: LiveChannel, now: Long, format: LiveClockFormat): CardPanel {
    val on = channel.onAt(now)
    return CardPanel(
        kicker = if (on != null) "On now · ${channel.number} ${channel.name} · ${format.span(on.startMs, on.endMs)}" else "Live TV · ${channel.number} ${channel.name}",
        title = on?.title ?: channel.name,
        facts = if (on != null) listOfNotNull(liveMinutesLeft(now, on.endMs), on.categories.firstOrNull()) else listOf("No listings"),
        overview = on?.description.orEmpty(),
        target = CardTarget.Channel,
        livePicture = on?.picture,
        seed = on?.title ?: channel.name,
    )
}

/** The panel's picture: the card's backdrop or still at [size], or the programme's picture. */
fun CardPanel.pictureUrl(library: LibraryRepository, live: LiveRepository, size: ImageSize): String? =
    livePicture?.let(live::artUrl) ?: picturePath?.let { library.imageUrl(it, size) }
