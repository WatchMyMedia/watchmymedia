package media.watchmy.client.model

// Related titles at the end of movie and show pages: the
// server works the rows out and sends them as ScreenRows, which the pages
// draw as Home draws its poster rows.

/** The server API level that brought the related rows; below it the pages end as they always have. */
const val RELATED_API_LEVEL = 30

/** The related rows' IDs, in the order the server sends them. */
object RelatedRowIds {
    /** The movie's TMDB collection: its other films the library has, in release order, named as TMDB names it. */
    const val COLLECTION = "collection"

    /** More like this: up to 20 titles, best first. */
    const val SIMILAR = "similar"
}

/** Beside the collection row's name on the TV. */
const val RELEASE_ORDER = "In release order"

/**
 * The related rows a page draws: poster rows with cards, in the server's
 * order. A row of another style (from a newer server) is left out, as is
 * one with nothing in it.
 */
fun List<ScreenRow>.relatedDrawn(): List<ScreenRow> = filter { it.style == RowStyle.Poster && it.items.isNotEmpty() }

/** The line beside a related row's name on the TV: the server's, else "In release order" for the collection. */
val ScreenRow.relatedNote: String?
    get() = subtitle ?: RELEASE_ORDER.takeIf { id == RelatedRowIds.COLLECTION }
