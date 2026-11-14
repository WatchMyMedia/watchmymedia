package media.watchmy.client.tv.search

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/** TV Search's voice typing, on Robolectric's speech recognizer (TvSearchKeyboard.dc.html). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceTypingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val heard = mutableListOf<String>()

    @Before
    fun reset() = ShadowSpeechRecognizer.reset()

    private fun voice(available: Boolean = true) = VoiceTyping(context, available) { heard += it }

    private fun recognizer() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun words(vararg w: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*w)) }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `it listens with partial results, fills the box as words come, and stops at the final ones`() {
        val voice = voice()
        voice.start()
        assertEquals(Voice.Listening, voice.state)
        assertEquals("Listening… say a title. It stops when you stop talking; Back stops it now.", voiceLine(voice.state))
        assertTrue(recognizer().lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))

        recognizer().triggerOnPartialResults(words("harbor li"))
        idle()
        assertEquals(listOf("harbor li"), heard)
        assertEquals("still listening", Voice.Listening, voice.state)

        recognizer().triggerOnResults(words("harbor lights"))
        idle()
        assertEquals(listOf("harbor li", "harbor lights"), heard)
        assertEquals(Voice.Ready, voice.state)
        assertNull(voiceLine(voice.state))
    }

    @Test
    fun `nothing heard just stops, and a failure says so`() {
        val voice = voice()
        voice.start()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        idle()
        assertEquals(Voice.Ready, voice.state)

        voice.start()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        idle()
        assertEquals(Voice.Failed(VoiceTyping.FAILED), voice.state)
        assertEquals("Voice typing didn’t work. Try again, or type the title.", voiceLine(voice.state))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a refused microphone says how to allow it, and differently once the TV stops asking`() {
        val voice = voice()
        voice.refused(stopped = false)
        assertEquals("Voice typing needs the microphone. Press the microphone again to allow it, or type the title.", voiceLine(voice.state))
        voice.refused(stopped = true)
        assertTrue(voiceLine(voice.state)!!.startsWith("The microphone is off for WatchMyMedia."))

        // The recognizer itself finding no permission counts as refused too.
        voice.start()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        idle()
        assertEquals(Voice.Refused(stopped = false), voice.state)
    }

    @Test
    fun `Back or the key stops it now, keeping what was heard, and a late error changes nothing`() {
        val voice = voice()
        voice.start()
        recognizer().triggerOnPartialResults(words("saltm"))
        idle()
        voice.stop()
        assertEquals(Voice.Ready, voice.state)
        recognizer().triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        idle()
        assertEquals(Voice.Ready, voice.state)
        assertEquals(listOf("saltm"), heard)
    }

    @Test
    fun `leaving the screen or the app releases the recognizer, and the next press makes a new one`() {
        val voice = voice()
        voice.start()
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        voice.release()
        assertEquals(Voice.Ready, voice.state)
        assertTrue(shadowOf(first).isDestroyed)
        voice.start()
        assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test
    fun `a TV without speech recognition has no microphone, and nothing starts`() {
        val voice = voice(available = false)
        assertFalse(voice.available)
        voice.start()
        assertEquals(Voice.Ready, voice.state)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test
    fun `the microphone shows where a recognition service is installed`() {
        assertFalse(VoiceTyping(context) {}.available)
        val pm = shadowOf(context.packageManager)
        val service = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        assertTrue(VoiceTyping(context) {}.available)
    }
}
