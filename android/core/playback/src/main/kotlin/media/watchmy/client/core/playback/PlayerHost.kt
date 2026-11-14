package media.watchmy.client.core.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.watchmy.client.core.playback.player.MediaPlayer
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.PlayerSource
import media.watchmy.client.core.playback.session.MediaSessionHost

/**
 * What the file and Live TV controllers both do around their one
 * [MediaPlayer]: make it on first use, post its callbacks to [scope] (so a
 * callback from inside a player call never re-enters the controller), put
 * it in the media session, follow the app's visibility, tick every 250 ms,
 * and release it all. Main thread only, as the player is.
 */
internal class PlayerHost(
    private val scope: CoroutineScope,
    private val factory: () -> MediaPlayer,
    private val sessions: MediaSessionHost,
    private val visibility: AppVisibility,
    private val clock: Clock,
) {
    /** The controller's side of the player's callbacks, each run on [scope]. */
    interface Events {
        fun onChanged()

        fun onError(failure: PlayerFailure)

        /** The first frame of the [load]th [PlayerHost.load] reached the renderer. */
        fun onFirstFrame(load: Int) = Unit

        fun onAudioUnavailable() = Unit
    }

    private val mutablePlayer = MutableStateFlow<Player?>(null)

    /** The Media3 player the screens render, as the media session wraps it; null in fakes and once released. */
    val player: StateFlow<Player?> = mutablePlayer.asStateFlow()

    var media: MediaPlayer? = null
        private set

    /** How many sources [load] has loaded; a first frame says which one it belongs to. */
    var loads = 0
        private set

    private var session: AutoCloseable? = null
    private var visible: AutoCloseable? = null
    private var ticker: Job? = null
    private var lastTickAt = 0L

    /** The player, made with [events] and the session's [wrap] of it on the first call. */
    fun ensure(events: Events, wrap: (Player) -> Player): MediaPlayer {
        media?.let { return it }
        val made = factory()
        media = made
        made.setListener(object : MediaPlayer.Listener {
            override fun onChanged() {
                scope.launch { events.onChanged() }
            }

            override fun onError(failure: PlayerFailure) {
                scope.launch { events.onError(failure) }
            }

            override fun onFirstFrame() {
                // Which load it was, now: a load can render before the next one starts, and its callback runs after.
                val load = loads
                scope.launch { events.onFirstFrame(load) }
            }

            override fun onAudioUnavailable() {
                scope.launch { events.onAudioUnavailable() }
            }
        })
        made.media3?.let {
            val wrapped = wrap(it)
            mutablePlayer.value = wrapped
            session = sessions.open(wrapped)
        }
        return made
    }

    /** Loads [source], counted for [Events.onFirstFrame]. */
    fun load(source: PlayerSource) {
        val p = media ?: return
        loads++
        p.load(source)
    }

    /** Calls [onChange] when the app comes to the foreground or leaves it, from the first call until [release]. */
    fun followVisibility(onChange: (visible: Boolean) -> Unit) {
        if (visible == null) visible = visibility.observe(onChange)
    }

    val ticking: Boolean get() = ticker?.isActive == true

    /** Calls [onTick] every [TICK_MS] with the time since the last tick, until [stopTicker]. */
    fun startTicker(onTick: (dtMs: Long) -> Unit) {
        if (ticking) return
        lastTickAt = clock.elapsedMs()
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                val now = clock.elapsedMs()
                val dt = now - lastTickAt
                lastTickAt = now
                onTick(dt)
            }
        }
    }

    fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun release() {
        stopTicker()
        visible?.close()
        visible = null
        session?.close()
        session = null
        mutablePlayer.value = null
        media?.setListener(null)
        media?.release()
        media = null
    }

    companion object {
        /** The controllers' tick: the scrubber, the prompts and Live TV's status checks while it tunes. */
        const val TICK_MS = 250L
    }
}
