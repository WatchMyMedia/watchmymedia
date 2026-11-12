package media.watchmy.client.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import media.watchmy.client.core.api.models.LiveTVRecordingCreate
import media.watchmy.client.core.api.models.LiveTVRecordingManual
import media.watchmy.client.core.api.models.LiveTVRecordingUpdate
import media.watchmy.client.core.api.models.LiveTVRecordingRuleUpdate
import media.watchmy.client.model.*
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.apis.LiveTvApi
import media.watchmy.client.core.api.apis.UsersApi
import media.watchmy.client.core.api.models.LiveTVCapabilities
import media.watchmy.client.core.api.models.LiveTVChannel
import media.watchmy.client.core.api.models.LiveTVGuide
import media.watchmy.client.core.api.models.LiveTVProgramme
import media.watchmy.client.core.api.models.LiveTVHeartbeatInputBody
import media.watchmy.client.core.api.models.LiveTVLiveProfile
import media.watchmy.client.core.api.models.LiveTVRemindInputBody
import media.watchmy.client.core.api.models.LiveTVReminder
import media.watchmy.client.core.api.models.UpdatePreferencesInputBody
import media.watchmy.client.core.api.models.LiveTVSessionView
import media.watchmy.client.core.api.models.LiveTVStartInputBody
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LIVE_CAST_API_LEVEL
import media.watchmy.client.model.LIVE_GUIDE_API_LEVEL
import media.watchmy.client.model.LIVE_GUIDE_WINDOW_HOURS
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LIVE_REMINDER_DEFAULT_LEAD
import media.watchmy.client.model.LIVE_REMINDER_LEADS
import media.watchmy.client.model.LIVE_SEARCH_MIN_CHARS
import media.watchmy.client.model.LIVE_TV_API_LEVEL
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveGuide
import media.watchmy.client.model.LiveGuideRow
import media.watchmy.client.model.LiveGuideState
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LivePlaybackGateway
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder as ReminderModel
import media.watchmy.client.model.LiveReminderState
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.LiveSearchHit
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.forLiveTV
import media.watchmy.client.model.isOnServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

/**
 * Browsing uses the current account and Http.signedIn discards responses after
 * it changes. Listings come in windows of [LIVE_GUIDE_WINDOW_HOURS] hours on
 * fixed boundaries, kept for this sign-in only; asking for one also fetches the
 * next, so moving later through the guide doesn't wait. From
 * [LIVE_PERSONAL_API_LEVEL] it also keeps the person's favourites, search and
 * reminders (PLAN-LIVE-TV-GUIDE.md §5.7); a change to a favourite drops the
 * windows, which carry them.
 */
internal class RealLiveRepository(
    private val http: Http,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val events: ServerEvents? = null,
) : LiveRepository {
    private class Window(val revision: Long, val fetchedAtMs: Long, val reply: Deferred<LiveGuide>)
    private val windows = mutableMapOf<Long, Window>()
    private val lock = Any()
    private val recordingChanges = MutableSharedFlow<LivePersonalChange>(extraBufferCapacity = 1)
    private val _reminders = MutableStateFlow<LiveReminders?>(null)
    override val reminders: StateFlow<LiveReminders?> = _reminders.asStateFlow()

    init {
        // Another server or account: its reminders and listings aren't this one's.
        scope.launch {
            store.sessionChanges.drop(1).collect {
                _reminders.value = null
                synchronized(lock) { windows.clear() }
            }
        }
    }

    private fun personal() = http.apiLevel() >= LIVE_PERSONAL_API_LEVEL

    override suspend fun setFavourite(channel: LiveChannel, favourite: Boolean) {
        if (!personal()) return
        http.signedIn { base, client ->
            if (favourite) LiveTvApi(base, client).addLiveTVFavourite(channel.id) else LiveTvApi(base, client).removeLiveTVFavourite(channel.id)
        }
        synchronized(lock) { windows.clear() }
    }

    override suspend fun search(query: String): List<LiveSearchHit> {
        val q = query.trim()
        if (!personal() || q.length < LIVE_SEARCH_MIN_CHARS) return emptyList()
        return http.signedIn { base, client ->
            LiveTvApi(base, client).searchLiveTVGuide(q, SEARCH_LIMIT).items.mapNotNull { hit ->
                if (!hit.channel.enabled) return@mapNotNull null
                hit.programme.toModel()?.let { LiveSearchHit(hit.channel.toModel(listings = true), it) }
            }
        }
    }

    override suspend fun refreshReminders(): LiveReminders? {
        if (!personal()) return null
        val revision = store.revision
        val reply = http.signedIn { base, client -> LiveTvApi(base, client).listLiveTVReminders() }
        val read = LiveReminders(reply.items.mapNotNull { it.toModel() }.sortedBy { it.startMs }, reply.leadMinutes.lead())
        if (store.revision == revision) _reminders.value = read
        return read
    }

    override suspend fun remind(programme: LiveProgramme): ReminderModel? {
        if (!personal()) return null
        val revision = store.revision
        val set = http.signedIn { base, client -> LiveTvApi(base, client).setLiveTVReminder(LiveTVRemindInputBody(programme.id)) }.toModel()
            ?: throw ApiException(null, "The server's reminder couldn't be read.")
        if (store.revision == revision) {
            val kept = _reminders.value
            _reminders.value = LiveReminders(((kept?.items.orEmpty()).filter { it.id != set.id } + set).sortedBy { it.startMs }, kept?.leadMinutes ?: LIVE_REMINDER_DEFAULT_LEAD)
        }
        return set
    }

    override suspend fun forget(reminderId: String) {
        if (!personal()) return
        val revision = store.revision
        try {
            http.signedIn { base, client -> LiveTvApi(base, client).deleteLiveTVReminder(reminderId) }
        } catch (e: ApiException) {
            // Already gone (another device, or it ended): the list no longer has it either way.
            if (e.status != 404) throw e
        }
        if (store.revision == revision) _reminders.value = _reminders.value?.let { it.copy(items = it.items.filter { r -> r.id != reminderId }) }
    }

    override suspend fun setReminderMinutes(minutes: Int) {
        if (!personal()) return
        require(minutes in LIVE_REMINDER_LEADS)
        val revision = store.revision
        val saved = http.signedIn { base, client -> UsersApi(base, client).updatePreferences(UpdatePreferencesInputBody(liveTVReminderMinutes = minutes.toLong())) }
        if (store.revision == revision) {
            val lead = saved.liveTVReminderMinutes.lead(minutes)
            _reminders.value = (_reminders.value ?: LiveReminders(emptyList())).copy(leadMinutes = lead)
        }
    }

    override val personalChanges: Flow<LivePersonalChange> = merge(recordingChanges, events?.liveChanges?.mapNotNull {
        when (it) {
            ServerEvents.LIVE_FAVOURITES -> LivePersonalChange.Favourites.also { synchronized(lock) { windows.clear() } }
            ServerEvents.LIVE_REMINDERS -> LivePersonalChange.Reminders
            ServerEvents.LIVE_RECORDINGS -> LivePersonalChange.Recordings.also { synchronized(lock) { windows.clear() } }
            else -> null
        }
    } ?: emptyFlow())

    private fun recordingLevel() = http.apiLevel() >= LIVE_RECORDING_API_LEVEL
    private fun recordingsChanged() {
        synchronized(lock) { windows.clear() }
        recordingChanges.tryEmit(LivePersonalChange.Recordings)
    }

    override suspend fun recordings(section: RecordingSection, cursor: String?): RecordingPage {
        if (!recordingLevel()) return RecordingPage()
        return http.signedIn { base, client -> LiveTvApi(base, client).listLiveTVRecordings(section = LiveTvApi.SectionListLiveTVRecordings.entries.first { it.value == section.id }, cursor = cursor).toModel() }
    }

    override suspend fun record(programme: LiveProgramme, options: RecordingOptions): LiveRecording? {
        if (!recordingLevel()) return null
        require(options.valid)
        val result = http.signedIn { base, client -> LiveTvApi(base, client).createLiveTVRecording(LiveTVRecordingCreate(
            programmeId = programme.id,
            scope = if (options.series) LiveTVRecordingCreate.Scope.series else LiveTVRecordingCreate.Scope.once,
            newOnly = options.newOnly, anyChannel = options.anyChannel, keep = options.keep.toLong(),
            padBefore = options.padBefore.toLong().takeIf { options.customPadding }, padAfter = options.padAfter.toLong().takeIf { options.customPadding },
        )).toModel() }
        recordingsChanged()
        return result
    }

    override suspend fun recordManual(request: ManualRecording): LiveRecording? {
        if (!recordingLevel()) return null
        request.validation(clock())?.let { throw ApiException(422, it) }
        val result = http.signedIn { base, client -> LiveTvApi(base, client).createManualLiveTVRecording(LiveTVRecordingManual(
            channelId = request.channelId, name = request.name,
            start = Instant.ofEpochMilli(request.startMs).toString(), end = Instant.ofEpochMilli(request.endMs).toString(),
            repeat = LiveTVRecordingManual.Repeat.entries.first { it.value == request.repeat }, zone = request.zone,
        )).toModel() }
        recordingsChanged()
        return result
    }

    override suspend fun updateRecording(id: String, options: RecordingOptions?, prefer: Boolean, over: String?) {
        if (!recordingLevel()) return
        http.signedIn { base, client -> LiveTvApi(base, client).updateLiveTVRecording(id, LiveTVRecordingUpdate(padBefore = options?.padBefore?.toLong(), padAfter = options?.padAfter?.toLong(), prefer = prefer.takeIf { it }, over = over?.takeIf { prefer && it.isNotBlank() })) }
        recordingsChanged()
    }

    override suspend fun cancelRecording(id: String) {
        if (!recordingLevel()) return
        try { http.signedIn { base, client -> LiveTvApi(base, client).cancelLiveTVRecording(id) } }
        catch (e: ApiException) { if (e.status != 404) throw e }
        recordingsChanged()
    }

    override suspend fun deleteRecordingFile(id: String, recordAgain: Boolean) {
        if (!recordingLevel()) return
        http.signedIn { base, client -> LiveTvApi(base, client).deleteLiveTVRecordingFile(id, recordAgain) }
        recordingsChanged()
    }

    override suspend fun updateRecordingRule(id: String, options: RecordingOptions) {
        if (!recordingLevel()) return
        require(options.valid)
        http.signedIn { base, client -> LiveTvApi(base, client).updateLiveTVRecordingRule(id, LiveTVRecordingRuleUpdate(
            newOnly = options.newOnly, anyChannel = options.anyChannel, keep = options.keep.toLong(),
            padBefore = options.padBefore.toLong(), padAfter = options.padAfter.toLong(),
        )) }
        recordingsChanged()
    }

    override suspend fun deleteRecordingRule(id: String) {
        if (!recordingLevel()) return
        try { http.signedIn { base, client -> LiveTvApi(base, client).deleteLiveTVRecordingRule(id) } }
        catch (e: ApiException) { if (e.status != 404) throw e }
        recordingsChanged()
    }

    override suspend fun capabilities(): LiveCapabilities {
        if (http.apiLevel() < LIVE_TV_API_LEVEL) return LiveCapabilities()
        return http.signedIn { base, client ->
            LiveTvApi(base, client).getLiveTVCapabilities().toModel(http.apiLevel())
        }
    }

    override suspend fun channels(): List<LiveChannel> {
        if (http.apiLevel() < LIVE_TV_API_LEVEL) return emptyList()
        val listings = http.apiLevel() >= LIVE_GUIDE_API_LEVEL
        return http.signedIn { base, client ->
            LiveTvApi(base, client).listLiveTVChannels().items.filter { it.enabled }.map { it.toModel(listings) }
        }
    }

    override fun artUrl(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = store.current.value?.server?.baseUrl ?: return null
        // Pictures come only from this server, whose client signs the request.
        val url = RealPlaybackGateway.absolute(base, p).toHttpUrlOrNull()?.toString() ?: return null
        return url.takeIf { isOnServer(base, it) }
    }


    override suspend fun guide(startMs: Long, endMs: Long): LiveGuide? {
        if (http.apiLevel() < LIVE_GUIDE_API_LEVEL || endMs <= startMs) return null
        val first = Math.floorDiv(startMs, WINDOW_MS) * WINDOW_MS
        val starts = generateSequence(first) { it + WINDOW_MS }.takeWhile { it < endMs }.toList()
        val replies = starts.map(::window)
        window(starts.last() + WINDOW_MS)
        return merge(replies.awaitAll(), startMs, endMs)
    }

    /** The window starting at [start], fetched once per sign-in and again after [WINDOW_TTL_MS]. */
    private fun window(start: Long): Deferred<LiveGuide> = synchronized(lock) {
        val revision = store.revision
        val now = clock()
        windows.entries.removeAll { (key, kept) -> kept.revision != revision || key + WINDOW_MS < now - WINDOW_MS || kept.reply.isCompleted && kept.reply.getCompletionExceptionOrNull() != null }
        windows[start]?.takeIf { now - it.fetchedAtMs < WINDOW_TTL_MS }?.let { return it.reply }
        val reply = scope.async {
            http.signedIn { base, client ->
                LiveTvApi(base, client).getLiveTVGuide(start = Instant.ofEpochMilli(start).toString(), hours = LIVE_GUIDE_WINDOW_HOURS.toLong()).toModel(base)
            }
        }
        windows[start] = Window(revision, now, reply)
        reply
    }

    private companion object {
        /** The server answers at most this many results. */
        const val SEARCH_LIMIT = 50L
        const val WINDOW_MS = LIVE_GUIDE_WINDOW_HOURS * 3_600_000L
        /** Listings change once a day; a guide left open picks up a refresh within this. */
        const val WINDOW_TTL_MS = 30 * 60_000L
    }
}

/** The server's Live TV capabilities as this app uses them at [level]: listings from 13, casting a channel from 14. */
internal fun LiveTVCapabilities.toModel(level: Int) = LiveCapabilities(
    enabled, allowed, playback, bufferSeconds.seconds(60), pauseSeconds.seconds(300),
    guide = guide && level >= LIVE_GUIDE_API_LEVEL,
    cast = cast == true && level >= LIVE_CAST_API_LEVEL,
    recording = recording == true && level >= LIVE_RECORDING_API_LEVEL,
    canRecord = canRecord == true && level >= LIVE_RECORDING_API_LEVEL,
    automatic = automatic == true && level >= LIVE_AUTOMATIC_API_LEVEL,
)

/**
 * The caster's channels and pictures on this TV (Cast Connect, PLAN-LIVE-TV.md
 * §4.1), with their cast token: what a cast token may read. Pictures carry the
 * token in the URL, since the TV's image loader signs in only to its own server.
 */
internal class CastLiveRepository(private val access: CastTokenAccess) : LiveRepository {
    override suspend fun capabilities(): LiveCapabilities {
        if (access.apiLevel() < LIVE_TV_API_LEVEL) return LiveCapabilities()
        return access.call { base, client -> LiveTvApi(base, client).getLiveTVCapabilities().toModel(access.apiLevel()) }
    }

    override suspend fun channels(): List<LiveChannel> {
        if (access.apiLevel() < LIVE_TV_API_LEVEL) return emptyList()
        val listings = access.apiLevel() >= LIVE_GUIDE_API_LEVEL
        return access.call { base, client -> LiveTvApi(base, client).listLiveTVChannels().items.filter { it.enabled }.map { it.toModel(listings) } }
    }

    override fun artUrl(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = RealPlaybackGateway.absolute(access.baseUrl, p).toHttpUrlOrNull() ?: return null
        if (!isOnServer(access.baseUrl, url.toString())) return null
        return url.newBuilder().setQueryParameter("access_token", access.token).build().toString()
    }
}

/** The windows' rows joined, in the first window's channel order, with programmes overlapping [startMs] to [endMs]. */
internal fun merge(windows: List<LiveGuide>, startMs: Long, endMs: Long): LiveGuide {
    val latest = windows.last()
    val rows = LinkedHashMap<String, LiveGuideRow>()
    val programmes = mutableMapOf<String, MutableMap<String, LiveProgramme>>()
    windows.forEach { window ->
        window.rows.forEach { row ->
            rows[row.channel.id] = rows[row.channel.id]?.let { it.copy(channel = row.channel, listings = it.listings || row.listings) } ?: row
            val kept = programmes.getOrPut(row.channel.id) { mutableMapOf() }
            row.programmes.forEach { kept[it.id] = it }
        }
    }
    return LiveGuide(startMs, endMs, latest.state, rows.values.map { row ->
        row.copy(programmes = programmes[row.channel.id].orEmpty().values.filter { it.endMs > startMs && it.startMs < endMs }.sortedBy { it.startMs })
    })
}

/** One sign-in owns a live lifecycle, including cleanup after switching to another account. */
internal class RealLivePlaybackGateway(
    private val store: LocalStore,
    private val http: Http,
    private val access: ServerAccess? = null,
    private val revision: Long? = null,
    /** Where media tokens come from instead of createMediaToken: a cast token's renewal (Cast Connect), which a cast token may call. */
    private val mediaTokens: (suspend () -> MediaToken)? = null,
) : LivePlaybackGateway {
    private var automaticEnabled = false
    override fun bind(): LivePlaybackGateway {
        if (access != null) return this
        val (session, capturedRevision) = store.requestSnapshot()
        return RealLivePlaybackGateway(store, http, http.bind(session), capturedRevision)
    }

    override val isCurrent: Boolean get() = revision == null || store.revision == revision
    override val active: Flow<Boolean>
        get() = revision?.let { captured -> store.sessionChanges.map { it == captured }.distinctUntilChanged() } ?: flowOf(true)

    override suspend fun liveCapabilities(): LiveCapabilities {
        if (access == null) return bind().liveCapabilities()
        requireCurrent()
        if (access.apiLevel() < LIVE_TV_API_LEVEL) return LiveCapabilities()
        val result = access.call { base, client -> LiveTvApi(base, client).getLiveTVCapabilities().toModel(access.apiLevel()) }
        automaticEnabled = result.automatic
        return result
    }

    override suspend fun start(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo): LiveSession =
        startRequest(target, quality, requestId, client, null)

    override suspend fun startNegotiated(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo, profile: PlaybackProfile): LiveSession =
        startRequest(target, quality, requestId, client, profile)

    private suspend fun startRequest(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo, profile: PlaybackProfile?): LiveSession {
        if (access == null) return if (profile == null) bind().start(target, quality, requestId, client)
            else bind().startNegotiated(target, quality, requestId, client, profile)
        requireCurrent()
        if (access.apiLevel() < LIVE_TV_API_LEVEL) throw ApiException(404, "Live TV needs a newer server.")
        if (!target.channel.available) throw ApiException(409, target.channel.reason ?: "This channel is unavailable.")
        require(requestId.isNotBlank() && requestId.length <= 64)
        val selected = quality.forLiveTV(automaticEnabled && access.apiLevel() >= LIVE_AUTOMATIC_API_LEVEL)
        return access.call { base, httpClient ->
            LiveTvApi(base, httpClient).startLiveTV(LiveTVStartInputBody(
                channelId = target.channel.id,
                requestId = requestId,
                quality = LiveTVStartInputBody.Quality.entries.first { it.value == selected.id },
                clientName = liveClientName(client.name),
                profile = profile?.takeIf { access.apiLevel() >= LIVE_PROFILE_API_LEVEL }?.toLiveApi(),
                autoRecovery = true.takeIf { selected == StreamQuality.Auto },
            )).toModel(base)
        }
        // Return a late successful allocation even when the account changed.
        // The controller checks isCurrent before attaching, then stops its ID
        // with this same bound gateway. Throwing here would leak that ID.
    }

    override suspend fun status(sessionId: String): LiveSession {
        if (access == null) return bind().status(sessionId)
        return access.call { base, client -> LiveTvApi(base, client).getLiveTVSession(sessionId).toModel(base) }
    }

    override suspend fun heartbeat(sessionId: String, paused: Boolean, interacted: Boolean): LiveSession {
        if (access == null) return bind().heartbeat(sessionId, paused, interacted)
        requireCurrent()
        return access.call { base, client ->
            LiveTvApi(base, client).heartbeatLiveTV(sessionId, LiveTVHeartbeatInputBody(paused = paused, interacted = interacted.takeIf { it })).toModel(base)
        }
    }

    override suspend fun heartbeatMeasured(sessionId: String, paused: Boolean, interacted: Boolean, measurement: LiveMeasurement): LiveSession {
        if (access == null) return bind().heartbeatMeasured(sessionId, paused, interacted, measurement)
        requireCurrent()
        if (access.apiLevel() < LIVE_AUTOMATIC_API_LEVEL) return heartbeat(sessionId, paused, interacted)
        return access.call { base, client ->
            LiveTvApi(base, client).heartbeatLiveTV(sessionId, LiveTVHeartbeatInputBody(
                paused = paused, interacted = interacted.takeIf { it },
                state = when (measurement.state) {
                    LivePlaybackState.Playing -> LiveTVHeartbeatInputBody.State.playing
                    LivePlaybackState.Paused -> LiveTVHeartbeatInputBody.State.paused
                    LivePlaybackState.Buffering -> LiveTVHeartbeatInputBody.State.buffering
                },
                bufferedMs = measurement.bufferedMs?.coerceAtLeast(0),
                throughputBps = measurement.throughputBps?.takeIf { it > 0 },
                throughputAgeMs = measurement.throughputAgeMs?.takeIf { it >= 0 },
                stalls = measurement.stalls?.coerceAtLeast(0),
                switchGeneration = measurement.switchGeneration?.takeIf { it > 0 },
                switchFailedGeneration = measurement.switchFailedGeneration?.takeIf { it > 0 },
            )).toModel(base)
        }
    }

    override suspend fun stop(sessionId: String) {
        if (access == null) return bind().stop(sessionId)
        try {
            access.call { base, client -> LiveTvApi(base, client).stopLiveTV(sessionId) }
        } catch (e: ApiException) {
            if (e.status != 404) throw e
        }
    }

    override suspend fun mediaToken(): MediaToken {
        mediaTokens?.let { return it() }
        if (access == null) return bind().mediaToken()
        requireCurrent()
        return access.call { base, client ->
            val token = AuthApi(base, client).createMediaToken()
            MediaToken(token.token, epochMs(token.expiresAt) ?: 0L, token.param.ifBlank { "access_token" }, base)
        }
    }

    private fun requireCurrent() {
        if (!isCurrent) throw CancellationException("The signed-in account changed.")
    }
}

/** MPEG-TS and fMP4 HLS use only codecs the device profile currently offers. */
private fun PlaybackProfile.toLiveApi() = LiveTVLiveProfile(
    segmentFormats = listOf("mpegts", "fmp4"),
    videoCodecs = videoCodecs.filter { it in setOf("h264", "hevc", "hevc10") },
    audioCodecs = audioCodecs.filter { it in setOf("aac", "ac3", "eac3") },
    interlaced = false,
    maxHeight = maxHeight?.toLong(), maxBitrate = maxBitrate,
    maxAudioChannels = maxAudioChannels?.toLong(), hdrTypes = hdrTypes.orEmpty(),
)

internal fun LiveTVChannel.toModel(listings: Boolean = false) = LiveChannel(
    id, sourceId, number, name, available && enabled, reason?.takeIf { it.isNotBlank() },
    logo = logoUrl.takeIf { listings }?.takeIf { it.isNotBlank() },
    now = now?.takeIf { listings }?.toModel(),
    next = next?.takeIf { listings }?.toModel(),
    favourite = favourite == true,
)

/** A lead time the app offers; the server's own default otherwise. */
private fun Long?.lead(fallback: Int = LIVE_REMINDER_DEFAULT_LEAD): Int = this?.toInt()?.takeIf { it in LIVE_REMINDER_LEADS } ?: fallback

/** Null when its times can't be read, so a bad row never shows. Unknown states read as set. */
internal fun LiveTVReminder.toModel(): ReminderModel? {
    val startMs = epochMs(start) ?: return null
    val endMs = epochMs(end)?.takeIf { it > startMs } ?: return null
    return ReminderModel(
        id = id,
        channelId = channelId?.takeIf { it.isNotBlank() },
        number = number,
        channelName = channelName?.takeIf { it.isNotBlank() },
        programmeId = programmeId,
        startMs = startMs,
        endMs = endMs,
        title = title,
        episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
        season = season?.toInt(),
        episode = episode?.toInt(),
        state = when (state) {
            "moved" -> LiveReminderState.Moved
            "gone" -> LiveReminderState.Gone
            else -> LiveReminderState.Set
        },
    )
}

private fun LiveTVGuide.toModel(base: String): LiveGuide = LiveGuide(
    epochMs(start) ?: 0L,
    epochMs(end) ?: 0L,
    LiveGuideState(state.enabled, epochMs(state.updatedAt), state.stale, epochMs(state.listedThrough)),
    channels.filter { it.enabled }.map { channel ->
        LiveGuideRow(
            LiveChannel(channel.id, channel.sourceId, channel.number, channel.name, channel.available && channel.enabled, channel.reason?.takeIf { it.isNotBlank() }, logo = channel.logoUrl?.takeIf { it.isNotBlank() }, favourite = channel.favourite == true),
            channel.listings,
            channel.programmes.mapNotNull { it.toModel() }.sortedBy { it.startMs },
        )
    },
)

/** Null when its times can't be read or don't run forwards, so a bad row never draws. */
internal fun LiveTVProgramme.toModel(): LiveProgramme? {
    val startMs = epochMs(start) ?: return null
    val endMs = epochMs(end)?.takeIf { it > startMs } ?: return null
    return LiveProgramme(
        id = id,
        startMs = startMs,
        endMs = endMs,
        title = title,
        episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
        season = season?.toInt(),
        episode = episode?.toInt(),
        episodeLabel = episodeLabel?.takeIf { it.isNotBlank() },
        airDate = airDate?.takeIf { it.isNotBlank() },
        kind = kind?.takeIf { it.isNotBlank() },
        new = new == true,
        live = live == true,
        repeat = repeat == true,
        hd = hd == true,
        categories = categories.orEmpty().filter { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        descriptionFromTmdb = descriptionSource == "tmdb",
        picture = pictureUrl?.takeIf { it.isNotBlank() },
        pictureFromTmdb = pictureSource == "tmdb",
        still = stillUrl?.takeIf { it.isNotBlank() },
        reminder = reminder == true,
        recording = recording?.let { RecordingMark(it.id, it.state, it.series, it.canCancel == true, it.reason?.takeIf { r -> r.isNotBlank() }) },
        seriesKey = seriesKey?.takeIf { it.isNotBlank() },
    )
}

private fun LiveTVSessionView.toModel(base: String): LiveSession {
    val state = when (state) {
        "starting" -> LiveSessionState.Starting
        "playing" -> LiveSessionState.Playing
        "stopping" -> LiveSessionState.Stopping
        "stopped" -> LiveSessionState.Stopped
        "failed" -> LiveSessionState.Failed
        else -> LiveSessionState.Unknown
    }
    val resolved = url.takeIf { it.isNotBlank() }?.let { RealPlaybackGateway.absolute(base, it).toHttpUrlOrNull()?.toString() }
    val invalidUrl = url.isNotBlank() && (resolved == null || !isOnServer(base, resolved))
    val next = directive?.let { planned ->
        val found = RealPlaybackGateway.absolute(base, planned.url).toHttpUrlOrNull()?.toString()
        LiveOutputDirective(planned.generation, found?.takeIf { isOnServer(base, it) }.orEmpty(), planned.from, planned.to, planned.reason)
    }
    return LiveSession(
        id, channel.toModel(), if (invalidUrl) LiveSessionState.Failed else state,
        StreamQuality.byId(quality)?.forLiveTV(automatic == true) ?: StreamQuality.FullHd,
        resolved?.takeUnless { invalidUrl },
        if (invalidUrl) "The server returned an unsafe Live TV stream address." else reason?.takeIf { it.isNotBlank() },
        paused, pauseRemainingSeconds.seconds(0), bufferSeconds.seconds(60),
        takeoverAtMs = epochMs(takeoverAt), takeoverTitle = takeoverTitle, takeoverRecordingId = takeoverRecordingId, takeoverCanCancel = takeoverCanCancel == true,
        automatic = automatic == true, actualQuality = StreamQuality.byId(actualQuality), outputGeneration = outputGeneration ?: 0,
        directive = next, mode = mode, modeReason = modeReason, switchStatus = switchStatus,
    )
}

/** Defend against optional fields and out-of-range server values before narrowing to Int. */
private fun Long?.seconds(fallback: Int): Int = this?.coerceIn(0L, 86_400L)?.toInt() ?: fallback

/** The shipped server caps UTF-8 bytes, not Kotlin chars; never split a surrogate pair. */
private fun liveClientName(name: String): String {
    val result = StringBuilder()
    var bytes = 0
    val points = name.codePoints().iterator()
    while (points.hasNext()) {
        val point = points.nextInt()
        val width = when {
            point <= 0x7f -> 1
            point <= 0x7ff -> 2
            point <= 0xffff -> 3
            else -> 4
        }
        if (bytes + width > 128) break
        result.appendCodePoint(point)
        bytes += width
    }
    return result.toString()
}
