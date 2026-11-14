package media.watchmy.client

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MainActivity's routing of the intents it is opened with (IntentRouter):
 * Cast Connect's casts to the TV's receiver, links to the UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IntentRouterTest {
    /** What Cast Connect was handed, and whether it took them: MediaManager.onNewIntent takes only its LOAD intents. */
    private val casts = mutableListOf<Intent>()
    private val castConnect: (Intent) -> Boolean = { intent -> (intent.action == CAST_LOAD).also { if (it) casts += intent } }

    private fun link(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test fun `a link goes to the UI, and the same link again is handled again`() {
        val router = IntentRouter(castConnect)
        router.receive(link("watchmymedia://shows/42"))
        assertEquals("watchmymedia://shows/42", router.deepLink)
        assertEquals(1L, router.deepLinkCount)
        router.receive(link("watchmymedia://shows/42"))
        assertEquals(2L, router.deepLinkCount)
        assertEquals(emptyList<Intent>(), casts)
    }

    @Test fun `a cast to this TV goes to Cast Connect and leaves the link alone`() {
        val router = IntentRouter(castConnect)
        router.receive(link("watchmymedia://play/7"))
        // Cast Connect's LOAD may carry data of its own; it is a cast, not a link.
        router.receive(Intent(CAST_LOAD).setData(Uri.parse("https://cast.example/load")))
        assertEquals(1, casts.size)
        assertEquals("watchmymedia://play/7", router.deepLink)
        assertEquals(1L, router.deepLinkCount)
    }

    @Test fun `on a phone, with no Cast Connect, every link goes to the UI`() {
        val router = IntentRouter { false }
        router.receive(link("watchmymedia://live?channel=live-2-1&account=sam"))
        assertEquals("watchmymedia://live?channel=live-2-1&account=sam", router.deepLink)
    }

    @Test fun `the launcher's own intent, or none, changes nothing`() {
        val router = IntentRouter(castConnect)
        router.receive(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        router.receive(null)
        assertNull(router.deepLink)
        assertEquals(0L, router.deepLinkCount)
        assertEquals(emptyList<Intent>(), casts)
    }

    private companion object {
        /** The action the manifest's Cast Connect intent filter takes. */
        const val CAST_LOAD = "com.google.android.gms.cast.tv.action.LOAD"
    }
}
