package media.watchmy.client.mobile.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import media.watchmy.client.fake.FakePlaybackController
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `selecting the original episode after autoplay starts it again while resizing does not`() {
        val original = PlayRequest(1L, title = "Episode one")
        val selection = mutableStateOf(PlayerSelection(original))
        val width = mutableStateOf(800.dp)
        val controller = RecordingController()
        compose.setContent {
            Box(Modifier.size(width.value, 400.dp)) {
                StartSelectedPlayback(controller, selection.value)
            }
        }
        compose.runOnIdle {
            assertEquals(listOf(1L), controller.starts)
            // Autoplay changed the controller's file, but the last manual request is still one.
            controller.currentFile = 2L
            selection.value = selection.value.select(original)
        }
        compose.runOnIdle {
            assertEquals(listOf(1L, 1L), controller.starts)
            assertEquals(1L, controller.currentFile)
            width.value = 1200.dp
        }
        compose.runOnIdle { assertEquals(listOf(1L, 1L), controller.starts) }
    }

    private class RecordingController : PlaybackController by FakePlaybackController() {
        val starts = mutableListOf<Long>()
        var currentFile = 0L
        override fun start(request: PlayRequest) {
            currentFile = request.fileId
            starts += request.fileId
        }
    }
}
