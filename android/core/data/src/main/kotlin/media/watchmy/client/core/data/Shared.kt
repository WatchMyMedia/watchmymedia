package media.watchmy.client.core.data

import media.watchmy.client.core.api.models.CastToken as ApiCastToken
import media.watchmy.client.core.api.models.Client
import media.watchmy.client.core.api.models.MediaToken as ApiMediaToken
import media.watchmy.client.core.api.models.OfflineToken as ApiOfflineToken
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.MediaToken

/**
 * What a token reply's expiry reads as. One the app can't read counts as an
 * hour from now: the server's media and offline tokens last six hours and
 * cast tokens twelve, so the player renews early rather than late. Live TV
 * used zero instead, and its player renews at what is left with a
 * one-second floor, so it would have asked for a new token every second.
 */
internal fun tokenExpiry(expiresAt: String?, nowMs: Long = System.currentTimeMillis()): Long = epochMs(expiresAt) ?: (nowMs + UNREAD_EXPIRY_MS)

/** An hour: what an expiry the app can't read counts as ([tokenExpiry]). */
internal const val UNREAD_EXPIRY_MS = 60 * 60_000L

/** A token for media URLs from [serverUrl], with the query parameter it goes in ("access_token" when the server names none). */
internal fun mediaToken(token: String, expiresAt: String?, param: String, serverUrl: String?): MediaToken =
    MediaToken(token, tokenExpiry(expiresAt), param.ifBlank { "access_token" }, serverUrl)

internal fun ApiMediaToken.toModel(serverUrl: String) = mediaToken(token, expiresAt, param, serverUrl)

internal fun ApiOfflineToken.toModel(serverUrl: String) = mediaToken(token, expiresAt, param, serverUrl)

/** A cast token, which also opens media URLs on the caster's server (Cast Connect). */
internal fun ApiCastToken.toMediaToken(serverUrl: String) = mediaToken(token, expiresAt, param, serverUrl)

/** This device as the server's sign-in, pairing, play and download calls take it, within the server's limits. */
internal fun ClientInfo.toApi() = Client(
    deviceId = deviceId.take(64),
    name = name.take(RealAuthRepository.MAX_NAME),
    product = product.take(64),
    platform = platform.take(32),
    version = version.take(32),
    model = model.take(64).ifBlank { null },
)

/** Runs [block]; a 404 means it is already gone (another device, a timeout, or it ended), which is what was asked. */
internal inline fun ignoring404(block: () -> Unit) {
    try {
        block()
    } catch (e: ApiException) {
        if (e.status != 404) throw e
    }
}

/** [block]'s answer; from a server without the call (older than this app, [unknownRoute]), [fallback]'s instead. */
internal inline fun <T> orIfUnknownRoute(fallback: () -> T, block: () -> T): T = try {
    block()
} catch (e: ApiException) {
    if (!e.unknownRoute()) throw e
    fallback()
}
