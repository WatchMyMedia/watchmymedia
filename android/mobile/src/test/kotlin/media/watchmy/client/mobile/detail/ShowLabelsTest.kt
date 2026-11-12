package media.watchmy.client.mobile.detail

import media.watchmy.client.fake.FakeData
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone show page's own wording; the ViewModel's tests are in core:presentation. */
class ShowLabelsTest {
    @Test fun `facts and status`() {
        val show = FakeData.show(100)
        assertEquals(listOf("2022– · 3 seasons · 24 episodes", "Drama, Crime · TV-14 · ★ 7.0"), showFacts(show))
        assertEquals(listOf("2022– · 3 seasons · 24 episodes", "Drama, Crime · ★ 7.0"), showFacts(show.copy(contentRating = null)))
        assertEquals("Continuing · next Nov 5" to true, statusLine(show))
        assertEquals("Ended" to false, statusLine(FakeData.show(106)))
    }
}
