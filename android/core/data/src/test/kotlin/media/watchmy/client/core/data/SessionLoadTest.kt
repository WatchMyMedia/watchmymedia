package media.watchmy.client.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks each sign-in starts (PLAN-ANDROID-REVIEW.md §4.1.6). */
class SessionLoadTest {
    @Test
    fun `the three checks go together, not one after another`() = runBlocking {
        Harness().use { h ->
            // The first check is kept waiting; the other two are asked meanwhile.
            val me = h.server.hold("GET /api/auth/me")
            h.server.on("GET /api/server", TestServer.json("""{"id":"srv-test","name":"Test","version":"1.0.0","apiLevel":1,"setupRequired":false,"turnstile":false,"platform":"linux","host":""}"""))
            h.signIn()
            me.awaitArrival()
            try {
                withTimeout(5_000) {
                    while (h.server.requests("GET /api/me/preferences").isEmpty() || h.server.requests("GET /api/server").isEmpty()) delay(10)
                }
                assertTrue(h.server.requests("GET /api/me/preferences").isNotEmpty())
            } finally {
                me.release()
            }
        }
    }
}
