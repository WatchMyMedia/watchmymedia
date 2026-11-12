package media.watchmy.client.fake

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.watchmy.client.model.*
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LIVE_CAST_API_LEVEL
import media.watchmy.client.model.LIVE_GUIDE_API_LEVEL
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.LIVE_SEARCH_MIN_CHARS
import media.watchmy.client.model.LIVE_TV_API_LEVEL
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveGuide
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveGuideState
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.LiveSearchHit
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.User
import media.watchmy.client.model.forLiveTV
import media.watchmy.client.model.liveTVError
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The guide boards' fictional lineup (design/mockups/android/LiveGuide): 4.3
 * isn't in the feed (no listings, no logo), 4.4 is listed without a logo, and
 * 36.1 has gone from the tuner's list. Logos and pictures are paths no image
 * loader can fetch, so they draw as their wordmarks and placeholders.
 */
val FakeLiveChannels = listOf(
    fakeChannel("2.1", "HRBR-HD"),
    fakeChannel("2.2", "Harbor Weather"),
    fakeChannel("2.3", "Retro Reels"),
    fakeChannel("4.1", "NFLD-HD"),
    fakeChannel("4.2", "Sleuth Hour"),
    fakeChannel("4.3", "Coast Shop", logo = false),
    fakeChannel("4.4", "Night Owl", logo = false),
    fakeChannel("7.1", "LKSD-HD"),
    fakeChannel("7.2", "Driftwood"),
    fakeChannel("7.3", "Kids Corner"),
    fakeChannel("9.1", "PNVL-HD"),
    fakeChannel("11.1", "Valley Public"),
    fakeChannel("20.1", "Cinema Vault"),
    LiveChannel("live-36-1", "fake-antenna", "36.1", "LKPT-36", available = false, reason = "unavailable"),
)

private fun fakeChannel(number: String, name: String, logo: Boolean = true) =
    LiveChannel("live-${number.replace('.', '-')}", "fake-antenna", number, name, logo = "/api/live-tv/art/logo-${number.replace('.', '-')}".takeIf { logo })

class FakeLiveRepository(
    private val level: () -> Int = { LIVE_TV_API_LEVEL },
    private val user: () -> User? = { User(1, "Sam", true) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LiveRepository {
    var enabled = true
    var allowed = true
    var playback = true
    var offline = false
    var items = FakeLiveChannels

    /** Listings are on at [LIVE_GUIDE_API_LEVEL]; false keeps the channel-only screens. */
    var listings = true

    /** This account may cast a channel, from [LIVE_CAST_API_LEVEL]. */
    var cast = true
    var stale = false
    /** Asked-for windows, for tests of prefetching. */
    val guideRequests = mutableListOf<Pair<Long, Long>>()
    val schedule: FakeSchedule by lazy { FakeSchedule(clock(), zone) }

    override suspend fun capabilities(): LiveCapabilities {
        if (level() < LIVE_TV_API_LEVEL || user() == null) return LiveCapabilities()
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        return LiveCapabilities(enabled, allowed, playback, guide = listings && level() >= LIVE_GUIDE_API_LEVEL, cast = cast && level() >= LIVE_CAST_API_LEVEL, recording = recordingOn && level() >= LIVE_RECORDING_API_LEVEL, canRecord = canRecord(), automatic = level() >= LIVE_AUTOMATIC_API_LEVEL)
    }

    override suspend fun channels(): List<LiveChannel> {
        if (level() < LIVE_TV_API_LEVEL || user() == null) return emptyList()
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        if (!allowed) throw ApiException(403, "Live TV access changed.")
        if (!enabled) return emptyList()
        if (!listings || level() < LIVE_GUIDE_API_LEVEL) return items.map { it.copy(logo = null) }
        val now = clock()
        return items.map { channel ->
            val programmes = schedule.programmes(channel)
            val on = programmes.indexOfFirst { it.airingAt(now) }
            channel.personalised().copy(now = programmes.getOrNull(on)?.personalised(), next = if (on >= 0) programmes.getOrNull(on + 1)?.personalised() else null)
        }
    }

    override suspend fun guide(startMs: Long, endMs: Long): LiveGuide? {
        if (level() < LIVE_GUIDE_API_LEVEL || user() == null) return null
        guideRequests += startMs to endMs
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        if (!allowed) throw ApiException(403, "Live TV access changed.")
        val state = LiveGuideState(listings, schedule.updatedAtMs(stale), stale, schedule.listedThroughMs)
        if (!listings || !enabled) return LiveGuide(startMs, endMs, state, items.map { LiveGuideRow(it.copy(logo = null), false, emptyList()) })
        return LiveGuide(startMs, endMs, state, items.map { channel ->
            val programmes = schedule.programmes(channel)
            LiveGuideRow(channel.personalised(), programmes.isNotEmpty(), programmes.filter { it.endMs > startMs && it.startMs < endMs }.map { it.personalised() })
        })
    }

    /**
     * Most programme pictures can't be fetched, so they draw their title
     * cards; every third title gets one of the fakes' fictional backdrops,
     * so Home's Live row screen has a picture to fill (PLAN-HOME.md §12).
     */
    override fun artUrl(path: String?): String? {
        if (path == null) return null
        val slot = Math.floorMod(path.hashCode(), 3)
        if (slot != 0) return null
        val name = listOf("precinct", "harbour", "saltmarsh", "meridian")[Math.floorMod(path.hashCode() / 3, 4)]
        return fakeArtUrl(FakeData.backdrop(name))
    }

    // --- favourites, search and reminders (LIVE_PERSONAL_API_LEVEL), as the extras boards draw them ---

    private fun personal() = level() >= LIVE_PERSONAL_API_LEVEL && user() != null && listings

    /** The boards' favourites, by number: 2.1, 4.1, 4.3 (no listings), 7.1 and 11.1. */
    var favourites: Set<String> = setOf("2.1", "4.1", "4.3", "7.1", "11.1")

    /** Every search asked for, for tests. */
    val searches = mutableListOf<String>()

    /** How long before each programme its reminder comes. */
    var leadMinutes = 5

    private val changes = MutableSharedFlow<LivePersonalChange>(extraBufferCapacity = 8)
    override val personalChanges: Flow<LivePersonalChange> = changes.asSharedFlow()

    /** As another device would: the server's event for [change]. */
    fun changedElsewhere(change: LivePersonalChange) {
        changes.tryEmit(change)
    }

    private val _reminders = MutableStateFlow<LiveReminders?>(null)
    override val reminders: StateFlow<LiveReminders?> = _reminders.asStateFlow()

    /** The person's reminders on the server; seeded with the boards' on first read. */
    private var stored: List<LiveReminder>? = null

    private fun channelFor(number: String) = items.firstOrNull { it.number == number }

    private fun LiveProgramme.reminderOn(channel: LiveChannel, id: String, state: LiveReminderState = LiveReminderState.Set) = LiveReminder(
        id, channel.id, channel.number, channel.name, this.id, startMs, endMs, title, episodeTitle, season, episode, state,
    )

    /** The boards' list: Dollar Dash and Station 9 tonight, Nature’s Edge tomorrow, one moved and one gone. */
    private fun seeded(): List<LiveReminder> {
        val now = clock()
        fun next(number: String, title: String, after: Long = now): Pair<LiveChannel, LiveProgramme>? {
            val channel = channelFor(number) ?: return null
            return schedule.programmes(channel).firstOrNull { it.title == title && it.startMs > after }?.let { channel to it }
        }
        val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return listOfNotNull(
            next("2.1", "Dollar Dash")?.let { (c, p) -> p.reminderOn(c, "rem-dollar-dash") },
            next("7.1", "Station 9")?.let { (c, p) -> p.reminderOn(c, "rem-station-9") },
            next("4.1", "The Sleuth Files", tomorrow)?.let { (c, p) -> p.reminderOn(c, "rem-sleuth", LiveReminderState.Moved) },
            next("11.1", "Nature’s Edge", tomorrow)?.let { (c, p) -> p.reminderOn(c, "rem-natures-edge") },
            next("11.1", "Great Rail Trips", tomorrow)?.let { (c, p) -> p.reminderOn(c, "rem-rail-trips", LiveReminderState.Gone).copy(programmeId = "gone-rail-trips") },
        ).sortedBy { it.startMs }
    }

    private fun publish() {
        val items = stored.orEmpty().filter { it.endMs > clock() }.sortedBy { it.startMs }
        _reminders.value = LiveReminders(items, leadMinutes)
    }

    override suspend fun setFavourite(channel: LiveChannel, favourite: Boolean) {
        if (!personal()) return
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        favourites = if (favourite) favourites + channel.number else favourites - channel.number
    }

    override suspend fun search(query: String): List<LiveSearchHit> {
        val q = query.trim()
        if (!personal() || q.length < LIVE_SEARCH_MIN_CHARS) return emptyList()
        searches += q
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val now = clock()
        val remindedIds = stored.orEmpty().filter { it.state != LiveReminderState.Gone }.map { it.programmeId }.toSet()
        return withListings().flatMap { channel ->
            schedule.programmes(channel).filter { p ->
                val text = (p.title + " " + p.episodeTitle.orEmpty()).lowercase()
                p.endMs > now && words.all { it in text }
            }.map { LiveSearchHit(channel, it.personalised().copy(reminder = it.id in remindedIds)) }
        }.sortedWith(compareBy({ it.programme.startMs }, { it.channel.number })).take(50)
    }

    override suspend fun refreshReminders(): LiveReminders? {
        if (!personal()) return null
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        if (stored == null) stored = seeded()
        publish()
        return _reminders.value
    }

    override suspend fun remind(programme: LiveProgramme): LiveReminder? {
        if (!personal()) return null
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        if (programme.startMs <= clock()) throw ApiException(409, "That programme has started.")
        val channel = items.firstOrNull { schedule.programmes(it).any { p -> p.id == programme.id } } ?: throw ApiException(404, "That programme isn't in the listings.")
        if (stored == null) stored = seeded()
        val existing = stored.orEmpty().firstOrNull { it.programmeId == programme.id && it.state != LiveReminderState.Gone }
        val set = existing ?: programme.reminderOn(channel, "rem-${programme.id}")
        stored = stored.orEmpty().filter { it.id != set.id } + set
        publish()
        return set
    }

    override suspend fun forget(reminderId: String) {
        if (!personal()) return
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        stored = stored.orEmpty().filter { it.id != reminderId }
        publish()
    }

    override suspend fun setReminderMinutes(minutes: Int) {
        if (!personal()) return
        require(minutes in LIVE_REMINDER_LEADS)
        if (offline) throw ApiException(null, "Couldn't reach the server. Check the connection and try again.")
        leadMinutes = minutes
        if (stored == null) stored = seeded()
        publish()
    }

    private fun withListings(): List<LiveChannel> = items.filter { it.available && SCHEDULE_NUMBERS.contains(it.number) }

    /** The channels' favourites and the programmes' reminders as the server marks them. */
    private fun LiveChannel.personalised(): LiveChannel = if (personal()) copy(favourite = number in favourites) else this

    private fun LiveProgramme.personalised(): LiveProgramme = copy(
        reminder = personal() && stored.orEmpty().any { it.programmeId == id && it.state != LiveReminderState.Gone },
        recording = if (canRecord()) dvrItems.firstOrNull { it.programmeId == id && it.state !in listOf("cancelled", "deleted") }?.let { RecordingMark(it.id, it.state, it.series, ownsRecording(it.userName), it.reason) } else null,
    )

    var recordingAccess = "admins"
    var recordingDeleteAccess = "admins"
    private fun ownsRecording(name: String) = user()?.let { it.isAdmin || it.name == name } == true
    private fun canDeleteRecordings() = user()?.let { it.isAdmin || recordingDeleteAccess == "everyone" } == true
    /** An admin has recording on (Settings › Live TV › Recordings). */
    var recordingOn = true
    private fun canRecord() = recordingOn && level() >= LIVE_RECORDING_API_LEVEL && allowed && user()?.let { it.isAdmin || recordingAccess == "everyone" } == true
    private fun requireRecording() { if (!canRecord()) throw ApiException(403, "You can’t record on this server."); if (offline) throw ApiException(null, "Couldn’t reach the server.") }
    private var dvrRules = mutableListOf<LiveRecordingRule>()
    private val dvrItems: MutableList<LiveRecording> by lazy {
        val now = clock()
        val channel = items.first { it.number == "4.2" }
        val current = schedule.programmes(channel).firstOrNull { it.airingAt(now) }
        val next = schedule.programmes(channel).firstOrNull { it.startMs > now }
        mutableListOf<LiveRecording>().also { rows ->
            if (current != null) rows += current.recordingOn(channel, "fake-recording-now", "recording").copy(startedAtMs = current.startMs, userName = "Sam")
            if (next != null) rows += next.recordingOn(channel, "fake-recording-upcoming", "scheduled").copy(userName = "Sam")
            rows += LiveRecording("fake-recording-complete", "Inspector Vane", channel.id, channel.number, channel.name, now - 7_200_000L, now - 3_600_000L, "done", mediaFileId = 9101, userName = "Sam", canCancel = true, canDelete = true)
            // Both tuners are booked three hours on: Conference Football and Harbor Evening News hold them.
            val football = LiveRecording("fake-recording-football", "Conference Football", "live-4-1", "4.1", "NFLD-HD", now + 3 * HOUR, now + 4 * HOUR + HOUR / 2, "scheduled", userName = "Alex", canCancel = true)
            val news = LiveRecording("fake-recording-news", "Harbor Evening News", "live-7-1", "7.1", "LKSD-HD", now + 3 * HOUR - HOUR / 6, now + 4 * HOUR - HOUR / 6, "scheduled", userName = "Sam", canCancel = true)
            rows += football
            rows += news
            rows += LiveRecording("fake-recording-conflict", "Coastline Rescue", "live-2-1", "2.1", "HRBR-HD", now + 3 * HOUR, now + 4 * HOUR, "conflict", userName = "Sam", canCancel = true, reason = "tuners_busy", conflictsWith = listOf(football.holder(), news.holder()))
        }
    }

    /** The fake server's two tuners, which recordings share. */
    private val fakeTuners = 2
    private companion object { const val HOUR = 3_600_000L }

    private fun LiveRecording.holder() = RecordingConflict(id, title, number, channelName, startMs, endMs, userName, running = active)

    /** The recordings holding both tuners at some moment of [startMs]–[endMs], as the server's plan names them. */
    private fun holders(startMs: Long, endMs: Long, except: String? = null): List<RecordingConflict> {
        val overlapping = dvrItems.filter { it.id != except && it.state in listOf("scheduled", "recording") && it.startMs < endMs && it.endMs > startMs }
        val moments = (listOf(startMs) + overlapping.map { it.startMs }).filter { it in startMs until endMs }
        val busiest = moments.map { at -> overlapping.filter { it.startMs <= at && at < it.endMs } }.maxByOrNull { it.size }.orEmpty()
        return if (busiest.size >= fakeTuners) busiest.map { it.holder() } else emptyList()
    }

    private fun LiveRecording.planned(): LiveRecording {
        val busy = holders(startMs, endMs, id)
        return if (busy.isEmpty() || active) this else copy(state = "conflict", reason = "tuners_busy", conflictsWith = busy)
    }

    private fun LiveProgramme.recordingOn(channel: LiveChannel, id: String, state: String, ruleId: String? = null) = LiveRecording(
        id, title, channel.id, channel.number, channel.name, startMs, endMs, state, ruleId = ruleId, userName = user()?.name.orEmpty(),
        sourceId = channel.sourceId, programmeId = this.id, episodeTitle = episodeTitle, season = season, episode = episode,
        series = ruleId != null, canCancel = true,
    )

    override suspend fun recordings(section: RecordingSection, cursor: String?): RecordingPage {
        requireRecording()
        val filtered = when (section) {
            RecordingSection.Now -> dvrItems.filter { it.active }
            RecordingSection.Upcoming -> dvrItems.filter { it.state == "scheduled" }
            RecordingSection.Conflicts -> dvrItems.filter { it.state in listOf("conflict", "failed", "gone") }
            RecordingSection.Recorded -> dvrItems.filter { it.state in listOf("done", "partial") }.sortedByDescending { it.startMs }
            RecordingSection.Series -> emptyList()
        }
        val start = cursor?.toIntOrNull() ?: 0
        val page = filtered.drop(start).take(20)
        return RecordingPage(page.map { it.copy(canCancel = ownsRecording(it.userName), canDelete = it.canDelete && canDeleteRecordings()) }, if (section == RecordingSection.Series) dvrRules.map { it.copy(canCancel = ownsRecording(it.userName)) } else emptyList(), (start + page.size).takeIf { it < filtered.size }?.toString(), 2, 500_000_000_000L)
    }

    override suspend fun record(programme: LiveProgramme, options: RecordingOptions): LiveRecording? {
        requireRecording()
        require(options.valid)
        dvrItems.firstOrNull { it.programmeId == programme.id && it.state != "cancelled" }?.let { return it }
        val channel = items.firstOrNull { schedule.programmes(it).any { p -> p.id == programme.id } } ?: throw ApiException(404, "That programme isn’t in the listings.")
        val rule = if (options.series) LiveRecordingRule("rule-${programme.id}", programme.title, "series", options.newOnly, options.anyChannel, options.keep, options.padBefore, options.padAfter, user()?.name.orEmpty(), channel.id, channel.number, canCancel = true).also { dvrRules += it } else null
        val row = programme.recordingOn(channel, "recording-${programme.id}", if (programme.airingAt(clock())) "recording" else "scheduled", rule?.id).copy(padBefore = options.padBefore, padAfter = options.padAfter).planned()
        dvrItems += row
        changes.tryEmit(LivePersonalChange.Recordings)
        return row
    }

    override suspend fun recordManual(request: ManualRecording): LiveRecording? {
        requireRecording()
        request.validation(clock())?.let { throw ApiException(422, it) }
        val channel = items.firstOrNull { it.id == request.channelId } ?: throw ApiException(404, "Channel not found.")
        val id = "manual-${request.startMs}-${dvrItems.size}"
        val rule = if (request.repeat != "once") LiveRecordingRule("rule-$id", request.name, "manual", channelId = channel.id, number = channel.number, repeat = request.repeat, canCancel = true).also { dvrRules += it } else null
        return LiveRecording(id, request.name, channel.id, channel.number, channel.name, request.startMs, request.endMs, "scheduled", ruleId = rule?.id, userName = user()?.name.orEmpty(), canCancel = true).planned().also { dvrItems += it; changes.tryEmit(LivePersonalChange.Recordings) }
    }

    override suspend fun cancelRecording(id: String) {
        requireRecording()
        val index = dvrItems.indexOfFirst { it.id == id }
        if (index >= 0 && !ownsRecording(dvrItems[index].userName)) throw ApiException(403, "Only its setter or an admin may cancel this recording.")
        if (index >= 0) dvrItems[index] = dvrItems[index].let { it.copy(state = if (it.active) "partial" else "cancelled", endedAtMs = clock(), canDelete = it.active || it.canDelete) }
        changes.tryEmit(LivePersonalChange.Recordings)
    }

    override suspend fun deleteRecordingFile(id: String, recordAgain: Boolean) {
        requireRecording()
        if (!canDeleteRecordings()) throw ApiException(403, "You can’t delete recordings on this server.")
        val row = dvrItems.firstOrNull { it.id == id && it.canDelete && it.state in listOf("done", "partial") } ?: throw ApiException(403, "You can’t delete this recording.")
        if (recordAgain) dvrItems.remove(row) else dvrItems[dvrItems.indexOf(row)] = row.copy(state = "deleted", mediaFileId = null)
        changes.tryEmit(LivePersonalChange.Recordings)
    }

    override suspend fun updateRecording(id: String, options: RecordingOptions?, prefer: Boolean, over: String?) {
        requireRecording()
        val index = dvrItems.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "Recording not found.")
        if (!ownsRecording(dvrItems[index].userName)) throw ApiException(403, "Only its setter or an admin may change this recording.")
        val preferred = dvrItems[index]
        if (prefer && preferred.state == "conflict") {
            // The one that gives way: the named holder, else the first not yet recording.
            val holder = preferred.conflictsWith.firstOrNull { it.id == (over ?: it.id) && !it.running }
                ?: if (over != null) throw ApiException(409, "That recording can’t give way.") else null
            holder?.let { yielded -> dvrItems.replaceAll { if (it.id == yielded.id) it.copy(state = "conflict", reason = "tuners_busy", conflictsWith = listOf(preferred.copy(state = "scheduled").holder())) else it } }
        }
        dvrItems[index] = preferred.let { it.copy(state = if (prefer) "scheduled" else it.state, reason = if (prefer) null else it.reason, conflictsWith = if (prefer) emptyList() else it.conflictsWith, padBefore = options?.padBefore ?: it.padBefore, padAfter = options?.padAfter ?: it.padAfter) }
        changes.tryEmit(LivePersonalChange.Recordings)
    }

    override suspend fun updateRecordingRule(id: String, options: RecordingOptions) {
        requireRecording()
        val index = dvrRules.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "Rule not found.")
        if (!ownsRecording(dvrRules[index].userName)) throw ApiException(403, "Only its setter or an admin may change this rule.")
        dvrRules[index] = dvrRules[index].copy(newOnly = options.newOnly, anyChannel = options.anyChannel, keep = options.keep, padBefore = options.padBefore, padAfter = options.padAfter)
        changes.tryEmit(LivePersonalChange.Recordings)
    }

    override suspend fun deleteRecordingRule(id: String) {
        requireRecording()
        if (dvrRules.any { it.id == id && !ownsRecording(it.userName) }) throw ApiException(403, "Only its setter or an admin may delete this rule.")
        dvrRules.removeAll { it.id == id }
        dvrItems.replaceAll { if (it.ruleId == id && it.state == "scheduled") it.copy(state = "cancelled") else it }
        changes.tryEmit(LivePersonalChange.Recordings)
    }
}

private val SCHEDULE_NUMBERS = setOf("2.1", "2.2", "2.3", "4.1", "4.2", "4.4", "7.1", "7.2", "7.3", "9.1", "11.1", "20.1")

/**
 * The boards' evening, from two hours before the hour now: the first five
 * hours of each channel repeat with the next episode numbers, from ten hours
 * before to 7 PM two days on, where listings end ("Wed 7 PM" on the boards).
 */
class FakeSchedule(nowMs: Long, private val zone: ZoneId) {
    private val anchor = Instant.ofEpochMilli(nowMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).minusHours(2).toInstant().toEpochMilli()
    val listedThroughMs: Long = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(2).atTime(19, 0).atZone(zone).toInstant().toEpochMilli()

    fun updatedAtMs(stale: Boolean): Long = anchor - if (stale) 49 * 3_600_000L else 2 * 3_600_000L

    private val cache = mutableMapOf<String, List<LiveProgramme>>()

    fun programmes(channel: LiveChannel): List<LiveProgramme> = synchronized(cache) {
        cache.getOrPut(channel.id) {
            val slots = SCHEDULE[channel.number] ?: return@getOrPut emptyList()
            val out = mutableListOf<LiveProgramme>()
            var repeat = -2
            while (anchor + repeat * BLOCK_MS < listedThroughMs) {
                var start = anchor + repeat * BLOCK_MS
                for (slot in slots) {
                    if (start >= listedThroughMs) break
                    out += slot.programme(channel.id, start, repeat)
                    start += slot.minutes * 60_000L
                }
                repeat++
            }
            out
        }
    }

    private class Slot(val minutes: Int, val title: String, val line: String, val categories: String, val tags: String) {
        fun programme(channelId: String, startMs: Long, repeat: Int): LiveProgramme {
            val categories = categories.split(" · ")
            val numbered = Regex("^(S)?(\\d+) E(\\d+)(?: · (.+))?$").find(line)
            val movie = Regex("^Movie · (\\d{4})$").find(line)
            val sports = categories.first() == "Sports"
            return LiveProgramme(
                id = "$channelId-$startMs",
                startMs = startMs,
                endMs = startMs + minutes * 60_000L,
                title = title,
                episodeTitle = numbered?.groupValues?.get(4)?.ifEmpty { null } ?: line.takeIf { numbered == null && movie == null && it.isNotEmpty() && (repeat == 0 || !sports) },
                season = numbered?.groupValues?.get(2)?.toInt(),
                episode = numbered?.groupValues?.get(3)?.toInt()?.let { (it + repeat).coerceAtLeast(1) },
                airDate = movie?.groupValues?.get(1),
                kind = when {
                    movie != null || categories.first() == "Movie" -> "movie"
                    numbered != null -> "episode"
                    else -> "show"
                },
                new = 'N' in tags,
                live = 'L' in tags,
                repeat = 'N' !in tags && 'L' !in tags,
                hd = 'H' in tags,
                categories = categories,
                description = DESCRIPTIONS[title],
                descriptionFromTmdb = title in TMDB_DESCRIPTIONS,
                picture = "/api/live-tv/art/title-${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}".takeIf { title !in NO_PICTURE },
                still = "/api/images/w780/fake-still-${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.jpg".takeIf { title in STILLS },
                // Films have no series id, as in the listings; everything else is a series.
                seriesKey = "SH-${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}".takeIf { movie == null && categories.first() != "Movie" },
            ).let { if (it.description == null) it.copy(description = TMDB_DESCRIPTIONS[title]) else it }
        }
    }

    private companion object {
        const val BLOCK_MS = 300 * 60_000L
        val NO_PICTURE = setOf("Northfield News", "Postgame")
        val STILLS = setOf("Station 9", "The Iron Coast")
        val TMDB_DESCRIPTIONS = mapOf("Laugh Lines" to "Gil signs up for the town dance contest to impress his new neighbour, then finds out she is the judge.")

        private fun s(minutes: Int, title: String, line: String, categories: String, tags: String) = Slot(minutes, title, line, categories, tags)

        /** Five hours a channel, as the boards draw from 6:00 PM: minutes, title, episode line, categories, tags (New, Live, HD). */
        val SCHEDULE: Map<String, List<Slot>> = mapOf(
            "2.1" to listOf(s(60, "Harbor News at 6", "", "News", "LH"), s(60, "Harbor Evening News", "", "News", "LH"), s(30, "Quiz Parlor", "2026 E88", "Game show", "H"), s(30, "Dollar Dash", "S3 E14 · Double or Nothing", "Game show", "NH"), s(60, "Coastline Rescue", "S2 E9 · Breakwater", "Series · Reality", "NH"), s(60, "Harbor News at 10", "", "News", "LH")),
            "2.2" to listOf(s(60, "Weather Now", "", "News · Weather", ""), s(120, "Weather Now", "", "News · Weather", ""), s(120, "Weather Now", "", "News · Weather", "")),
            "2.3" to listOf(s(60, "Corner Diner", "S1 E3 · Open Late", "Sitcom", ""), s(60, "Laugh Lines", "S2 E5 · Two Left Feet", "Sitcom", ""), s(90, "The Iron Coast", "Movie · 1958", "Movie · Drama", ""), s(90, "Night of the Lanterns", "Movie · 1961", "Movie · Mystery", "")),
            "4.1" to listOf(s(60, "Northfield News at 6", "", "News", "LH"), s(60, "Market Minute", "", "News", "H"), s(60, "The Sleuth Files", "S2 E4 · The Low Tide", "Series · Crime drama", "NH"), s(90, "Conference Football", "Ridgeway at Lakeport", "Sports · Football", "LH"), s(30, "Northfield News", "", "News", "LH")),
            "4.2" to listOf(s(60, "Inspector Vane", "S3 E1 · A Quiet Village", "Series · Mystery", ""), s(60, "Inspector Vane", "S3 E2 · The Lantern Room", "Series · Mystery", ""), s(60, "Inspector Vane", "S3 E3 · Low Water", "Series · Mystery", ""), s(120, "Case Closed?", "S1 E7 · The Second Key", "Series · Documentary", "")),
            "4.4" to listOf(s(60, "Midnight Mysteries", "S1 E4 · The Whistling Pier", "Series · Mystery", ""), s(60, "Midnight Mysteries", "S1 E5 · Fog Bell", "Series · Mystery", ""), s(60, "Midnight Mysteries", "S1 E6 · The Empty Ferry", "Series · Mystery", ""), s(60, "Midnight Mysteries", "S1 E7 · Lamplighter", "Series · Mystery", ""), s(60, "Midnight Mysteries", "S1 E8 · Last Tide", "Series · Mystery", "")),
            "7.1" to listOf(s(60, "Lakeside News at 6", "", "News", "LH"), s(60, "Lakeside Evening News", "", "News", "LH"), s(60, "Valley Bake-Off", "S6 E10 · Pies", "Series · Cooking", "NH"), s(60, "Station 9", "S4 E12 · The Harbor Fire", "Series · Drama", "NH"), s(60, "Lakeside News at 10", "", "News", "LH")),
            "7.2" to listOf(s(60, "Trail Kitchen", "S2 E1 · Campfire Bread", "Series · Cooking", ""), s(60, "Trail Kitchen", "S2 E2 · River Fish", "Series · Cooking", ""), s(60, "Cabin Builders", "S1 E5 · The Roof", "Series · Home", ""), s(120, "The Long Shore", "Movie · 1974", "Movie · Drama", "")),
            "7.3" to listOf(s(60, "Robo Rangers", "S2 E3 · Bolt Goes Missing", "Kids", ""), s(60, "Robo Rangers", "S2 E4 · The Big Race", "Kids", ""), s(30, "Sprout & Pip", "S1 E12 · Rainy Day", "Kids", "N"), s(30, "Puzzle Pals", "S1 E9 · Lost Shapes", "Kids", ""), s(120, "Kids Corner Overnight", "", "Kids", "")),
            "9.1" to listOf(s(60, "Pineville Tonight", "", "News", "LH"), s(60, "Trivia Town", "2026 E31", "Game show", "H"), s(120, "Pro Hockey", "Ridgeway Hawks vs. Port Ellis", "Sports · Hockey", "LH"), s(60, "Postgame", "", "Sports", "LH")),
            "11.1" to listOf(s(60, "Valley Week", "", "News", ""), s(60, "World Desk", "", "News", "H"), s(60, "Nature’s Edge", "2026 E14 · Owls of the Valley", "Series · Nature", "NH"), s(120, "Great Rail Trips", "S2 E3 · The Coast Line", "Series · Travel", "H")),
            "20.1" to listOf(s(60, "Silver Screen Stories", "S1 E6 · The Studio Lot", "Series · Documentary", ""), s(135, "The Glass Harbor", "Movie · 1949", "Movie · Drama", ""), s(105, "Seven Bridges West", "Movie · 1955", "Movie · Western", "")),
        )

        /** The provider's descriptions as the boards give them; movies end in their star rating. Laugh Lines has none. */
        val DESCRIPTIONS = mapOf(
            "Harbor News at 6" to "Local news, traffic and weather from the harbor towns, with tonight’s forecast at 6:20.",
            "Harbor Evening News" to "The day’s top stories from around the harbor, with weather every fifteen minutes.",
            "Quiz Parlor" to "Three returning champions face a lightning round on maps, music and old movies before the final puzzle.",
            "Dollar Dash" to "Two families race the clock through a warehouse of oddities, and one bets everything on the last aisle.",
            "Coastline Rescue" to "A storm surge strands a fishing crew on the breakwater, and the night team has one window before the tide turns.",
            "Harbor News at 10" to "The late edition: the day’s stories, the overnight forecast and the scores.",
            "Corner Diner" to "Rosa keeps the diner open all night for a stranded wedding party, and the cook has never made a cake.",
            "The Iron Coast" to "A lighthouse keeper and a shipwrecked stranger wait out a winter storm, each hiding why they came. (3.0/4.0)",
            "Night of the Lanterns" to "On the night of the lantern festival, a young reporter follows a stranger through the old quarter. (2.5/4.0)",
            "Northfield News at 6" to "News, weather and sports for Northfield and the upper valley.",
            "Market Minute" to "Prices, harvests and the week ahead for growers and small businesses across the valley.",
            "The Sleuth Files" to "Detective Mara Quill returns to the fishing town where she grew up when the harbor master vanishes at low tide. The only witness is a boy who swears he saw lantern light out on the flats, and the town would rather she left the old case alone. Quill and her partner retrace the last night of the regatta while a storm closes the causeway and cuts the town off until morning.",
            "Conference Football" to "Ridgeway brings an unbeaten season to Lakeport in the conference’s oldest rivalry, live from Harbor Field.",
            "Weather Now" to "Forecast, radar and the marine report for the harbor, updated through the hour.",
            "Northfield News" to "Late headlines and the overnight forecast.",
            "Inspector Vane" to "Vane is called to a village where the river has dropped to its lowest in a century, and something has surfaced.",
            "Case Closed?" to "Retired detectives revisit a locked-door case from 1987 with new evidence and old suspects.",
            "Midnight Mysteries" to "Strange stories from the harbor towns, told by the people who were there.",
            "Lakeside News at 6" to "News, weather and sports for the lakeside towns.",
            "Lakeside Evening News" to "The evening’s stories from around the lake, with the forecast and the commute.",
            "Valley Bake-Off" to "Pie week: the six remaining bakers make a lattice-top fruit pie, then a savoury pie to feed forty.",
            "Station 9" to "A warehouse fire on the harbor front tests the new crew, and Captain Reyes has to choose who goes back in.",
            "Lakeside News at 10" to "The day’s stories, the overnight forecast and the scores.",
            "Trail Kitchen" to "Cooking over an open fire along the coast trail, with what the season and the river give.",
            "Cabin Builders" to "The roof has to go on before the first snow, if the timber arrives in time.",
            "The Long Shore" to "Two brothers walk the length of the coast to scatter their father’s ashes, and argue the whole way. (3.5/4.0)",
            "Robo Rangers" to "The Rangers build a robot to win the school race, but it only goes backwards.",
            "Sprout & Pip" to "It’s raining, so Sprout and Pip turn the kitchen into a jungle.",
            "Puzzle Pals" to "The Pals look for the shapes that have gone missing from the playground.",
            "Kids Corner Overnight" to "Gentle stories and songs through the night.",
            "Pineville Tonight" to "News and weather for Pineville and the hill towns.",
            "Trivia Town" to "Pineville’s pub quiz teams play for the town trophy.",
            "Pro Hockey" to "The Ridgeway Hawks host Port Ellis with first place on the line.",
            "Postgame" to "Highlights, interviews and the standings after tonight’s game.",
            "Valley Week" to "The week’s stories from around the valley, in depth.",
            "World Desk" to "International news and analysis, with reports from correspondents abroad.",
            "Nature’s Edge" to "A year with the barn owls of the valley, from the first eggs to the young birds’ first flight.",
            "Great Rail Trips" to "The coast line from Port Ellis to Northfield, stopping at harbor towns along the way.",
            "Silver Screen Stories" to "How the old studio lot in Port Ellis made forty films in ten years.",
            "The Glass Harbor" to "A glassblower’s daughter takes over the family works, and the feud that comes with it. (3.0/4.0)",
            "Seven Bridges West" to "A surveyor crossing the mountains on horseback has to decide whom to trust. (3.5/4.0)",
        )
    }
}

/** The same live-only controls as the real player, with a controllable clock for previews/tests. */
class FakeLivePlaybackController(
    quality: StreamQuality = StreamQuality.FullHd,
    private val automaticAvailable: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = System::currentTimeMillis,
    private val latencyMs: Long = fakeLatencyMs,
    active: Flow<Boolean> = flowOf(true),
) : LivePlaybackController {
    private val mutableState = MutableStateFlow(LivePlayerState(quality = quality.forLiveTV(automaticAvailable), automaticAvailable = automaticAvailable))
    override val state = mutableState.asStateFlow()
    override val player: StateFlow<Player?> = MutableStateFlow(null)
    private var change: Job? = null
    private var tick: Job? = null
    private var sequence = 0L
    private var released = false
    private var accountActive = true
    private var pausedAt: Long? = null
    private var lastInput = clock()

    init {
        scope.launch {
            active.collect { current ->
                if (!current) {
                    accountActive = false
                    stop()
                }
            }
        }
    }

    /** Set a server reason, such as tuners_busy or no_signal, for the next deliberate start. */
    var nextFailure: String? = null

    override fun start(target: LiveTarget) {
        if (released || !accountActive) return
        change?.cancel()
        tick?.cancel()
        pausedAt = null
        lastInput = clock()
        mutableState.value = LivePlayerState(target = target, quality = state.value.quality, automaticAvailable = automaticAvailable)
        change = scope.launch {
            if (latencyMs > 0) delay(latencyMs)
            val failure = nextFailure.also { nextFailure = null } ?: if (!target.channel.available) target.channel.reason ?: "channel_unavailable" else null
            if (failure != null) {
                fail(failure)
                return@launch
            }
            mutableState.value = state.value.copy(
                status = PlayerStatus.Playing,
                session = LiveSession("fake-live-${++sequence}", target.channel, LiveSessionState.Playing, state.value.quality, null,
                    automatic = state.value.quality == StreamQuality.Auto, actualQuality = StreamQuality.FullHd.takeIf { state.value.quality == StreamQuality.Auto }),
            )
            tick = scope.launch {
                while (true) {
                    delay(1_000)
                    refreshClock()
                }
            }
        }
    }

    /** Recompute pause expiry and still-watching without needing a four-hour fake run. */
    fun refreshClock() {
        if (state.value.status !in listOf(PlayerStatus.Playing, PlayerStatus.Paused)) return
        val elapsed = clock() - lastInput
        val pauseSeconds = pausedAt?.let { ((clock() - it) / 1000).toInt() }
        when {
            pauseSeconds != null && pauseSeconds >= 300 -> fail("pause_expired")
            elapsed >= 4 * 60 * 60_000L + 60_000L -> fail("inactive")
            else -> mutableState.value = state.value.copy(
                askStillWatching = elapsed >= 4 * 60 * 60_000L,
                session = state.value.session?.copy(pauseRemainingSeconds = 300 - (pauseSeconds ?: 0)),
            )
        }
    }

    override fun play() {
        noteUserInput()
        if (state.value.status == PlayerStatus.Paused) {
            val expired = pausedAt?.let { clock() - it >= 60_000L } == true
            pausedAt = null
            mutableState.value = state.value.copy(
                status = PlayerStatus.Playing, atLiveEdge = expired || state.value.atLiveEdge,
                message = if (expired) "Your paused position expired. Resumed at live." else null,
                session = state.value.session?.copy(paused = false, pauseRemainingSeconds = 300),
            )
        } else if (state.value.status in listOf(PlayerStatus.Ended, PlayerStatus.Error)) retry()
    }

    override fun pause() {
        if (state.value.status != PlayerStatus.Playing) return
        noteUserInput()
        pausedAt = clock()
        mutableState.value = state.value.copy(status = PlayerStatus.Paused, atLiveEdge = false, session = state.value.session?.copy(paused = true))
    }

    override fun togglePlayPause() = if (state.value.status == PlayerStatus.Paused) play() else pause()
    override fun goLive() {
        if (state.value.status !in listOf(PlayerStatus.Playing, PlayerStatus.Paused)) return
        noteUserInput()
        pausedAt = null
        mutableState.value = state.value.copy(status = PlayerStatus.Playing, atLiveEdge = true, message = null, session = state.value.session?.copy(paused = false))
    }

    override fun selectQuality(quality: StreamQuality) {
        if (quality.forLiveTV(automaticAvailable) == state.value.quality) return
        mutableState.value = state.value.copy(quality = quality.forLiveTV(automaticAvailable))
        state.value.target?.let(::start)
    }

    /** A fictional bandwidth change, without another channel or tuner. */
    fun automaticStep(actual: StreamQuality) {
        val session = state.value.session?.takeIf { it.automatic } ?: return
        mutableState.value = state.value.copy(session = session.copy(actualQuality = actual, outputGeneration = session.outputGeneration + 1),
            message = if (actual == StreamQuality.FullHd || actual == StreamQuality.Original) "Quality improved for this connection."
                else "Lowered quality for this connection.")
    }

    override fun noteUserInput() {
        lastInput = clock()
        mutableState.value = state.value.copy(askStillWatching = false)
    }

    override fun answerStillWatching(keepWatching: Boolean) = if (keepWatching) noteUserInput() else stop()
    override fun retry() { state.value.target?.let(::start) }
    override fun stop() {
        change?.cancel()
        tick?.cancel()
        mutableState.value = state.value.copy(status = PlayerStatus.Ended, session = null, askStillWatching = false)
    }

    override fun release() {
        stop()
        released = true
        scope.cancel()
    }

    /** An admin stop or revoked access, independent of file-player state. */
    fun fail(reason: String) {
        tick?.cancel()
        mutableState.value = state.value.copy(status = PlayerStatus.Error, session = null, error = liveTVError(reason), askStillWatching = false, reason = reason)
    }
}
