package media.watchmy.client.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The remote's Guide opens Live TV from any screen that doesn't take it itself (PLAN-ANDROID-REVIEW.md §6.2). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class RemoteKeysTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var latency = 0L

    @Before fun start() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) { TvApp() }
        }
        compose.waitForIdle()
    }

    @After fun stop() {
        fakeLatencyMs = latency
    }

    @Test fun `Guide on Home opens Live TV`() {
        compose.onNodeWithText("Go to channel").assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.Guide) }
        compose.waitForIdle()
        compose.onNodeWithText("Go to channel").assertExists()
    }
}
