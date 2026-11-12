package media.watchmy.client.core.playback.watchnext

/** Invalidates queued updates even when a viewer switches away and back. */
internal class WatchNextScope(initial: String?) {
    data class Ticket(val account: String?, val generation: Long)
    @Volatile private var current = Ticket(initial, 0)

    @Synchronized fun select(account: String?) { current = Ticket(account, current.generation + 1) }
    fun capture(): Ticket = current
    fun permits(ticket: Ticket): Boolean = ticket.account != null && ticket == current
}
