package media.watchmy.client.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.size.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The size artwork is decoded at: the box it is
 * drawn in, or the larger [Artwork] decodeSize of art that grows after it is
 * first laid out, such as a TV row's cards when the row takes focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class ArtworkDecodeSizeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val asked = CopyOnWriteArrayList<Size>()

    @Before fun recordSizes() {
        // Records the size each request asks for, then fails it: nothing is fetched.
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(compose.activity).components {
                add(Interceptor { chain -> asked += chain.size; ErrorResult(null, chain.request, IllegalStateException("not fetched")) })
            }.build(),
        )
    }

    @After fun resetLoader() = SingletonImageLoader.reset()

    @Test fun `art is decoded at the size it's drawn, or at the larger size it grows to`() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                Artwork("https://art.invalid/drawn.jpg", "Drawn", null, Modifier.size(90.dp, 135.dp))
                Artwork("https://art.invalid/grows.jpg", "Grows", null, Modifier.size(90.dp, 135.dp), decodeSize = DpSize(135.dp, 202.dp))
            }
        }
        compose.waitUntil(5_000) { asked.size >= 2 }
        // At mdpi a dp is a pixel.
        assertEquals(setOf(Size(90, 135), Size(135, 202)), asked.toSet())
    }
}
