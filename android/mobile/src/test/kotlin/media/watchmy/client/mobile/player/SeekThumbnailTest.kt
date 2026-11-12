package media.watchmy.client.mobile.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeSeekThumbnails
import media.watchmy.client.mobile.ComposableRuns
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.UpNext
import media.watchmy.client.ui.ProvideAppServices
import media.watchmy.client.ui.components.SEEK_THUMBNAIL_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The seek thumbnail on the phone player (PlayerScrub): it shows above the
 * thumb while a finger drags the scrubber, the middle buttons make way for
 * it, it stays inside the screen, and the drag recomposes only the
 * scrubber's row and the thumbnail. Without thumbnails a drag shows the time
 * alone, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w915dp-h412dp-land")
class SeekThumbnailTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val runs = ComposableRuns()

    private val services = FakeAppServices()
    private val thumbnails = services.seekThumbnails as FakeSeekThumbnails

    private val state = PlayerUiState(
        status = PlayerStatus.Playing,
        title = "Harbor Lights",
        subtitle = "S03 E07 · The Long Dark",
        durationMs = 3_120_000,
        audio = listOf(AudioOption(1, "English · EAC3 5.1 (Default)", "en", true)),
        selectedAudio = 1,
        nextEpisode = UpNext(9102, 1308, 100, "Harbor Lights", 3, 8, "Squall Line", null),
        trickplay = FakeData.trickplay,
    )
    private val actions = ControlsActions({}, {}, {}, {}, {}, {}, null, {}, {})

    private fun show(state: PlayerUiState) = compose.setContent {
        ProvideAppServices(services) {
            PlayerControls(state, progress = { PlaybackProgress(660_000, 3_120_000, 30_000) }, rotationLocked = false, actions = actions)
        }
    }

    private val scrubber get() = compose.onNodeWithContentDescription("Seek")
    private val thumbnail get() = compose.onNodeWithTag(SEEK_THUMBNAIL_TAG)

    @Test fun `dragging shows the seek thumbnail and the middle buttons make way, until the finger lifts`() {
        show(state)
        thumbnail.assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertExists()

        scrubber.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
        }
        thumbnail.assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back 10 seconds").assertDoesNotExist()
        compose.onNodeWithContentDescription("Forward 10 seconds").assertDoesNotExist()
        compose.waitForIdle()
        assertTrue("the controls coming up fetched the sheets, and so did the drag", thumbnails.prefetches >= 2)
        assertTrue("a tile was drawn", thumbnails.loads > 0)

        scrubber.performTouchInput { up() }
        thumbnail.assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test fun `without thumbnails a drag shows the time alone and the buttons stay`() {
        show(state.copy(trickplay = null))
        scrubber.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
        }
        thumbnail.assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertExists()
        assertEquals(0, thumbnails.prefetches)
        scrubber.performTouchInput { up() }
    }

    @Test fun `the thumbnail sits above the thumb and stays inside the screen's edges`() {
        show(state)
        val track = scrubber.getBoundsInRoot()
        val screen = compose.onRoot().getBoundsInRoot()

        scrubber.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
        }
        val middle = thumbnail.getBoundsInRoot()
        assertAbout("200 dp wide and its light edge, as on the board", 204.dp, middle.right - middle.left)
        assertTrue("above the track", middle.bottom < track.top)
        val thumb = track.left + (track.right - track.left) * 0.5f + with(compose.density) { 60f.toDp() }
        assertAbout("centred on the thumb", thumb, (middle.left + middle.right) / 2, within = 4.dp)

        // The finger near the right end: the thumbnail stops 16 dp in from the edge.
        scrubber.performTouchInput { moveTo(Offset(width - 1f, centerY)) }
        assertAbout("inside the right edge", screen.right - 16.dp, thumbnail.getBoundsInRoot().right)

        scrubber.performTouchInput { moveTo(Offset(1f, centerY)) }
        assertAbout("inside the left edge", screen.left + 16.dp, thumbnail.getBoundsInRoot().left)
        scrubber.performTouchInput { up() }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun `on a tablet the thumbnail is the tablet board's size`() {
        show(state)
        scrubber.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
        }
        val tile = thumbnail.getBoundsInRoot()
        assertAbout("256 dp wide and its light edge (TabPlayerScrub)", 260.dp, tile.right - tile.left)
        scrubber.performTouchInput { up() }
    }

    private fun assertAbout(message: String, expected: Dp, actual: Dp, within: Dp = 0.5.dp) =
        assertTrue("$message: expected $expected, was $actual", abs((expected - actual).value) <= within.value)

    @Test fun `a drag recomposes the scrubber's row and the thumbnail, and nothing else`() {
        show(state)
        scrubber.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
        }
        compose.waitForIdle()
        runs.clear()

        repeat(MOVES) {
            // A pixel is about 4 s of this 52-minute file.
            scrubber.performTouchInput { moveBy(Offset(-1f, 0f)) }
            compose.waitForIdle()
        }

        assertEquals(MOVES, runs["ScrubberRow"])
        assertEquals("the time, a second or more a move", MOVES, runs["SeekTime"])
        assertTrue("a new tile every 10 s of the drag, not every move", runs["TileFrame"] < MOVES)
        assertEquals(0, runs["SeekThumbnail"])
        assertEquals(0, runs["PlayerControls"])
        assertEquals(0, runs["TopBar"])
        assertEquals(0, runs["BottomBar"])
        scrubber.performTouchInput { up() }
    }

    private companion object {
        const val MOVES = 8
    }
}
