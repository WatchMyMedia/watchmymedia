package media.watchmy.client.core.playback.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import java.util.concurrent.atomic.AtomicInteger

/** Publishes a media session for a player while a viewing lasts. */
fun interface MediaSessionHost {
    /** Starts a session for [player]; closing the result ends it. */
    fun open(player: Player): AutoCloseable

    companion object {
        val None = MediaSessionHost { AutoCloseable {} }
    }
}

/**
 * The app's media session: remote media keys and Assistant "pause" on TVs,
 * and with [notification] (phones) the media notification, shown by
 * [PlaybackService], which this starts. [artwork] fetches the pictures the
 * player names, signed in to the server; without it Media3 would fetch them
 * without the sign-in, and fail.
 */
@OptIn(UnstableApi::class)
class AndroidMediaSessionHost(context: Context, private val notification: Boolean, artwork: BitmapLoader? = null) : MediaSessionHost {
    private val app = context.applicationContext

    // The last picture kept, as Media3's own loader does, for the notification's updates.
    private val artwork = artwork?.let(::CacheBitmapLoader)

    override fun open(player: Player): AutoCloseable {
        val builder = MediaSession.Builder(app, player).setId("watchmymedia-${ids.incrementAndGet()}")
        launchIntent()?.let { builder.setSessionActivity(it) }
        artwork?.let { builder.setBitmapLoader(it) }
        val session = builder.build()
        PlaybackSessions.publish(session)
        if (notification) {
            try {
                app.startService(Intent(app, PlaybackService::class.java))
            } catch (e: IllegalStateException) {
                // Started from the background (not expected: playback starts on screen). The session still works.
                Log.w(TAG, "Could not start the playback service", e)
            }
        }
        return AutoCloseable {
            PlaybackSessions.withdraw(session)
            session.release()
        }
    }

    private fun launchIntent(): PendingIntent? {
        val pm = app.packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: pm.getLeanbackLaunchIntentForPackage(app.packageName) ?: return null
        return PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val TAG = "WmmSession"
        val ids = AtomicInteger()
    }
}

/** The session playing now, for [PlaybackService]. Main thread only. */
internal object PlaybackSessions {
    var current: MediaSession? = null
        private set
    private val listeners = mutableListOf<(MediaSession?) -> Unit>()

    fun publish(session: MediaSession) {
        checkMain()
        current = session
        listeners.toList().forEach { it(session) }
    }

    fun withdraw(session: MediaSession) {
        checkMain()
        if (current !== session) return
        current = null
        listeners.toList().forEach { it(null) }
    }

    fun observe(listener: (MediaSession?) -> Unit): AutoCloseable {
        checkMain()
        listeners += listener
        listener(current)
        return AutoCloseable { listeners -= listener }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "media sessions are managed on the main thread" }
}
