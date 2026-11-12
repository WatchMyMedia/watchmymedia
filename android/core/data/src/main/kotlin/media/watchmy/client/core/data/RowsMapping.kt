package media.watchmy.client.core.data

import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.ListOwner
import media.watchmy.client.model.MediaList
import media.watchmy.client.model.MediaListEntries
import media.watchmy.client.model.MediaListEntry
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.RowLayout
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowSource
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.core.api.models.MediaList as ApiMediaList
import media.watchmy.client.core.api.models.MediaListEntries as ApiMediaListEntries
import media.watchmy.client.core.api.models.MediaListEntry as ApiMediaListEntry
import media.watchmy.client.core.api.models.RowLayout as ApiRowLayout
import media.watchmy.client.core.api.models.RowLayoutEntry as ApiRowLayoutEntry
import media.watchmy.client.core.api.models.ScreenRow as ApiScreenRow
import media.watchmy.client.core.api.models.ScreenRowLink as ApiScreenRowLink

/*
 * Rows, layouts and lists (PLAN-ROWS.md §6) as the app's model. Styles,
 * source kinds, link kinds and owners are plain strings in the API: a row of
 * a style this app doesn't know is left out, and a See all of a kind it
 * doesn't know is dropped, so later row types need no app change to be safe.
 */

/**
 * The row as the app draws it; null for a style this app doesn't know, or a
 * row with nothing in it for its style (the server sends only rows with
 * something to show, but a card of a kind this app doesn't know may leave
 * one empty). The Live TV row has no items: the app fills it.
 */
internal fun ApiScreenRow.toModel(): ScreenRow? {
    val style = RowStyle.of(style) ?: return null
    val row = ScreenRow(
        id = id,
        style = style,
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        items = if (style == RowStyle.Poster || style == RowStyle.Wide) items.orEmpty().mapNotNull { it.toModel() } else emptyList(),
        episodes = if (style == RowStyle.Episode) episodes.orEmpty().map { it.toModel() } else emptyList(),
        continueItems = if (style == RowStyle.Continue) `continue`.orEmpty().mapNotNull { it.toModel() } else emptyList(),
        seeAll = seeAll?.toModel(),
        source = source?.takeIf { it.name.isNotBlank() }?.let { RowSource(it.kind, it.name, it.url?.takeIf { u -> u.isNotBlank() }) },
        listId = listId?.takeIf { it > 0 } ?: id.removePrefix("list:").takeIf { id.startsWith("list:") }?.toLongOrNull(),
    )
    return row.takeIf { it.hasContent }
}

/** The rows this app draws, in the server's order. */
internal fun List<ApiScreenRow>?.toRows(): List<ScreenRow> = orEmpty().mapNotNull { it.toModel() }

/** See all's target; null for a kind this app doesn't know, or one missing its ID. */
internal fun ApiScreenRowLink.toModel(): RowLink? = when (kind) {
    "library" -> libraryId?.takeIf { it > 0 }?.let { id ->
        RowLink.Library(id, sort = sortOf(sort), filter = filterOf(filter), genre = genre?.takeIf { it.isNotBlank() }, decade = decade?.toInt()?.takeIf { it > 0 })
    }
    "list" -> listId?.takeIf { it > 0 }?.let { RowLink.ToList(it) }
    else -> null
}

/** listLibraryItems' sort as the app's; null for one it doesn't know (the grid keeps its own). */
internal fun sortOf(value: String?): ItemSort? = when (value) {
    "title" -> ItemSort.Title
    "added" -> ItemSort.Added
    "year" -> ItemSort.Year
    "rating" -> ItemSort.Rating
    "released" -> ItemSort.Released
    "runtime" -> ItemSort.Runtime
    "aired" -> ItemSort.Aired
    else -> null
}

/** listLibraryItems' filter as the app's; null for none, or one it doesn't know. */
internal fun filterOf(value: String?): ItemFilter? = when (value) {
    "continuing" -> ItemFilter.Continuing
    "ended" -> ItemFilter.Ended
    "4k" -> ItemFilter.FourK
    "hdr" -> ItemFilter.Hdr
    "unwatched" -> ItemFilter.Unwatched
    else -> null
}

private fun ownerOf(value: String?): ListOwner? = when (value) {
    "me" -> ListOwner.Me
    "everyone" -> ListOwner.Everyone
    else -> null
}

internal fun ApiRowLayoutEntry.toModel() = RowLayoutEntry(
    id = id,
    title = title,
    style = style,
    kind = when (kind) {
        "builtin" -> RowLayoutEntry.Kind.Builtin
        "list" -> RowLayoutEntry.Kind.List
        else -> RowLayoutEntry.Kind.Other
    },
    hidden = hidden,
    shown = shown,
    note = note?.takeIf { it.isNotBlank() },
    listId = listId?.takeIf { it > 0 },
    owner = ownerOf(owner),
)

internal fun ApiRowLayout.toModel() = RowLayout(screen, rows.map { it.toModel() }, customized)

internal fun ApiMediaList.toModel() = MediaList(
    id = id,
    name = name,
    mediaType = mediaType,
    sourceKind = source.kind,
    owner = ownerOf(owner),
    canEdit = canEdit,
    screen = screen,
    limit = limit.toInt(),
    entries = propertyEntries.toInt(),
    inLibrary = inLibrary.toInt(),
    notFound = notFound?.toInt() ?: 0,
    fetching = fetching,
    fetchedAtMs = epochMs(fetchedAt),
    nextFetchAtMs = epochMs(nextFetchAt),
    error = error?.takeIf { it.isNotBlank() },
    errorAtMs = epochMs(errorAt),
    createdAtMs = epochMs(createdAt),
    sourceName = sourceName?.takeIf { it.isNotBlank() },
    sourceUrl = sourceUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
)

private fun mediaTypeOf(value: String): MediaType? = when (value) {
    "movie" -> MediaType.Movie
    "tv" -> MediaType.Tv
    else -> null
}

/** Requestable.state's words; null for none, or one this app doesn't know (no Request then). */
internal fun requestStateOf(value: String?): RequestableState? = when (value) {
    "requestable" -> RequestableState.Requestable
    "requested" -> RequestableState.Requested
    "downloading" -> RequestableState.Downloading
    "available" -> RequestableState.Available
    else -> null
}

internal fun ApiMediaListEntry.toModel() = MediaListEntry(
    rank = rank.toInt(),
    mediaType = mediaTypeOf(mediaType),
    tmdbId = tmdbId?.takeIf { it > 0 },
    title = title,
    year = year?.toInt()?.takeIf { it > 0 },
    posterPath = posterPath?.takeIf { it.isNotBlank() },
    found = found,
    item = item?.toModel(),
    requestState = requestStateOf(requestState),
)

internal fun ApiMediaListEntries.toModel() = MediaListEntries(items.map { it.toModel() }, total.toInt(), inLibrary.toInt())
