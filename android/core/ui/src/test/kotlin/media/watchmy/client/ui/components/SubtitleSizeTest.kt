package media.watchmy.client.ui.components

import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.subtitleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Test

/** The person's subtitle size on top of the system's caption size, as the players draw it. */
class SubtitleSizeTest {
    @Test
    fun `each step scales the cue as the server names it`() {
        val steps = SubtitleSize.entries.associate { it.id to Math.round(it.scale * 100) / 100f }
        assertEquals(mapOf("smaller" to 0.79f, "normal" to 1f, "larger" to 1.21f, "largest" to 1.5f), steps)
    }

    @Test
    fun `phones multiply the system's caption scale by the step`() {
        // Captions on at 1.25 in Accessibility, the person at Larger: 18 sp × 1.25 × 34/28.
        val system = captionLook(enabled = true, fontScale = 1.25f, style = SYSTEM_STANDARD, standard = SYSTEM_STANDARD)
        assertEquals(1.25f * 34f / 28f, system.at(SubtitleSize.Larger).scale, 0.0001f)
        // Captions off: the step alone.
        val off = captionLook(enabled = false, fontScale = 1.25f, style = SYSTEM_STANDARD, standard = SYSTEM_STANDARD)
        assertEquals(1.5f, off.at(SubtitleSize.Largest).scale, 0.0001f)
        assertEquals(off, off.at(SubtitleSize.Normal))
    }

    @Test
    fun `the TV draws 48 sp times the step`() {
        assertEquals(48f * 22f / 28f, 48f * CaptionLook.Default.at(SubtitleSize.Smaller).scale, 0.001f)
    }

    @Test
    fun `a play's own size wins, then the person's where the server keeps one, then the TV's own`() {
        assertEquals(SubtitleSize.Largest, subtitleSizeFor(SubtitleSize.Largest, SubtitleSize.Smaller, personal = true, device = SubtitleSize.Larger))
        assertEquals(SubtitleSize.Smaller, subtitleSizeFor(null, SubtitleSize.Smaller, personal = true, device = SubtitleSize.Larger))
        assertEquals(SubtitleSize.Larger, subtitleSizeFor(null, SubtitleSize.Smaller, personal = false, device = SubtitleSize.Larger))
        assertEquals(SubtitleSize.Normal, subtitleSizeFor(null, SubtitleSize.Smaller, personal = false))
    }
}
