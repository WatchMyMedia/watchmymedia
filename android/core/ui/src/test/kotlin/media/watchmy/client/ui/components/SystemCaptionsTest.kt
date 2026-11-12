package media.watchmy.client.ui.components

import android.provider.Settings
import android.view.accessibility.CaptioningManager
import android.view.accessibility.CaptioningManager.CaptionStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The phone's caption settings as the players draw them: nothing changes until someone sets them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemCaptionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val standard = SYSTEM_STANDARD

    @Test
    fun `off, or on with the system's own style, keeps the players' look`() {
        val yellow = standard.copy(foreground = 0xFFFFFF00.toInt())
        assertSame(CaptionLook.Default, captionLook(enabled = false, fontScale = 2f, style = yellow, standard = standard))
        assertEquals(CaptionLook.Default, captionLook(enabled = true, fontScale = 1f, style = standard, standard = standard))
        assertEquals(CaptionLook.Default.copy(scale = 1.5f), captionLook(enabled = true, fontScale = 1.5f, style = standard, standard = standard))
        assertEquals("A style that leaves everything to the app", CaptionLook.Default, captionLook(true, 1f, CaptionStyleParts(), standard))
    }

    @Test
    fun `a chosen style brings its colours and edge`() {
        val chosen = CaptionStyleParts(
            foreground = 0xFFFFFF00.toInt(),
            background = 0x80000000.toInt(),
            window = 0xFF0000FF.toInt(),
            edgeType = CaptionStyle.EDGE_TYPE_OUTLINE,
            edgeColor = 0xFF222222.toInt(),
        )
        val look = captionLook(enabled = true, fontScale = 0.5f, style = chosen, standard = standard)
        assertEquals(0.5f, look.scale)
        assertEquals(Color(0xFFFFFF00), look.text)
        assertEquals(Color(0x80000000), look.plate)
        assertEquals(Color(0xFF0000FF), look.window)
        assertEquals(CaptionEdge.Outline, look.edge)
        assertEquals(Color(0xFF222222), look.edgeColor)

        val shadow = captionLook(true, 1f, CaptionStyleParts(foreground = 0xFFFFFFFF.toInt(), edgeType = CaptionStyle.EDGE_TYPE_DROP_SHADOW), standard)
        assertEquals(CaptionEdge.DropShadow, shadow.edge)
        assertEquals("Unset colours stay the players'", CaptionLook.Default.plate, shadow.plate)
    }

    @Test
    fun `the phone's settings are read`() {
        val context = RuntimeEnvironment.getApplication()
        var look: CaptionLook? = null
        Settings.Secure.putFloat(context.contentResolver, "accessibility_captioning_font_scale", 1.5f)
        shadowOf(context.getSystemService(CaptioningManager::class.java)).setEnabled(true)
        compose.setContent { look = rememberSystemCaptionLook() }
        compose.runOnIdle { assertEquals(1.5f, look?.scale) }
    }
}
