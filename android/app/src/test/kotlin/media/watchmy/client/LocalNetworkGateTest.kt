package media.watchmy.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkGateTest {
    @Test fun `a dismissed dialog is not a refusal`() {
        assertEquals(LocalNetworkAnswer.Granted, localNetworkAnswer(granted = true, rationaleBefore = false, rationaleAfter = false))
        // The first "Don't allow" turns the rationale flag on: "Not now", remembered.
        assertEquals(LocalNetworkAnswer.NotNow, localNetworkAnswer(granted = false, rationaleBefore = false, rationaleAfter = true))
        // The second turns it off again, and the system stops asking: settings is the only way.
        assertEquals(LocalNetworkAnswer.Blocked, localNetworkAnswer(granted = false, rationaleBefore = true, rationaleAfter = false))
        // Back or a tap outside changes nothing: asked again next time.
        assertEquals(LocalNetworkAnswer.Dismissed, localNetworkAnswer(granted = false, rationaleBefore = false, rationaleAfter = false))
        assertEquals(LocalNetworkAnswer.NotNow, localNetworkAnswer(granted = false, rationaleBefore = true, rationaleAfter = true))
    }

    @Test fun `a cast waits for the answer`() = runTest {
        val question = LocalNetworkQuestion(open = true)
        val waiting = async { question.awaitAnswer() }
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(waiting.isCompleted)
        question.answer()
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertFalse(question.open.value)
    }

    @Test fun `a cast doesn't wait when there is nothing to ask, or no one answers`() = runTest {
        val answered = LocalNetworkQuestion(open = false)
        val now = async { answered.awaitAnswer() }
        runCurrent()
        assertTrue(now.isCompleted)

        val unanswered = LocalNetworkQuestion(open = true)
        val later = async { unanswered.awaitAnswer() }
        advanceTimeBy(LocalNetworkQuestion.ANSWER_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(later.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(later.isCompleted)
    }
}
