package media.watchmy.client.model

/**
 * The account a download belongs to: the server and the person signed in
 * there when it was made. Downloads stay with it; signing it out deletes
 * them (PLAN-DOWNLOADS.md §5).
 */
data class OfflineOwner(val serverId: String, val userId: Long, val baseUrl: String, val apiLevel: Int) {
    val key: String get() = "$serverId/$userId"
}

/** A download record as the server keeps it (`OfflineDownload`). */
data class OfflineRecord(
    val id: String,
    val fileId: Long,
    val state: OfflineRecordState,
    val replacedBy: Long?,
    val mode: PlaybackMode,
    val protocol: StreamProtocol,
    /** Absolute, without a token. */
    val url: String,
    val sizeBytes: Long,
    val sizeEstimated: Boolean,
    val quality: DownloadQuality,
    val createdAtMs: Long,
    val item: OfflineItem,
    /** Made downloads: what the offline player needs, with URLs on the offline routes. */
    val decision: PlaybackDecision?,
)

/** A record's state on the server. A value this app doesn't know reads as [Revoked], as the server asks. */
enum class OfflineRecordState { Ready, Replaced, Gone, Revoked, Restart }

/** A made download: the record, and the server's reply as it came, kept on the device to read again offline. */
data class CreatedDownload(val record: OfflineRecord, val json: String)

/** What a device says of a download it holds, in a sync. */
enum class DeviceDownloadState { Queued, Downloading, Downloaded, Removed }

data class SyncedDownload(val id: String, val state: DeviceDownloadState, val sizeBytes: Long? = null)

/** A position (with [durationMs]) or a mark ([watched]) made offline, with when it happened by this device's clock. */
data class OfflineProgress(
    val fileId: Long,
    val downloadId: String?,
    val atMs: Long,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val watched: Boolean? = null,
)

enum class SyncOutcome { Applied, Newer, Unavailable }

/** What became of one progress entry, with the item's watch state now (null when it has none). */
data class SyncResult(val outcome: SyncOutcome, val watch: WatchProgress? = null)

/** POST /api/offline/sync's reply: whether this person may download now, every download of this device, and a result per progress entry, in order. */
data class SyncReply(val offline: Boolean, val downloads: List<OfflineRecord>, val results: List<SyncResult>)

/**
 * The offline routes' calls (PLAN-DOWNLOADS.md §9) and the sign-in they are
 * made with. core:data implements it; core:downloads uses it. Calls for an
 * [OfflineOwner] go to its server as it, whichever server is current, and
 * throw [SignedOutException] once it is signed out there (a 401 signs it
 * out); the rest throw [ApiException].
 */
interface OfflineGateway {
    /** The server and account in use now; null when signed out. */
    fun owner(): OfflineOwner?

    /** Whether [owner] is still signed in on its server. */
    fun signedIn(owner: OfflineOwner): Boolean

    /** Called whenever the sign-in in use changes: another server or account, or signed out. */
    fun onSessionChange(listener: () -> Unit)

    /** Runs [hook] before a sign-out ends the session, while the server still takes it. */
    fun beforeSignOut(hook: suspend () -> Unit)

    /** Whether [owner] may download now ([OFFLINE_API_LEVEL] and RequestOptions.offline). */
    suspend fun allowed(owner: OfflineOwner): Boolean

    suspend fun plan(owner: OfflineOwner, fileId: Long, profile: PlaybackProfile): DownloadPlan

    suspend fun create(owner: OfflineOwner, choice: DownloadChoice, profile: PlaybackProfile, client: ClientInfo): CreatedDownload

    /** Optional artwork fetched with this download's bound account, never a media/cast token. */
    suspend fun artwork(owner: OfflineOwner, path: String, size: ImageSize): ByteArray? = null

    /** Reads a record kept from [create] again. */
    fun parse(owner: OfflineOwner, json: String): OfflineRecord

    /** An `o1.` token for the download's media routes (createOfflineToken). */
    suspend fun token(owner: OfflineOwner, downloadId: String): MediaToken

    suspend fun sync(owner: OfflineOwner, client: ClientInfo, sentAtMs: Long, downloads: List<SyncedDownload>, progress: List<OfflineProgress>): SyncReply
}
