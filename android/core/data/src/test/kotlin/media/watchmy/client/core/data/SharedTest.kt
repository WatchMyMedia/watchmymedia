package media.watchmy.client.core.data

import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SharedTest {
    @Test
    fun `an expiry the app can't read counts as an hour, never as zero`() {
        assertEquals(1_000L + UNREAD_EXPIRY_MS, tokenExpiry("soon", nowMs = 1_000L))
        assertEquals(1_000L + UNREAD_EXPIRY_MS, tokenExpiry(null, nowMs = 1_000L))
        assertEquals(1_893_456_000_000L, tokenExpiry("2030-01-01T00:00:00Z"))
        val token = mediaToken("t", "", "", "https://example.test")
        assertEquals("access_token", token.param)
        assertTrue(token.expiresAtMs > System.currentTimeMillis() + UNREAD_EXPIRY_MS - 60_000)
    }

    @Test
    fun `this device goes to the server within its limits`() {
        val client = ClientInfo("d".repeat(80), "n".repeat(80), "p".repeat(80), "a".repeat(40), "v".repeat(40), model = "")
        val api = client.toApi()
        assertEquals(64, api.deviceId.length)
        assertEquals(RealAuthRepository.MAX_NAME, api.name?.length)
        assertEquals(64, api.product?.length)
        assertEquals(32, api.platform?.length)
        assertEquals(32, api.version?.length)
        assertNull("a blank model is left out", api.model)
    }

    @Test
    fun `a 404 counts as done, and nothing else does`() {
        ignoring404 { throw ApiException(404, "gone") }
        try {
            ignoring404 { throw ApiException(500, "broken") }
            fail("expected the 500")
        } catch (e: ApiException) {
            assertEquals(500, e.status)
        }
    }

    @Test
    fun `an older server's missing call gives the fallback`() {
        assertEquals("old", orIfUnknownRoute({ "old" }) { throw ApiException(404, "no such route") })
        assertEquals("old", orIfUnknownRoute({ "old" }) { throw ApiException(405, "no such method") })
        assertEquals("new", orIfUnknownRoute({ "old" }) { "new" })
        try {
            orIfUnknownRoute({ "old" }) { throw ApiException(403, "not allowed") }
            fail("expected the 403")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
    }
}
