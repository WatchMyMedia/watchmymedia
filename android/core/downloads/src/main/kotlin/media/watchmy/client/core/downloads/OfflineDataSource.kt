package media.watchmy.client.core.downloads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.SignedOutException

/** What the downloader meets on a download's routes, for the repository to show and act on. */
internal interface FetchEvents {
    /** The server asked to wait (503 with Retry-After): the download shows Preparing. */
    fun preparing(id: String)

    /** A fetch went through: whatever the wait was, it is over. */
    fun fetching(id: String)

    /** The server refused the download for good: 403 (revoked), 410 (the file changed), or the account signed out (401). */
    fun refused(id: String, status: Int)

    /** 409: a remade session's init segment differs; delete what came and start over. */
    fun restart(id: String)
}

/**
 * The server refused a download's fetch for good. Not an [IOException], so
 * Media3 fails the download at once rather than retrying it.
 */
internal class OfflineRefusedException(val status: Int) : RuntimeException("the server refused the download ($status)")

/**
 * The download's media fetches (PLAN-DOWNLOADS.md §10.2): each request to an
 * offline route carries the download's current `o1.` token as
 * `access_token`, in place of any its playlist gave it. A 401 gets a fresh
 * token once; a 503 is waited out for as long as its Retry-After says, up to
 * [MAX_WAIT_MS] in all; 409 starts the download over; 403 and 410 end it.
 * Anything else goes to [upstream] as it is.
 */
@OptIn(UnstableApi::class)
internal class OfflineDataSource(
    private val upstream: HttpDataSource,
    private val tokens: OfflineTokens,
    private val events: FetchEvents,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val id = downloadIdOf(dataSpec.uri) ?: return upstream.open(dataSpec)
        var renewed = false
        val started = elapsed()
        while (true) {
            val token = token(id, renewed)
            try {
                val length = upstream.open(dataSpec.withUri(withToken(dataSpec.uri, token)))
                events.fetching(id)
                return length
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                upstream.close()
                when (e.responseCode) {
                    401 -> if (renewed) refuse(id, 401) else renewed = true
                    403, 410 -> refuse(id, e.responseCode)
                    409 -> {
                        events.restart(id)
                        throw OfflineRefusedException(409)
                    }
                    503 -> {
                        val wait = retryAfterMs(e.headerFields)
                        if (elapsed() - started + wait > MAX_WAIT_MS) {
                            events.fetching(id)
                            throw e
                        }
                        events.preparing(id)
                        try {
                            sleep(wait)
                        } catch (_: InterruptedException) {
                            // Paused or removed while waiting.
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException("stopped while the server prepared the download")
                        }
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun token(id: String, fresh: Boolean): MediaToken = try {
        runBlocking { tokens.token(id, fresh) }
    } catch (e: CancellationException) {
        throw InterruptedIOException("stopped while asking for a token")
    } catch (e: SignedOutException) {
        refuse(id, 401)
    } catch (e: ApiException) {
        val status = e.status
        // No answer (offline): the downloader tries again, as for any network failure.
        if (status == null || status >= 500) throw IOException(e.message, e)
        refuse(id, status)
    }

    private fun refuse(id: String, status: Int): Nothing {
        events.refused(id, status)
        throw OfflineRefusedException(status)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    /** Makes one per download thread, over [upstream]'s HTTP. */
    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val tokens: OfflineTokens,
        private val events: FetchEvents,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = OfflineDataSource(upstream.createDataSource(), tokens, events)
    }

    companion object {
        /** How long a download waits on the server in all before it fails (and is retried by Media3). */
        const val MAX_WAIT_MS = 30 * 60_000L
        private const val DEFAULT_WAIT_MS = 10_000L
        private const val MAX_ONE_WAIT_MS = 5 * 60_000L

        private val OFFLINE_ROUTE = Regex("""/api/offline/([a-z2-7]{16})/(stream|hls/|subtitles/)""")

        /** The download an offline route belongs to, or null for any other URL. */
        fun downloadIdOf(uri: Uri): String? = uri.path?.let { OFFLINE_ROUTE.find(it)?.groupValues?.get(1) }

        /** [uri] with [token] as its only `access_token`. */
        fun withToken(uri: Uri, token: MediaToken): Uri = withoutToken(uri, token.param).buildUpon().appendQueryParameter(token.param, token.token).build()

        /** [uri] without its access token: the same bytes whatever token fetched them. */
        fun withoutToken(uri: Uri, param: String = "access_token"): Uri {
            if (uri.getQueryParameter(param) == null) return uri
            val b = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (name == param) continue
                for (v in uri.getQueryParameters(name)) b.appendQueryParameter(name, v)
            }
            return b.build()
        }

        /** Cache keys without the token (PLAN-DOWNLOADS.md §10.2), so tokenized playlists and segments find their bytes. */
        val cacheKeys = CacheKeyFactory { spec -> spec.key ?: withoutToken(spec.uri).toString() }

        /** The wait a 503 asks for, in seconds; 10 s when it names none. */
        fun retryAfterMs(headers: Map<String, List<String>>): Long {
            val value = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull()
            val seconds = value?.trim()?.toLongOrNull() ?: return DEFAULT_WAIT_MS
            return (seconds * 1000).coerceIn(1000, MAX_ONE_WAIT_MS)
        }
    }
}
