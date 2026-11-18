package media.watchmy.client.tv.detail

import media.watchmy.client.ui.PlayerKey
import media.watchmy.client.ui.playerKey

/**
 * What a remote key does on the TV's trailer screen: PLAN-TV-TRAILERS.md
 * §4.3's table, through YouTube's IFrame API ([trailerScript]). TV0 found
 * that YouTube's keyboard controls do nothing in the embedded player, on a
 * Google TV Streamer as on an emulator, so the API is the only way. While
 * a trailer is paused, the screen's own pause screen takes the player's
 * place, and Select and Play show the player again before it plays.
 */
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

/** How far Left and Right (and Rewind and Fast-forward) jump. */
const val TRAILER_SEEK_SECONDS = 10

/**
 * The page's call for [key], through YouTube's IFrame API (`trailerPage`);
 * null calls nothing. The jumps return where the player went
 * (`parseTrailerTime`), for the pause screen's time.
 */
fun trailerScript(key: TrailerKey): String? = when (key) {
    TrailerKey.Toggle -> "toggleTrailer()"
    TrailerKey.Play -> "playTrailer()"
    TrailerKey.Pause -> "pauseTrailer()"
    TrailerKey.Back10 -> "seekBy(-$TRAILER_SEEK_SECONDS)"
    TrailerKey.Ahead10 -> "seekBy($TRAILER_SEEK_SECONDS)"
    TrailerKey.Leave, TrailerKey.Swallow, TrailerKey.Pass -> null
}
