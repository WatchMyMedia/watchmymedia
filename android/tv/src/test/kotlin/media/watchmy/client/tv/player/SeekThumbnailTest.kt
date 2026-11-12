package media.watchmy.client.tv.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Trickplay
import media.watchmy.client.ui.ProvideAppServices
import media.watchmy.client.ui.components.SEEK_THUMBNAIL_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seek thumbnail on the TV player (PlayerScrub): left and right show the
 * target's tile above the scrubber with the step beside the time, Skip Intro
 * makes way for it, and it goes once the player gets there. Without
 * thumbnails, the scrubber alone as before. On the fakes, at a 1080p TV's
 * size; the fake player only moves when told, so nothing ticks.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SeekThumbnailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun fast() {
        fakeLatencyMs = 0
    }

    /** Playing Harbor Lights at 1:10, in the intro, with or without [trickplay]. */
    private fun play(trickplay: Trickplay?): FakePlaybackController {
        val player = FakePlaybackController(CoroutineScope(StandardTestDispatcher()), trickplay = trickplay)
        compose.setContent {
            ProvideAppServices(FakeAppServices(tv = true)) {
                PlayerScreen(PlayRequest(9101, startMs = 70_000, title = ""), onExit = {}, controller = player)
            }
        }
        compose.runOnIdle {
            player.play()
            player.seekTo(70_000)
        }
        compose.waitForIdle()
        return player
    }

    private val thumbnail get() = compose.onNodeWithTag(SEEK_THUMBNAIL_TAG)

    @Test fun `a press shows the target's tile with its step, and Skip Intro makes way until the player gets there`() {
        val player = play(FakeData.trickplay)
        compose.onNodeWithText("Skip Intro").assertIsDisplayed()
        thumbnail.assertDoesNotExist()

        // Held down: the tile at the target above the scrubber, and no Skip.
        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight) }
        compose.waitForIdle()
        thumbnail.assertIsDisplayed()
        compose.onNodeWithText("Skip Intro").assertDoesNotExist()
        val tile = thumbnail.getBoundsInRoot()
        val track = compose.onNodeWithContentDescription("Position", substring = true).getBoundsInRoot()
        assertTrue("above the scrubber", tile.bottom < track.top)

        // Up: the seek goes, and once the player is there the scrubber is plain again.
        compose.onRoot().performKeyInput { keyUp(Key.DirectionRight) }
        compose.waitForIdle()
        thumbnail.assertDoesNotExist()
        compose.onNodeWithText("Skip Intro").assertIsDisplayed()
        assertEquals(80_000L, player.progress.value.positionMs)
    }

    @Test fun `without thumbnails the remote seeks as before`() {
        play(trickplay = null)
        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight) }
        compose.waitForIdle()
        thumbnail.assertDoesNotExist()
        compose.onNodeWithText("Skip Intro").assertIsDisplayed()
        compose.onRoot().performKeyInput { keyUp(Key.DirectionRight) }
    }
}
