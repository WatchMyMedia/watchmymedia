package media.watchmy.client.core.playback.watchnext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextScopeTest {
    @Test fun `sign out and switching away invalidate queued launcher writes`() {
        val scope = WatchNextScope("viewer-a")
        val old = scope.capture()
        assertTrue(scope.permits(old))
        scope.select(null)
        assertFalse(scope.permits(old))
        assertFalse(scope.permits(scope.capture()))
        scope.select("viewer-a")
        assertFalse(scope.permits(old))
        assertTrue(scope.permits(scope.capture()))
        val second = scope.capture()
        scope.select("viewer-b")
        assertFalse(scope.permits(second))
    }
}
