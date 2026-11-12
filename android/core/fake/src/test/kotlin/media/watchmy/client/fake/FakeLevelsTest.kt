package media.watchmy.client.fake

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import media.watchmy.client.model.AUTO_API_LEVEL
import media.watchmy.client.model.DELETE_API_LEVEL
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.REQUESTS_API_LEVEL
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.TRAILERS_API_LEVEL
import media.watchmy.client.model.TRICKPLAY_API_LEVEL
import media.watchmy.client.model.Trickplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The fakes offer what the server in use's API level brings, and no more, as the real repositories do. */
class FakeLevelsTest {
    private val services = FakeAppServices()

    @Before
    fun fast() {
        fakeLatencyMs = 0
    }

    private fun at(level: Int) = runBlocking { services.servers.select(LivingRoom.copy(apiLevel = level)) }

    @Test
    fun `the fake servers are at the current level, with everything on`() = runBlocking {
        assertEquals(listOf(22, 22, 22), listOf(LivingRoom, OfficeMini, RemoteHome).map { it.apiLevel })
        assertTrue("rows", services.rows.supported)
        assertTrue("trailers", services.library.movie(500).trailers.isNotEmpty())
        assertTrue("favourites, search and reminders", services.live.refreshReminders() != null)
        assertTrue(services.live.capabilities().available)
        assertTrue(services.live.capabilities().guide)
        assertTrue(services.live.capabilities().cast)
        assertTrue(services.live.capabilities().recording)
        assertTrue(services.live.capabilities().canRecord)
        assertTrue(services.live.channels().isNotEmpty())
        assertEquals(FakeData.trickplay, playersThumbnails())
        val options = services.requests.options()
        assertTrue(options.supported)
        assertTrue(options.canRequest)
        assertTrue("Sam is an admin", options.delete)
        assertTrue(services.preferences.autoQuality.value)
        assertEquals(StreamQuality.Auto, services.preferences.quality.value)
    }

    @Test
    fun `below the Live TV level the established navigation remains available`() = runBlocking {
        at(10)
        assertFalse(services.live.capabilities().available)
        assertTrue(services.live.channels().isEmpty())
        assertTrue(services.requests.options().supported)
        assertTrue(services.preferences.autoQuality.value)
    }

    @Test
    fun `below the requests level there are no requests, missing episodes or Inbox`() = runBlocking {
        at(REQUESTS_API_LEVEL - 1)
        assertEquals(RequestOptions.None, services.requests.options())
        assertFalse(services.requests.options().missingEpisodes)
        assertTrue(services.requests.mine().isEmpty())
        assertTrue(services.requests.search("harbor").isEmpty())
        assertTrue(services.requests.inbox().requests.isEmpty())
    }

    @Test
    fun `below the delete level requests stay but Delete goes`() = runBlocking {
        at(DELETE_API_LEVEL - 1)
        val options = services.requests.options()
        assertTrue(options.canRequest)
        assertFalse(options.delete)
        at(DELETE_API_LEVEL)
        assertTrue(services.requests.options().delete)
    }

    @Test
    fun `Automatic quality follows the server in use`() = runBlocking {
        at(AUTO_API_LEVEL - 1)
        assertFalse(services.preferences.autoQuality.value)
        assertEquals("A saved Automatic plays at the original there", StreamQuality.Original, services.preferences.quality.value)
        services.preferences.setQuality(StreamQuality.Hd)
        assertEquals(StreamQuality.Hd, services.preferences.quality.value)
        services.preferences.setQuality(StreamQuality.Auto)
        assertEquals(StreamQuality.Original, services.preferences.quality.value)

        at(AUTO_API_LEVEL)
        assertTrue(services.preferences.autoQuality.value)
        assertEquals(StreamQuality.Auto, services.preferences.quality.value)
    }

    @Test
    fun `below the seek thumbnails' level the player has none`() = runBlocking {
        at(TRICKPLAY_API_LEVEL - 1)
        assertNull(playersThumbnails())
    }

    /** The seek thumbnails a play on the fakes has. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun playersThumbnails(): Trickplay? {
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            val player = services.playback.create()
            player.start(PlayRequest(9101, title = ""))
            return player.state.value.trickplay.also { player.release() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `on their own the fakes stand for a current server`() = runBlocking {
        assertTrue(FakeRequestRepository({ null }).options().supported)
        assertTrue(FakePreferencesRepository().autoQuality.value)
    }

    @Test
    fun `below level 14 a channel can't be cast`() = runBlocking {
        at(13)
        assertTrue(services.live.capabilities().guide)
        assertFalse(services.live.capabilities().cast)
    }

    @Test
    fun `below the trailers' level movies have none`() = runBlocking {
        at(TRAILERS_API_LEVEL - 1)
        assertTrue(services.library.movie(500).trailers.isEmpty())
        assertTrue(services.library.movie(501).trailers.isEmpty())
        at(TRAILERS_API_LEVEL)
        assertEquals(2, services.library.movie(500).trailers.size)
    }
}
