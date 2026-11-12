package media.watchmy.client.core.data

import java.time.Instant
import java.io.ByteArrayOutputStream
import media.watchmy.client.model.ImageSize
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.watchmy.client.core.api.apis.OfflineApi
import media.watchmy.client.core.api.apis.RequestsApi
import media.watchmy.client.core.api.infrastructure.Serializer
import media.watchmy.client.core.api.models.Client
import media.watchmy.client.core.api.models.CreateOfflineInputBody
import media.watchmy.client.core.api.models.OfflinePlanInputBody
import media.watchmy.client.core.api.models.OfflineSyncDownload
import media.watchmy.client.core.api.models.OfflineSyncInputBody
import media.watchmy.client.core.api.models.OfflineSyncProgress
import media.watchmy.client.core.data.RealPlaybackGateway.Companion.absolute
import media.watchmy.client.core.data.RealPlaybackGateway.Companion.toApi
import media.watchmy.client.core.data.RealPlaybackGateway.Companion.toModel
import media.watchmy.client.model.AUDIO_LANGUAGES_API_LEVEL
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.CreatedDownload
import media.watchmy.client.model.DeviceDownloadState
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadOption
import media.watchmy.client.model.DownloadPlan
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OFFLINE_API_LEVEL
import media.watchmy.client.model.OfflineGateway
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.OfflineOwner
import media.watchmy.client.model.OfflineProgress
import media.watchmy.client.model.OfflineRecord
import media.watchmy.client.model.OfflineRecordState
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SyncOutcome
import media.watchmy.client.model.SyncReply
import media.watchmy.client.model.SyncResult
import media.watchmy.client.model.SyncedDownload
import media.watchmy.client.core.api.models.OfflineDownload as ApiOfflineDownload
import media.watchmy.client.core.api.models.OfflineItem as ApiOfflineItem

/**
 * The offline routes (PLAN-DOWNLOADS.md §9) for core:downloads. Each call
 * goes to its owner's server as that account ([Http.bindTo]), so a download
 * keeps its tokens and sync while another server is in use; once the
 * account is signed out there, calls throw [SignedOutException], and a 401
 * signs it out.
 */
internal class RealOfflineGateway(
    private val store: LocalStore,
    private val http: Http,
    private val auth: RealAuthRepository,
) : OfflineGateway {
    override fun owner(): OfflineOwner? {
        val session = store.session() ?: return null
        val account = session.account ?: return null
        return OfflineOwner(session.server.id, account.userId, session.server.baseUrl, session.server.apiLevel)
    }

    override fun signedIn(owner: OfflineOwner): Boolean =
        store.value.servers.firstOrNull { it.id == owner.serverId }?.account?.userId == owner.userId

    override fun onSessionChange(listener: () -> Unit) = store.onSessionChange { listener() }

    override fun beforeSignOut(hook: suspend () -> Unit) = auth.beforeSignOut(hook)

    private suspend fun <T> call(owner: OfflineOwner, block: suspend (OfflineApi) -> T): T {
        val access = http.bindTo(owner.serverId, owner.userId) ?: throw SignedOutException()
        return access.call { base, client -> block(OfflineApi(base, client)) }
    }

    private fun level(owner: OfflineOwner): Int =
        store.value.servers.firstOrNull { it.id == owner.serverId }?.apiLevel ?: owner.apiLevel

    /** Older servers, and servers that turned requests off, offer no downloads. */
    override suspend fun allowed(owner: OfflineOwner): Boolean {
        if (level(owner) < OFFLINE_API_LEVEL) return false
        val access = http.bindTo(owner.serverId, owner.userId) ?: throw SignedOutException()
        return try {
            access.call { base, client -> RequestsApi(base, client).getRequestOptions() }.offline == true
        } catch (e: ApiException) {
            if (e.unknownRoute() || e.status == 503) false else throw e
        }
    }

    override suspend fun plan(owner: OfflineOwner, fileId: Long, profile: PlaybackProfile): DownloadPlan {
        val body = OfflinePlanInputBody(profile = profile.forApiLevel(level(owner)).toApi())
        val plan = call(owner) { it.planOfflineDownload(fileId, body) }
        return DownloadPlan(
            fileId = plan.fileId,
            durationMs = plan.durationMs,
            options = plan.qualities.map { q ->
                DownloadOption(
                    quality = DownloadQuality.byId(q.quality),
                    mode = mode(q.mode),
                    protocol = protocol(q.protocol),
                    sizeBytes = q.sizeBytes.coerceAtLeast(0),
                    estimated = q.sizeEstimated,
                    audioConverted = !q.audio.copy,
                    // Before audio languages, always the file's default: the sheet works that out itself.
                    audioIndex = q.audio.index.toInt().takeIf { it >= 0 && level(owner) >= AUDIO_LANGUAGES_API_LEVEL },
                )
            },
            audio = plan.audioTracks.map { it.toModel() },
            subtitles = plan.subtitles.map { it.toOption() },
        )
    }

    override suspend fun create(owner: OfflineOwner, choice: DownloadChoice, profile: PlaybackProfile, client: ClientInfo): CreatedDownload {
        val body = CreateOfflineInputBody(
            client = client.toApi(),
            profile = profile.forApiLevel(level(owner)).toApi(),
            audioIndex = choice.audioIndex?.toLong(),
            burnIn = choice.burnInSubtitleId?.let { true },
            quality = CreateOfflineInputBody.Quality.entries.firstOrNull { it.value == choice.quality.id } ?: CreateOfflineInputBody.Quality.original,
            subtitleId = choice.burnInSubtitleId,
        )
        val reply = call(owner) { it.createOfflineDownload(choice.fileId, body) }
        return CreatedDownload(reply.toModel(owner.baseUrl), Serializer.kotlinxSerializationJson.encodeToString(ApiOfflineDownload.serializer(), reply))
    }

    override suspend fun artwork(owner: OfflineOwner, path: String, size: ImageSize): ByteArray? {
        if (!Regex("""^/?[A-Za-z0-9_-]+\.(jpg|png|webp)$""", RegexOption.IGNORE_CASE).matches(path)) return null
        val access = http.bindTo(owner.serverId, owner.userId) ?: throw SignedOutException()
        return access.call { base, client ->
            if (base != owner.baseUrl) throw SignedOutException()
            withContext(Dispatchers.IO) {
                val url = "$base/api/images/${size.path}/${path.trimStart('/')}"
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful || response.header("Content-Type")?.substringBefore(';') !in setOf("image/jpeg", "image/png", "image/webp")) return@use null
                    val limit = 8 * 1024 * 1024
                    if (response.body.contentLength() > limit) return@use null
                    response.body.byteStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > limit) return@use null
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }
            }
        }
    }

    override fun parse(owner: OfflineOwner, json: String): OfflineRecord =
        Serializer.kotlinxSerializationJson.decodeFromString(ApiOfflineDownload.serializer(), json).toModel(owner.baseUrl)

    override suspend fun token(owner: OfflineOwner, downloadId: String): MediaToken {
        val t = call(owner) { it.createOfflineToken(downloadId) }
        return MediaToken(t.token, epochMs(t.expiresAt) ?: (System.currentTimeMillis() + 60 * 60_000L), t.`param`.ifBlank { "access_token" }, serverUrl = owner.baseUrl)
    }

    override suspend fun sync(owner: OfflineOwner, client: ClientInfo, sentAtMs: Long, downloads: List<SyncedDownload>, progress: List<OfflineProgress>): SyncReply {
        val body = OfflineSyncInputBody(
            client = client.toApi(),
            sentAt = Instant.ofEpochMilli(sentAtMs).toString(),
            downloads = downloads.map { d ->
                OfflineSyncDownload(
                    deviceState = when (d.state) {
                        DeviceDownloadState.Queued -> OfflineSyncDownload.DeviceState.queued
                        DeviceDownloadState.Downloading -> OfflineSyncDownload.DeviceState.downloading
                        DeviceDownloadState.Downloaded -> OfflineSyncDownload.DeviceState.downloaded
                        DeviceDownloadState.Removed -> OfflineSyncDownload.DeviceState.removed
                    },
                    id = d.id,
                    sizeBytes = d.sizeBytes?.takeIf { it > 0 },
                )
            }.takeIf { it.isNotEmpty() },
            progress = progress.map { p ->
                OfflineSyncProgress(
                    at = Instant.ofEpochMilli(p.atMs).toString(),
                    fileId = p.fileId,
                    downloadId = p.downloadId,
                    durationMs = p.durationMs?.takeIf { it > 0 },
                    positionMs = p.positionMs?.coerceAtLeast(0),
                    watched = p.watched,
                )
            }.takeIf { it.isNotEmpty() },
        )
        val reply = call(owner) { it.syncOffline(body) }
        return SyncReply(
            offline = reply.offline,
            downloads = reply.downloads.map { it.toModel(owner.baseUrl) },
            results = reply.progress.map {
                val outcome = when (it.outcome) {
                    "applied" -> SyncOutcome.Applied
                    "newer" -> SyncOutcome.Newer
                    else -> SyncOutcome.Unavailable
                }
                SyncResult(outcome, it.watchState?.toModel())
            },
        )
    }

    private fun ClientInfo.toApi() = Client(
        deviceId = deviceId.take(64), name = name.take(64), product = product.take(64), platform = platform.take(32), version = version.take(32),
        model = model.take(64).ifBlank { null },
    )

    companion object {
        fun mode(value: String) = when (value) {
            "direct" -> PlaybackMode.Direct
            "remux" -> PlaybackMode.Remux
            "audio" -> PlaybackMode.AudioTranscode
            else -> PlaybackMode.Transcode
        }

        fun protocol(value: String) = if (value == "file") StreamProtocol.File else StreamProtocol.Hls

        /** A state this app doesn't know reads as revoked, as the server asks. */
        fun state(value: String) = when (value) {
            "ready" -> OfflineRecordState.Ready
            "replaced" -> OfflineRecordState.Replaced
            "gone" -> OfflineRecordState.Gone
            "restart" -> OfflineRecordState.Restart
            else -> OfflineRecordState.Revoked
        }

        fun ApiOfflineDownload.toModel(base: String) = OfflineRecord(
            id = id,
            fileId = fileId,
            state = state(state),
            replacedBy = replacedBy?.takeIf { it > 0 },
            mode = mode(mode),
            protocol = protocol(protocol),
            url = absolute(base, url),
            sizeBytes = sizeBytes.coerceAtLeast(0),
            sizeEstimated = sizeEstimated,
            quality = DownloadQuality.byId(quality),
            createdAtMs = epochMs(createdAt) ?: 0,
            item = item.toModel(),
            // The offline routes play from the start; a decision's play and session are not this download's.
            // A copy plays from the device, so its stored decision carries no seek thumbnails (they live on
            // the server, under URLs that change with the file's version).
            decision = decision?.toModel(base, apiLevel = 0),
        )

        fun ApiOfflineItem.toModel() = OfflineItem(
            kind = when (type) {
                "movie" -> OfflineItem.Kind.Movie
                "episode" -> OfflineItem.Kind.Episode
                else -> OfflineItem.Kind.File
            },
            title = title,
            movieId = movieId,
            showId = showId,
            episodeId = episodeId,
            showTitle = showTitle?.takeIf { it.isNotBlank() },
            season = seasonNumber?.toInt(),
            episode = episodeNumber?.toInt(),
            year = year?.toInt()?.takeIf { it > 0 },
            posterPath = posterPath?.takeIf { it.isNotBlank() },
            artPath = artPath?.takeIf { it.isNotBlank() },
        )
    }
}
