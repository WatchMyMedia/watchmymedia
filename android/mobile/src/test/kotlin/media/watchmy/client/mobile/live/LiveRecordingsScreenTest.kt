package media.watchmy.client.mobile.live

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.RecordingSection
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class LiveRecordingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val services = FakeAppServices()
    private var latency = 0L
    @Before fun setup() { latency = fakeLatencyMs; fakeLatencyMs = 0 }
    @After fun teardown() { fakeLatencyMs = latency }

    private fun open(onPlay: (PlayRequest) -> Unit = {}) {
        val channels = runBlocking { services.live.channels() }
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { LiveRecordingsScreen(channels, onBack = {}, onPlay = onPlay) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `watch from the start retains a recording identity instead of inventing a library file`() {
        var play: PlayRequest? = null
        open { play = it }
        compose.onNodeWithText("Watch from the start").performClick()
        assertEquals("fake-recording-now", play?.recordingId)
        assertEquals(0L, play?.fileId)
    }

    @Test fun `manual form validates before submitting and remains open for correction`() {
        open()
        compose.onNodeWithText("New recording").performClick()
        compose.onNodeWithText("Record").performClick()
        compose.onNode(hasText("Give the recording a name.") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("Name").assertExists()
    }

    private fun programme(series: Boolean): LiveProgramme {
        val now = System.currentTimeMillis()
        val guide = runBlocking { services.live.guide(now, now + 86_400_000) }!!
        return guide.rows.flatMap { it.programmes }.first { it.startMs > now + 600_000 && it.offersSeries == series && it.recording == null }
    }

    private fun controls(programme: LiveProgramme) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                WatchMyMediaPhoneTheme { RecordControls(programme) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `a programme without a series records straight away`() {
        val film = programme(series = false)
        controls(film)
        compose.onNodeWithText("Record").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Just this episode").assertDoesNotExist()
        val upcoming = runBlocking { services.live.recordings(RecordingSection.Upcoming).items + services.live.recordings(RecordingSection.Conflicts).items }
        assertTrue(upcoming.any { it.programmeId == film.id && !it.series })
    }

    @Test fun `a programme of a series asks which episodes to record`() {
        controls(programme(series = true))
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Just this episode").assertExists()
        compose.onNodeWithText("Every new episode").assertExists()
    }

    @Test fun `older servers do not offer recording actions`() {
        runBlocking { services.servers.select(LivingRoom.copy(apiLevel = 18)) }
        open()
        compose.onNodeWithText("New recording").assertDoesNotExist()
    }
}
