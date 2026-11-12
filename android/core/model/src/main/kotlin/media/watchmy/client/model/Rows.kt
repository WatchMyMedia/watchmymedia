package media.watchmy.client.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// Rows people arrange on Home and each library's Movies or TV screen, and rows
// made from lists (PLAN-ROWS.md §6, §8.2). The server orders and fills the
// rows; the apps draw them by style and skip a style they don't know.

/** The server API level that brought arranged rows and rows made from lists; below it the screens keep their fixed rows. */
const val ROWS_API_LEVEL = 22

/** The screens a person arranges: Home, and each library's Movies or TV screen. */
object RowScreens {
    const val HOME = "home"

    fun library(libraryId: Long): String = "library-$libraryId"

    /** The library a `library-<id>` screen is for; null for Home and anything else. */
    fun libraryId(screen: String): Long? = screen.removePrefix("library-").takeIf { screen.startsWith("library-") }?.toLongOrNull()
}

/** The built-in rows' IDs; a list's row is `list:<id>`. */
object RowIds {
    const val CONTINUE = "continue"
    const val LIVE = "live"
    const val RECENTLY_ADDED_SHOWS = "recentlyAddedShows"
    const val RECENTLY_AIRED = "recentlyAired"
    const val RECENTLY_ADDED_MOVIES = "recentlyAddedMovies"
    const val RECENTLY_RELEASED = "recentlyReleased"

    /** A library screen's Recently added. */
    const val RECENTLY_ADDED = "recentlyAdded"

    fun list(listId: Long): String = "list:$listId"
}

/**
 * How a row is drawn. The server sends the style as a plain string; [of]
 * is null for one this app doesn't know, and that row is skipped.
 */
enum class RowStyle(val wire: String) {
    /** 2:3 posters ([ScreenRow.items]). */
    Poster("poster"),

    /** 16:9 cards ([ScreenRow.items]), as Recently released. */
    Wide("wide"),

    /** Episode cards ([ScreenRow.episodes]), as Recently aired. */
    Episode("episode"),

    /** Continue watching ([ScreenRow.continueItems]). */
    Continue("continue"),

    /** No items: the app draws its Live TV row (On now, or Watch a channel) in this place. */
    Live("live"),
    ;

    companion object {
        fun of(value: String): RowStyle? = entries.firstOrNull { it.wire == value }
    }
}

/** Where a row's See all goes; a kind this app doesn't know has no See all. */
sealed interface RowLink {
    /** The library's grid with these options. */
    data class Library(val libraryId: Long, val sort: ItemSort? = null, val filter: ItemFilter? = null, val genre: String? = null, val decade: Int? = null) : RowLink

    /** The list's page. */
    data class ToList(val listId: Long) : RowLink
}

/** Where a list comes from, for the row's line ("From TMDB"). [kind] is a plain string: tmdb, mdblist, library or import. */
data class RowSource(val kind: String, val name: String, val url: String? = null)

/** One row of a screen, in the person's order. */
data class ScreenRow(
    val id: String,
    val style: RowStyle,
    val title: String,
    /** Lists: "18 of 50 · From TMDB". */
    val subtitle: String? = null,
    val items: List<BrowseItem> = emptyList(),
    val episodes: List<EpisodeCard> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
    val seeAll: RowLink? = null,
    val source: RowSource? = null,
    val listId: Long? = null,
) {
    /** It has something to draw. A Live TV row is the app's to fill, so it always has. */
    val hasContent: Boolean
        get() = when (style) {
            RowStyle.Poster, RowStyle.Wide -> items.isNotEmpty()
            RowStyle.Episode -> episodes.isNotEmpty()
            RowStyle.Continue -> continueItems.isNotEmpty()
            RowStyle.Live -> true
        }
}

/**
 * The row's heading as the screens draw it: the title, and the smaller words
 * beside it. A list's line ("18 of 50 · From TMDB") goes beside its name; a
 * built-in title such as "Recently added · Movies" splits at its dot, as
 * Home's headings read before rows could be arranged.
 */
fun ScreenRow.heading(): Pair<String, String?> {
    if (subtitle != null) return title to subtitle
    val dot = title.indexOf(" · ")
    if (dot <= 0) return title to null
    return title.substring(0, dot) to title.substring(dot + 3).ifBlank { null }
}

/** Home in the person's order: [HomeData.rows] is set, and the fixed arrays are empty. */
val HomeData.arranged: Boolean get() = rows != null

/**
 * The rows this app draws, in order: the Live TV row only where there are
 * channels to put in it ([live]). The tablet's Continue beside the hero and
 * the TV's first focus go by the first of these.
 */
fun List<ScreenRow>.drawn(live: Boolean): List<ScreenRow> = filter { it.hasContent && (it.style != RowStyle.Live || live) }

/** Every episode card Home holds: Recently aired's, and arranged rows' (the hero names its episode from them). */
val HomeData.episodeCards: List<EpisodeCard> get() = recentlyAired + rows.orEmpty().flatMap { it.episodes }

/**
 * Continue watching as Home has it: the arranged Continue row's, else the
 * fixed array. Null when Home is arranged without a Continue row (the person
 * hid it, or nothing is partway), so a caller that needs the list asks for it.
 */
val HomeData.continueRow: List<ContinueItem>?
    get() {
        val rows = rows ?: return continueWatching
        return rows.firstOrNull { it.style == RowStyle.Continue }?.continueItems
    }

/** Every movie and show card on Home, fixed or arranged. */
internal val HomeData.allItems: List<BrowseItem>
    get() = recentlyAddedShows + recentlyAddedMovies + recentlyReleased + rows.orEmpty().flatMap { it.items }

/** The rows as they read once [target] is marked: as [HomeData.withWatched] does for the fixed rows. */
fun List<ScreenRow>.withWatched(target: WatchTarget, watched: Boolean): List<ScreenRow> {
    fun List<BrowseItem>.marked(kind: ItemKind, id: Long) = map { if (it.kind == kind && it.id == id) it.withWatched(watched) else it }
    fun List<EpisodeCard>.marked(which: (EpisodeCard) -> Boolean) = map { if (it.hasFile && which(it)) it.copy(watched = watched) else it }
    val episode = ContinueItem.Kind.Episode
    return map { row ->
        when (target) {
            is WatchTarget.Movie -> row.copy(
                items = row.items.marked(ItemKind.Movie, target.id),
                continueItems = row.continueItems.filterNot { it.kind == ContinueItem.Kind.Movie && it.movieId == target.id },
            )
            is WatchTarget.Episode -> row.copy(
                episodes = row.episodes.marked { it.episodeId == target.id },
                continueItems = row.continueItems.filterNot { it.kind == episode && it.episodeId == target.id },
            )
            is WatchTarget.Season -> row.copy(
                episodes = row.episodes.marked { it.showId == target.showId && it.season == target.number },
                continueItems = row.continueItems.filterNot { it.kind == episode && it.showId == target.showId && it.season == target.number },
            )
            is WatchTarget.Show -> row.copy(
                items = row.items.marked(ItemKind.Show, target.id),
                episodes = row.episodes.marked { it.showId == target.id },
                continueItems = row.continueItems.filterNot { it.kind == episode && it.showId == target.id },
            )
        }
    }
}

/** Who a list is for. */
enum class ListOwner { Me, Everyone }

/** One row a person can place on a screen, for the editor. */
data class RowLayoutEntry(
    val id: String,
    val title: String,
    /** As [ScreenRow.style], a plain string: [drawable] is false for one this app can't draw. */
    val style: String,
    val kind: Kind,
    /** Hidden by the person, or a list made for another screen and not placed here yet: Add a row offers it. */
    val hidden: Boolean,
    /** Drawn now: not hidden, has something to show, and within the screen's 30 rows. */
    val shown: Boolean,
    /** Why a row that isn't hidden isn't drawn ("Nothing from this list is in your library yet"). */
    val note: String? = null,
    val listId: Long? = null,
    val owner: ListOwner? = null,
) {
    enum class Kind { Builtin, List, Other }

    /** This app draws its style; a row from a newer server is still placed, for the apps that draw it. */
    val drawable: Boolean get() = RowStyle.of(style) != null
}

/** A screen's rows in order, for the editor. [customized] false: it follows the household's default. */
data class RowLayout(val screen: String, val rows: List<RowLayoutEntry>, val customized: Boolean)

/** One row in a saved order. */
data class RowPlacement(val id: String, val hidden: Boolean)

/** The order to save: every row, hidden ones too, so each keeps its place. */
fun List<RowLayoutEntry>.placements(): List<RowPlacement> = map { RowPlacement(it.id, it.hidden) }

/** [id] moved [by] places (−1 up, +1 down), stopping at the ends. */
fun List<RowLayoutEntry>.moved(id: String, by: Int): List<RowLayoutEntry> {
    val from = indexOfFirst { it.id == id }
    if (from < 0) return this
    return movedFrom(from, (from + by).coerceIn(0, lastIndex))
}

/** The row at [from] put at [to], as a drag leaves it. */
fun List<RowLayoutEntry>.movedFrom(from: Int, to: Int): List<RowLayoutEntry> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** [id] hidden or shown again in its place. */
fun List<RowLayoutEntry>.withHidden(id: String, hidden: Boolean): List<RowLayoutEntry> =
    map { if (it.id == id) it.copy(hidden = hidden, shown = !hidden && it.shown) else it }

/** Add a row: [id] shown again where it was ("A row comes back where it was", AddRow.dc.html). */
fun List<RowLayoutEntry>.added(id: String): List<RowLayoutEntry> = withHidden(id, false)

/** What Add a row offers: the hidden rows. */
val List<RowLayoutEntry>.addable: List<RowLayoutEntry> get() = filter { it.hidden }

/** The same rows in the same order, hidden alike: nothing to save. */
fun List<RowLayoutEntry>.sameOrderAs(other: List<RowLayoutEntry>): Boolean = placements() == other.placements()

/** A screen draws at most this many rows (PLAN-ROWS.md Decision 13); the editor says so past it. */
const val MAX_ROWS_ON_SCREEN = 30

/** More rows are shown than the screen draws: the editor's notice. */
val List<RowLayoutEntry>.overLimit: Boolean get() = count { !it.hidden } > MAX_ROWS_ON_SCREEN

/** "18 of 50" for a list's row, from what the list has. */
private fun MediaList.countLine(): String = "$inLibrary of $entries"

/** Who a list is for, as the editor names it: "For everyone", "Your list". */
fun ListOwner.label(): String = when (this) {
    ListOwner.Me -> "Your list"
    ListOwner.Everyone -> "For everyone"
}

/**
 * The line under a row in the editor (HomeEditRows.dc.html): "Built in",
 * "For everyone · 18 of 50 · From TMDB", "Your list · 9 of 24 · Imported",
 * with "Hidden · " before it when hidden. [list] is the row's list, when the
 * lists could be read.
 */
fun RowLayoutEntry.editorLine(list: MediaList? = null): String {
    val line = when {
        !drawable -> "Shown in newer versions of the app"
        kind == RowLayoutEntry.Kind.List -> listOfNotNull(owner?.label() ?: "List", list?.countLine(), list?.fromLine()).joinToString(" · ")
        style == RowStyle.Live.wire -> "Built in · On now, or Watch a channel"
        else -> "Built in"
    }
    return if (hidden) "Hidden · $line" else line
}

/** Why a row that isn't hidden isn't drawn ("Not shown: too many rows"), under its line. */
val RowLayoutEntry.notShown: String? get() = note.takeIf { !hidden && !shown }

/**
 * The line under a row Add a row offers (AddRow.dc.html): "Hidden · Built in",
 * "From TMDB · 12 of 20 in your library", "From your library · 16 movies".
 */
fun RowLayoutEntry.addLine(list: MediaList? = null): String {
    if (kind != RowLayoutEntry.Kind.List || list == null) return editorLine(list)
    if (list.sourceKind == "library") {
        val noun = when (list.mediaType) {
            "movie" -> "movie"
            "tv" -> "show"
            else -> "title"
        }
        return listOfNotNull(list.fromLine(), plural(list.inLibrary, noun)).joinToString(" · ")
    }
    return listOfNotNull(list.fromLine(), "${list.countLine()} in your library").joinToString(" · ")
}

/** A heading in Add a row with its rows; [empty] says there is nothing of its kind at all. */
data class AddRowGroup(val name: String, val rows: List<RowLayoutEntry>, val empty: String? = null)

/**
 * Add a row's groups (AddRow.dc.html): the hidden built-in rows, the
 * household's lists and the person's own. A list group says it has none
 * only when there are no such lists at all; one whose lists are all on the
 * screen already is left out, as is a group of built-in rows with none hidden.
 */
fun addRowGroups(entries: List<RowLayoutEntry>, lists: List<MediaList>): List<AddRowGroup> {
    val hidden = entries.addable
    fun listGroup(name: String, owner: ListOwner, empty: String): AddRowGroup? {
        val rows = hidden.filter { it.kind == RowLayoutEntry.Kind.List && (it.owner ?: lists.firstOrNull { l -> l.id == it.listId }?.owner) == owner }
        val any = lists.any { it.owner == owner } || entries.any { it.kind == RowLayoutEntry.Kind.List && it.owner == owner }
        return when {
            rows.isNotEmpty() -> AddRowGroup(name, rows)
            !any -> AddRowGroup(name, emptyList(), empty)
            else -> null
        }
    }
    val builtins = hidden.filter { it.kind != RowLayoutEntry.Kind.List }
    return listOfNotNull(
        AddRowGroup("Built-in rows", builtins).takeIf { builtins.isNotEmpty() },
        listGroup("For everyone", ListOwner.Everyone, "No lists for everyone yet."),
        listGroup("Your lists", ListOwner.Me, "You have no lists yet."),
    )
}

/** Add a row's line about making lists, which phones, tablets and TVs don't do (PLAN-ROWS.md Decision 10). */
const val LISTS_ON_THE_WEB = "Lists are made in WatchMyMedia on the web."

/** The TMDB notice its terms ask for, in About. */
const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."

/** A list a row is made from. */
data class MediaList(
    val id: Long,
    val name: String,
    /** movie, tv or all; a plain string. */
    val mediaType: String,
    /** tmdb, mdblist, library or import; a plain string. */
    val sourceKind: String,
    val owner: ListOwner?,
    val canEdit: Boolean,
    /** The screen its row was made on: home or library-<id>. */
    val screen: String,
    val limit: Int,
    val entries: Int,
    val inLibrary: Int,
    val notFound: Int = 0,
    val fetching: Boolean = false,
    val fetchedAtMs: Long? = null,
    val nextFetchAtMs: Long? = null,
    /** Why the last fetch failed ("MDBList refused the key"); the list keeps its last good entries. */
    val error: String? = null,
    val errorAtMs: Long? = null,
    val createdAtMs: Long? = null,
    /** The server's words for From <name> ("TMDB", "an IMDb export"); older servers of this level send none. */
    val sourceName: String? = null,
    /** The list's page on TMDB or mdblist.com, when it has one. */
    val sourceUrl: String? = null,
) {
    /** Fetched again from its source; imports and library lists are not. */
    val refreshable: Boolean get() = sourceKind == "tmdb" || sourceKind == "mdblist"
}

/** "TMDB", "MDBList": a source's name as people read it; null for one this app doesn't know. */
fun listSourceName(kind: String): String? = when (kind) {
    "tmdb" -> "TMDB"
    "mdblist" -> "MDBList"
    "library" -> "your library"
    "import" -> "an import"
    else -> null
}

/** "From TMDB", "From your library", "Imported"; null for a source this app doesn't know and the server doesn't name. */
fun MediaList.fromLine(): String? = when (sourceKind) {
    "import" -> "Imported"
    else -> (sourceName?.takeIf { it.isNotBlank() } ?: listSourceName(sourceKind))?.let { "From $it" }
}

/** The list page's kicker (ListPage.dc.html): "List · For everyone", "Your list". */
fun MediaList.kicker(): String = when (owner) {
    ListOwner.Me -> "Your list"
    ListOwner.Everyone -> "List · For everyone"
    null -> "List"
}

/** "From TMDB · Updated 3 h ago", "Imported Nov 2 · from an IMDb export", "From your library": under the list page's name. */
fun MediaList.sourceLine(now: Long = System.currentTimeMillis()): String? {
    if (sourceKind == "import") {
        val imported = listOfNotNull("Imported", createdAtMs?.let { monthDay(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())) }).joinToString(" ")
        return listOfNotNull(imported, sourceName?.takeIf { it.isNotBlank() }?.let { "from $it" }).joinToString(" · ")
    }
    val from = fromLine() ?: return null
    val updated = fetchedAtMs?.takeIf { refreshable }?.let { "Updated ${formatAgo(it, now)}" }
    return listOfNotNull(from, updated).joinToString(" · ")
}

/** "18 of 50 in your library · 2 not found". */
fun MediaList.summaryLine(): String = listOfNotNull("$inLibrary of $entries in your library", notFound.takeIf { it > 0 }?.let { "$it not found" }).joinToString(" · ")

/** "Couldn’t update this list: MDBList refused the key. It’s shown as it was 2 days ago." when the last fetch failed. */
fun MediaList.errorLine(now: Long = System.currentTimeMillis()): String? = error?.let { e ->
    val reason = e.trimEnd('.')
    val asWas = fetchedAtMs?.let { " It’s shown as it was ${formatAgo(it, now)}." }.orEmpty()
    "Couldn’t update this list: $reason.$asWas"
}

/** One title on a list, in the list's order. */
data class MediaListEntry(
    val rank: Int,
    val mediaType: MediaType?,
    /** Null when TMDB couldn't find the title (imports). */
    val tmdbId: Long?,
    val title: String,
    val year: Int?,
    /** TMDB's poster, for a title the library doesn't have. */
    val posterPath: String?,
    /** False for an imported title TMDB couldn't find: Not found. */
    val found: Boolean,
    /** The library's movie or show, when it has the title. */
    val item: BrowseItem? = null,
    /** What Request would do; null when the person may not request or requests aren't set up. */
    val requestState: RequestableState? = null,
) {
    val inLibrary: Boolean get() = item != null

    /** Request offered: missing, found on TMDB, and requestable. */
    val requestable: Boolean get() = item == null && found && tmdbId != null && mediaType != null && requestState == RequestableState.Requestable

    /** The entry as the request sheet takes it; null when it can't be asked for. */
    fun requestSubject(): Requestable? {
        val type = mediaType ?: return null
        val id = tmdbId ?: return null
        return Requestable(type, id, title, year, overview = "", posterPath = posterPath, state = requestState ?: RequestableState.Requestable)
    }

    /** The line under a missing entry's title: "1988 · Not in your library", or "Not found on TMDB". */
    val missingLine: String
        get() = if (!found) "Not found on TMDB" else listOfNotNull(year?.toString(), "Not in your library").joinToString(" · ")

    /** What a missing entry's poster says where it has no Request: "Requested", "Downloading", or "Not in library" without requests. */
    val missingStatus: String?
        get() = when {
            !found || requestable -> null
            requestState == RequestableState.Requested -> "Requested"
            requestState == RequestableState.Downloading -> "Downloading"
            requestState == RequestableState.Available -> "Available soon"
            else -> "Not in library"
        }
}

/** A page of a list's entries. */
data class MediaListEntries(val items: List<MediaListEntry>, val total: Int, val inLibrary: Int)

/** rows.changed or lists.changed, so the screens that show them read them again. */
sealed interface RowsChange {
    /** A screen's layout changed; [screen] null after a reconnection, when any may have. */
    data class Layout(val screen: String?) : RowsChange

    /** A list was made, changed, deleted or refreshed with new entries; [listId] null after a reconnection. */
    data class Lists(val listId: Long?) : RowsChange

    /** Whether a screen showing [screen] reads again: its layout, or any list (a list's row may be on it). */
    fun affects(screen: String): Boolean = when (this) {
        is Layout -> this.screen == null || this.screen == screen
        is Lists -> true
    }
}

/**
 * Arranging rows and reading lists on the server in use (PLAN-ROWS.md §6.2,
 * §6.3). Below [ROWS_API_LEVEL] [supported] is false and nothing else is
 * called. Methods throw [ApiException].
 */
interface RowsRepository {
    /** The server arranges rows: [ROWS_API_LEVEL] or later. */
    val supported: Boolean

    suspend fun layout(screen: String): RowLayout

    /** Saves [rows] in order; the reply is the layout as the server now has it. */
    suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout

    /** Back to the household's default. */
    suspend fun resetLayout(screen: String): RowLayout

    /** The person's own lists, then the household's. */
    suspend fun lists(): List<MediaList>

    suspend fun list(id: Long): MediaList

    suspend fun entries(id: Long, offset: Int = 0, limit: Int = MAX_LIST_ENTRIES): MediaListEntries

    /** Fetches the list again now (409 while a fetch runs). */
    suspend fun refreshList(id: Long): MediaList

    suspend fun deleteList(id: Long)

    val changes: Flow<RowsChange>

    companion object {
        /** The most entries a list keeps (PLAN-ROWS.md Decision 13). */
        const val MAX_LIST_ENTRIES = 250
    }

    /** Where nothing is arranged: an older server, or no server. */
    object Unavailable : RowsRepository {
        override val supported: Boolean = false

        private fun none(): Nothing = throw ApiException(404, "Update the server to arrange rows.")

        override suspend fun layout(screen: String): RowLayout = none()

        override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout = none()

        override suspend fun resetLayout(screen: String): RowLayout = none()

        override suspend fun lists(): List<MediaList> = emptyList()

        override suspend fun list(id: Long): MediaList = none()

        override suspend fun entries(id: Long, offset: Int, limit: Int): MediaListEntries = none()

        override suspend fun refreshList(id: Long): MediaList = none()

        override suspend fun deleteList(id: Long) = none()

        override val changes: Flow<RowsChange> = emptyFlow()
    }
}
