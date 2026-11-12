package media.watchmy.client.model

/** Evidence from the library list, rather than guessing that a scan is running. */
data class HomeEmptyState(val kind: Kind, val failures: List<Library> = emptyList(), val otherScanRunning: Boolean = false) {
    enum class Kind { NoLibraries, Scanning, ScanFailed, EmptyLibraries, Unavailable }

    val title: String get() = when (kind) {
        Kind.NoLibraries -> "This server has no movies or TV yet."
        Kind.Scanning -> "Finding your files…"
        Kind.ScanFailed -> if (failures.size == 1) "Couldn’t read the library" else "Couldn’t read some libraries"
        Kind.EmptyLibraries -> "No movies or shows found"
        Kind.Unavailable -> "Nothing here yet"
    }

    fun message(isAdmin: Boolean): String = when (kind) {
        Kind.NoLibraries -> if (isAdmin) "Add a library on the server’s web page, then come back here." else "Ask the person who runs it to add a library."
        Kind.Scanning -> "They appear here as they’re found."
        Kind.ScanFailed -> failures.joinToString("\n") { "Couldn’t read ${it.name}: ${it.lastScanError}" } + "\n" +
            (if (otherScanRunning) "Other libraries are still being scanned.\n" else "") +
            (if (isAdmin) "Check the library on the server’s web page." else "Ask the person who runs the server to check the library.")
        Kind.EmptyLibraries -> if (isAdmin) "Check the library’s folders and scan status on the server’s web page." else "Ask the person who runs the server to check its libraries."
        Kind.Unavailable -> "Couldn’t check the library’s scan status. Try again."
    }

    val offersSetup: Boolean get() = kind in setOf(Kind.NoLibraries, Kind.ScanFailed, Kind.EmptyLibraries)

    companion object {
        fun from(libraries: List<Library>): HomeEmptyState {
            // A retry supersedes that library's old failure, but never hides another library's failure.
            val failures = libraries.filter { !it.scanning && !it.lastScanError.isNullOrBlank() }
            val running = libraries.any { it.scanning }
            return when {
                failures.isNotEmpty() -> HomeEmptyState(Kind.ScanFailed, failures, running)
                running -> HomeEmptyState(Kind.Scanning)
                libraries.isEmpty() -> HomeEmptyState(Kind.NoLibraries)
                else -> HomeEmptyState(Kind.EmptyLibraries)
            }
        }
    }
}

/** Nothing to show: no hero and no row with something in it (the Live TV row is the app's, and doesn't count). */
fun HomeData.isEmpty(): Boolean = hero == null && continueWatching.isEmpty() && recentlyAddedShows.isEmpty() &&
    recentlyAired.isEmpty() && recentlyAddedMovies.isEmpty() && recentlyReleased.isEmpty() &&
    rows.orEmpty().none { it.style != RowStyle.Live && it.hasContent }
