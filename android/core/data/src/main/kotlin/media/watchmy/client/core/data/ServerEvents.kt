package media.watchmy.client.core.data

import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import okhttp3.Request
import okio.BufferedSource
import media.watchmy.client.model.RowsChange

/**
 * A top-level string or number field of an event's JSON data ("screen",
 * "listId"); null when the data isn't an object or lacks it. Events are a
 * few hundred bytes, so the data is parsed whole.
 */
internal fun eventField(data: String, name: String): String? {
    val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(data) }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
    val value = element[name] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return value.content.takeIf { value !is kotlinx.serialization.json.JsonNull }
}

/** One server-sent event. */
internal data class ServerEvent(val event: String, val data: String, val id: String? = null)

/**
 * Reads the text/event-stream format a line at a time (https://html.spec.whatwg.org/#event-stream-interpretation).
 * An event whose data runs past [maxDataChars] throws [EventTooLargeException].
 */
internal class SseParser(private val maxDataChars: Int = ServerEvents.MAX_EVENT_CHARS, private val onEvent: (ServerEvent) -> Unit) {
    private var event = ""
    private val data = StringBuilder()
    private var id: String? = null
    private var hasData = false

    fun line(line: String) {
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(':')) return
        val field = line.substringBefore(':')
        var value = if (':' in line) line.substringAfter(':') else ""
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.length + value.length + 1 > maxDataChars) throw EventTooLargeException("an event's data is over $maxDataChars characters")
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> id = value
        }
    }

    private fun dispatch() {
        if (hasData) onEvent(ServerEvent(event.ifEmpty { "message" }, data.toString(), id))
        event = ""
        data.clear()
        hasData = false
    }
}

/**
 * Each line of [source], without its line break, to [each], until the stream
 * ends (a last line without a break is dropped: it can't finish an event). A
 * line longer than [maxLineBytes] throws [EventTooLargeException] rather than
 * be read into memory whole.
 */
internal fun readEventLines(source: BufferedSource, maxLineBytes: Long = ServerEvents.MAX_LINE_BYTES, each: (String) -> Unit) {
    while (true) {
        val line = try {
            source.readUtf8LineStrict(maxLineBytes)
        } catch (e: EOFException) {
            // No line break within the limit: a line too long, or the end of the stream.
            if (source.buffer.size > maxLineBytes) throw EventTooLargeException("an event-stream line is over $maxLineBytes bytes")
            return
        }
        each(line)
    }
}

/** A line or an event over the limit: the connection is dropped and made again. */
internal class EventTooLargeException(message: String) : IOException(message)

/** The stream answered something other than 200; [status] 401 means signed out. */
internal class EventStreamException(val status: Int) : IOException("event stream answered $status")

/**
 * GET /api/events on the current server, signed in: [changes] emits for
 * watch.changed and library.changed, [requestChanges] for requests.changed
 * and problems.changed, [liveChanges] for the Live TV ones, [rowChanges] for
 * rows.changed and lists.changed. Connected only while collected (shared by every
 * collector, and kept 5 s after the last one leaves so moving between screens
 * does not reconnect). A dropped stream reconnects with backoff, and both emit
 * once after reconnecting, since events may have been missed.
 */
internal class ServerEvents(
    private val store: LocalStore,
    private val http: Http,
    scope: CoroutineScope,
    private val backoffMs: (attempt: Int) -> Long = BACKOFF,
) {

    /** The events screens refetch for, and [RECONNECTED]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val events: Flow<ServerEvent> = store.sessionChanges
        .transformLatest {
            val target = store.requestSnapshot().first ?: return@transformLatest
            if (target.account == null) return@transformLatest
            var attempt = 0
            var connectedBefore = false
            while (true) {
                var pinged = false
                try {
                    stream(target).collect { e ->
                        when (e.event) {
                            "ping" -> {
                                attempt = 0
                                // Only a connection's first ping can follow missed events;
                                // servers up to 0.4.1 also ping every 15 s to keep it open.
                                if (!pinged) {
                                    if (connectedBefore) emit(ServerEvent(RECONNECTED, ""))
                                    connectedBefore = true
                                    pinged = true
                                }
                            }
                            in LIBRARY_EVENTS, in REQUEST_EVENTS, in LIVE_EVENTS, in ROW_EVENTS -> emit(e)
                        }
                    }
                } catch (e: EventStreamException) {
                    // Signed out (the interceptor has cleared the sign-in) or no events here: wait for a new sign-in.
                    if (e.status == 401 || e.status == 404) return@transformLatest
                } catch (_: IOException) {
                    // Dropped: reconnect below.
                }
                delay(backoffMs(attempt++))
            }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 0)

    private val names: Flow<String> = events.map { it.event }

    val changes: Flow<Unit> = names.filter { it == RECONNECTED || it in LIBRARY_EVENTS }.map { }

    val requestChanges: Flow<Unit> = names.filter { it == RECONNECTED || it in REQUEST_EVENTS }.map { }

    /**
     * rows.changed (with its screen) and lists.changed (with its list), to the
     * person (PLAN-ROWS.md §6.2, §6.3); a reconnection emits both for every
     * screen and list, since either may have been missed.
     */
    val rowChanges: Flow<RowsChange> = events.filter { it.event == RECONNECTED || it.event in ROW_EVENTS }.transform { e ->
        when (e.event) {
            RECONNECTED -> {
                emit(RowsChange.Layout(null))
                emit(RowsChange.Lists(null))
            }
            ROWS_CHANGED -> emit(RowsChange.Layout(eventField(e.data, "screen")?.takeIf { it.isNotBlank() }))
            else -> emit(RowsChange.Lists(eventField(e.data, "listId")?.toLongOrNull()))
        }
    }

    /**
     * live.favourites.changed and live.reminders.changed, private to the
     * person signed in (PLAN-LIVE-TV-GUIDE.md §5.7), by their event names; a
     * reconnection emits both, since either may have been missed.
     */
    val liveChanges: Flow<String> = names.filter { it == RECONNECTED || it in LIVE_EVENTS }
        .transform { if (it == RECONNECTED) LIVE_EVENTS.forEach { e -> emit(e) } else emit(it) }

    /** The raw events of one connection; completes when the server closes it. */
    private fun stream(session: Session): Flow<ServerEvent> = callbackFlow {
        val account = session.account ?: run { close(); return@callbackFlow }
        val token = store.tokenForRequest(session.server.id, account) ?: run { close(); return@callbackFlow }
        val client = http.withToken(token).newBuilder().readTimeout(45, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        val baseUrl = session.server.baseUrl
        val call = client.newCall(
            Request.Builder().url("$baseUrl/api/events").header("Accept", "text/event-stream").build(),
        )
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (response.code == 401) store.tokenRejected(session.server.id, token)
                    if (!response.isSuccessful) throw EventStreamException(response.code)
                    val parser = SseParser { trySendBlocking(it) }
                    readEventLines(response.body.source(), each = parser::line)
                }
                close()
            } catch (e: IOException) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    companion object {
        private const val RECONNECTED = "reconnected"
        // Refresh on transitions, never for every scan.progress event.
        private val LIBRARY_EVENTS = setOf("watch.changed", "library.changed", "scan.started", "scan.finished", "scan.failed")
        private val REQUEST_EVENTS = setOf("requests.changed", "problems.changed")
        const val LIVE_FAVOURITES = "live.favourites.changed"
        const val LIVE_REMINDERS = "live.reminders.changed"
        const val LIVE_RECORDINGS = "live.recordings.changed"
        private val LIVE_EVENTS = setOf(LIVE_FAVOURITES, LIVE_REMINDERS, LIVE_RECORDINGS)
        const val ROWS_CHANGED = "rows.changed"
        const val LISTS_CHANGED = "lists.changed"
        private val ROW_EVENTS = setOf(ROWS_CHANGED, LISTS_CHANGED)

        /**
         * The longest line and event data this reads, 1 MB each. The server's
         * events are a few hundred bytes; the limits keep a broken or hostile
         * server from filling memory through a line that never ends.
         */
        const val MAX_LINE_BYTES = 1L shl 20
        const val MAX_EVENT_CHARS = 1 shl 20

        /** 1 s, 2 s, 4 s … up to a minute. */
        val BACKOFF: (Int) -> Long = { attempt -> (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L) }
    }
}
