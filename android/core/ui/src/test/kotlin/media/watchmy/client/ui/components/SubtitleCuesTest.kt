package media.watchmy.client.ui.components

import android.graphics.Typeface
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubtitleCuesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun drawsTheCuesThePlayerSends() {
        val player = CuePlayer()
        compose.setContent { SubtitleCues(player, fontSize = 18.sp, sidePadding = 48.dp, bottomPadding = 24.dp) }
        compose.onNodeWithText("The harbor lights came on one by one.").assertDoesNotExist()

        compose.runOnIdle { player.show("The harbor lights came on one by one.", "Second line") }
        compose.onNodeWithText("The harbor lights came on one by one.").assertIsDisplayed()
        compose.onNodeWithText("Second line").assertIsDisplayed()

        compose.runOnIdle { player.show("One minute in.") }
        compose.onNodeWithText("One minute in.").assertIsDisplayed()
        compose.onNodeWithText("Second line").assertDoesNotExist()

        compose.runOnIdle { player.show() }
        compose.onNodeWithText("One minute in.").assertDoesNotExist()
    }

    @Test
    fun drawsCuesAlreadyShowingAndSkipsBlankOnes() {
        val player = CuePlayer()
        player.show("Already on screen", " ")
        compose.setContent { SubtitleCues(player, fontSize = 18.sp, sidePadding = 48.dp, bottomPadding = 104.dp) }
        compose.onNodeWithText("Already on screen").assertIsDisplayed()
        compose.onNodeWithText(" ").assertDoesNotExist()
    }

    @Test
    fun drawsACueRepeatedAcrossHlsSegmentsOnce() {
        val player = CuePlayer()
        compose.setContent { SubtitleCues(player, fontSize = 18.sp, sidePadding = 48.dp, bottomPadding = 24.dp) }
        compose.runOnIdle { player.show("SRT sidecar on a remux", "SRT sidecar on a remux", "SRT sidecar on a remux") }
        compose.onAllNodesWithText("SRT sidecar on a remux").assertCountEquals(1)
    }

    @Test
    fun keepsBoldItalicsAndUnderlines() {
        val player = CuePlayer()
        val text = SpannableString("I told you never to come back.")
        text.setSpan(StyleSpan(Typeface.ITALIC), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), 7, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(UnderlineSpan(), 13, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        compose.setContent { SubtitleCues(player, fontSize = 18.sp, sidePadding = 48.dp, bottomPadding = 24.dp) }
        compose.runOnIdle { player.showCues(Cue.Builder().setText(text).build()) }

        val shown = compose.onNodeWithText("I told you never to come back.").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val styles = shown.spanStyles.map { Triple(it.start, it.end, it.item) }
        assertTrue(styles.any { (start, end, style) -> start == 0 && end == 6 && style.fontStyle == FontStyle.Italic })
        assertTrue(styles.any { (start, end, style) -> start == 7 && end == 12 && style.fontWeight == FontWeight.Bold })
        assertTrue(styles.any { (start, end, style) -> start == 13 && end == 18 && style.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun aCuePlacedAtTheTopIsDrawnAtTheTop() {
        val player = CuePlayer()
        compose.setContent { SubtitleCues(player, fontSize = 18.sp, sidePadding = 48.dp, bottomPadding = 24.dp, topPadding = 24.dp) }
        compose.runOnIdle {
            player.showCues(
                // An ASS \an8 sign, as Media3's SSA parser places it.
                Cue.Builder().setText("SIGN: Harbor Road").setLine(0.05f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START).build(),
                Cue.Builder().setText("Turn left at the harbor.").build(),
            )
        }
        val sign = compose.onNodeWithText("SIGN: Harbor Road").getUnclippedBoundsInRoot()
        val speech = compose.onNodeWithText("Turn left at the harbor.").getUnclippedBoundsInRoot()
        assertTrue("${sign.top} above ${speech.top}", sign.bottom < speech.top)
        assertTrue("near the top", sign.top < 100.dp)
    }

    @Test
    fun theLookScalesTheTextAndAnOutlineIsNotReadTwice() {
        val player = CuePlayer()
        player.show("Size check")
        var look by mutableStateOf(CaptionLook.Default)
        compose.setContent { SubtitleCues(player, fontSize = 28.sp, sidePadding = 48.dp, bottomPadding = 24.dp, look = look) }
        assertEquals(28.sp, drawnStyle("Size check").fontSize)
        compose.runOnIdle { look = CaptionLook(scale = 42f / 28f, edge = CaptionEdge.Outline, text = Color.Yellow) }
        assertEquals(42f, drawnStyle("Size check").fontSize.value, 0.01f)
        assertEquals(Color.Yellow, drawnStyle("Size check").color)
        compose.onAllNodesWithText("Size check").assertCountEquals(1)
    }

    /** The style the line [text] was laid out with. */
    private fun drawnStyle(text: String): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return layouts.single().layoutInput.style
    }

    @Test
    fun placesCuesByTheirLine() {
        fun cue(line: Float, type: Int, anchor: Int = Cue.ANCHOR_TYPE_START) = Cue.Builder().setText("x").setLine(line, type).setLineAnchor(anchor).build()
        assertFalse("Media3's usual bottom cue", placedAtTop(Cue.Builder().setText("x").build()))
        assertFalse("WebVTT's stacked bottom lines", placedAtTop(cue(-1f, Cue.LINE_TYPE_NUMBER)))
        assertTrue("WebVTT line:0", placedAtTop(cue(0f, Cue.LINE_TYPE_NUMBER)))
        assertTrue("WebVTT line:10%", placedAtTop(cue(0.1f, Cue.LINE_TYPE_FRACTION)))
        assertTrue("ASS top", placedAtTop(cue(0.05f, Cue.LINE_TYPE_FRACTION)))
        assertFalse("ASS bottom", placedAtTop(cue(0.95f, Cue.LINE_TYPE_FRACTION, Cue.ANCHOR_TYPE_END)))
        assertFalse("ASS middle", placedAtTop(cue(0.5f, Cue.LINE_TYPE_FRACTION, Cue.ANCHOR_TYPE_MIDDLE)))
        assertTrue("Ending at the middle", placedAtTop(cue(0.5f, Cue.LINE_TYPE_FRACTION, Cue.ANCHOR_TYPE_END)))
    }

    /** A player that only has cues: [show] sends them as ExoPlayer does, through onCues. */
    @OptIn(UnstableApi::class)
    private class CuePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var cues = CueGroup.EMPTY_TIME_ZERO

        fun show(vararg lines: String) = showCues(*lines.map { Cue.Builder().setText(it).build() }.toTypedArray())

        fun showCues(vararg shown: Cue) {
            cues = CueGroup(shown.toList(), 0)
            invalidateState()
        }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_GET_TEXT).build())
            .setCurrentCues(cues)
            .build()
    }
}
