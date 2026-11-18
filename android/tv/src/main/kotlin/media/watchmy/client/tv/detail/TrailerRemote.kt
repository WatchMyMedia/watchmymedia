package media.watchmy.client.tv.detail

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.KeyEvent
import androidx.core.content.edit
import media.watchmy.client.ui.PlayerKey
import media.watchmy.client.ui.playerKey

/**
 * How the remote drives YouTube's player on the TV's trailer screen
 * (PLAN-TV-TRAILERS.md §4.3). Both ways use only what YouTube documents;
 * the check on a real Google TV (TV0) decides which one stays.
 */
enum class TrailerRemote {
    /**
     * YouTube's keyboard controls: the WebView holds the focus, on the
     * player's frame, and the screen turns each remote key into the key
     * YouTube documents for it (k plays and pauses, j and l jump 10 s).
     */
    Keyboard,

    /**
     * YouTube's IFrame API: the WebView never takes the focus, and the
     * screen calls `playVideo`, `pauseVideo` and `seekTo` in the page.
     */
    Api,
}

/** What a remote key does while YouTube's player is on screen: PLAN-TV-TRAILERS.md §4.3's table. */
enum class TrailerKey {
    /** Select (centre) and Play/Pause: play or pause. */
    Toggle,

    /** The remote's own Play: play when paused. */
    Play,

    /** The remote's own Pause: pause when playing. */
    Pause,

    /** Left and Rewind: back 10 s. */
    Back10,

    /** Right and Fast-forward: ahead 10 s. */
    Ahead10,

    /** Back: leave for the movie page. Always the screen's own, never the player's. */
    Leave,

    /** Up and Down: nothing (YouTube's player would change its volume). */
    Swallow,

    /** Any other key: left alone, so volume, Home and Guide work as anywhere else. */
    Pass,
}

/** The trailer screen's [TrailerKey] for [keyCode], from the same remote and keyboard keys as the players ([playerKey]). */
fun trailerKey(keyCode: Int): TrailerKey = when (playerKey(keyCode)) {
    PlayerKey.Centre, PlayerKey.PlayPause -> TrailerKey.Toggle
    PlayerKey.Play -> TrailerKey.Play
    PlayerKey.Pause -> TrailerKey.Pause
    PlayerKey.Left, PlayerKey.Rewind -> TrailerKey.Back10
    PlayerKey.Right, PlayerKey.FastForward -> TrailerKey.Ahead10
    PlayerKey.Up, PlayerKey.Down -> TrailerKey.Swallow
    PlayerKey.Back -> TrailerKey.Leave
    else -> TrailerKey.Pass
}

/** How far Left and Right (and Rewind and Fast-forward) jump: YouTube's own j and l. */
const val TRAILER_SEEK_SECONDS = 10

/**
 * The key YouTube's keyboard controls take for [key] ([TrailerRemote.Keyboard]):
 * k plays and pauses, j goes back 10 s and l ahead 10 s. Play and Pause
 * are k only when they change something ([playing]); null sends nothing.
 */
fun youTubeKeyCode(key: TrailerKey, playing: Boolean): Int? = when (key) {
    TrailerKey.Toggle -> KeyEvent.KEYCODE_K
    TrailerKey.Play -> KeyEvent.KEYCODE_K.takeIf { !playing }
    TrailerKey.Pause -> KeyEvent.KEYCODE_K.takeIf { playing }
    TrailerKey.Back10 -> KeyEvent.KEYCODE_J
    TrailerKey.Ahead10 -> KeyEvent.KEYCODE_L
    TrailerKey.Leave, TrailerKey.Swallow, TrailerKey.Pass -> null
}

/**
 * The page's call for [key] ([TrailerRemote.Api]), through YouTube's IFrame
 * API (`trailerPage`); null calls nothing.
 */
fun trailerScript(key: TrailerKey): String? = when (key) {
    TrailerKey.Toggle -> "toggleTrailer()"
    TrailerKey.Play -> "playTrailer()"
    TrailerKey.Pause -> "pauseTrailer()"
    TrailerKey.Back10 -> "seekBy(-$TRAILER_SEEK_SECONDS)"
    TrailerKey.Ahead10 -> "seekBy($TRAILER_SEEK_SECONDS)"
    TrailerKey.Leave, TrailerKey.Swallow, TrailerKey.Pass -> null
}

/**
 * Which [TrailerRemote] the trailer screen uses: [TrailerRemote.Keyboard],
 * unless a debug build was switched with adb (`TrailerRemoteSwitch` in
 * tv/src/debug; android/README.md), for TV0 to try both without a rebuild.
 * Release builds never read the setting.
 */
internal object TrailerRemoteSetting {
    const val PREFS = "trailer-remote"
    const val KEY = "remote"

    /** Reads storage in a debug build: call it off the main thread. */
    fun read(context: Context): TrailerRemote {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return TrailerRemote.Keyboard
        return parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)) ?: TrailerRemote.Keyboard
    }

    fun write(context: Context, remote: TrailerRemote) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, remote.name) }

    /** "keyboard" or "api", in any case. */
    fun parse(name: String?): TrailerRemote? = TrailerRemote.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
