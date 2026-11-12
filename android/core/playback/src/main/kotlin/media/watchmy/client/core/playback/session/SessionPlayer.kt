package media.watchmy.client.core.playback.session

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.IdentityHashMap

/** What [SessionPlayer] hands to the controller: commands, and positions in file time. */
internal interface SessionControls {
    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    val filePositionMs: Long

    val fileDurationMs: Long

    val fileBufferedPositionMs: Long
}

/**
 * The player the UI renders and the media session drives. Play, pause and
 * seeks from remote keys, Assistant or the notification go through the
 * controller (so they count as input, are reported and map to file time),
 * and positions read back as file positions, which differ from player time
 * in an event playlist. What the controller has no part in (previous and
 * next, speed, repeat, changing the items) is not offered, so the
 * notification, a headset or a car can't reach the player around it.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(player: Player, private val controls: SessionControls) : ForwardingPlayer(player) {
    /** Each listener added, and the wrapper that hands it the commands as offered here. */
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun getAvailableCommands(): Player.Commands = offered(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = command !in NOT_OFFERED && super.isCommandAvailable(command)

    override fun addListener(listener: Player.Listener) {
        val wrapped = synchronized(listeners) { listeners.getOrPut(listener) { OfferedCommandsListener(listener) { offered(it) } } }
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapped = synchronized(listeners) { listeners.remove(listener) } ?: return
        super.removeListener(wrapped)
    }

    override fun play() = controls.play()

    override fun pause() = controls.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) controls.play() else controls.pause()

    // The controller loads and ends streams: a remote's prepare does nothing
    // (its play retries after an error), and its stop pauses.
    override fun prepare() = Unit

    override fun stop() = controls.pause()

    override fun seekTo(positionMs: Long) = controls.seekTo(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = controls.seekTo(positionMs)

    override fun seekBack() = controls.seekBy(-SEEK_STEP_MS)

    override fun seekForward() = controls.seekBy(SEEK_STEP_MS)

    // Play at the end starts the file again: from its start, through the controller.
    override fun seekToDefaultPosition() = controls.seekTo(0)

    override fun seekToDefaultPosition(mediaItemIndex: Int) = controls.seekTo(0)

    // In player time, "previous" would go to the start of the server's session in an
    // event playlist and "next" to its live edge; one file plays at a time.
    override fun seekToPrevious() = Unit

    override fun seekToPreviousMediaItem() = Unit

    override fun seekToNext() = Unit

    override fun seekToNextMediaItem() = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun getSeekBackIncrement(): Long = SEEK_STEP_MS

    override fun getSeekForwardIncrement(): Long = SEEK_STEP_MS

    override fun getCurrentPosition(): Long = controls.filePositionMs

    override fun getContentPosition(): Long = controls.filePositionMs

    override fun getDuration(): Long = controls.fileDurationMs

    override fun getContentDuration(): Long = controls.fileDurationMs

    override fun getBufferedPosition(): Long = controls.fileBufferedPositionMs

    override fun getContentBufferedPosition(): Long = controls.fileBufferedPositionMs

    private companion object {
        // The TV player's left/right nudge (PLAN-CLIENTS.md §5.3).
        const val SEEK_STEP_MS = 10_000L

        val NOT_OFFERED = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
        )

        fun offered(commands: Player.Commands): Player.Commands = commands.buildUpon().removeAll(*NOT_OFFERED).build()
    }
}
