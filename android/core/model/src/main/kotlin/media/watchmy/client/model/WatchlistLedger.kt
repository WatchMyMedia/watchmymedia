package media.watchmy.client.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Where titles are that this device saved or removed: the part of a
 * [WatchlistRepository] that is the same whoever answers (the server or the
 * fakes). A change shows in [held] before it is sent, the reply's answer
 * replaces it, and a refusal puts back what was there. Held removals are
 * sent in [scope], which outlives the screen that made them.
 */
class WatchlistLedger(private val scope: CoroutineScope) {
    private val overlay = MutableStateFlow<Map<WatchlistKey, List<Long>>>(emptyMap())
    val held: StateFlow<Map<WatchlistKey, List<Long>>> = overlay.asStateFlow()

    private val revisions = AtomicLong(0)
    val revision: Long get() = revisions.get()

    private val refused = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val refusals: Flow<String> = refused.asSharedFlow()

    /** [edit] shown at once; [send]'s reply placed after, by [placed] (null leaves the edit); put back if [send] fails. */
    suspend fun <T> change(key: WatchlistKey, edit: (List<Long>) -> List<Long>, placed: (T) -> List<Long>?, send: suspend () -> T): T {
        val before = overlay.value[key]
        set(key, edit(before.orEmpty()))
        val reply = try {
            send()
        } catch (e: Exception) {
            if (e !is CancellationException) putBack(key, before)
            throw e
        }
        placed(reply)?.let { set(key, it) }
        return reply
    }

    /** [key] shown off [from] (every watchlist, when null) at once; [send] only on [HeldRemoval.send]. */
    fun removeLater(key: WatchlistKey, from: Long?, send: suspend () -> List<Long>): HeldRemoval {
        val before = overlay.value[key]
        set(key, if (from == null) emptyList() else before.orEmpty() - from)
        val done = AtomicBoolean(false)
        return object : HeldRemoval {
            override fun send() {
                if (!done.compareAndSet(false, true)) return
                scope.launch {
                    try {
                        set(key, send())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        putBack(key, before)
                        refused.tryEmit(errorMessage(e))
                    }
                }
            }

            override fun undo() {
                if (done.compareAndSet(false, true)) putBack(key, before)
            }
        }
    }

    /** As [WatchlistRepository.known]: taken only when nothing changed here since [since]. */
    fun known(key: WatchlistKey, ids: List<Long>, since: Long) {
        if (revisions.get() == since) overlay.update { it + (key to ids) }
    }

    /** What the server said of [key] in a reply. */
    fun place(key: WatchlistKey, ids: List<Long>) = overlay.update { it + (key to ids) }

    /** The watchlists still there: one deleted holds nothing. */
    fun keepOnly(ids: Set<Long>) = overlay.update { held -> held.mapValues { (_, on) -> on.filter { it in ids || it == NEW_WATCHLIST_ID } } }

    /** Another server or account: nothing here is theirs. */
    fun reset() {
        revisions.incrementAndGet()
        overlay.value = emptyMap()
    }

    private fun set(key: WatchlistKey, ids: List<Long>) {
        revisions.incrementAndGet()
        overlay.update { it + (key to ids) }
    }

    private fun putBack(key: WatchlistKey, before: List<Long>?) {
        revisions.incrementAndGet()
        overlay.update { if (before == null) it - key else it + (key to before) }
    }
}
