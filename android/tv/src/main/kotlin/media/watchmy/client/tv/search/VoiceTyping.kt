package media.watchmy.client.tv.search

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** What Search's microphone is doing (TvSearchKeyboard.dc.html). */
@Immutable
internal sealed interface Voice {
    /** Not listening: the microphone key reads "Voice typing". */
    data object Ready : Voice

    /** Listening: the words go into the search box as they are heard, and the key reads "Stop listening". */
    data object Listening : Voice

    /** The microphone permission was refused; [stopped] when the TV won't ask again. */
    data class Refused(val stopped: Boolean) : Voice

    /** The recognizer failed; [message] says so. */
    data class Failed(val message: String) : Voice
}

/**
 * Voice typing on TV Search: the TV's speech recognizer, in the app, with
 * partial results, so the search box fills as the person speaks and the
 * results follow. It stops when they stop talking, and at once with Back or
 * the key ([stop]). [available] is false on a TV that doesn't offer speech
 * recognition to apps, which then has no microphone key.
 *
 * The microphone permission is asked for at the first press, never at
 * start-up ([refused] records the answer). The recognizer is made at the
 * first press and destroyed when Search leaves or the app goes to the
 * background ([release]). Runs on the main thread, as SpeechRecognizer must.
 */
@Stable
internal class VoiceTyping(
    private val context: Context,
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(context),
    private val onWords: (String) -> Unit,
) {
    var state by mutableStateOf<Voice>(Voice.Ready)
        private set

    private var recognizer: SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            words(partialResults)?.let(onWords)
        }

        override fun onResults(results: Bundle?) {
            words(results)?.let(onWords)
            state = Voice.Ready
        }

        override fun onError(error: Int) {
            if (state != Voice.Listening) return
            state = when (error) {
                // Nothing said, or nothing understood: it just stops, as it does when they stop talking.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Voice.Ready
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Voice.Refused(stopped = false)
                else -> Voice.Failed(FAILED)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Starts listening; the permission must already be granted. */
    fun start() {
        if (!available) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        state = Voice.Listening
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Back or the key while listening: stops now; what was heard stays in the box. */
    fun stop() {
        if (state != Voice.Listening) return
        state = Voice.Ready
        recognizer?.cancel()
    }

    /** The microphone permission was refused at the press; [stopped] when the TV won't ask again. */
    fun refused(stopped: Boolean) {
        state = Voice.Refused(stopped)
    }

    /** Search left or the app went to the background: the recognizer goes, and is made again at the next press. */
    fun release() {
        if (state == Voice.Listening) state = Voice.Ready
        recognizer?.destroy()
        recognizer = null
    }

    private fun words(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val FAILED = "Voice typing didn’t work. Try again, or type the title."
    }
}

/**
 * Search's voice typing for this screen: released when the screen leaves
 * or the app goes to the background. [onWords] gets what is heard, partial
 * and final.
 */
@Composable
internal fun rememberVoiceTyping(onWords: (String) -> Unit): VoiceTyping {
    val context = LocalContext.current
    val words by rememberUpdatedState(onWords)
    val voice = remember(context) { VoiceTyping(context) { words(it) } }
    DisposableEffect(voice) { onDispose { voice.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { voice.release() }
    return voice
}

/**
 * The microphone key's press: stops when listening; otherwise starts, asking
 * for the microphone permission first when it hasn't been granted (the TV's
 * own question, at the first press, never at start-up).
 */
@Composable
internal fun rememberVoicePress(voice: VoiceTyping): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voice.start()
        } else {
            // Refused with no reason to show again: the TV won't ask any more.
            voice.refused(stopped = context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) != true)
        }
    }
    return remember(voice, ask) {
        {
            when {
                voice.state == Voice.Listening -> voice.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start()
                else -> ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** The line under the search box for [voice]'s state, or null when there is nothing to say. */
internal fun voiceLine(voice: Voice): String? = when (voice) {
    Voice.Ready -> null
    Voice.Listening -> "Listening… say a title. It stops when you stop talking; Back stops it now."
    is Voice.Refused ->
        if (voice.stopped) {
            "The microphone is off for WatchMyMedia. To speak a title, allow it in the TV’s settings under Apps › WatchMyMedia › Permissions."
        } else {
            "Voice typing needs the microphone. Press the microphone again to allow it, or type the title."
        }
    is Voice.Failed -> voice.message
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
