package media.watchmy.client.core.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import media.watchmy.client.core.api.infrastructure.ClientError
import media.watchmy.client.core.api.infrastructure.ClientException
import media.watchmy.client.core.api.infrastructure.Serializer
import media.watchmy.client.core.api.infrastructure.ServerError
import media.watchmy.client.core.api.infrastructure.ServerException
import media.watchmy.client.core.api.models.ErrorModel
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.isOnServer
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * The HTTP side: one OkHttp client whose interceptors add the bearer token to
 * requests for the current server (never to other hosts) and clear the
 * sign-in when the server answers 401, plus [signedIn] and [call], which turn
 * every failure into [ApiException].
 */
internal class Http(base: OkHttpClient, private val store: LocalStore, private val userAgent: String) {
    /**
     * Public probes and sign-in never inherit the current account's token.
     * No client follows a redirect between http and https: one to http would
     * send on in the clear what was meant to be encrypted, and the app's own
     * calls are never redirected that way (a probe handles an upgrade itself).
     */
    val publicClient: OkHttpClient = base.newBuilder().followSslRedirects(false).addInterceptor(ReplyLimit()).addInterceptor(::userAgent).build()
    /** API calls and images. */
    val client: OkHttpClient = base.newBuilder()
        .followSslRedirects(false)
        .addInterceptor(ReplyLimit())
        .addInterceptor(::userAgent)
        .addInterceptor(::authorize)
        .build()

    /**
     * Signing in and pairing follow no redirects at all: a 307 or 308 would
     * send the password, or the pairing secret that collects the token, on
     * to wherever it points.
     */
    val authClient: OkHttpClient = publicClient.newBuilder().followRedirects(false).build()

    /**
     * Looking up an address someone typed: give up quickly. A redirect is
     * not followed but reported ([RedirectException]), for the probe to
     * follow the ones that stay on the same host.
     */
    val probeClient: OkHttpClient = publicClient.newBuilder()
        .followRedirects(false)
        .addInterceptor(::reportRedirect)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** The event stream: the server pings every 15 s, so silence for longer means the connection is gone. */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** A client that sends [token] itself, for a server that is no longer the current one. */
    fun withToken(token: String): OkHttpClient = publicClient.newBuilder().apply {
        interceptors().add(0, Interceptor { it.proceed(it.request().newBuilder().header(AUTHORIZATION, "Bearer $token").build()) })
    }.build()

    /** Runs [block] with the current server's base URL as the signed-in user. */
    suspend fun <T> signedIn(block: suspend (baseUrl: String, client: OkHttpClient) -> T): T {
        val (session, revision) = store.requestSnapshot()
        val result = bind(session).call(block)
        if (store.revision != revision) throw CancellationException("The signed-in account changed.")
        return result
    }

    /** Capture before suspension; playback keeps this access through its final stop report. */
    fun bind(): ServerAccess = bind(store.requestSnapshot().first)

    /** The current server's API level as last read; 0 before one is chosen. */
    fun apiLevel(): Int = store.session()?.server?.apiLevel ?: 0

    /**
     * Access to the saved server [serverId] as [userId], whether or not it is
     * the current one (an offline download's account); null once that
     * account is no longer signed in there.
     */
    fun bindTo(serverId: String, userId: Long): ServerAccess? {
        val server = store.value.servers.firstOrNull { it.id == serverId } ?: return null
        val account = server.account?.takeIf { it.userId == userId } ?: return null
        return bind(Session(server, account))
    }

    /** Bind an already captured atomic identity/revision snapshot (Live TV). */
    internal fun bind(session: Session?): ServerAccess {
        return object : ServerAccess {
            override suspend fun <T> call(block: suspend (baseUrl: String, client: OkHttpClient) -> T): T {
                val account = session?.account ?: throw SignedOutException()
                val token = withContext(Dispatchers.IO) { store.tokenForRequest(session.server.id, account) } ?: throw SignedOutException()
                val baseUrl = session.server.baseUrl
                val fixed = publicClient.newBuilder().addInterceptor { chain ->
                    val request = chain.request()
                    val signed = if (owns(baseUrl, request.url)) request.newBuilder().header(AUTHORIZATION, "Bearer $token").build() else request
                    val response = chain.proceed(signed)
                    if (response.code == 401) store.tokenRejected(session.server.id, token)
                    response
                }.build()
                return Http.call(signedIn = true) { block(baseUrl, fixed) }
            }

            // The bound server's level as refreshed since, which the start-up check may still change.
            override fun apiLevel(): Int {
                val server = session?.server ?: return 0
                return store.value.servers.firstOrNull { it.id == server.id }?.apiLevel ?: server.apiLevel
            }
        }
    }

    private fun reportRedirect(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isRedirect) return response
        val location = response.header("Location")?.let { response.request.url.resolve(it) }
        response.close()
        throw RedirectException(location)
    }

    private fun userAgent(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }

    private fun authorize(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = store.session()
        val account = session?.account
        if (account == null || request.header(AUTHORIZATION) != null || !owns(session.server.baseUrl, request.url) ||
            request.url.encodedPath.endsWith(LOGIN_PATH)
        ) {
            return chain.proceed(request)
        }
        val token = store.tokenForRequest(session.server.id, account) ?: return chain.proceed(request)
        val response = chain.proceed(request.newBuilder().header(AUTHORIZATION, "Bearer $token").build())
        if (response.code == 401) store.tokenRejected(session.server.id, token)
        return response
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
        private const val LOGIN_PATH = "/api/auth/login"

        /** True when [url] is on the server at [baseUrl] (same origin, under its path): [isOnServer], which the player uses for media URLs too. */
        fun owns(baseUrl: String, url: HttpUrl): Boolean = isOnServer(baseUrl, url.toString())

        /**
         * Runs an API call and turns what can go wrong into [ApiException]:
         * the server's message for a refused request, [SignedOutException] for
         * 401 when [signedIn], and a plain sentence for network trouble or a
         * reply it doesn't understand.
         */
        suspend fun <T> call(signedIn: Boolean, block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: ClientException) {
            if (signedIn && e.statusCode == 401) throw SignedOutException()
            throw ApiException(e.statusCode, detail((e.response as? ClientError<*>)?.body) ?: clientMessage(e.statusCode), e)
        } catch (e: ServerException) {
            throw ApiException(e.statusCode, detail((e.response as? ServerError<*>)?.body) ?: "The server had a problem (${e.statusCode}). Try again.", e)
        } catch (e: ReplyTooLargeException) {
            throw ApiException(null, "The server sent a reply this app doesn't understand.", e)
        } catch (e: IOException) {
            throw ApiException(null, "Couldn't reach the server. Check the connection and try again.", e)
        } catch (e: SerializationException) {
            throw ApiException(null, "The server sent a reply this app doesn't understand. Update the app or the server.", e)
        } catch (e: UnsupportedOperationException) {
            // The generated client met a reply that is not JSON: not a WatchMyMedia server, or a proxy's error page.
            throw ApiException(null, "The server sent a reply this app doesn't understand.", e)
        } catch (e: NullPointerException) {
            // A success with no body: the generated client casts its null to the reply's type.
            throw ApiException(null, "The server sent a reply this app doesn't understand.", e)
        } catch (e: ClassCastException) {
            throw ApiException(null, "The server sent a reply this app doesn't understand.", e)
        } catch (e: IllegalStateException) {
            throw ApiException(null, "That server address is not valid.", e)
        }

        /** The `detail` of the server's problem+json body, as a sentence. */
        fun detail(body: Any?): String? {
            val text = body as? String ?: return null
            val model = runCatching { Serializer.kotlinxSerializationJson.decodeFromString(ErrorModel.serializer(), text) }.getOrNull()
            return model?.detail?.takeIf { it.isNotBlank() }?.let(::sentence)
        }

        private fun clientMessage(status: Int) = when (status) {
            401 -> "Sign in required."
            403 -> "Not allowed."
            404 -> "Not found."
            429 -> "Too many attempts. Wait a while and try again."
            else -> "The server refused the request ($status)."
        }

        /** "invalid name or password" → "Invalid name or password." */
        fun sentence(s: String): String {
            val t = s.trim().replaceFirstChar { it.uppercaseChar() }
            return if (t.endsWith('.') || t.endsWith('!') || t.endsWith('?')) t else "$t."
        }
    }
}

/**
 * Stops reading a reply past [maxBytes]: the generated client reads replies
 * whole into memory (JSON, and error pages as text), so a broken or hostile
 * server could otherwise fill it. 16 MB is far past the largest page the
 * server sends (1,000 items). Images and the event stream are streamed and
 * keep their own limits.
 */
internal class ReplyLimit(private val maxBytes: Long = MAX_REPLY_BYTES) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val type = response.body.contentType()
        if (type?.type == "image" || chain.request().header("Accept") == EVENT_STREAM || "${type?.type}/${type?.subtype}" == EVENT_STREAM) return response
        if (response.body.contentLength() > maxBytes) {
            response.close()
            throw ReplyTooLargeException(maxBytes)
        }
        return response.newBuilder().body(LimitedBody(response.body, maxBytes)).build()
    }

    private class LimitedBody(private val body: ResponseBody, private val maxBytes: Long) : ResponseBody() {
        private val limited: BufferedSource by lazy {
            object : ForwardingSource(body.source()) {
                private var total = 0L

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) {
                        total += read
                        if (total > maxBytes) throw ReplyTooLargeException(maxBytes)
                    }
                    return read
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = body.contentType()

        override fun contentLength(): Long = body.contentLength()

        override fun source(): BufferedSource = limited

        override fun close() = body.close()
    }

    companion object {
        const val MAX_REPLY_BYTES = 16L shl 20
        private const val EVENT_STREAM = "text/event-stream"
    }
}

/** A reply ran past [ReplyLimit]'s limit. */
internal class ReplyTooLargeException(limit: Long) : IOException("the reply is over $limit bytes")

/** A probe was answered with a redirect to [location] (null when it named none or one that can't be read). */
internal class RedirectException(val location: HttpUrl?) : IOException("redirected to $location")

/** The server has no such call (it is older than this app): 404 or 405. */
internal fun ApiException.unknownRoute() = status == 404 || status == 405
