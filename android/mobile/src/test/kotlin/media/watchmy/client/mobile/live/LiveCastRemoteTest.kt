package media.watchmy.client.mobile.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeCastService
import media.watchmy.client.fake.FakeLiveCastRemote
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.fake.FakeLivePlaybackController
import media.watchmy.client.model.AppServices
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LivePlaybackControllerFactory
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The live player as the TV's remote (the LiveCast board), on the fakes' TV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class LiveCastRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val remote = FakeLiveCastRemote(tuneMs = 0)
    private val cast = FakeCastService("Living Room TV", connected = false, live = remote)
    private val locals = mutableListOf<FakeLivePlaybackController>()
    private val services = object : AppServices by FakeAppServices() {
        override val cast = this@LiveCastRemoteTest.cast
        override val livePlayback = LivePlaybackControllerFactory { FakeLivePlaybackController(latencyMs = 0).also { locals += it } }
    }
    private val target = LiveTarget(FakeLiveChannels.first { it.number == "4.1" })
    private val open = mutableStateOf(true)

    private fun show(onTv: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme {
                    if (open.value) LivePlayerScreen(target, FakeLiveChannels, onExit = { open.value = false }, castable = true, onTv = onTv)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a TV turning up while a channel plays here stops it here and casts it`() {
        show(onTv = false)
        compose.runOnIdle { assertEquals(PlayerStatus.Playing, locals.single().state.value.status) }
        compose.onNodeWithContentDescription("Cast").assertExists()
        cast.connect()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("the tuner here is given back first", PlayerStatus.Ended, locals.single().state.value.status)
            assertEquals(listOf(target.channel.id to StreamQuality.FullHd), remote.casts)
        }
        compose.onNodeWithText("Playing on Living Room TV").assertExists()
        compose.onNodeWithText("Continue on this phone").assertExists()
    }

    @Test
    fun `the remote changes channel, pauses and brings the channel back to this phone`() {
        cast.connect()
        show(onTv = true)
        compose.runOnIdle {
            assertEquals("opened on the TV, nothing tunes here", 0, locals.size)
            assertEquals(LiveCastPhase.Playing, remote.state.value.phase)
        }
        compose.onNodeWithContentDescription("Pause the TV").performClick()
        compose.onNodeWithText("Paused on the TV · the tuner goes back in 4 min").assertExists()
        compose.onNodeWithContentDescription("Play on the TV").performClick()
        compose.onNodeWithContentDescription("Channel up on the TV, 4.2 Sleuth Hour").performClick()
        compose.runOnIdle { assertEquals("ch 4.2", "ch " + remote.state.value.number) }
        compose.onNodeWithText("Continue on this phone").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(remote.commands.contains("stop"))
            assertEquals("4.2", locals.single().state.value.target?.channel?.number)
        }
    }

    @Test
    fun `a TV that can't play Live TV says so and offers this phone`() {
        val old = FakeLiveCastRemote(receiverLive = false, tuneMs = 0)
        val oldCast = FakeCastService("Living Room TV", connected = false, live = old)
        val withOld = object : AppServices by services {
            override val cast = oldCast
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides withOld) {
                WatchMyMediaPhoneTheme { LivePlayerScreen(target, FakeLiveChannels, onExit = {}, castable = true) }
            }
        }
        compose.waitForIdle()
        oldCast.connect()
        compose.waitForIdle()
        compose.onNodeWithText("Living Room TV can’t play Live TV yet").assertExists()
        compose.runOnIdle { assertTrue("nothing was cast", old.casts.isEmpty()) }
        compose.onNodeWithText("Watch on this phone").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(PlayerStatus.Playing, locals.last().state.value.status) }
    }

    @Test
    fun `stop casting ends the session and leaves the remote`() {
        cast.connect()
        show(onTv = true)
        compose.onNodeWithText("Stop casting").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(remote.commands.contains("stop"))
            assertEquals(null, remote.state.value.deviceName)
            assertEquals(false, open.value)
        }
    }
}
