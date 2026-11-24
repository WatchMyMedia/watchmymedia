package media.watchmy.client.presentation.library

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LetterOffset
import media.watchmy.client.model.Where
import media.watchmy.client.model.attempt
import media.watchmy.client.model.cardKey
import media.watchmy.client.model.on
import media.watchmy.client.model.plus
import media.watchmy.client.presentation.servers.OtherServers

/**
 * A library grid filled from every server this device is signed in to, a
 * page at a time: each server is asked for its own pages in the person's
 * sort and filters, and the pages are merged in that order, a card a title.
 * A server's card goes out only while every other server still in the grid
 * has its next card in hand, so the order holds across pages.
 *
 * A server that doesn't answer in [timeoutMs], or fails, is left out and
 * named ([failed]); one that answers after the first draw joins from where
 * the grid has got to, so nothing drawn moves. Counts are the servers' added
 * up, less the titles found on several, and [about] until every server has
 * answered.
 */
internal class MergedPages(
    sources: List<Pair<OtherServers.Other, Long?>>,
    private val query: ItemQuery,
    private val pageSize: Int,
    private val timeoutMs: Long = OtherServers.TIMEOUT_MS,
    /** Where each server starts: a letter's offset there, for a jump. */
    private val startAt: (OtherServers.Other) -> Int = { 0 },
) {
    class Source(val other: OtherServers.Other, val libraryId: Long?) {
        var offset = 0
        val buffer = ArrayDeque<BrowseItem>()
        var total = -1
        var answered = false
        var done = false
        var failure: Throwable? = null
        var page: ItemPage? = null
        /** Asked and not answered when the grid was drawn: its cards join from where the grid has got to. */
        var late = false
        val active: Boolean get() = failure == null && answered && libraryId != null
    }

    val sources = sources.map { (o, id) -> Source(o, id).also { s -> if (id == null) { s.answered = true; s.done = true } } }

    /** The merged cards so far. */
    val items = ArrayList<BrowseItem>()
    private val at = HashMap<String, Int>()
    private var shared = 0
    private var ascending: Boolean? = null

    /** The servers that didn't answer, or failed, by name. */
    val failed: List<String> get() = sources.filter { it.failure != null }.map { it.other.name }

    /** The servers asked and not answered yet. */
    val pending: List<String> get() = sources.filter { !it.answered }.map { it.other.name }

    /** The count reads "about" while a server is still out. */
    val about: Boolean get() = sources.any { !it.answered }

    /** Every server's titles, added up, less those found on several. */
    val total: Int get() = sources.filter { it.active }.sumOf { it.total.coerceAtLeast(0) } - shared

    /** Every server's pages have been merged. */
    val finished: Boolean get() = sources.all { it.done || it.failure != null } && sources.all { it.buffer.isEmpty() }

    /** The server in use's first page, for its genres, decades, letters and counts. */
    val first: ItemPage? get() = sources.firstOrNull { it.other.id == null }?.page ?: sources.firstOrNull { it.page != null }?.page

    /** Every answered server's letters, to jump each by. */
    fun letters(source: Source): List<LetterOffset>? = source.page?.letters

    /**
     * Fetches the next page of each source in [needing] at once, each with
     * [timeoutMs]; one that runs out or fails is out of the grid.
     */
    private suspend fun fetch(needing: List<Source>) = coroutineScope {
        needing.map { s ->
            async {
                val id = s.libraryId ?: return@async
                val start = if (!s.answered) startAt(s.other) else s.offset
                val page = attempt {
                    withTimeoutOrNull(timeoutMs) { s.other.library.items(query.copy(libraryId = id, offset = start, limit = pageSize)) }
                        ?: throw ApiException(null, "${s.other.name} didn’t answer.")
                }
                page.onSuccess { p ->
                    if (!s.answered) s.page = p
                    s.answered = true
                    s.total = p.total
                    s.offset = p.offset + p.items.size
                    s.buffer.addAll(p.items)
                    if (p.items.isEmpty() || s.offset >= p.total) s.done = true
                }.onFailure { e ->
                    s.answered = true
                    s.failure = e
                }
            }
        }.awaitAll()
    }

    /** One merge or fetch at a time: a late server's page and a scroll's may come together. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /**
     * The first pages, from every server at once. Returns with the first
     * page merged once the server in use has answered and the others have
     * had [waitMs] from the start; one still out then is fetched in [scope]
     * and joins from where the grid has got to when it answers, after which
     * [onJoined] is called.
     */
    suspend fun start(scope: kotlinx.coroutines.CoroutineScope, waitMs: Long, onJoined: () -> Unit) {
        val asked = sources.filter { !it.answered }
        val firsts = asked.associateWith { s -> scope.async { fetch(listOf(s)) } }
        val started = System.nanoTime()
        firsts.entries.firstOrNull { it.key.other.id == null }?.value?.await()
        val left = waitMs - (System.nanoTime() - started) / 1_000_000
        withTimeoutOrNull(left.coerceAtLeast(0)) { firsts.values.awaitAll() }
        firsts.forEach { (s, job) ->
            if (s.answered) return@forEach
            s.late = true
            scope.launch {
                job.await()
                next()
                onJoined()
            }
        }
        next()
    }

    /**
     * Appends up to a page of merged cards: the next in the sort among every
     * server's next card, while each server still in the grid has one in hand.
     */
    suspend fun next(): Int = lock.withLock { nextLocked() }

    private suspend fun nextLocked(): Int {
        var added = 0
        while (added < pageSize) {
            val needing = sources.filter { it.active && !it.done && it.buffer.isEmpty() }
            if (needing.isNotEmpty()) {
                fetch(needing)
                continue
            }
            val ready = sources.filter { it.active && it.buffer.isNotEmpty() }
            if (ready.isEmpty()) break
            val order = order(ready)
            val s = ready.minWith { a, b -> order.compare(a.buffer.first(), b.buffer.first()) }
            val card = s.buffer.removeFirst()
            if (place(card, s)) added++
        }
        return added
    }

    /** A card in its place: a title the grid has already gains this copy, else it joins the end. True when it joined. */
    private fun place(card: BrowseItem, s: Source): Boolean {
        val key = card.cardKey()
        val i = key?.let { at[it] }
        if (i != null) {
            items[i] = items[i].plus(Where.Copy(s.other.id, s.other.name.takeIf { s.other.id != null }, card.id), card)
            shared++
            return false
        }
        if (key != null) at[key] = items.size
        items += card.on(s.other.tag)
        return true
    }

    /**
     * The sort's order, as the server in use pages it: ascending or
     * descending by the sort's value, read from its first page, so the
     * merge goes the way each server already does.
     */
    private fun order(ready: List<Source>): Comparator<BrowseItem> {
        val base = valueOrder(query.sort)
        val up = ascending ?: (sources.firstOrNull { it.page != null }?.page?.items?.let { list ->
            val pairs = list.zipWithNext()
            pairs.count { (a, b) -> base.compare(a, b) <= 0 } >= pairs.count { (a, b) -> base.compare(a, b) >= 0 }
        } ?: true).also { ascending = it }
        return if (up) base else base.reversed()
    }

    companion object {
        /** A title as the libraries sort it: without a leading article, any case. */
        fun titleKey(title: String): String {
            val lower = title.lowercase(java.util.Locale.ROOT)
            val article = listOf("the ", "a ", "an ").firstOrNull { lower.startsWith(it) }
            return if (article == null) lower else lower.removePrefix(article)
        }

        fun valueOrder(sort: ItemSort): Comparator<BrowseItem> = when (sort) {
            ItemSort.Title -> compareBy { titleKey(it.title) }
            ItemSort.Added -> compareBy { it.addedAtMs ?: 0L }
            ItemSort.Year, ItemSort.Aired -> compareBy<BrowseItem> { it.year ?: 0 }.thenBy { titleKey(it.title) }
            ItemSort.Released -> compareBy<BrowseItem> { it.releaseDate.orEmpty() }.thenBy { titleKey(it.title) }
            ItemSort.Rating -> compareBy<BrowseItem> { it.rating ?: 0.0 }.thenBy { titleKey(it.title) }
            ItemSort.Runtime -> compareBy<BrowseItem> { it.runtimeMin ?: 0 }.thenBy { titleKey(it.title) }
        }
    }
}
