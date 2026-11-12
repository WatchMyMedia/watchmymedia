package media.watchmy.client.core.playback.session

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionPlayerTest {
    private val exo = Exo()
    private val controls = Controls()
    private val player = SessionPlayer(exo, controls)

    @Test
    fun `previous, next and speed are not offered and never reach the player`() {
        for (command in listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            assertFalse(player.isCommandAvailable(command))
            assertFalse(player.availableCommands.contains(command))
        }
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_BACK))

        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.setPlaybackSpeed(2f)
        player.setPlaybackParameters(PlaybackParameters(1.5f))
        ShadowLooper.idleMainLooper()
        assertEquals(emptyList<String>(), exo.calls)
        assertEquals(emptyList<String>(), controls.calls)
    }

    @Test
    fun `play at the end starts the file again through the controller`() {
        exo.phase = Player.STATE_ENDED
        Util.handlePlayButtonAction(player)
        assertEquals(listOf("seekTo 0", "play"), controls.calls)
        assertEquals(emptyList<String>(), exo.calls)
    }

    @Test
    fun `listeners hear the commands as offered, and every other event`() {
        val heard = mutableListOf<String>()
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                heard += "commands next=${availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)} back=${availableCommands.contains(Player.COMMAND_SEEK_BACK)}"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                heard += "state $playbackState"
            }
        }
        player.addListener(listener)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_BACK))
        exo.withoutSeekBack()
        ShadowLooper.idleMainLooper()
        exo.phase = Player.STATE_ENDED
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("commands next=false back=false", "state ${Player.STATE_ENDED}"), heard)

        player.removeListener(listener)
        exo.phase = Player.STATE_READY
        ShadowLooper.idleMainLooper()
        assertEquals(2, heard.size)
    }

    @Test
    fun `the listener wrapper passes on every event Player_Listener has`() {
        val missing = Player.Listener::class.java.methods.filter { m ->
            runCatching { OfferedCommandsListener::class.java.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure
        }
        assertEquals(emptyList<String>(), missing.map { it.name })
    }

    /** ExoPlayer's part: one file, a minute in, every command; records what reaches it. */
    private class Exo : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        private var commands = Player.Commands.Builder().addAllCommands().build()

        var phase: Int = Player.STATE_READY
            set(value) {
                field = value
                invalidateState()
            }

        fun withoutSeekBack() {
            commands = commands.buildUpon().remove(Player.COMMAND_SEEK_BACK).build()
            invalidateState()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(MediaItemData.Builder("file").setMediaItem(MediaItem.fromUri("http://server/master.m3u8")).setDurationUs(3_000_000_000).build()))
            .setContentPositionMs(60_000)
            .setPlaybackState(phase)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            calls += "seek $mediaItemIndex $positionMs"
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            calls += "speed ${playbackParameters.speed}"
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            calls += "playWhenReady $playWhenReady"
            return Futures.immediateVoidFuture()
        }
    }

    private class Controls : SessionControls {
        val calls = mutableListOf<String>()

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seekTo $positionMs"
        }

        override fun seekBy(deltaMs: Long) {
            calls += "seekBy $deltaMs"
        }

        override val filePositionMs = 60_000L
        override val fileDurationMs = 3_000_000L
        override val fileBufferedPositionMs = 90_000L
    }
}
