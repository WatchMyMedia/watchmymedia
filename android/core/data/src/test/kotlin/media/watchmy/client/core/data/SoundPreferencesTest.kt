package media.watchmy.client.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.core.data.TestServer.Companion.json
import media.watchmy.client.core.data.TestServer.Companion.problem
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.SOUND_API_LEVEL
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SubtitlePreferences
import media.watchmy.client.model.SubtitleSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Boost dialogue, Reduce loud sounds and the person's subtitle size, from API level 35, and the TV's own size moving up once. */
class SoundPreferencesTest {
    private val h = Harness()
    private val server get() = h.server
    private val prefs get() = h.services.preferences

    @After
    fun tearDown() = h.close()

    private suspend fun signInAt(level: Int, preferences: String = """{"skip":{},"autoplayNext":true}""") {
        server.always("GET /api/server", json("""{"id":"srv-test","name":"Test server","version":"2.3.0","apiLevel":$level,"setupRequired":false,"turnstile":false}"""))
        server.always("GET /api/me/preferences", json(preferences))
        h.signIn()
        withTimeout(5_000) { while (h.services.servers.current.value?.server?.apiLevel != level) delay(5) }
        withTimeout(5_000) { while (server.requests("GET /api/me/preferences").isEmpty()) delay(5) }
    }

    @Test
    fun `the sound settings and the size are read from the server, off and normal when it says nothing`() = runBlocking {
        signInAt(SOUND_API_LEVEL, """{"skip":{},"autoplayNext":true,"audio":{"languages":["en"],"dialogueBoost":"high","reduceLoudSounds":"medium"},"subtitles":{"languages":[],"mode":"off","automaticDownload":false,"size":"largest"}}""")
        prefs.refresh()
        val p = prefs.playback.value
        assertEquals(SoundLevel.High, p.audio.dialogueBoost)
        assertEquals(SoundLevel.Medium, p.audio.reduceLoudSounds)
        assertEquals(SubtitleSize.Largest, p.subtitles.size)

        server.always("GET /api/me/preferences", json("""{"skip":{},"autoplayNext":true,"audio":{"languages":[]},"subtitles":{"languages":[],"mode":"off","automaticDownload":false}}"""))
        prefs.refresh()
        assertEquals(SoundLevel.Off, prefs.playback.value.audio.dialogueBoost)
        assertEquals(SubtitleSize.Normal, prefs.playback.value.subtitles.size)
    }

    @Test
    fun `a sound change sends only its own field and shows at once`() = runBlocking {
        signInAt(SOUND_API_LEVEL)
        server.on("PUT /api/me/preferences", json("""{"skip":{},"autoplayNext":true,"audio":{"languages":[],"reduceLoudSounds":"high"}}"""))
        prefs.setSound(SoundSetting.ReduceLoudSounds, SoundLevel.High)
        val body = h.body(server.requests("PUT /api/me/preferences").single())
        val audio = body["audio"]!!.jsonObject
        assertEquals(setOf("reduceLoudSounds"), audio.keys)
        assertEquals("high", audio["reduceLoudSounds"]!!.jsonPrimitive.content)
        assertNull(body["subtitles"])
        assertEquals(SoundLevel.High, prefs.playback.value.audio.reduceLoudSounds)
        assertEquals(SoundLevel.Off, prefs.playback.value.audio.dialogueBoost)
    }

    @Test
    fun `a sound change that fails is back where it was`() = runBlocking {
        signInAt(SOUND_API_LEVEL)
        server.on("PUT /api/me/preferences", problem(500, "database is locked"))
        try {
            prefs.setSound(SoundSetting.DialogueBoost, SoundLevel.Medium)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.status)
        }
        assertEquals(SoundLevel.Off, prefs.playback.value.audio.dialogueBoost)
    }

    @Test
    fun `below level 35 a sound change is refused here and nothing is sent`() = runBlocking {
        signInAt(SOUND_API_LEVEL - 1)
        try {
            prefs.setSound(SoundSetting.DialogueBoost, SoundLevel.High)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(503, e.status)
        }
        assertTrue(server.requests("PUT /api/me/preferences").isEmpty())
    }

    @Test
    fun `a subtitle size goes to servers that keep one, and never to older ones`() {
        val before = SubtitlePreferences(listOf("en"), SubtitleMode.On)
        val larger = before.copy(size = SubtitleSize.Larger)
        assertEquals("larger", subtitlePatch(before, larger, SOUND_API_LEVEL)?.propertySize)
        assertNull(subtitlePatch(before, larger, SOUND_API_LEVEL - 1))
        // Another change leaves the size out.
        val languages = subtitlePatch(larger, larger.copy(languages = listOf("es")), SOUND_API_LEVEL)!!
        assertNull(languages.propertySize)
        assertEquals(listOf("es"), languages.languages)
    }

    @Test
    fun `the sound fields go one at a time`() {
        assertEquals("medium", soundPatch(SoundSetting.DialogueBoost, SoundLevel.Medium).dialogueBoost)
        assertNull(soundPatch(SoundSetting.DialogueBoost, SoundLevel.Medium).reduceLoudSounds)
        assertEquals("off", soundPatch(SoundSetting.ReduceLoudSounds, SoundLevel.Off).reduceLoudSounds)
        assertNull(soundPatch(SoundSetting.ReduceLoudSounds, SoundLevel.Off).languages)
    }

    @Test
    fun `a TV's own size goes up once to a person with none saved`() = runBlocking {
        signInAt(SOUND_API_LEVEL)
        prefs.setSubtitleSize(SubtitleSize.Larger)
        server.on("PUT /api/me/preferences", json("""{"skip":{},"autoplayNext":true,"subtitles":{"languages":[],"mode":"off","automaticDownload":false,"size":"larger"}}"""))
        assertTrue(prefs.moveSubtitleSizeUp())
        val body = h.body(server.requests("PUT /api/me/preferences").single())
        assertEquals("larger", body["subtitles"]!!.jsonObject["size"]!!.jsonPrimitive.content)
        assertEquals(SubtitleSize.Larger, prefs.playback.value.subtitles.size)
        // Never again for them here, whatever they choose later.
        assertFalse(prefs.moveSubtitleSizeUp())
        assertEquals(1, server.requests("PUT /api/me/preferences").size)
        // The TV keeps its own for older servers.
        assertEquals(SubtitleSize.Larger, prefs.device.value.subtitleSize)
    }

    @Test
    fun `a TV's own size leaves a person's saved size alone`() = runBlocking {
        signInAt(SOUND_API_LEVEL, """{"skip":{},"autoplayNext":true,"subtitles":{"languages":[],"mode":"off","automaticDownload":false,"size":"normal"}}""")
        prefs.setSubtitleSize(SubtitleSize.Largest)
        assertFalse(prefs.moveSubtitleSizeUp())
        assertTrue(server.requests("PUT /api/me/preferences").isEmpty())
    }

    @Test
    fun `a TV's own size stays on the TV for an older server, and a Normal one never moves`() = runBlocking {
        signInAt(SOUND_API_LEVEL - 1)
        prefs.setSubtitleSize(SubtitleSize.Larger)
        assertFalse(prefs.moveSubtitleSizeUp())
        prefs.setSubtitleSize(SubtitleSize.Normal)
        assertFalse(prefs.moveSubtitleSizeUp())
        assertTrue(server.requests("PUT /api/me/preferences").isEmpty())
    }
}
