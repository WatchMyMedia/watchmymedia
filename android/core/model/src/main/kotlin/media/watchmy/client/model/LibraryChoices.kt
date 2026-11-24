package media.watchmy.client.model

// A library grid's sorts and filters, named and offered the same on the phone
// and the TV, as the Apple apps' WMMModel Library.swift has them.

/** "Title", "Date added", "Release date". */
val ItemSort.label: String
    get() = when (this) {
        ItemSort.Title -> "Title"
        ItemSort.Added -> "Date added"
        ItemSort.Year -> "Year"
        ItemSort.Rating -> "Rating"
        ItemSort.Released -> "Release date"
        ItemSort.Runtime -> "Runtime"
        ItemSort.Aired -> "Last aired"
    }

/** "All", "Continuing", "4K". */
val ItemFilter.label: String
    get() = when (this) {
        ItemFilter.All -> "All"
        ItemFilter.Continuing -> "Continuing"
        ItemFilter.Ended -> "Ended"
        ItemFilter.FourK -> "4K"
        ItemFilter.Hdr -> "HDR"
        ItemFilter.Unwatched -> "Unwatched"
    }

/** The sorts a library of [type] offers, in menu order. */
fun sortOptions(type: LibraryType): List<ItemSort> = when (type) {
    LibraryType.Tv -> listOf(ItemSort.Title, ItemSort.Added, ItemSort.Aired, ItemSort.Year, ItemSort.Rating)
    LibraryType.Movie -> listOf(ItemSort.Title, ItemSort.Added, ItemSort.Released, ItemSort.Year, ItemSort.Rating, ItemSort.Runtime)
}

/** The filter chips a library of [type] offers; Continuing and Ended are for shows. */
fun filterOptions(type: LibraryType): List<ItemFilter> = when (type) {
    LibraryType.Tv -> listOf(ItemFilter.All, ItemFilter.Continuing, ItemFilter.Ended, ItemFilter.Unwatched, ItemFilter.FourK, ItemFilter.Hdr)
    LibraryType.Movie -> listOf(ItemFilter.All, ItemFilter.Unwatched, ItemFilter.FourK, ItemFilter.Hdr)
}

/**
 * A library grid's sort and filters, kept on this device for each server's
 * library, so the library opens as it was left: every app's TV does this.
 * The choices are names under the grid's own keys ("sort" to "Title").
 */
interface GridChoices {
    /** What was kept for [key]; null when nothing was. */
    fun recalled(key: String): Map<String, String>?

    fun keep(key: String, choices: Map<String, String>)

    /** Nowhere to keep them: the phone and tablet, and tests that don't ask. */
    object None : GridChoices {
        override fun recalled(key: String): Map<String, String>? = null
        override fun keep(key: String, choices: Map<String, String>) = Unit
    }

    /** Kept in memory: the fakes'. */
    class InMemory : GridChoices {
        private val kept = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
        override fun recalled(key: String): Map<String, String>? = kept[key]
        override fun keep(key: String, choices: Map<String, String>) {
            kept[key] = choices
        }
    }
}
