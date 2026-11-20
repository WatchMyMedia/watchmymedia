package media.watchmy.client.model

/**
 * What changed on the server, from its events,
 * so a page reloads only for what it shows. A burst of events comes
 * as one change ([plus]).
 *
 * - [movies] and [episodes]: the person's progress or watched marks on these
 *   (watch.changed, which names its item type and ids).
 * - [libraries]: a scan started, finished or failed in these libraries, or
 *   their contents changed (scan.* and library.changed, by libraryId).
 * - [everything]: the stream reconnected, so events may have been missed, or
 *   an event couldn't be read: anything may have changed.
 */
data class LibraryChange(
    val everything: Boolean = false,
    val movies: Set<Long> = emptySet(),
    val episodes: Set<Long> = emptySet(),
    val libraries: Set<Long> = emptySet(),
) {
    /** Someone's progress or watched marks changed: Continue watching, Home's rows and every page with a progress bar. */
    val watchState: Boolean get() = everything || movies.isNotEmpty() || episodes.isNotEmpty()

    /** A library's contents changed: what is in it, and so any page or row built from it. */
    val libraryContents: Boolean get() = everything || libraries.isNotEmpty()

    /** The grid of library [id] may differ: its contents, or the watched state it filters and marks. */
    fun touchesLibrary(id: Long): Boolean = everything || id in libraries || watchState

    /** Movie [id]'s page may differ: its own progress, or a library change that may have rematched it. */
    fun touchesMovie(id: Long): Boolean = everything || id in movies || libraries.isNotEmpty()

    /** A show's page may differ when any of [episodeIds] (its episodes) changed, or a library change came. */
    fun touchesEpisodes(episodeIds: Collection<Long>): Boolean =
        everything || libraries.isNotEmpty() || episodes.isNotEmpty() && episodeIds.any { it in episodes }

    /** This change and [other] as one. */
    operator fun plus(other: LibraryChange): LibraryChange = if (everything || other.everything) Everything else LibraryChange(
        movies = movies + other.movies,
        episodes = episodes + other.episodes,
        libraries = libraries + other.libraries,
    )

    companion object {
        /** Anything may have changed: reload. */
        val Everything = LibraryChange(everything = true)
    }
}
