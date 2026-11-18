package media.watchmy.client.tv.detail

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PLAN-TV-TRAILERS.md §4.3's table: what each remote key does on the trailer screen, in both ways of driving YouTube's player. */
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
    fun `YouTube's keyboard controls get k to play or pause, j and l to jump 10 s`() {
        assertEquals(KeyEvent.KEYCODE_K, youTubeKeyCode(TrailerKey.Toggle, playing = true))
        assertEquals(KeyEvent.KEYCODE_K, youTubeKeyCode(TrailerKey.Toggle, playing = false))
        assertEquals(KeyEvent.KEYCODE_J, youTubeKeyCode(TrailerKey.Back10, playing = true))
        assertEquals(KeyEvent.KEYCODE_L, youTubeKeyCode(TrailerKey.Ahead10, playing = true))
        assertEquals("Play plays when paused", KeyEvent.KEYCODE_K, youTubeKeyCode(TrailerKey.Play, playing = false))
        assertNull("and does nothing while playing", youTubeKeyCode(TrailerKey.Play, playing = true))
        assertEquals("Pause pauses while playing", KeyEvent.KEYCODE_K, youTubeKeyCode(TrailerKey.Pause, playing = true))
        assertNull("and does nothing when paused", youTubeKeyCode(TrailerKey.Pause, playing = false))
        for (k in listOf(TrailerKey.Leave, TrailerKey.Swallow, TrailerKey.Pass)) assertNull(k.name, youTubeKeyCode(k, playing = true))
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

    @Test
    fun `the debug switch takes either way's name`() {
        assertEquals(TrailerRemote.Api, TrailerRemoteSetting.parse("api"))
        assertEquals(TrailerRemote.Keyboard, TrailerRemoteSetting.parse("Keyboard"))
        assertNull(TrailerRemoteSetting.parse("mouse"))
        assertNull(TrailerRemoteSetting.parse(null))
    }
}
