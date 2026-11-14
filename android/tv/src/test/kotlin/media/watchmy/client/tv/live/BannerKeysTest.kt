package media.watchmy.client.tv.live

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV reminder banner's OK (android-tv/LiveReminder): it never takes focus,
 * so it reads it first. Back, which dismisses it, is `Modifier.onBack`'s.
 */
class BannerKeysTest {
    private var watched = 0
    private val keys = BannerKeys(onWatch = { watched++ })

    @Test fun `a short OK goes to what has focus`() {
        assertFalse(keys.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeat = 0))
        assertFalse(keys.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeat = 0))
        assertEquals(0, watched)
    }

    @Test fun `holding OK watches once, and its release goes nowhere`() {
        assertFalse(keys.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeat = 0))
        assertTrue(keys.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeat = 1))
        assertTrue(keys.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeat = 2))
        assertTrue(keys.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeat = 0))
        assertEquals(1, watched)
    }

    @Test fun `a banner with nothing for Hold OK leaves it to what has focus`() {
        val warning = BannerKeys(onWatch = { watched++ }, holdsOk = false)
        assertFalse(warning.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeat = 0))
        assertFalse(warning.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = true, repeat = 1))
        assertFalse(warning.handle(KeyEvent.KEYCODE_DPAD_CENTER, down = false, repeat = 0))
        assertEquals(0, watched)
    }

    @Test fun `Back and the arrows pass`() {
        assertFalse(keys.handle(KeyEvent.KEYCODE_BACK, down = true, repeat = 0))
        assertFalse(keys.handle(KeyEvent.KEYCODE_DPAD_DOWN, down = true, repeat = 0))
    }
}
