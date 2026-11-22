package media.watchmy.client.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

// Watchlists: movies and shows a person means to watch, saved to lists they
// name. A watchlist is a list (MediaList with source kind "watchlist"), so its
// rows and its page are the lists'; what is its own is here: saving, the
// button's and the menus' words, and the Watchlists page.

/** The server API level that brought watchlists; below it nothing saves, though their rows still come as lists. */
const val WATCHLISTS_API_LEVEL = 33

/** A list's source kind for a watchlist. */
const val WATCHLIST_KIND = "watchlist"

/** The most watchlists one person may make, and titles one holds, as the server keeps them. */
const val MAX_WATCHLISTS = 25
const val MAX_WATCHLIST_TITLES = 500

/** A title as watchlists keep it: a movie or show by its TMDB id, whichever library has it, or none. */
data class WatchlistKey(val type: MediaType, val tmdbId: Long)

/**
 * A title to save: its key, and what the server keeps for a title it has no
 * record of (only search's and lists' missing titles need it).
 */
data class WatchlistTitle(val key: WatchlistKey, val title: String, val year: Int? = null, val posterPath: String? = null)

/** One of the person's watchlists. */
data class Watchlist(
    val id: Long,
    val name: String,
    /** Titles on it, in the library or not. */
    val titles: Int,
    /** Of those, the ones a library has. */
    val inLibrary: Int,
    /** A finished title leaves it. */
    val removeWatched: Boolean,
    /** Its first four posters, newest saved first. */
    val posters: List<String> = emptyList(),
    /** It holds the title [WatchlistRepository.holding] asked about. */
    val contains: Boolean = false,
)

/** What the one press did ([WatchlistRepository.saveToOnly]). */
sealed interface OnePress {
    /** Saved to the person's only watchlist, made now when they had none: [held] is where the title is now. */
    data class Saved(val watchlist: Watchlist, val held: List<Long>) : OnePress

    /** The person has several: the picker, with [watchlists] saying which hold the title. */
    data class Choose(val watchlists: List<Watchlist>) : OnePress
}

/** A removal shown at once and sent later ([WatchlistRepository.removeLater]), so Undo can take it back. */
interface HeldRemoval {
    /** Sends it; once, in the data layer's scope, so it is sent even if the screen that made it has closed. */
    fun send()

    /** Puts the title back where it was, and sends nothing. */
    fun undo()
}

/**
 * The person's watchlists on the server in use.
 * Below [WATCHLISTS_API_LEVEL] [supported] is false and every call fails
 * without asking the server. Saving and removing are shown at once in
 * [held] and put back if the server refuses; screens lay [held] over the
 * marks they hold, so a card elsewhere on screen says Add or Remove rightly
 * without reading again. Methods throw [ApiException].
 */
interface WatchlistRepository {
    val supported: Boolean

    /** The person's watchlists, in the order made, as last read; null before the first read for this sign-in. */
    val watchlists: StateFlow<List<Watchlist>?>

    /** Where titles this device saved or removed now are: the watchlists that hold each, by title. */
    val held: StateFlow<Map<WatchlistKey, List<Long>>>

    /** Moves on with every save and removal made here: what [known] compares against. */
    val revision: Long

    /** Reads the watchlists again. */
    suspend fun refresh(): List<Watchlist>

    /** The person's watchlists, each saying whether it holds [key]: what the picker shows. */
    suspend fun holding(key: WatchlistKey): List<Watchlist>

    suspend fun create(name: String): Watchlist

    suspend fun update(id: Long, name: String? = null, removeWatched: Boolean? = null): Watchlist

    suspend fun delete(id: Long)

    /** The one press: saves to the person's only watchlist, making Watchlist when they have none. */
    suspend fun saveToOnly(title: WatchlistTitle): OnePress

    /** Saves to watchlist [id]; where the title is now. */
    suspend fun save(id: Long, title: WatchlistTitle): List<Long>

    /** Takes [key] off watchlist [id] at once; where the title is now. */
    suspend fun remove(id: Long, key: WatchlistKey): List<Long>

    /**
     * Shows [key] off watchlist [from] (every one, when null) at once, and
     * sends it only on [HeldRemoval.send], so a message's Undo can take it
     * back before anything was sent.
     */
    fun removeLater(key: WatchlistKey, from: Long? = null): HeldRemoval

    /**
     * A screen read where [key] is: [ids], as of a read begun at [since] (a
     * [revision]). Taken only when nothing was saved or removed here since,
     * so a reply already on its way when a button was pressed doesn't undo it.
     */
    fun known(key: WatchlistKey, ids: List<Long>, since: Long)

    /** A removal [removeLater] sent was refused: what to say, for whichever screen is up. */
    val refusals: Flow<String>

    /** A watchlist was made, renamed, deleted or its titles changed (here, on another device, or by watching). */
    val changes: Flow<Unit>

    /** No server, or one too old: no watchlists. */
    object Unavailable : WatchlistRepository {
        override val supported: Boolean get() = false
        override val watchlists: StateFlow<List<Watchlist>?> = MutableStateFlow<List<Watchlist>?>(null).asStateFlow()
        override val held: StateFlow<Map<WatchlistKey, List<Long>>> = MutableStateFlow(emptyMap<WatchlistKey, List<Long>>()).asStateFlow()
        override val revision: Long get() = 0
        private fun none(): Nothing = throw ApiException(404, WatchlistWords.UPDATE_SERVER)
        override suspend fun refresh(): List<Watchlist> = none()
        override suspend fun holding(key: WatchlistKey): List<Watchlist> = none()
        override suspend fun create(name: String): Watchlist = none()
        override suspend fun update(id: Long, name: String?, removeWatched: Boolean?): Watchlist = none()
        override suspend fun delete(id: Long) = none()
        override suspend fun saveToOnly(title: WatchlistTitle): OnePress = none()
        override suspend fun save(id: Long, title: WatchlistTitle): List<Long> = none()
        override suspend fun remove(id: Long, key: WatchlistKey): List<Long> = none()
        override fun removeLater(key: WatchlistKey, from: Long?): HeldRemoval = none()
        override fun known(key: WatchlistKey, ids: List<Long>, since: Long) = Unit
        override val refusals: Flow<String> = emptyFlow()
        override val changes: Flow<Unit> = emptyFlow()
    }
}

/** The id [WatchlistRepository.held] uses for Watchlist while the one press that makes it is on its way. */
const val NEW_WATCHLIST_ID = 0L

private fun ItemKind.mediaType(): MediaType? = when (this) {
    ItemKind.Movie -> MediaType.Movie
    ItemKind.Show -> MediaType.Tv
    ItemKind.Unmatched -> null
}

/** The card as a title to save; null for one TMDB hasn't matched, which has no menu item. */
fun BrowseItem.watchlistTitle(): WatchlistTitle? {
    val type = kind.mediaType() ?: return null
    val id = tmdbId ?: return null
    return WatchlistTitle(WatchlistKey(type, id), title, year, null)
}

/** The movie's page as a title to save; null without a TMDB match, when the page has no button. */
fun MovieDetail.watchlistTitle(): WatchlistTitle? = tmdbId?.let { WatchlistTitle(WatchlistKey(MediaType.Movie, it), title, year, null) }

fun ShowDetail.watchlistTitle(): WatchlistTitle? = tmdbId?.let { WatchlistTitle(WatchlistKey(MediaType.Tv, it), title, year, null) }

/** A list's entry as a title to save, with TMDB's poster for one the library lacks. */
fun MediaListEntry.watchlistTitle(): WatchlistTitle? {
    val type = mediaType ?: return null
    val id = tmdbId?.takeIf { found } ?: return null
    return WatchlistTitle(WatchlistKey(type, id), title, year, posterPath.takeIf { item == null })
}

/** Where [key] is: what this device changed lately, else what the screen read ([read]). */
fun Map<WatchlistKey, List<Long>>.holding(key: WatchlistKey?, read: List<Long>): List<Long> = key?.let { this[key] } ?: read

/** On a watchlist: what this device changed lately, else the card's own mark. */
fun Map<WatchlistKey, List<Long>>.watchlisted(item: BrowseItem): Boolean = item.watchlistTitle()?.let { this[it.key] }?.isNotEmpty() ?: item.watchlisted

/**
 * The words watchlists use, shared by the phone, tablet and TV (and as the
 * web and Apple apps word them). "Watchlist" has a capital as the name of
 * the first list and none as the common noun.
 */
object WatchlistWords {
    /** The first watchlist's name, made on the first save. */
    const val FIRST = "Watchlist"

    const val UPDATE_SERVER = "Update the server to save titles to watchlists."

    /** The account menu's entry and the page's title. */
    const val WATCHLISTS = "Watchlists"

    /** Above a watchlist's name on its page. */
    const val KICKER = "Watchlist"

    const val PICKER = "Save to watchlist"
    const val SAVE_TO = "Save to watchlist…"
    const val NEW_ONE = "New watchlist…"
    const val NEW_BUTTON = "New watchlist"
    const val NAME = "Name"
    const val CANCEL = "Cancel"
    const val CREATE = "Create"
    const val DONE = "Done"
    const val UNDO = "Undo"
    const val RENAME = "Rename"
    const val SAVE = "Save"
    const val DELETE = "Delete"
    const val REMOVE = "Remove"
    const val SWITCH = "Remove titles once watched"
    const val SWITCH_NOTE = "A movie leaves when you finish it. A show leaves when you’ve watched every episode in your library."
    const val KEEPS = "Keeps watched titles"
    const val NOT_IN_LIBRARY = "Not in your library"
    const val NONE_TITLE = "No watchlists yet"
    const val NONE_BODY = "Save a movie or show to watch later. Press Watchlist on its page."
    const val EMPTY_TITLE = "Nothing on this watchlist yet"
    const val EMPTY_BODY = "Press Watchlist on a movie’s or show’s page to save it here."
    const val BACK_CLOSES = "Back closes this panel."

    /** The button: "Watchlist", "On Watchlist", "On Halloween", "On 2 watchlists", from the names of the watchlists holding the title. */
    fun button(names: List<String>): String = when (names.size) {
        0 -> FIRST
        1 -> "On ${names[0]}"
        else -> "On ${names.size} watchlists"
    }

    /** What the button says to screen readers, which also hear it is a toggle and whether it is on. */
    fun buttonSpoken(names: List<String>, title: String): String = if (names.isEmpty()) "Save $title to a watchlist" else "${button(names)}: $title"

    fun saved(name: String) = "Saved to $name"

    fun removed(name: String) = "Removed from $name"

    fun add(name: String) = "Add to $name"

    fun removeFrom(name: String) = "Remove from $name"

    fun taken(name: String) = "You already have a watchlist called ${name.trim()}."

    fun limit(max: Int) = "You have $max watchlists, the most you can make."

    /** "12 titles", "1 title". */
    fun titles(n: Int) = plural(n, "title")

    /** "12 titles · 9 in your library", under a watchlist's name. */
    fun line(titles: Int, inLibrary: Int) = "${titles(titles)} · $inLibrary in your library"

    fun deleteQuestion(name: String) = "Delete $name?"

    /** "Its 6 titles stay in your library. This can’t be undone." — only what the library has stays. */
    fun deleteBody(inLibrary: Int): String = when (inLibrary) {
        0 -> "This can’t be undone."
        1 -> "Its 1 title stays in your library. This can’t be undone."
        else -> "Its $inLibrary titles stay in your library. This can’t be undone."
    }
}

/** The names of [ids] among the person's watchlists; Watchlist for one not read yet, as the one being made. */
fun List<Watchlist>?.namesOf(ids: List<Long>): List<String> = ids.map { id -> this?.firstOrNull { it.id == id }?.name ?: WatchlistWords.FIRST }

/** The one name a person with at most one watchlist sees in the menus: theirs, or Watchlist before the first. */
fun List<Watchlist>?.onlyName(): String = this?.singleOrNull()?.name ?: WatchlistWords.FIRST

/** A name already used by another of the person's watchlists, whatever the case; [except] is the one being renamed. */
fun List<Watchlist>.nameTaken(name: String, except: Long? = null): Boolean = any { it.id != except && it.name.trim().equals(name.trim(), ignoreCase = true) }

/**
 * A card's menu item for watchlists: "Save to watchlist…" with two or more
 * (it opens the picker), else Add to or Remove from the only one; null for a
 * card that can't be saved or a server without watchlists.
 */
fun watchlistMenuLabel(watchlists: List<Watchlist>?, watchlisted: Boolean): String = when {
    (watchlists?.size ?: 0) >= 2 -> WatchlistWords.SAVE_TO
    watchlisted -> WatchlistWords.removeFrom(watchlists.onlyName())
    else -> WatchlistWords.add(watchlists.onlyName())
}

/** The sorts of a watchlist's page. */
enum class ListSort(val label: String) {
    Added("Date added"),
    Title("Title"),
    Released("Release year"),
}

/** The type chips of a watchlist's page: All, Movies, TV shows. */
enum class ListShown(val label: String, val type: MediaType?) {
    All("All", null),
    Movies("Movies", MediaType.Movie),
    Shows("TV shows", MediaType.Tv),
    ;

    companion object {
        fun of(type: MediaType?): ListShown = entries.first { it.type == type }
    }
}
