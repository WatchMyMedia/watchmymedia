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

/** The sorts a library of [type] offers, in menu order (PLAN.md §10). */
fun sortOptions(type: LibraryType): List<ItemSort> = when (type) {
    LibraryType.Tv -> listOf(ItemSort.Title, ItemSort.Added, ItemSort.Aired, ItemSort.Year, ItemSort.Rating)
    LibraryType.Movie -> listOf(ItemSort.Title, ItemSort.Added, ItemSort.Released, ItemSort.Year, ItemSort.Rating, ItemSort.Runtime)
}

/** The filter chips a library of [type] offers; Continuing and Ended are for shows. */
fun filterOptions(type: LibraryType): List<ItemFilter> = when (type) {
    LibraryType.Tv -> listOf(ItemFilter.All, ItemFilter.Continuing, ItemFilter.Ended, ItemFilter.Unwatched, ItemFilter.FourK, ItemFilter.Hdr)
    LibraryType.Movie -> listOf(ItemFilter.All, ItemFilter.Unwatched, ItemFilter.FourK, ItemFilter.Hdr)
}
