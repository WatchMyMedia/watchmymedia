package media.watchmy.client.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.ApiException
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A success the generated client can't turn into its reply type, such as
 * one with no body (a proxy, or a server cut short): the generated client
 * casts null to the type and throws NullPointerException, which Http.call
 * turns into an [ApiException] like any other failure.
 */
class UnreadableReplyTest {
    private val h = Harness()
    private val server get() = h.server

    @After
    fun tearDown() = h.close()

    @Test
    fun `an empty success is a reply the app doesn't understand`() = runBlocking {
        h.signIn()
        server.on("GET /api/home", MockResponse().setResponseCode(200))
        try {
            h.services.library.home()
            fail("An empty reply is no Home")
        } catch (e: ApiException) {
            assertEquals("The server sent a reply this app doesn't understand.", e.message)
            assertNull(e.status)
            assertTrue(e.cause is NullPointerException)
        }
        assertEquals("Still signed in: the server didn't refuse anything", "viewer", h.services.auth.user.value?.name)
    }

    @Test
    fun `an empty sign-in reply fails the sign-in instead of crashing`() = runBlocking {
        h.select()
        server.on("POST /api/auth/login", MockResponse().setResponseCode(200))
        try {
            h.services.auth.signIn("viewer", "fixture-viewer-1")
            fail("No account in an empty reply")
        } catch (e: ApiException) {
            assertEquals("The server sent a reply this app doesn't understand.", e.message)
        }
        assertNull(h.services.auth.user.value)
    }

    @Test
    fun `cancellation still goes through`() = runBlocking {
        try {
            Http.call(signedIn = true) { throw CancellationException("left the screen") }
            fail("Cancellation is not a failure")
        } catch (e: CancellationException) {
            assertEquals("left the screen", e.message)
        }
    }

    @Test
    fun `a reply of the wrong type is unreadable too`() = runBlocking {
        try {
            Http.call(signedIn = true) { @Suppress("UNCHECKED_CAST") (listOf<Any>(1) as List<String>).first().length }
            fail("A number is no text")
        } catch (e: ApiException) {
            assertEquals("The server sent a reply this app doesn't understand.", e.message)
            assertTrue(e.cause is ClassCastException)
        }
    }
}
