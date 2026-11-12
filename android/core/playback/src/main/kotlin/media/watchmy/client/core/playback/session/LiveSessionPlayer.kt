package media.watchmy.client.core.playback.session

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.IdentityHashMap
import media.watchmy.client.model.LivePlaybackController

/** Live transport commands only: a headset/Assistant cannot seek into the rolling buffer. */
@OptIn(UnstableApi::class)
internal class LiveSessionPlayer(player: Player, private val controls: LivePlaybackController) : ForwardingPlayer(player) {
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()
    override fun getAvailableCommands(): Player.Commands = offered(super.getAvailableCommands())
    override fun isCommandAvailable(command: Int): Boolean = command !in NOT_OFFERED && super.isCommandAvailable(command)
    override fun addListener(listener: Player.Listener) {
        val wrapped = synchronized(listeners) { listeners.getOrPut(listener) { OfferedCommandsListener(listener, ::offered) } }
        super.addListener(wrapped)
    }
    override fun removeListener(listener: Player.Listener) {
        val wrapped = synchronized(listeners) { listeners.remove(listener) } ?: return
        super.removeListener(wrapped)
    }
    override fun play() = controls.play()
    override fun pause() = controls.pause()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) controls.play() else controls.pause()
    override fun stop() = controls.stop()
    override fun prepare() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Unit
    override fun seekBack() = Unit
    override fun seekForward() = Unit
    override fun seekToDefaultPosition() = controls.goLive()
    override fun seekToDefaultPosition(mediaItemIndex: Int) = controls.goLive()
    override fun seekToPrevious() = Unit
    override fun seekToPreviousMediaItem() = Unit
    override fun seekToNext() = Unit
    override fun seekToNextMediaItem() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    private companion object {
        val NOT_OFFERED = intArrayOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
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
