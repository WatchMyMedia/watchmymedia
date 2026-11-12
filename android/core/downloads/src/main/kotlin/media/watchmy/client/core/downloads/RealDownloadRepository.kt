package media.watchmy.client.core.downloads

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DeviceDownloadState
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadPlan
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.DownloadSettings
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.DownloadStorage
import media.watchmy.client.model.OFFLINE_API_LEVEL
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OfflineGateway
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.OfflineProgress
import media.watchmy.client.model.OfflineRecord
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SyncOutcome
import media.watchmy.client.model.SyncedDownload
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.downloadTitle
import media.watchmy.client.model.neededSize

/** What a download's end says to the person: notifications, on the real device. */
internal interface DownloadNotices {
    fun downloaded(title: String)

    fun failed(title: String)

    /** An admin revoked it, or the person may no longer download: it was deleted. */
    fun removedByAdmin(title: String)
}

/**
 * Downloads on this device (PLAN-DOWNLOADS.md §5, §6, §10): records and
 * progress in [store], bytes in the [engine], the server through [gateway].
 *
 * - Downloads belong to the account that made them; one no longer signed
 *   in (signed out, removed, or refused with 401) has its downloads deleted.
 * - A sync sends what this device holds and the progress made on it, and
 *   applies [reconcile]'s rule to what comes back.
 * - Progress on a downloaded item goes to the queue, never to the server's
 *   progress route; it is synced by when it happened.
 *
 * Everything runs on [scope]'s thread (the main thread), as Media3 asks.
 */
internal class RealDownloadRepository(
    private val store: OfflineStore,
    private val gateway: OfflineGateway,
    private val engine: DownloadEngine,
    private val client: () -> ClientInfo,
    private val profile: suspend () -> PlaybackProfile,
    private val space: () -> Pair<Long, Long>,
    private val notices: DownloadNotices,
    /** Syncs when a network is back (WorkManager). */
    private val syncLater: () -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val artwork: OfflineArtwork? = null,
) : DownloadRepository, FetchEvents, OfflinePlays {
    private val owner = MutableStateFlow(gateway.owner())
    private val preparing = MutableStateFlow<Set<String>>(emptySet())
    private val engineChanges = MutableStateFlow(0L)
    private val parsed = ConcurrentHashMap<String, Pair<String, OfflineRecord>>()
    private val plans = HashMap<Long, DownloadPlan>()
    private val syncs = Mutex()
    private var ticker: Job? = null

    override val downloads: StateFlow<List<OfflineDownload>> =
        combine(store.state, owner, preparing, engineChanges) { s, o, p, _ -> held(s, o, p) }
            .stateIn(scope, SharingStarted.Eagerly, held(store.value, owner.value, emptySet()))

    override val canDownload: StateFlow<Boolean> = combine(store.state, owner) { s, o ->
        o != null && o.apiLevel >= OFFLINE_API_LEVEL && s.allowed[o.key] == true
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override val enabled: StateFlow<Boolean> = combine(canDownload, downloads) { can, held -> can || held.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val settings: StateFlow<DownloadSettings> = store.state.map { DownloadSettings(DownloadQuality.byId(it.quality), it.wifiOnly) }
        .stateIn(scope, SharingStarted.Eagerly, DownloadSettings(DownloadQuality.byId(store.value.quality), store.value.wifiOnly))

    init {
        engine.setWifiOnly(store.value.wifiOnly)
        engine.listen(::onEngineChange)
        gateway.onSessionChange { scope.launch { onSessionChange() } }
        gateway.beforeSignOut { reportAllRemoved() }
        scope.launch { onSessionChange() }
    }

    // --- the account ---

    /** Another account or server, or signed out: deletes what belongs to accounts no longer signed in, then checks what this one may do. */
    private suspend fun onSessionChange() {
        owner.value = gateway.owner()
        prune()
        store.value.records.filter { gateway.signedIn(it.owner) }.forEach(::keepArtwork)
        refreshAllowed()
    }

    /** Deletes the downloads, progress and pending reports of every account no longer signed in. */
    private fun prune() {
        val s = store.value
        val gone = s.records.filter { !gateway.signedIn(it.owner) }
        if (gone.isEmpty() && s.progress.all { p -> signedInKey(p.ownerKey) } && s.removed.all { signedInKey(it.ownerKey) }) return
        gone.forEach { removeBytes(it) }
        val goneIds = gone.map { it.id }.toSet()
        store.update { st ->
            st.copy(
                records = st.records.filterNot { it.id in goneIds },
                progress = st.progress.filter { signedInKey(it.ownerKey) },
                removed = st.removed.filter { signedInKey(it.ownerKey) },
                allowed = st.allowed.filterKeys { signedInKey(it) },
            )
        }
    }

    private fun signedInKey(key: String): Boolean {
        val serverId = key.substringBeforeLast('/')
        val userId = key.substringAfterLast('/').toLongOrNull() ?: return false
        return gateway.signedIn(OfflineOwner(serverId, userId, "", 0))
    }

    private suspend fun refreshAllowed() {
        val o = owner.value ?: return
        try {
            val allowed = gateway.allowed(o)
            store.update { it.copy(allowed = it.allowed + (o.key to allowed)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, or the server is away: what was last heard stands.
            Log.d(TAG, "could not check whether downloads are allowed", e)
        }
    }

    // --- what the screens see ---

    private fun held(s: StoredState, o: OfflineOwner?, preparing: Set<String>): List<OfflineDownload> {
        if (o == null) return emptyList()
        val engineDownloads = engine.downloads()
        val notMet = engine.notMet()
        return s.records.filter { it.ownerKey == o.key }.mapNotNull { rec ->
            val record = parse(rec) ?: return@mapNotNull null
            val e = engineDownloads[rec.id]
            val status = when {
                e != null -> statusOf(e, notMet, s.wifiOnly, rec.id in preparing)
                rec.completedAtMs != null -> DownloadStatus.Downloaded
                // Just added: the manager takes it on its own thread.
                now() - rec.createdAtMs < ADDING_MS -> DownloadStatus.Waiting(DownloadStatus.Reason.Turn)
                else -> DownloadStatus.Failed
            }
            val done = status == DownloadStatus.Downloaded
            OfflineDownload(
                id = rec.id,
                fileId = rec.fileId,
                item = record.item,
                quality = record.quality,
                mode = record.mode,
                protocol = record.protocol,
                sizeBytes = if (done && e != null && e.bytes > 0) e.bytes else record.sizeBytes,
                sizeEstimated = !done && record.sizeEstimated,
                status = status,
                mark = rec.downloadMark,
                replacedBy = rec.replacedBy,
                createdAtMs = rec.createdAtMs,
                completedAtMs = rec.completedAtMs,
                durationMs = record.decision?.durationMs ?: 0,
                watch = watchOf(rec, record, s.progress),
            )
        }.sortedByDescending { it.createdAtMs }
    }

    /** Where the viewer is: the newest thing done here, else what the server last said. */
    private fun watchOf(rec: StoredRecord, record: OfflineRecord, progress: List<StoredProgress>): WatchProgress? {
        val duration = record.decision?.durationMs ?: 0
        val latest = progress.filter { it.ownerKey == rec.ownerKey && it.fileId == rec.fileId }.maxByOrNull { it.atMs }
        if (latest != null) {
            return when {
                latest.watched == true -> WatchProgress(0, duration, true)
                latest.watched == false -> null
                else -> {
                    val pos = latest.positionMs ?: 0
                    val dur = latest.durationMs?.takeIf { it > 0 } ?: duration
                    WatchProgress(pos, dur, watchedPast(pos, dur, record))
                }
            }
        }
        rec.watchPositionMs?.let { return WatchProgress(it, rec.watchDurationMs ?: duration, rec.watchCompleted) }
        if (rec.watchCompleted) return WatchProgress(0, duration, true)
        return record.decision?.resumeMs?.takeIf { it > 0 }?.let { WatchProgress(it, duration, false) }
    }

    private fun parse(rec: StoredRecord): OfflineRecord? {
        parsed[rec.id]?.let { (json, r) -> if (json === rec.json) return r }
        return try {
            gateway.parse(rec.owner, rec.json).also { parsed[rec.id] = rec.json to it }
        } catch (e: Exception) {
            Log.w(TAG, "could not read download ${rec.id}", e)
            null
        }
    }

    // --- the engine ---

    private fun onEngineChange(changed: EngineDownload?, failure: Exception?) {
        engineChanges.value = engineChanges.value + 1
        if (changed != null && !isPart(changed.id)) {
            val rec = store.record(changed.id)
            when {
                rec == null -> Unit
                changed.state == EngineDownload.State.Completed && rec.completedAtMs == null -> completed(rec)
                changed.state == EngineDownload.State.Failed && failure != null -> {
                    preparing.value -= changed.id
                    // A refusal is the sync's to explain (deleted or marked); anything else is said here.
                    if (failure !is OfflineRefusedException) parse(rec)?.let { notices.failed(downloadTitle(it.item)) }
                }
            }
        }
        val running = engine.downloads().values.any { it.state == EngineDownload.State.Downloading }
        if (running && ticker?.isActive != true) {
            ticker = scope.launch {
                // Media3 has no progress callback: look again each second while something downloads.
                while (isActive && engine.downloads().values.any { it.state == EngineDownload.State.Downloading }) {
                    delay(TICK_MS)
                    engineChanges.value = engineChanges.value + 1
                }
            }
        }
    }

    private fun completed(rec: StoredRecord) {
        store.updateRecord(rec.id) { it.copy(completedAtMs = now()) }
        parse(rec)?.let { notices.downloaded(downloadTitle(it.item)) }
        // A new version done: the old copy goes.
        rec.replaces?.let { old -> store.record(old)?.let { removeRecord(it, report = true) } }
        scope.launch { sync() }
    }

    override fun preparing(id: String) {
        if (id !in preparing.value) scope.launch { preparing.value += id }
    }

    override fun fetching(id: String) {
        if (id in preparing.value) scope.launch { preparing.value -= id }
    }

    /** 403 or 410 (or signed out): the next sync deletes or marks it. */
    override fun refused(id: String, status: Int) {
        scope.launch {
            preparing.value -= id
            sync()
        }
    }

    override fun restart(id: String) {
        scope.launch { restartDownload(id) }
    }

    private fun restartDownload(id: String) {
        val rec = store.record(id) ?: return
        val record = parse(rec) ?: return
        Log.i(TAG, "download $id starts over: the server's session changed")
        engine.remove(id)
        rec.subtitleParts.forEach(engine::remove)
        store.updateRecord(id) { it.copy(completedAtMs = null) }
        enqueue(record, rec.json)
    }

    private fun enqueue(record: OfflineRecord, json: String): List<String> {
        val hls = record.protocol == StreamProtocol.Hls
        engine.add(EngineRequest(record.id, record.url, hls, cacheKey = record.url.takeIf { !hls }, data = json.encodeToByteArray()))
        // An original file plays its text subtitles from beside it: each comes as a file of its own.
        if (hls) return emptyList()
        return record.decision?.subtitles.orEmpty().mapNotNull { sub ->
            val url = sub.url ?: return@mapNotNull null
            partId(record.id, sub.option.id).also { engine.add(EngineRequest(it, url, false, cacheKey = url)) }
        }
    }

    private fun removeBytes(rec: StoredRecord) {
        artwork?.remove(rec.owner, rec.id)
        engine.remove(rec.id)
        rec.subtitleParts.forEach(engine::remove)
        parsed.remove(rec.id)
    }

    /** Deletes the copy; [report] tells the server at the next sync. */
    private fun removeRecord(rec: StoredRecord, report: Boolean) {
        removeBytes(rec)
        preparing.value -= rec.id
        store.update { s ->
            s.copy(
                records = s.records.filterNot { it.id == rec.id },
                removed = if (report) s.removed + StoredRemoval(rec.id, rec.ownerKey) else s.removed,
            )
        }
    }

    // --- what the screens do ---

    override suspend fun plan(fileId: Long): DownloadPlan {
        val o = owner.value ?: throw SignedOutException()
        return gateway.plan(o, fileId, profile()).also { plans[fileId] = it }
    }

    override suspend fun download(choice: DownloadChoice) {
        val o = owner.value ?: throw SignedOutException()
        plans[choice.fileId]?.option(choice.quality)?.let { option ->
            val free = storage()
            if (!free.fits(option.sizeBytes)) throw ApiException(null, "Needs ${neededSize(option.sizeBytes)} free. Pick a lower quality or free up space.")
        }
        val created = gateway.create(o, choice, profile(), client())
        val record = created.record
        // Burn-in can turn an Original into a larger prepared copy. Check the
        // server's resulting size before queuing bytes, rather than trusting
        // the unmodified file's plan estimate.
        if (!storage().fits(record.sizeBytes)) {
            store.update { s -> s.copy(removed = (s.removed + StoredRemoval(record.id, o.key)).distinctBy { it.ownerKey to it.id }) }
            syncLater()
            scope.launch { sync() }
            throw ApiException(null, "The prepared download needs ${neededSize(record.sizeBytes)} free. Pick a lower quality or free up space.")
        }
        // The server replaced this device's earlier download of the file; so does this.
        store.value.records.filter { it.ownerKey == o.key && it.fileId == record.fileId && it.id != record.id }.forEach { removeRecord(it, report = false) }
        val replaces = store.value.records.firstOrNull { old ->
            old.ownerKey == o.key && old.replacedBy == record.fileId
        }?.id
        val parts = enqueue(record, created.json)
        store.update { s ->
            s.copy(
                records = s.records + StoredRecord(
                    id = record.id, serverId = o.serverId, userId = o.userId, baseUrl = o.baseUrl, apiLevel = o.apiLevel,
                    json = created.json, createdAtMs = now(), fileId = record.fileId, replaces = replaces, subtitleParts = parts,
                ),
            )
        }
        store.record(record.id)?.let(::keepArtwork)
    }

    private fun keepArtwork(rec: StoredRecord) {
        val cache = artwork ?: return
        val item = parse(rec)?.item ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { cache.keep(rec.owner, rec.id, item, { path, size -> gateway.artwork(rec.owner, path, size) }) { gateway.signedIn(rec.owner) && store.record(rec.id) != null } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Optional artwork never turns a usable media download into a failure; retried at the next start.
            }
        }
    }

    override fun localArtwork(path: String?): String? {
        val p = path ?: return null
        val o = owner.value ?: return null
        return store.value.records.asSequence().filter { it.ownerKey == o.key && it.baseUrl == o.baseUrl }.firstNotNullOfOrNull { rec ->
            val item = parse(rec)?.item
            if (p == item?.posterPath || p == item?.artPath) artwork?.local(o, rec.id, p) else null
        }
    }

    override fun pause(id: String) = engine.setPaused(id, true)

    override fun resume(id: String) = engine.setPaused(id, false)

    override fun retry(id: String) {
        val rec = store.record(id) ?: return
        val record = parse(rec) ?: return
        enqueue(record, rec.json)
    }

    override fun remove(id: String) {
        val rec = store.record(id) ?: return
        removeRecord(rec, report = true)
        scope.launch { sync() }
    }

    override fun removeAll() {
        val o = owner.value ?: return
        store.value.records.filter { it.ownerKey == o.key }.forEach { removeRecord(it, report = true) }
        scope.launch { sync() }
    }

    override fun storage(): DownloadStorage {
        val (free, total) = space()
        return DownloadStorage(engine.usedBytes(), free, total)
    }

    override fun setQuality(quality: DownloadQuality) {
        store.update { it.copy(quality = quality.id) }
    }

    override fun setWifiOnly(wifiOnly: Boolean) {
        store.update { it.copy(wifiOnly = wifiOnly) }
        engine.setWifiOnly(wifiOnly)
    }

    override fun mark(fileId: Long, watched: Boolean) {
        val o = owner.value ?: return
        val rec = store.value.records.firstOrNull { it.ownerKey == o.key && it.fileId == fileId } ?: return
        store.addProgress { seq -> StoredProgress(seq, rec.ownerKey, fileId, rec.id, now(), watched = watched) }
        scope.launch { sync() }
    }

    // --- offline plays (OfflinePlaybackGateway) ---

    /** The current account's finished download of [fileId], to play from the device. */
    fun playable(fileId: Long): OfflineDownload? = downloads.value.firstOrNull { it.fileId == fileId && it.downloaded }

    fun record(id: String): OfflineRecord? = store.record(id)?.let(::parse)

    /** The subtitle parts of [id] that finished downloading. */
    fun downloadedParts(id: String): Set<String> {
        val all = engine.downloads()
        return store.record(id)?.subtitleParts.orEmpty().filter { all[it]?.state == EngineDownload.State.Completed }.toSet()
    }

    override fun offlinePlay(fileId: Long): OfflinePlay? {
        val d = playable(fileId) ?: return null
        val stored = record(d.id)?.decision ?: return null
        val next = stored.next?.fileId?.let { playable(it) } != null
        return OfflinePlay(d, OfflinePlaybackGateway.offlineDecision(d, stored, next, downloadedParts(d.id)) { partId(d.id, it) })
    }

    /** A heartbeat of an offline play: the newest position goes to the queue, and a play that ends syncs. */
    override fun progress(play: OfflinePlay, report: ProgressReport) {
        val rec = store.record(play.download.id) ?: return
        store.addProgress { seq ->
            StoredProgress(seq, rec.ownerKey, report.fileId, rec.id, now(), positionMs = report.positionMs.coerceAtLeast(0), durationMs = report.durationMs)
        }
        if (OfflinePlaybackGateway.ended(report.state)) scope.launch { sync() }
    }

    // --- sync ---

    /**
     * Syncs every account that has downloads or reports here and is still
     * signed in. Offline, or with the server away, it tries again when a
     * network is back ([syncLater]).
     */
    override suspend fun sync() = syncs.withLock {
        val s = store.value
        val keys = (s.records.map { it.ownerKey } + s.removed.map { it.ownerKey } + s.progress.map { it.ownerKey }).toSet()
        for (key in keys) {
            val o = s.records.firstOrNull { it.ownerKey == key }?.owner ?: ownerOf(key) ?: continue
            if (gateway.signedIn(o)) syncOwner(o, again = true)
        }
    }

    /** An account by its key, for reports left after its last download went. */
    private fun ownerOf(key: String): OfflineOwner? {
        val userId = key.substringAfterLast('/').toLongOrNull() ?: return null
        return OfflineOwner(key.substringBeforeLast('/'), userId, owner.value?.takeIf { it.key == key }?.baseUrl.orEmpty(), 0)
    }

    private suspend fun syncOwner(o: OfflineOwner, again: Boolean) {
        val s = store.value
        val held = s.records.filter { it.ownerKey == o.key }
        val removals = s.removed.filter { it.ownerKey == o.key }
        // A download whose file the server deleted syncs no progress (PLAN-DOWNLOADS.md §5).
        val gone = held.filter { it.mark == StoredRecord.MARK_GONE }.map { it.id }.toSet()
        val entries = s.progress.filter { it.ownerKey == o.key }
        val sent = entries.filter { it.downloadId !in gone }
        val engineDownloads = engine.downloads()
        val reports = held.map { rec ->
            val e = engineDownloads[rec.id]
            val state = when {
                rec.completedAtMs != null || e?.state == EngineDownload.State.Completed -> DeviceDownloadState.Downloaded
                e?.state == EngineDownload.State.Downloading -> DeviceDownloadState.Downloading
                else -> DeviceDownloadState.Queued
            }
            SyncedDownload(rec.id, state, e?.bytes?.takeIf { state == DeviceDownloadState.Downloaded })
        } + removals.map { SyncedDownload(it.id, DeviceDownloadState.Removed) }
        val finished = held.associate { it.id to (it.completedAtMs != null) }
        val reply = try {
            gateway.sync(
                o, client(), now(), reports,
                sent.map { OfflineProgress(it.fileId, it.downloadId, it.atMs, it.positionMs, it.durationMs, it.watched) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignedOutException) {
            // Signed out on that server (the session was revoked): its downloads go.
            prune()
            return
        } catch (e: ApiException) {
            Log.i(TAG, "could not sync downloads: ${e.message}")
            syncLater()
            return
        }
        val sentSeqs = entries.map { it.seq }.toSet()
        // The item's state now, as the server answered each entry: what the copy resumes from once the queue is empty.
        val watch = HashMap<Long, WatchProgress?>()
        sent.forEachIndexed { i, entry ->
            reply.results.getOrNull(i)?.takeIf { it.outcome != SyncOutcome.Unavailable }?.let { watch[entry.fileId] = it.watch }
        }
        val removedIds = removals.map { it.id }.toSet()
        store.update { st ->
            st.copy(
                // Every outcome is final: the entries sent go, and so do a gone download's.
                progress = st.progress.filterNot { it.seq in sentSeqs },
                removed = st.removed.filterNot { it.ownerKey == o.key && it.id in removedIds },
                allowed = st.allowed + (o.key to reply.offline),
                records = st.records.map { rec ->
                    if (rec.ownerKey != o.key || rec.fileId !in watch) return@map rec
                    val w = watch[rec.fileId]
                    rec.copy(watchPositionMs = w?.positionMs ?: 0, watchDurationMs = w?.durationMs, watchCompleted = w?.completed == true)
                },
            )
        }
        var removedHere = false
        for (action in reconcile(finished, reply)) {
            val rec = store.record(action.id) ?: continue
            when (action) {
                is SyncAction.Delete -> {
                    if (action.notice) parse(rec)?.let { notices.removedByAdmin(downloadTitle(it.item)) }
                    // A revoked record goes once this device says its copy is gone.
                    removeRecord(rec, report = action.notice)
                    removedHere = removedHere || action.notice
                }
                is SyncAction.Mark -> if (rec.mark != action.mark || rec.replacedBy != action.replacedBy) {
                    store.updateRecord(rec.id) { it.copy(mark = action.mark, replacedBy = action.replacedBy) }
                }
                is SyncAction.Restart -> restartDownload(rec.id)
            }
        }
        // Say at once that the revoked copies are gone, so the server can forget them.
        if (removedHere && again) syncOwner(o, again = false)
    }

    /** Before signing out: the progress made here and every copy, reported removed, while the session stands. */
    private suspend fun reportAllRemoved() {
        val o = owner.value ?: return
        val held = store.value.records.filter { it.ownerKey == o.key }
        if (held.isEmpty() && store.value.progress.none { it.ownerKey == o.key }) return
        val entries = store.value.progress.filter { it.ownerKey == o.key && it.downloadId !in held.filter { r -> r.mark == StoredRecord.MARK_GONE }.map { r -> r.id } }
        try {
            gateway.sync(
                o, client(), now(),
                held.map { SyncedDownload(it.id, DeviceDownloadState.Removed) } + store.value.removed.filter { it.ownerKey == o.key }.map { SyncedDownload(it.id, DeviceDownloadState.Removed) },
                entries.map { OfflineProgress(it.fileId, it.downloadId, it.atMs, it.positionMs, it.durationMs, it.watched) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "could not report downloads before signing out", e)
        }
    }

    /** Past the credits (or 92% without them) counts as watched, as on the server. */
    private fun watchedPast(positionMs: Long, durationMs: Long, record: OfflineRecord): Boolean {
        val at = record.decision?.watchedAtMs?.takeIf { it > 0 } ?: (durationMs * 0.92).toLong()
        return durationMs > 0 && positionMs >= at
    }

    private companion object {
        const val TAG = "WmmDownloads"
        const val TICK_MS = 1_000L
        const val ADDING_MS = 30_000L
    }
}
