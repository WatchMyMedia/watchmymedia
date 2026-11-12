package media.watchmy.client.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.noContent
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminderState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Favourites, search and reminders against the server (PLAN-LIVE-TV-GUIDE.md §5.7), and nothing asked below API level 15. */
class LivePersonalTest {
    private val h = Harness()
    private val server get() = h.server
    private val channel = LiveChannel("channel-a", "source-a", "4.1", "NFLD-HD")

    @After fun close() = h.close()

    private suspend fun signInAt(level: Int) {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.5.3","apiLevel":$level,"setupRequired":false,"turnstile":false}"""))
        h.services.servers.select(h.testServer.copy(apiLevel = level))
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.servers.current.first { it?.server?.apiLevel == level } }
    }

    @Test fun `older servers are asked nothing about favourites, search or reminders`() = runBlocking {
        signInAt(14)
        h.services.live.setFavourite(channel, true)
        assertTrue(h.services.live.search("harbor").isEmpty())
        assertNull(h.services.live.refreshReminders())
        assertNull(h.services.live.remind(LiveProgramme("p1", 0, 1, "Station 9")))
        h.services.live.forget("r1")
        h.services.live.setReminderMinutes(10)
        assertNull(h.services.live.reminders.value)
        assertTrue(server.requests.none { it.path!!.contains("favourite") || it.path!!.contains("search") || it.path!!.contains("reminders") })
        assertTrue(server.requests("PUT /api/me/preferences").isEmpty())
    }

    @Test fun `a star is the person's own and the channels say which they starred`() = runBlocking {
        signInAt(15)
        server.on("PUT /api/live-tv/channels/channel-a/favourite", noContent())
        server.on("DELETE /api/live-tv/channels/channel-a/favourite", noContent())
        h.services.live.setFavourite(channel, true)
        h.services.live.setFavourite(channel, false)
        assertEquals(1, server.requests("PUT /api/live-tv/channels/channel-a/favourite").size)
        assertEquals(1, server.requests("DELETE /api/live-tv/channels/channel-a/favourite").size)
        server.always("GET /api/live-tv/channels", json("""{"items":[${CHANNEL.replace("}", ",\"favourite\":true}")},${CHANNEL.replace("channel-a", "channel-b")}]}"""))
        assertEquals(listOf(true, false), h.services.live.channels().map { it.favourite })
    }

    @Test fun `search sends the words, keeps two characters and reads each programme with its channel`() = runBlocking {
        signInAt(15)
        assertTrue(h.services.live.search(" h ").isEmpty())
        assertTrue(server.requests("GET /api/live-tv/guide/search").isEmpty())
        server.on("GET /api/live-tv/guide/search", json("""{"items":[{"channel":$CHANNEL,"programme":${STATION.replace("}", ",\"reminder\":true}")}},{"channel":${CHANNEL.replace("\"enabled\":true", "\"enabled\":false")},"programme":$STATION}]}"""))
        val hits = h.services.live.search("  harbor fire ")
        val asked = server.last("GET /api/live-tv/guide/search").requestUrl!!
        assertEquals("harbor fire", asked.queryParameter("q"))
        assertEquals("50", asked.queryParameter("limit"))
        assertEquals(1, hits.size)
        assertEquals("Station 9", hits.single().programme.title)
        assertTrue(hits.single().programme.reminder)
        assertEquals("4.1", hits.single().channel.number)
    }

    @Test fun `reminders are read, set, moved, gone and removed, with the lead time`() = runBlocking {
        signInAt(15)
        server.on("GET /api/live-tv/reminders", json("""{"items":[$GONE,$SET,{"id":"bad","number":"4.1","programmeId":"x","start":"nope","end":"nope","title":"Bad","state":"set"}],"leadMinutes":10}"""))
        val read = h.services.live.refreshReminders()!!
        assertEquals(10, read.leadMinutes)
        assertEquals(listOf("r-set", "r-gone"), read.items.map { it.id })
        assertEquals(LiveReminderState.Gone, read.items[1].state)
        assertNull(read.items[1].channelId)
        assertEquals("S4 E12 · The Harbor Fire", read.items[0].episodeLine())
        assertEquals(read, h.services.live.reminders.value)

        server.on("PUT /api/live-tv/reminders", json(SET.replace("r-set", "r-new").replace("\"p2\"", "\"p3\"").replace("2026-11-02T21:00:00Z", "2026-11-02T20:30:00Z").replace("\"set\"", "\"moved\"")))
        val set = h.services.live.remind(LiveProgramme("p3", 0, 1, "Station 9"))!!
        assertTrue(server.last("PUT /api/live-tv/reminders").body.readUtf8().contains("\"programmeId\":\"p3\""))
        assertEquals(LiveReminderState.Moved, set.state)
        assertEquals(listOf("r-new", "r-set", "r-gone"), h.services.live.reminders.value!!.items.map { it.id })

        server.on("DELETE /api/live-tv/reminders/r-set", noContent())
        server.on("DELETE /api/live-tv/reminders/r-gone", problem(404, "No such reminder."))
        h.services.live.forget("r-set")
        // Already gone elsewhere: the list loses it all the same.
        h.services.live.forget("r-gone")
        assertEquals(listOf("r-new"), h.services.live.reminders.value!!.items.map { it.id })

        server.on("PUT /api/me/preferences", json("""{"skip":{},"autoplayNext":true,"liveTVReminderMinutes":15}"""))
        h.services.live.setReminderMinutes(15)
        assertTrue(server.last("PUT /api/me/preferences").body.readUtf8().contains("\"liveTVReminderMinutes\":15"))
        assertEquals(15, h.services.live.reminders.value!!.leadMinutes)
    }

    @Test fun `another account's reminders go with its sign-in`() = runBlocking {
        signInAt(15)
        server.on("GET /api/live-tv/reminders", json("""{"items":[$SET],"leadMinutes":5}"""))
        h.services.live.refreshReminders()
        server.on("POST /api/auth/login", json(TestServer.SIGN_IN.replace("tok-viewer", "new-sign-in-token").replace("86e0d90b273fc14a", "new-sign-in")))
        h.services.auth.signIn("viewer", "fixture-viewer-1")
        withTimeout(5_000) { h.services.live.reminders.first { it == null } }
        assertFalse(h.services.live.reminders.value != null)
    }

    @Test fun `guide replies mark favourites and reminders`() = runBlocking {
        signInAt(15)
        val guide = """{"start":"2026-11-02T18:00:00Z","end":"2026-11-03T00:00:00Z","state":{"enabled":true,"stale":false},"channels":[{"id":"channel-a","sourceId":"source-a","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"listings":true,"favourite":true,"programmes":[${STATION.replace("}", ",\"reminder\":true}")}]}]}"""
        server.always("GET /api/live-tv/guide", json(guide))
        val start = Instant.parse("2026-11-02T20:00:00Z").toEpochMilli()
        h.now = start
        val row = h.services.live.guide(start, start + 3 * 3_600_000L)!!.rows.single()
        assertTrue(row.channel.favourite)
        assertTrue(row.programmes.single().reminder)
    }

    private companion object {
        const val CHANNEL = """{"id":"channel-a","sourceId":"source-a","number":"4.1","name":"NFLD-HD","enabled":true,"available":true}"""
        const val STATION = """{"id":"p2","start":"2026-11-02T21:00:00Z","end":"2026-11-02T22:00:00Z","title":"Station 9","episodeTitle":"The Harbor Fire","season":4,"episode":12}"""
        const val SET = """{"id":"r-set","channelId":"channel-a","number":"4.1","channelName":"NFLD-HD","programmeId":"p2","start":"2026-11-02T21:00:00Z","end":"2026-11-02T22:00:00Z","title":"Station 9","episodeTitle":"The Harbor Fire","season":4,"episode":12,"state":"set"}"""
        const val GONE = """{"id":"r-gone","number":"11.1","programmeId":"p9","start":"2026-11-03T22:00:00Z","end":"2026-11-03T23:00:00Z","title":"Great Rail Trips","state":"gone"}"""
    }
}
