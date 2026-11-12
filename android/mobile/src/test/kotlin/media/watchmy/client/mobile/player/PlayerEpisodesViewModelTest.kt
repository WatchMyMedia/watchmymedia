package media.watchmy.client.mobile.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PlayerEpisodesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `a deep linked file resolves its season without parsing display text`() = runTest {
        val model = PlayerEpisodesViewModel(FakeLibraryRepository())
        model.load(PlayRequest(9101, title = "Unrelated display title"))
        advanceUntilIdle()
        assertEquals("Harbor Lights", model.state.value.season?.showTitle)
        assertEquals(3, model.state.value.season?.season?.number)
        assertNull(model.state.value.error)
    }

    @Test fun `a movie has no episode list`() = runTest {
        val model = PlayerEpisodesViewModel(FakeLibraryRepository())
        model.load(PlayRequest(1, title = "Movie"))
        advanceUntilIdle()
        assertNull(model.state.value.season)
        assertNull(model.state.value.error)
    }

    @Test fun `a newer playing file cancels an older lookup`() = runTest {
        val fake = FakeLibraryRepository()
        val library = object : LibraryRepository by fake {
            override suspend fun playRequest(fileId: Long): PlayRequest {
                if (fileId == 9101L) delay(1000)
                return fake.playRequest(fileId)
            }
        }
        val model = PlayerEpisodesViewModel(library)
        model.load(PlayRequest(9101, title = "First"))
        model.load(PlayRequest(1, title = "Second"))
        advanceUntilIdle()
        assertEquals(1L, model.state.value.fileId)
        assertNull(model.state.value.season)
        assertNull(model.state.value.error)
    }

    @Test fun `a season error can be retried without restarting the video`() = runTest {
        val fake = FakeLibraryRepository()
        var failed = true
        var lookups = 0
        val library = object : LibraryRepository by fake {
            override suspend fun season(showId: Long, number: Int) = if (failed) {
                lookups++
                error("Offline")
            } else {
                lookups++
                fake.season(showId, number)
            }
        }
        val model = PlayerEpisodesViewModel(library)
        model.load(PlayRequest(9101, title = "Harbor Lights", episodeContext = EpisodeContext(100, 3)))
        advanceUntilIdle()
        assertEquals("Offline", model.state.value.error)
        failed = false
        model.retry()
        advanceUntilIdle()
        assertNotNull(model.state.value.season)
        assertNull(model.state.value.error)
        assertEquals(2, lookups)
    }
}
