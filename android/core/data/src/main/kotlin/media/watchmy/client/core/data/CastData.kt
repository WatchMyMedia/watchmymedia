package media.watchmy.client.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.apis.LiveTvApi
import media.watchmy.client.core.api.apis.SystemApi
import media.watchmy.client.core.api.apis.UsersApi
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastServer
import media.watchmy.client.model.CastToken
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleSize
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.URLEncoder

/** How a gateway reaches the server: its base URL, and an HTTP client that signs the request in. */
internal interface ServerAccess {
    suspend fun <T> call(block: suspend (baseUrl: String, client: OkHttpClient) -> T): T
    fun bind(): ServerAccess = this

    /** The server's API level as last read, which decides what a request may carry. */
    fun apiLevel(): Int
}

/** As this device's signed-in user, on the server in use. */
internal class SignedInAccess(private val http: Http) : ServerAccess {
    override suspend fun <T> call(block: suspend (baseUrl: String, client: OkHttpClient) -> T): T =
        http.signedIn(block)

    override fun bind(): ServerAccess = http.bind()

    override fun apiLevel(): Int = http.apiLevel()
}

/**
 * As the person who cast to this TV (Cast Connect), with their cast token at
 * [baseUrl]. [renew] swaps the token for a fresh one; the receiver does that
 * at every play and before it runs out (PLAN-CAST.md §3.1).
 */
internal class CastTokenAccess(
    val baseUrl: String,
    private val http: Http,
    token: String,
    expiresAtMs: Long,
    private val apiLevel: Int,
) : ServerAccess {
    private val lock = Mutex()
    private var current = token to http.withToken(token)
    private var expiresAt = expiresAtMs

    /** The cast token in use: the sender's, then each renewed one. */
    @Volatile var token: String = token
        private set

    override suspend fun <T> call(block: suspend (baseUrl: String, client: OkHttpClient) -> T): T {
        val client = lock.withLock { current.second }
        return Http.call(signedIn = false) { block(baseUrl, client) }
    }

    override fun apiLevel(): Int = apiLevel

    /** A fresh cast token, which also opens media URLs. */
    suspend fun renew(): MediaToken {
        val t = call { base, client -> AuthApi(base, client).createCastToken() }
        val exp = epochMs(t.expiresAt) ?: (System.currentTimeMillis() + 60 * 60_000L)
        lock.withLock {
            current = t.token to http.withToken(t.token)
            expiresAt = exp
            token = t.token
        }
        return MediaToken(t.token, exp, t.param.ifBlank { "access_token" }, serverUrl = baseUrl)
    }
}

/**
 * The caster's artwork at [path] on their server ([baseUrl]), opened by their
 * cast [token] in the URL, since the TV's image loader signs in only to the
 * TV's own server; null for no image.
 */
internal fun castImageUrl(baseUrl: String, token: String, path: String?, size: ImageSize = ImageSize.W342): String? {
    path ?: return null
    return "$baseUrl/api/images/${size.path}${if (path.startsWith('/')) path else "/$path"}?access_token=${URLEncoder.encode(token, "UTF-8")}"
}

/** A sender's server calls: whether the server in use can cast, and a token for the TV. */
internal class RealCastGateway(private val store: LocalStore, private val http: Http) : CastGateway {
    override suspend fun liveAutomaticAvailable(): Boolean =
        http.apiLevel() >= media.watchmy.client.model.LIVE_AUTOMATIC_API_LEVEL &&
            http.signedIn { base, client -> LiveTvApi(base, client).getLiveTVCapabilities().automatic == true }

    override suspend fun castServer(): CastServer? {
        val base = store.session()?.server?.baseUrl ?: return null
        val info = Http.call(signedIn = false) { SystemApi(base, http.probeClient).getServer() }
        return CastServer(info.id, base, casting = info.apiLevel >= CAST_API_LEVEL && info.casting == true)
    }

    override suspend fun castToken(): CastToken {
        val t = http.signedIn { base, client -> AuthApi(base, client).createCastToken() }
        return CastToken(t.token, epochMs(t.expiresAt) ?: (System.currentTimeMillis() + 60 * 60_000L), t.urls)
    }

    companion object {
        /** The API level that brought cast tokens. */
        const val CAST_API_LEVEL = 2
    }
}

/**
 * The caster's playback preferences on this TV: skip modes and autoplay
 * read with their cast token (which cannot change them, so changes made here
 * last for the play), and the quality the sender picked, else this TV's own.
 * A quality change is the play's, not this TV's default. Automatic plays as
 * Original when the caster's server can't do it ([auto] false).
 */
internal class CastPreferences(
    private val access: ServerAccess,
    private val local: PreferencesRepository,
    picked: StreamQuality?,
    auto: Boolean,
) : PreferencesRepository {
    private val prefs = MutableStateFlow(PlaybackPreferences())
    override val playback: StateFlow<PlaybackPreferences> = prefs.asStateFlow()

    override suspend fun refresh() {
        prefs.value = access.call { base, client -> UsersApi(base, client).getPreferences() }.toModel()
    }

    override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) {
        prefs.value = prefs.value.copy(skip = prefs.value.skip + (kind to mode))
    }

    override suspend fun setAutoplayNext(enabled: Boolean) {
        prefs.value = prefs.value.copy(autoplayNext = enabled)
    }

    override val autoQuality: StateFlow<Boolean> = MutableStateFlow(auto).asStateFlow()

    private val chosen = MutableStateFlow(playable(picked ?: local.quality.value))
    override val quality: StateFlow<StreamQuality> = chosen.asStateFlow()

    override suspend fun setQuality(quality: StreamQuality) {
        chosen.value = playable(quality)
    }

    private fun playable(q: StreamQuality) = if (q == StreamQuality.Auto && !autoQuality.value) StreamQuality.Original else q

    // Frame rate, passthrough and subtitle size belong to this TV, whoever casts.
    override val device: StateFlow<DevicePlayback> = local.device

    override suspend fun setMobileQuality(quality: StreamQuality?) = local.setMobileQuality(quality)

    override suspend fun setMatchFrameRate(enabled: Boolean) = local.setMatchFrameRate(enabled)

    override suspend fun setAudioPassthrough(mode: AudioPassthrough) = local.setAudioPassthrough(mode)

    override suspend fun setSubtitleSize(size: SubtitleSize) = local.setSubtitleSize(size)
}

/** A caster's server as this TV reached it (Cast Connect): the address that answered, and its API level. */
data class ReachedServer(val baseUrl: String, val apiLevel: Int)

/** Which of [urls] answers GET /api/server as the server [serverId], best first; null when none does. */
internal suspend fun reachServer(http: Http, serverId: String, urls: List<String>): ReachedServer? = coroutineScope {
    val probes = castServerUrls(urls).map { base ->
        async {
            try {
                Http.call(signedIn = false) { SystemApi(base, http.probeClient).getServer() }
                    .takeIf { it.id == serverId }
                    ?.let { ReachedServer(base, it.apiLevel.toInt()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val found = probes.firstNotNullOfOrNull { it.await() }
    probes.forEach { it.cancel() }
    found
}

/** How many of a sender's addresses this TV tries; it tries them all at once. */
internal const val MAX_CAST_URLS = 8

/**
 * The addresses a sender lists for its server, as this TV tries them: http
 * and https only, without a user name, each once, and the first
 * [MAX_CAST_URLS] (the server lists a handful, best first).
 */
internal fun castServerUrls(urls: List<String>): List<String> = urls.asSequence()
    .mapNotNull { it.trim().toHttpUrlOrNull() }
    .filter { (it.scheme == "http" || it.scheme == "https") && it.username.isEmpty() && it.password.isEmpty() }
    .map(ServerAddress::normalise)
    .distinct()
    .take(MAX_CAST_URLS)
    .toList()
