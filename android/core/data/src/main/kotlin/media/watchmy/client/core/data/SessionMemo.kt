package media.watchmy.client.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * One answer per sign-in, shared while it is fresh (PLAN-ANDROID-REVIEW.md
 * §4.1.4): for reference data that changes only when an admin changes
 * settings, which every movie page, show page and grid asked for again.
 * Callers at the same time share one request (the [Deferred] in flight); the
 * answer is kept for [ttlMs], for this sign-in only (the store's revision)
 * and the server's level as last read (what it may offer changes with it),
 * and dropped by [clear] when an event says it changed. A failure isn't
 * kept. The guide's windows in [RealLiveRepository] work the same way.
 */
internal class SessionMemo<T>(
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMs: Long = TTL_MS,
    private val load: suspend () -> T,
) {
    private class Kept<T>(val key: Pair<Long, Int>, val atMs: Long, val reply: Deferred<T>)

    private val lock = Any()
    private var kept: Kept<T>? = null

    suspend fun get(): T = synchronized(lock) {
        val key = store.revision to (store.session()?.server?.apiLevel ?: 0)
        val now = clock()
        kept?.takeIf { it.key == key && now - it.atMs < ttlMs && !(it.reply.isCompleted && it.reply.getCompletionExceptionOrNull() != null) }?.reply
            ?: scope.async { load() }.also { kept = Kept(key, now, it) }
    }.await()

    /** Asks again next time. */
    fun clear() = synchronized(lock) { kept = null }

    companion object {
        /** Reference data is asked again after five minutes, as well as on its event. */
        const val TTL_MS = 5 * 60_000L
    }
}
