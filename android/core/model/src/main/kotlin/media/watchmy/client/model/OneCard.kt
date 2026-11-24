package media.watchmy.client.model

import java.net.URI

/*
 * One card for a title on several of the servers this device is signed in
 * to. The same title is the same kind with the same TMDB id: a movie with a
 * movie, a show with a show; an episode is its show's TMDB id with its
 * season and number. A title TMDB didn't match is never merged.
 */

/** What matches this card on other servers; null for one that never merges. */
fun BrowseItem.cardKey(): String? = tmdbId?.takeIf { it > 0 && kind != ItemKind.Unmatched }?.let { "${kind.name}:$it" }

fun EpisodeCard.cardKey(): String? = showTmdbId?.takeIf { it > 0 }?.let { "Episode:$it:$season:$episode" }

fun ContinueItem.cardKey(): String? {
    val id = tmdbId?.takeIf { it > 0 } ?: return null
    return if (kind == ContinueItem.Kind.Movie) "Movie:$id" else season?.let { s -> episode?.let { e -> "Episode:$id:$s:$e" } }
}

/** The movie's or episode's id a Continue watching card is for, which is what a copy of it carries. */
val ContinueItem.titleId: Long? get() = if (kind == ContinueItem.Kind.Movie) movieId else episodeId

/** A server a merged row or result comes from: [id] null is the server in use. */
data class ServerTag(val id: String?, val name: String)

/**
 * Merges each server's cards into one list, a card a title: [lists] in the
 * order they are to be placed (the server in use's first, then Servers'
 * order), each card placed where its first copy is and the later copies
 * merged into it ([withCopy]); cards that never merge ([key] null) are each
 * their own. [own] stamps a card with the server it came from.
 */
fun <T> mergeCards(
    lists: List<Pair<ServerTag, List<T>>>,
    key: (T) -> String?,
    own: (T, ServerTag) -> T,
    withCopy: (T, Where.Copy, T) -> T,
    id: (T) -> Long,
): List<T> {
    val out = ArrayList<T>()
    val at = HashMap<String, Int>()
    for ((server, cards) in lists) {
        for (card in cards) {
            val k = key(card)
            val i = k?.let { at[it] }
            if (i != null) {
                out[i] = withCopy(out[i], Where.Copy(server.id, server.name.takeIf { server.id != null }, id(card)), card)
                continue
            }
            if (k != null) at[k] = out.size
            out += own(card, server)
        }
    }
    return out
}

private fun ServerTag.where() = if (id == null) Where.Here else Where(id, name)

fun BrowseItem.on(server: ServerTag): BrowseItem = if (server.id == null) this else copy(where = server.where())

fun EpisodeCard.on(server: ServerTag): EpisodeCard = if (server.id == null) this else copy(where = server.where())

fun ContinueItem.on(server: ServerTag): ContinueItem = if (server.id == null) this else copy(where = server.where())

/** A copy merged into the card; a mark on any copy marks the merged card (watched on any server is watched). */
fun BrowseItem.plus(copy: Where.Copy, other: BrowseItem): BrowseItem =
    copy(where = where.copy(copies = where.copies + copy), watched = watched || other.watched)

fun EpisodeCard.plus(copy: Where.Copy, other: EpisodeCard): EpisodeCard =
    copy(where = where.copy(copies = where.copies + copy), watched = watched || other.watched)

fun ContinueItem.plus(copy: Where.Copy): ContinueItem = copy(where = where.copy(copies = where.copies + copy))

/**
 * A late server's copy [other] joining this card where it stands: when it was
 * played later, the card takes its place and server (Resume plays from the
 * latest place), with this card's copies kept; otherwise it is one more copy.
 */
fun ContinueItem.joinedBy(copy: Where.Copy, other: ContinueItem): ContinueItem {
    if ((other.updatedAtMs ?: Long.MIN_VALUE) <= (updatedAtMs ?: Long.MIN_VALUE)) return plus(copy)
    val mine = Where.Copy(where.serverId, where.serverName, titleId ?: fileId)
    return other.copy(where = Where(copy.serverId, copy.serverName, listOf(mine) + where.copies))
}

/** One card a title across [lists], as [mergeCards]. */
fun mergeItems(lists: List<Pair<ServerTag, List<BrowseItem>>>): List<BrowseItem> =
    mergeCards(lists, BrowseItem::cardKey, BrowseItem::on, { a, c, b -> a.plus(c, b) }, BrowseItem::id)

fun mergeEpisodes(lists: List<Pair<ServerTag, List<EpisodeCard>>>): List<EpisodeCard> =
    mergeCards(lists, EpisodeCard::cardKey, EpisodeCard::on, { a, c, b -> a.plus(c, b) }, EpisodeCard::episodeId)

/**
 * Continue watching from every server, a card a title: the copy played last
 * is the card (its place is the latest, and Resume plays from its server),
 * the others merged into it, and the cards ordered by when each was last
 * played on any server.
 */
fun mergeContinue(lists: List<Pair<ServerTag, List<ContinueItem>>>): List<ContinueItem> {
    val all = lists.flatMap { (server, items) -> items.map { server to it } }
    val byKey = LinkedHashMap<String, MutableList<Pair<ServerTag, ContinueItem>>>()
    val single = ArrayList<Pair<ServerTag, ContinueItem>>()
    for (entry in all) {
        val k = entry.second.cardKey()
        if (k == null) single += entry else byKey.getOrPut(k) { ArrayList() } += entry
    }
    val merged = byKey.values.map { copies ->
        val latest = copies.maxBy { it.second.updatedAtMs ?: Long.MIN_VALUE }
        copies.filter { it !== latest }.fold(latest.second.on(latest.first)) { card, (server, item) ->
            card.plus(Where.Copy(server.id, server.name.takeIf { server.id != null }, item.titleId ?: return@fold card))
        }
    } + single.map { (server, item) -> item.on(server) }
    // Newest first; cards without a time keep their place after the dated ones, in the order they came.
    return merged.sortedWith(compareByDescending<ContinueItem> { it.updatedAtMs ?: Long.MIN_VALUE })
}

/** "Home and Cabin", "Home, Cabin and Den": servers by their short names. */
fun serverList(names: List<String>): String {
    val short = names.map(::shortServerName).distinct()
    return when (short.size) {
        0 -> ""
        1 -> short[0]
        else -> short.dropLast(1).joinToString(", ") + " and " + short.last()
    }
}

/** "Cabin server" as cards call it: "Cabin". A name that doesn't end in "server" stays as it is. */
fun shortServerName(name: String): String = name.removeSuffix(" server").removeSuffix(" Server").ifBlank { name }

/** "Cabin server" as a heading calls it: the name, with "server" after it when it doesn't say so. */
fun serverHeading(name: String): String = if (name.endsWith(" server", ignoreCase = true)) name else "$name server"

/**
 * What a card says of where its title is, when it matters (none on a title
 * the server in use has): "On Cabin" for a title only another server has,
 * and on a Continue watching card ([resumes]) "from Cabin" when Resume will
 * play another server's copy.
 */
fun Where.cardLine(resumes: Boolean = false): String? = when {
    serverName == null -> null
    resumes -> "from ${shortServerName(serverName)}"
    onlyElsewhere -> "On ${shortServerName(serverName)}"
    else -> null
}

/** Search's line for a result: "On Home and Cabin" when several servers have it; the server's name when only another has it. */
fun Where.searchLine(inUse: String?): String? {
    if (copies.isEmpty()) return serverName?.let(::serverHeading)
    val names = all.map { it.serverName ?: inUse ?: "this server" }
    return "On " + serverList(names)
}

/** The server at [baseUrl] is reached over the internet, away from this network ("Away from home, so it may be converted to keep up"). */
fun isAwayFromHome(baseUrl: String): Boolean {
    val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return false
    return !isPrivateNetworkHost(host)
}

/** Said under another server's copies when that server is away from home. */
const val AWAY_FROM_HOME_NOTE = "Away from home, so it may be converted to keep up."


/** "Den server didn't answer, so its titles aren't on Home." */
fun notOnHome(name: String): String = "${serverHeading(name)} didn’t answer, so its titles aren’t on Home."

/**
 * Which copy Play uses for a title on several servers, remembered on this
 * device for each title: [titleKey] is a card key ("Movie:603", or an
 * episode's), and the choice is the registered server's id with its file.
 */
interface CopyChoices {
    fun chosen(titleKey: String): Chosen?

    fun choose(titleKey: String, chosen: Chosen)

    data class Chosen(val serverId: String, val fileId: Long)

    /** Nowhere to keep them (tests and previews that don't ask). */
    object None : CopyChoices {
        override fun chosen(titleKey: String): Chosen? = null
        override fun choose(titleKey: String, chosen: Chosen) = Unit
    }

    /** Kept in memory: the fakes'. */
    class InMemory : CopyChoices {
        private val kept = java.util.concurrent.ConcurrentHashMap<String, Chosen>()
        override fun chosen(titleKey: String): Chosen? = kept[titleKey]
        override fun choose(titleKey: String, chosen: Chosen) {
            kept[titleKey] = chosen
        }
    }
}

/** A reminder's or link's Live TV is another server's: "That's Cabin server's Live TV. Switch to it from Servers to watch." */
fun liveIsElsewhere(name: String): String = "That’s ${serverHeading(name)}’s Live TV. Switch to it from Servers to watch."
