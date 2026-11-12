package media.watchmy.client.core.playback.session

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlaybackControllerFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveSessionPlayerTest {
    private val calls = mutableListOf<String>()
    private val exo = Exo()
    private val controls = object : LivePlaybackController by LivePlaybackControllerFactory.Unavailable.create() {
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun goLive() { calls += "live" }
    }
    private val player = LiveSessionPlayer(exo, controls)

    @Test fun `live media session offers transport and live edge without seek skip or speed controls`() {
        for (command in listOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            assertFalse(player.isCommandAvailable(command))
            assertFalse(player.availableCommands.contains(command))
        }
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue(player.isCommandAvailable(Player.COMMAND_STOP))
        player.seekTo(100)
        player.seekTo(0, 100)
        player.seekBack()
        player.seekForward()
        player.seekToPrevious()
        player.seekToNext()
        player.setPlaybackSpeed(2f)
        player.setPlaybackParameters(PlaybackParameters(2f))
        ShadowLooper.idleMainLooper()
        assertTrue(exo.calls.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun `remote stop releases live playback and remote default position means Go live`() {
        player.play()
        player.pause()
        player.setPlayWhenReady(true)
        player.seekToDefaultPosition()
        player.stop()
        assertEquals(listOf("play", "pause", "play", "live", "stop"), calls)
        assertTrue(exo.calls.isEmpty())
    }

    private class Exo : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("live").setMediaItem(MediaItem.fromUri("http://server/live.m3u8")).setDurationUs(60_000_000).build()))
            .setContentPositionMs(50_000)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            calls += "seek"
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            calls += "speed"
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            calls += "play"
            return Futures.immediateVoidFuture()
        }
    }
}
