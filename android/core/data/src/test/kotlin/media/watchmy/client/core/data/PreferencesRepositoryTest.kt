package media.watchmy.client.core.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import androidx.datastore.preferences.core.stringPreferencesKey
import media.watchmy.client.model.AUDIO_LANGUAGES_API_LEVEL
import media.watchmy.client.model.AUTO_API_LEVEL
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreferencesRepositoryTest {
    private val h = Harness()
    private val server get() = h.server
    private val prefs get() = h.services.preferences

    @After
    fun tearDown() = h.close()

    /**
     * Signs in and waits until the load the sign-in starts has reached the
     * server, so it can't start after a change lands and put back this
     * server's fixed preferences (a real server would answer with the change).
     */
    private suspend fun signIn() {
        h.signIn()
        withTimeout(5_000) { while (server.requests("GET /api/me/preferences").isEmpty()) delay(5) }
    }

    @Test
    fun `refresh reads skip modes and autoplay, ignoring kinds it does not know`() = runBlocking {
        signIn()
        server.always(
            "GET /api/me/preferences",
            json("""{"skip":{"intro":"auto-delay","recap":"none","credits":"auto","preview":"ask","commercial":"none","sponsor":"auto"},"autoplayNext":false}"""),
        )
        prefs.refresh()
        val p = prefs.playback.value
        assertEquals(SkipMode.AutoDelay, p.skipMode(SegmentKind.Intro))
        assertEquals(SkipMode.None, p.skipMode(SegmentKind.Recap))
        assertEquals(SkipMode.Auto, p.skipMode(SegmentKind.Credits))
        assertEquals(SkipMode.Ask, p.skipMode(SegmentKind.Preview))
        assertFalse(p.autoplayNext)
    }

    @Test
    fun `changes are sent as partial updates`() = runBlocking {
        signIn()
        server.on(
            "PUT /api/me/preferences",
            json("""{"skip":{"intro":"auto","recap":"ask","credits":"ask","preview":"none","commercial":"none"},"autoplayNext":true}"""),
            json("""{"skip":{"intro":"auto","recap":"ask","credits":"ask","preview":"none","commercial":"none"},"autoplayNext":false}"""),
        )
        prefs.setSkip(SegmentKind.Intro, SkipMode.Auto)
        val skipBody = h.body(server.requests("PUT /api/me/preferences")[0])
        assertEquals("auto", skipBody["skip"]!!.jsonObject["intro"]!!.jsonPrimitive.content)
        assertEquals(1, skipBody["skip"]!!.jsonObject.size)
        assertEquals(SkipMode.Auto, prefs.playback.value.skipMode(SegmentKind.Intro))

        prefs.setAutoplayNext(false)
        val autoplayBody = h.body(server.requests("PUT /api/me/preferences")[1])
        assertFalse(autoplayBody["autoplayNext"]!!.jsonPrimitive.boolean)
        assertFalse(prefs.playback.value.autoplayNext)
    }

    @Test
    fun `a load sent while a change is on its way leaves it to the change's reply`() = runBlocking {
        signIn()
        server.on(
            "PUT /api/me/preferences",
            json("""{"skip":{"intro":"auto","recap":"ask","credits":"ask","preview":"none","commercial":"none"},"autoplayNext":true}"""),
        )
        // The load gets the preferences from before the change, as from a server that reads them first.
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { prefs.setSkip(SegmentKind.Intro, SkipMode.Auto) }
        prefs.refresh()
        saving.join()
        assertEquals(SkipMode.Auto, prefs.playback.value.skipMode(SegmentKind.Intro))
    }

    @Test
    fun `a refused change rolls back`() = runBlocking {
        signIn()
        // Compare against the acknowledged server values, including its explicit Ads choice.
        prefs.refresh()
        server.on("PUT /api/me/preferences", problem(422, "unknown skip mode"))
        val before = prefs.playback.value
        try {
            prefs.setSkip(SegmentKind.Credits, SkipMode.AutoDelay)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(422, e.status)
        }
        assertEquals(before, prefs.playback.value)
    }

    @Test
    fun `subtitle writes stay ordered even when the first reply is slow`() = runBlocking {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.9.0","apiLevel":18,"setupRequired":false,"turnstile":false}"""))
        signIn()
        withTimeout(5_000) { while (h.services.servers.current.value?.server?.apiLevel != 18) delay(5) }
        val first = media.watchmy.client.model.SubtitlePreferences(listOf("en"), media.watchmy.client.model.SubtitleMode.On)
        val second = first.copy(languages = listOf("es", "en"))
        fun reply(languages: String) = json("""{"skip":{},"autoplayNext":true,"subtitles":{"languages":$languages,"mode":"on","automaticDownload":false}}""")
        server.on("PUT /api/me/preferences", reply("[\"en\"]"), reply("[\"es\",\"en\"]"))
        val held = server.hold("PUT /api/me/preferences")
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { prefs.setSubtitles(first) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { held.awaitArrival() }
        val newer = launch(start = CoroutineStart.UNDISPATCHED) { prefs.setSubtitles(second) }
        assertEquals(1, server.requests("PUT /api/me/preferences").size)
        // A refresh reads the old state while a write is outstanding, but
        // its delayed reply arrives after both writes have been acknowledged.
        val refreshGate = server.hold("GET /api/me/preferences")
        val refreshing = launch(start = CoroutineStart.UNDISPATCHED) { prefs.refresh() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { refreshGate.awaitArrival() }
        held.release()
        saving.join(); newer.join()
        refreshGate.release()
        refreshing.join()
        assertEquals(second, prefs.playback.value.subtitles)
        assertEquals(2, server.requests("PUT /api/me/preferences").size)
        // A fresh repository can apply the last acknowledged settings before any network work.
        val cached = LocalStore.decode(h.dataStore.data.first())
        val local = LocalStore(h.dataStore, h.scope, PlainSealer, cached)
        val reopened = RealPreferencesRepository(local, Http(okhttp3.OkHttpClient(), local, "test"))
        assertEquals(second, reopened.playback.value.subtitles)
        local.updateAndSave { stored -> stored.withServer("srv-test") { it.copy(baseUrl = "https://other.example") } }
        reopened.reset()
        assertEquals(media.watchmy.client.model.SubtitlePreferences(), reopened.playback.value.subtitles)
    }

    @Test
    fun `defaults until loaded, and the quality is kept on the device`() = runBlocking {
        assertEquals(PlaybackPreferences(), prefs.playback.value)
        assertEquals(StreamQuality.Original, prefs.quality.value)
        prefs.setQuality(StreamQuality.Hd)
        assertEquals(StreamQuality.Hd, prefs.quality.value)
        assertEquals(StreamQuality.Hd, h.reopen().preferences.quality.value)
    }

    @Test
    fun `Automatic is the default where the server can do it, and a saved one plays as Original where it can't`() = runBlocking {
        assertFalse("no server in use", prefs.autoQuality.value)
        assertEquals(StreamQuality.Original, prefs.quality.value)
        h.services.servers.select(h.testServer.copy(apiLevel = AUTO_API_LEVEL))
        assertTrue(prefs.autoQuality.value)
        assertEquals("never picked", StreamQuality.Auto, prefs.quality.value)

        h.services.servers.select(h.testServer.copy(apiLevel = AUTO_API_LEVEL - 1))
        assertFalse(prefs.autoQuality.value)
        assertEquals(StreamQuality.Original, prefs.quality.value)
        prefs.setQuality(StreamQuality.Auto)
        assertEquals("kept, but plays as Original here", StreamQuality.Original, prefs.quality.value)
        h.services.servers.select(h.testServer.copy(apiLevel = AUTO_API_LEVEL))
        assertEquals(StreamQuality.Auto, h.reopen().preferences.quality.value)

        prefs.setQuality(StreamQuality.Low)
        assertEquals(StreamQuality.Low, h.reopen().preferences.quality.value)
    }

    @Test
    fun `a quality this app doesn't know plays as Original`() = runBlocking {
        h.services.servers.select(h.testServer.copy(apiLevel = AUTO_API_LEVEL))
        h.editStorage { it[stringPreferencesKey("quality")] = "1080p-8" }
        assertEquals(StreamQuality.Original, h.reopen().preferences.quality.value)
    }

    @Test
    fun `this device's other settings start at their defaults and are kept on the device`() = runBlocking {
        assertEquals(DevicePlayback(), prefs.device.value)
        prefs.setMobileQuality(StreamQuality.Sd)
        prefs.setMatchFrameRate(true)
        prefs.setAudioPassthrough(AudioPassthrough.Off)
        prefs.setSubtitleSize(SubtitleSize.Largest)
        val saved = DevicePlayback(StreamQuality.Sd, matchFrameRate = true, audioPassthrough = AudioPassthrough.Off, subtitleSize = SubtitleSize.Largest)
        assertEquals(saved, prefs.device.value)
        assertEquals(saved, h.reopen().preferences.device.value)
        assertEquals("the Default quality is its own", StreamQuality.Original, h.services.preferences.quality.value)
        assertTrue("never sent to the server", server.requests("PUT /api/me/preferences").isEmpty())

        h.services.preferences.setMobileQuality(null)
        h.services.preferences.setMatchFrameRate(false)
        h.services.preferences.setAudioPassthrough(AudioPassthrough.Auto)
        h.services.preferences.setSubtitleSize(SubtitleSize.Normal)
        assertEquals(DevicePlayback(), h.reopen().preferences.device.value)
    }

    @Test
    fun `Automatic on mobile data plays as Original where the server can't do it, and values this app doesn't know as the defaults`() = runBlocking {
        h.services.servers.select(h.testServer.copy(apiLevel = AUTO_API_LEVEL))
        prefs.setMobileQuality(StreamQuality.Auto)
        assertEquals(StreamQuality.Auto, prefs.device.value.mobileQuality)
        h.services.servers.select(h.testServer.copy(apiLevel = AUTO_API_LEVEL - 1))
        assertEquals("kept, but plays as Original here", StreamQuality.Original, prefs.device.value.mobileQuality)

        // The server change is written behind; editStorage lets it land before writing over it.
        h.editStorage {
            it[stringPreferencesKey("mobile_quality")] = "1080p-8"
            it[stringPreferencesKey("audio_passthrough")] = "Bitstream"
            it[stringPreferencesKey("subtitle_size")] = "Huge"
        }
        val read = h.reopen().preferences.device.value
        assertEquals(StreamQuality.Original, read.mobileQuality)
        assertEquals(AudioPassthrough.Auto, read.audioPassthrough)
        assertEquals(SubtitleSize.Normal, read.subtitleSize)
    }

    @Test
    fun `signing in brings the server's API level up to date`() = runBlocking {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"1.2.0","apiLevel":3,"setupRequired":false,"turnstile":false}"""))
        h.signIn()
        withTimeout(5_000) { while (!prefs.autoQuality.value) delay(5) }
        assertEquals(StreamQuality.Auto, prefs.quality.value)
        assertEquals("1.2.0", h.services.servers.current.value?.server?.version)
        // The store publishes memory before writing it behind. Reopening must
        // wait for that write, not just for the capability flow above.
        withTimeout(5_000) {
            h.dataStore.data.first { LocalStore.decode(it).current?.apiLevel == AUTO_API_LEVEL }
        }
        assertEquals(AUTO_API_LEVEL, h.reopen().servers.current.value?.server?.apiLevel)
    }

    /** Signs in to a server at [level], once the app knows the level. */
    private suspend fun signInAt(level: Int) {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"0.9.0","apiLevel":$level,"setupRequired":false,"turnstile":false}"""))
        signIn()
        withTimeout(5_000) { while (h.services.servers.current.value?.server?.apiLevel != level) delay(5) }
    }

    private fun saved(subtitles: String, audio: String? = null) =
        json("""{"skip":{},"autoplayNext":true,"subtitles":$subtitles${audio?.let { ",\"audio\":$it" } ?: ""}}""")

    @Test
    fun `a server before audio languages has none, and refuses saving them`() = runBlocking {
        signInAt(18)
        server.always("GET /api/me/preferences", saved("""{"languages":["en"],"mode":"forced","automaticDownload":false}"""))
        prefs.refresh()
        assertEquals(emptyList<String>(), prefs.playback.value.audio.languages)
        assertFalse(prefs.playback.value.subtitles.foreignAudio)
        try {
            prefs.setAudioLanguages(listOf("es"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(503, e.status)
        }
        // A choice changed on such a server sends the mode alone: it would refuse foreignAudio.
        server.on("PUT /api/me/preferences", saved("""{"languages":["en"],"mode":"on","automaticDownload":false}"""))
        prefs.setSubtitles(prefs.playback.value.subtitles.withChoice(SubtitleChoice.On))
        val body = h.body(server.requests("PUT /api/me/preferences").single())
        assertEquals("""{"mode":"on"}""", body["subtitles"].toString())
        assertFalse("audio" in body)
    }

    @Test
    fun `changes send only the fields they change, and the server's foreign-audio default is reported`() = runBlocking {
        signInAt(AUDIO_LANGUAGES_API_LEVEL)
        server.always("GET /api/me/preferences", saved("""{"languages":[],"mode":"forced","automaticDownload":false}"""))
        prefs.refresh()
        // The first audio languages of someone who never chose a mode: the server turns foreignAudio on.
        server.on(
            "PUT /api/me/preferences",
            saved("""{"languages":[],"mode":"forced","foreignAudio":true,"automaticDownload":false}""", """{"languages":["es","en"]}"""),
            saved("""{"languages":["en"],"mode":"forced","foreignAudio":true,"automaticDownload":false}""", """{"languages":["es","en"]}"""),
            saved("""{"languages":["en"],"mode":"on","foreignAudio":false,"automaticDownload":false}""", """{"languages":["es","en"]}"""),
        )
        assertTrue(prefs.setAudioLanguages(listOf("es", "en")))
        val puts = { server.requests("PUT /api/me/preferences").map { h.body(it).toString() } }
        assertEquals("""{"audio":{"languages":["es","en"]}}""", puts()[0])
        assertEquals(SubtitleChoice.ForeignAudio, prefs.playback.value.subtitles.choice)
        assertEquals(listOf("es", "en"), prefs.playback.value.audio.languages)

        // A subtitle language alone: no mode, which would count as choosing one.
        prefs.setSubtitles(prefs.playback.value.subtitles.copy(languages = listOf("en")))
        assertEquals("""{"subtitles":{"languages":["en"]}}""", puts()[1])

        // A choice sends the mode and the flag together.
        prefs.setSubtitles(prefs.playback.value.subtitles.withChoice(SubtitleChoice.On))
        assertEquals("""{"subtitles":{"foreignAudio":false,"mode":"on"}}""", puts()[2])

        // Nothing changed: nothing sent, and no notice.
        assertFalse(prefs.setAudioLanguages(listOf("es", "en")))
        prefs.setSubtitles(prefs.playback.value.subtitles)
        assertEquals(3, puts().size)

        // Offline plays choose from what was last saved, the audio languages and the flag included.
        val cached = LocalStore.decode(h.dataStore.data.first())
        val local = LocalStore(h.dataStore, h.scope, PlainSealer, cached)
        val reopened = RealPreferencesRepository(local, Http(okhttp3.OkHttpClient(), local, "test"))
        assertEquals(listOf("es", "en"), reopened.playback.value.audio.languages)
        assertEquals(SubtitleChoice.On, reopened.playback.value.subtitles.choice)
    }

    @Test
    fun `audio languages saved by someone who chose a mode leave subtitles alone`() = runBlocking {
        signInAt(AUDIO_LANGUAGES_API_LEVEL)
        server.always("GET /api/me/preferences", saved("""{"languages":["en"],"mode":"forced","automaticDownload":false}"""))
        prefs.refresh()
        server.on("PUT /api/me/preferences", saved("""{"languages":["en"],"mode":"forced","automaticDownload":false}""", """{"languages":["ja"]}"""))
        assertFalse(prefs.setAudioLanguages(listOf("ja")))
        assertEquals(SubtitleChoice.Forced, prefs.playback.value.subtitles.choice)
    }
}
