package media.watchmy.client.model

import kotlinx.coroutines.CancellationException

/** A request that failed; [message] is the server's detail when it sent one. */
open class ApiException(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server's API level is below [REQUIRED_API_LEVEL]. */
class ServerTooOldException(val server: Server) :
    ApiException(null, "${server.name} runs version ${server.version}, which is too old for this app. Update the server.")

/** Not signed in (or the session was revoked): show sign-in. */
class SignedOutException : ApiException(401, "Signed out")

/** What a screen says when a call fails without a sentence of its own. */
const val GENERIC_ERROR = "Something went wrong. Try again."

/**
 * What a screen says about [error]: the repository's sentence, which every
 * [ApiException] carries (the server's message, a network failure, "Signed
 * out", a server too old), else [fallback]. Other exceptions are the app's
 * own slips, whose messages are not written for people; an app-level
 * failure meant to be read is an [ApiException] too.
 */
fun errorMessage(error: Throwable, fallback: String = GENERIC_ERROR): String =
    (error as? ApiException)?.message?.takeIf { it.isNotBlank() } ?: fallback

/** Runs [block] and catches its failure, letting cancellation through. */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** The server could not be reached at all (no network, or it is away), as against a refusal or a reply this app can't read. */
fun Throwable.isUnreachable(): Boolean = this is ApiException && status == null && cause is java.io.IOException
