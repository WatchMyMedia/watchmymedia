package media.watchmy.client.core.playback.session

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Shows the media notification on phones for the session the player
 * publishes (Media3 handles foreground state while it plays), and lets
 * system controllers, Bluetooth and Assistant connect. Declared in this
 * module's manifest; [AndroidMediaSessionHost] starts it, and it stops when
 * the viewing ends.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var subscription: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        subscription = PlaybackSessions.observe { session ->
            sessions.filter { it !== session }.forEach { removeSession(it) }
            when {
                session == null -> stopSelf()
                !isSessionAdded(session) -> addSession(session)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackSessions.current

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        subscription?.close()
        subscription = null
        super.onDestroy()
    }
}
