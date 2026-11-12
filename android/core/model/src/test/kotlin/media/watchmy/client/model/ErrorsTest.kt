package media.watchmy.client.model

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {
    @Test fun `a screen shows the repository's sentence`() {
        assertEquals("The server said no.", errorMessage(ApiException(500, "The server said no.")))
        assertEquals("Couldn't reach the server. Check the connection and try again.", errorMessage(ApiException(null, "Couldn't reach the server. Check the connection and try again.")))
        // The TV used to say "You were signed out. Sign in again."; the phone and the Apple apps say this.
        assertEquals("Signed out", errorMessage(SignedOutException()))
        val old = Server("id", "Living room", "http://192.168.1.20:27587", "0.0.9", 0)
        assertEquals("Living room runs version 0.0.9, which is too old for this app. Update the server.", errorMessage(ServerTooOldException(old)))
    }

    @Test fun `anything else gets a plain line`() {
        assertEquals(GENERIC_ERROR, errorMessage(ApiException(500, " ")))
        // The TV used to show these raw.
        assertEquals(GENERIC_ERROR, errorMessage(IllegalStateException("Required value was null.")))
        assertEquals(GENERIC_ERROR, errorMessage(java.io.IOException("timeout")))
        assertEquals("fallback", errorMessage(IllegalStateException("internal"), "fallback"))
    }

    @Test fun `attempt catches failures and lets cancellation through`() {
        assertEquals(Result.success(3), attempt { 1 + 2 })
        val failed = attempt { throw ApiException(404, "Gone") }
        assertEquals("Gone", failed.exceptionOrNull()?.message)
        var cancelled = false
        try {
            attempt { throw CancellationException("left") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
