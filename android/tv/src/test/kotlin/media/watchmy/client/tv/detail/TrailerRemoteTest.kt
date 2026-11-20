package media.watchmy.client.tv.detail

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What each remote key does on the trailer screen, through YouTube's IFrame API. */
class TrailerRemoteTest {
    @Test
    fun `each remote key does what the table says`() {
        val table = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to TrailerKey.Toggle,
            KeyEvent.KEYCODE_ENTER to TrailerKey.Toggle,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to TrailerKey.Toggle,
            KeyEvent.KEYCODE_MEDIA_PLAY to TrailerKey.Play,
            KeyEvent.KEYCODE_MEDIA_PAUSE to TrailerKey.Pause,
            KeyEvent.KEYCODE_DPAD_LEFT to TrailerKey.Back10,
            KeyEvent.KEYCODE_MEDIA_REWIND to TrailerKey.Back10,
            KeyEvent.KEYCODE_DPAD_RIGHT to TrailerKey.Ahead10,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to TrailerKey.Ahead10,
            KeyEvent.KEYCODE_BACK to TrailerKey.Leave,
            KeyEvent.KEYCODE_DPAD_UP to TrailerKey.Swallow,
            KeyEvent.KEYCODE_DPAD_DOWN to TrailerKey.Swallow,
            // The system's and the app's own keys work as anywhere else.
            KeyEvent.KEYCODE_VOLUME_UP to TrailerKey.Pass,
            KeyEvent.KEYCODE_HOME to TrailerKey.Pass,
            KeyEvent.KEYCODE_GUIDE to TrailerKey.Pass,
        )
        for ((code, key) in table) assertEquals(KeyEvent.keyCodeToString(code), key, trailerKey(code))
    }

    @Test
    fun `YouTube's IFrame API gets playVideo, pauseVideo and seekTo through the page`() {
        assertEquals("toggleTrailer()", trailerScript(TrailerKey.Toggle))
        assertEquals("playTrailer()", trailerScript(TrailerKey.Play))
        assertEquals("pauseTrailer()", trailerScript(TrailerKey.Pause))
        assertEquals("seekBy(-10)", trailerScript(TrailerKey.Back10))
        assertEquals("seekBy(10)", trailerScript(TrailerKey.Ahead10))
        for (k in listOf(TrailerKey.Leave, TrailerKey.Swallow, TrailerKey.Pass)) assertNull(k.name, trailerScript(k))
    }
}
