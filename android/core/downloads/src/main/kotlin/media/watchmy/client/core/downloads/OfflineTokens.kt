package media.watchmy.client.core.downloads

import java.util.concurrent.ConcurrentHashMap
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OfflineOwner

/**
 * Each download's current `o1.` token (PLAN-DOWNLOADS.md §4.2), fetched with
 * its account's session and kept until it is near its end. The downloader
 * asks on its own threads, one download at a time.
 */
internal class OfflineTokens(
    private val fetch: suspend (owner: OfflineOwner, downloadId: String) -> MediaToken,
    private val owner: (downloadId: String) -> OfflineOwner?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tokens = ConcurrentHashMap<String, MediaToken>()

    /**
     * The token for [downloadId]: the one held unless it ends within
     * [MARGIN_MS] or [fresh] asks for a new one (the server refused it).
     * Throws what the token call throws: [media.watchmy.client.model.ApiException]
     * (403 revoked, 410 the file changed) or SignedOutException.
     */
    suspend fun token(downloadId: String, fresh: Boolean = false): MediaToken {
        val held = tokens[downloadId]
        if (!fresh && held != null && held.expiresAtMs - now() > MARGIN_MS) return held
        val o = owner(downloadId) ?: throw media.watchmy.client.model.SignedOutException()
        return fetch(o, downloadId).also { tokens[downloadId] = it }
    }

    fun forget(downloadId: String) {
        tokens.remove(downloadId)
    }

    private companion object {
        /** Tokens last 6 h; a new one well before the end, so a slow segment never outlives its token. */
        const val MARGIN_MS = 10 * 60_000L
    }
}
