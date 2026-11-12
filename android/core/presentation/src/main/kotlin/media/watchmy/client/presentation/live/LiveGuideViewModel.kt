package media.watchmy.client.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.LIVE_GUIDE_WINDOW_HOURS
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LIVE_REMINDER_DEFAULT_LEAD
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveGuide
import media.watchmy.client.model.LiveGuideFilter
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.at
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The guide grid the phone, tablet and TV share (PLAN-LIVE-TV-GUIDE.md §6):
 * a window of half hours from now, the days listed, the category filters and
 * the programme whose details show. It asks only while a screen [show]s it
 * with listings on, so servers without them never see a guide request.
 * From [LIVE_PERSONAL_API_LEVEL] it also keeps the person's favourites (the
 * Favourites filter) and their reminders (PLAN-LIVE-TV-GUIDE.md §6.5).
 */
class LiveGuideViewModel(
    private val live: LiveRepository,
    private val servers: ServerRepository,
    private val auth: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    data class Selection(val channel: LiveChannel, val programme: LiveProgramme)

    /** A programme to open the guide at: a search result or a reminder. */
    data class Target(val channelId: String, val programmeId: String, val startMs: Long)

    /** A day the listings cover: picking it moves the window there, at the time of day it is now. */
    data class Day(val label: String, val startMs: Long, val endMs: Long)

    data class State(
        val enabled: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val nowMs: Long = 0L,
        val windowStartMs: Long = 0L,
        val spanMinutes: Int = 60,
        val guide: LiveGuide? = null,
        val filter: LiveGuideFilter = LiveGuideFilter.All,
        val selection: Selection? = null,
        val days: List<Day> = emptyList(),
        val earliestMs: Long = 0L,
        val latestMs: Long = 0L,
        /** The server keeps favourites and reminders ([LIVE_PERSONAL_API_LEVEL]). */
        val personal: Boolean = false,
        /** Stars changed here, by channel id, until the server's listings say the same. */
        val favouriteChanges: Map<String, Boolean> = emptyMap(),
        val reminders: LiveReminders? = null,
        /** Something the person asked for didn't happen: a favourite or a reminder the server refused. */
        val notice: String? = null,
    ) {
        /** The channels the person starred, by id. */
        val favourites: Set<String> get() {
            if (!personal) return emptySet()
            val listed = guide?.rows.orEmpty().filter { it.channel.favourite }.map { it.channel.id }.toSet()
            return listed - favouriteChanges.filterValues { !it }.keys + favouriteChanges.filterValues { it }.keys
        }

        fun isFavourite(channel: LiveChannel): Boolean = channel.id in favourites

        /** The filters the guide offers: Favourites first once the person has one. */
        val filters: List<LiveGuideFilter> get() = LiveGuideFilter.offered(favourites.isNotEmpty())

        /** The filter in force: Favourites falls back to All once the last favourite goes. */
        val shownFilter: LiveGuideFilter get() = filter.takeIf { it in filters } ?: LiveGuideFilter.All

        val leadMinutes: Int get() = reminders?.leadMinutes ?: LIVE_REMINDER_DEFAULT_LEAD

        /** The reminder for [programme]: the list's, else the listing's mark until the list is read. */
        fun reminderFor(programme: LiveProgramme): LiveReminder? {
            if (!personal) return null
            reminders?.let { return it.forProgramme(programme.id) }
            return if (programme.reminder) LiveReminder("", null, "", null, programme.id, programme.startMs, programme.endMs, programme.title) else null
        }

        /** A reminder whose programme left the listings, for the slot on [channel] where [programme] now is. */
        fun goneReminderAt(channel: LiveChannel, programme: LiveProgramme): LiveReminder? = reminders?.items?.firstOrNull {
            it.state == LiveReminderState.Gone && it.number == channel.number && it.startMs >= programme.startMs && it.startMs < programme.endMs
        }

        val windowEndMs: Long get() = windowStartMs + spanMinutes * MINUTE
        val dayIndex: Int get() = days.indexOfLast { it.startMs <= windowStartMs }.coerceAtLeast(0)
        val canGoEarlier: Boolean get() = windowStartMs > earliestMs
        val canGoLater: Boolean get() = windowStartMs < latestMs
        val showsNow: Boolean get() = nowMs in windowStartMs until windowEndMs
        val stale: Boolean get() = guide?.state?.stale == true
        val updatedAtMs: Long? get() = guide?.state?.updatedAtMs
        val listedThroughMs: Long? get() = guide?.state?.listedThroughMs

        /** The time a filter judges a channel by: now while the window shows it, else the window's start. */
        private val filterTime: Long get() = nowMs.coerceIn(windowStartMs, windowEndMs - 1)

        /**
         * The rows the filter keeps ([LiveGuideFilter.keeps]), in channel order, each with the
         * programmes overlapping the window. Channels without listings show under All, and under
         * Favourites when starred.
         */
        val rows: List<LiveGuideRow> get() {
            val shown = shownFilter
            val starred = favourites
            return guide?.rows.orEmpty()
                .filter { row -> shown.keeps(row, row.channel.id in starred, filterTime) }
                .map { row -> row.copy(programmes = row.programmes.filter { it.endMs > windowStartMs && it.startMs < windowEndMs }) }
        }

        /** Half-hour slots across the window. */
        val slots: List<Long> get() = (0 until spanMinutes / 30).map { windowStartMs + it * 30 * MINUTE }
    }

    private data class Identity(val server: String?, val user: Long?)
    private fun identity() = Identity(servers.current.value?.server?.id, auth.user.value?.id)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var generation = 0L
    private var load: Job? = null
    private var preferredChannel: String? = null
    private var personalJob: Job? = null
    private var pending: Target? = null

    private fun personalLevel() = (servers.current.value?.server?.apiLevel ?: 0) >= LIVE_PERSONAL_API_LEVEL

    init {
        viewModelScope.launch {
            live.reminders.collect { reminders -> _state.update { it.copy(reminders = reminders?.takeIf { _ -> it.personal }) } }
        }
        viewModelScope.launch {
            combine(servers.current, auth.user) { _, _ -> identity() }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    // Another server or account: drop its listings and start again at now.
                    generation++
                    load?.cancel()
                    personalJob?.cancel()
                    val on = _state.value.enabled
                    _state.value = State(spanMinutes = _state.value.spanMinutes)
                    if (on) show(true, preferredChannel)
                }
        }
    }

    /** Called by a screen as it shows listings ([on]), or the channel-only screens instead. [channelId] is selected first. */
    fun show(on: Boolean, channelId: String? = null) {
        preferredChannel = channelId ?: preferredChannel
        if (!on) {
            if (_state.value.enabled) {
                generation++
                load?.cancel()
                personalJob?.cancel()
                _state.value = State(spanMinutes = _state.value.spanMinutes)
            }
            return
        }
        if (_state.value.enabled && _state.value.guide != null) return
        val now = clock()
        val start = halfHour(now)
        val personal = personalLevel()
        val target = pending?.startMs?.let { halfHour(it.coerceAtLeast(now)).coerceIn(start, start + DAY) } ?: start
        _state.update { it.copy(enabled = true, nowMs = now, windowStartMs = target, earliestMs = start, latestMs = start + DAY, days = days(now, null), personal = personal, reminders = live.reminders.value.takeIf { personal }) }
        fetch()
        if (personal) followPersonal()
    }

    /** Reads the reminders, and follows the person's changes on their other devices while the guide shows. */
    private fun followPersonal() {
        personalJob?.cancel()
        personalJob = viewModelScope.launch {
            launch { attempt { live.refreshReminders() } }
            live.personalChanges.collect { change ->
                if (!_state.value.enabled) return@collect
                when (change) {
                    LivePersonalChange.Favourites -> {
                        _state.update { it.copy(favouriteChanges = emptyMap()) }
                        move(_state.value.windowStartMs, force = true)
                    }
                    LivePersonalChange.Reminders -> attempt { live.refreshReminders() }
                    LivePersonalChange.Recordings -> move(_state.value.windowStartMs, force = true)
                }
            }
        }
    }

    /** Stars or unstars [channel], here at once and then on the server; put back if the server refuses. */
    fun toggleFavourite(channel: LiveChannel) {
        val s = _state.value
        if (!s.personal) return
        val on = !s.isFavourite(channel)
        _state.update { it.copy(favouriteChanges = it.favouriteChanges + (channel.id to on), notice = null) }
        viewModelScope.launch {
            attempt { live.setFavourite(channel, on) }.onFailure { error ->
                _state.update { it.copy(favouriteChanges = it.favouriteChanges + (channel.id to !on), notice = errorMessage(error)) }
            }
        }
    }

    /** Remind me for [programme]; the list updates when the server answers. */
    fun remind(programme: LiveProgramme) {
        if (!_state.value.personal) return
        _state.update { it.copy(notice = null) }
        viewModelScope.launch { attempt { live.remind(programme) }.onFailure { error -> _state.update { it.copy(notice = errorMessage(error)) } } }
    }

    /** Removes [reminder], [LiveReminder.id] the server's. */
    fun forget(reminder: LiveReminder) {
        if (!_state.value.personal) return
        _state.update { it.copy(notice = null) }
        viewModelScope.launch {
            val id = reminder.id.ifEmpty { live.reminders.value?.forProgramme(reminder.programmeId)?.id ?: attempt { live.refreshReminders() }.getOrNull()?.forProgramme(reminder.programmeId)?.id }
                ?: return@launch
            attempt { live.forget(id) }.onFailure { error -> _state.update { it.copy(notice = errorMessage(error)) } }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** The phone shows an hour across, the tablet and TV two. */
    fun setSpan(minutes: Int) {
        val span = minutes.coerceIn(30, MAX_SPAN_MINUTES) / 30 * 30
        if (span == _state.value.spanMinutes) return
        _state.update { it.copy(spanMinutes = span) }
        move(_state.value.windowStartMs)
    }

    fun earlier() = move(_state.value.windowStartMs - 30 * MINUTE)
    fun later() = move(_state.value.windowStartMs + 30 * MINUTE)

    /** Today, at the half hour it is now, with what is on selected again. */
    fun toNow() {
        val now = clock()
        _state.update { it.copy(nowMs = now, earliestMs = halfHour(now)) }
        move(halfHour(now))
        _state.value.guide?.let { select(it, preferredChannel ?: _state.value.selection?.channel?.id) }
    }

    fun pickDay(index: Int) {
        val day = _state.value.days.getOrNull(index) ?: return
        val now = clock()
        val timeOfDay = Instant.ofEpochMilli(halfHour(now)).atZone(zone()).toLocalTime()
        val target = Instant.ofEpochMilli(day.startMs).atZone(zone()).toLocalDate().atTime(timeOfDay).atZone(zone()).toInstant().toEpochMilli()
        move(target.coerceAtLeast(day.startMs))
    }

    fun filter(filter: LiveGuideFilter) = _state.update { it.copy(filter = filter) }

    /**
     * Opens the guide at [target]: its day and half hour, with it selected
     * once the listings are in, under All channels so the filter can't hide it.
     */
    fun open(target: Target) {
        pending = target
        preferredChannel = target.channelId
        _state.update { it.copy(filter = LiveGuideFilter.All) }
        if (!_state.value.enabled) return
        move(target.startMs.coerceAtLeast(clock()))
        selectPending()
    }

    private fun selectPending() {
        val target = pending ?: return
        val row = _state.value.guide?.rows?.firstOrNull { it.channel.id == target.channelId } ?: return
        val programme = row.programmes.firstOrNull { it.id == target.programmeId } ?: row.programmes.firstOrNull { it.startMs == target.startMs } ?: return
        pending = null
        _state.update { it.copy(selection = Selection(row.channel, programme)) }
    }

    /** The programme whose details show: picked on a phone or tablet, focused on a TV. */
    fun select(channel: LiveChannel, programme: LiveProgramme) = _state.update { it.copy(selection = Selection(channel, programme)) }

    fun refresh() {
        if (!_state.value.enabled) return
        val now = clock()
        _state.update { it.copy(nowMs = now, earliestMs = halfHour(now), error = null) }
        move(_state.value.windowStartMs)
    }

    private fun move(startMs: Long, force: Boolean = false) {
        val s = _state.value
        if (!s.enabled) return
        val target = halfHour(startMs).coerceIn(s.earliestMs, maxOf(s.earliestMs, s.latestMs))
        val moved = target != s.windowStartMs
        _state.update { it.copy(windowStartMs = target) }
        if (force || s.guide == null || !s.guide.covers(target, target + MAX_SPAN_MINUTES * MINUTE)) fetch()
        else if (moved) _state.update { it.copy(nowMs = clock()) }
    }

    private fun fetch() {
        val current = identity()
        val request = ++generation
        val start = _state.value.windowStartMs
        load?.cancel()
        _state.update { it.copy(loading = true) }
        load = viewModelScope.launch {
            // As far as one of the server's windows, so moving later rarely waits.
            attempt { live.guide(start, start + LIVE_GUIDE_WINDOW_HOURS * 60 * MINUTE) }
                .onSuccess { guide ->
                    if (request != generation || current != identity()) return@onSuccess
                    val now = clock()
                    val listed = guide?.state?.listedThroughMs
                    val latest = listed?.let { halfHour(it - 30 * MINUTE) } ?: (halfHour(now) + DAY)
                    _state.update {
                        it.copy(
                            loading = false, error = null, guide = guide, nowMs = now,
                            latestMs = maxOf(it.earliestMs, latest),
                            days = days(now, listed),
                        )
                    }
                    if (guide != null && _state.value.selection == null) select(guide, preferredChannel)
                    else if (guide != null) reselect(guide)
                    // A target beyond the first day waits for where listings end, then moves there.
                    val target = pending
                    val s = _state.value
                    if (target != null && target.startMs >= s.windowEndMs && halfHour(target.startMs) <= s.latestMs) move(target.startMs) else selectPending()
                }
                .onFailure { error ->
                    if (request != generation || current != identity()) return@onFailure
                    _state.update { it.copy(loading = false, error = errorMessage(error)) }
                }
        }
    }

    /**
     * The selected programme as these listings have it, so its details show
     * what changed since: a recording set or cancelled, a reminder. One the
     * listings no longer hold stays as it was.
     */
    private fun reselect(guide: LiveGuide) {
        val selection = _state.value.selection ?: return
        val row = guide.rows.firstOrNull { it.channel.id == selection.channel.id } ?: return
        val programme = row.programmes.firstOrNull { it.id == selection.programme.id } ?: return
        if (programme != selection.programme || row.channel != selection.channel) _state.update { it.copy(selection = Selection(row.channel, programme)) }
    }

    /** What is on now on [channelId], else on the first channel with listings. */
    private fun select(guide: LiveGuide, channelId: String?) {
        val now = clock()
        val rows = guide.rows.filter { it.listings }
        val row = rows.firstOrNull { it.channel.id == channelId && it.programmes.at(now) != null } ?: rows.firstOrNull { it.programmes.at(now) != null } ?: return
        _state.update { it.copy(selection = Selection(row.channel, row.programmes.at(now)!!)) }
    }

    /** Today from the half hour now, then each day up to the one listings end on. */
    private fun days(now: Long, listedThrough: Long?): List<Day> {
        val zone = zone()
        val format = LiveClockFormat(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val last = listedThrough?.let { Instant.ofEpochMilli(it - 1).atZone(zone).toLocalDate() } ?: today.plusDays(1)
        return generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { date ->
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            Day(format.dayName(start, now), if (date == today) halfHour(now) else start, end)
        }.toList()
    }

    private fun LiveGuide.covers(from: Long, to: Long) = startMs <= from && endMs >= to

    /** The local half hour [ms] falls in (a few zones are offset by 45 minutes). */
    private fun halfHour(ms: Long): Long {
        val t = Instant.ofEpochMilli(ms).atZone(zone()).truncatedTo(ChronoUnit.MINUTES)
        return t.withMinute(t.minute / 30 * 30).toInstant().toEpochMilli()
    }

    companion object {
        const val MAX_SPAN_MINUTES = 120
        private const val MINUTE = 60_000L
        private const val DAY = 24 * 60 * MINUTE
    }
}
