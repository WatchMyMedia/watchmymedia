package media.watchmy.client.presentation.settings

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakePreferencesRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.kindLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun vm(auth: AuthRepository = FakeAuthRepository(), prefs: PreferencesRepository = FakePreferencesRepository()) =
        SettingsViewModel(auth, FakeServerRepository(), prefs)

    @Test
    fun `shows the account, server, device, preferences and signed-in devices`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Sam", s.user?.name)
        assertEquals("Living room server", s.server?.server?.name)
        assertEquals("Living room TV", s.deviceName)
        assertEquals("never picked, on a server that does Automatic", StreamQuality.Auto, s.quality)
        assertEquals(StreamQuality.Auto, s.qualities.first())
        assertEquals(SkipMode.Ask, s.playback.skipMode(SegmentKind.Intro))
        val sessions = s.sessions.value!!
        assertEquals(3, sessions.size)
        assertTrue("this device first", sessions.first().current)
        assertEquals("Android TV", sessions.first().kindLabel)
        assertEquals("Web browser", sessions.last().kindLabel)
    }

    @Test
    fun `skip modes, autoplay, quality and the device name are saved`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setSkip(SegmentKind.Intro, SkipMode.AutoDelay)
        vm.setAutoplayNext(false)
        vm.setQuality(StreamQuality.FullHd)
        vm.rename("  Bedroom TV  ")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(SkipMode.AutoDelay, s.playback.skipMode(SegmentKind.Intro))
        assertFalse(s.playback.autoplayNext)
        assertEquals(StreamQuality.FullHd, s.quality)
        assertEquals("Bedroom TV", s.deviceName)
        assertNull(s.message)
    }

    @Test
    fun `quality on mobile data, frame rate, passthrough and subtitle size are this device's own`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(DevicePlayback(), vm.state.value.device)
        assertNull("Same as on Wi-Fi until picked", vm.state.value.device.mobileQuality)
        vm.setMobileQuality(StreamQuality.Hd)
        vm.setMatchFrameRate(true)
        vm.setAudioPassthrough(AudioPassthrough.Off)
        vm.setSubtitleSize(SubtitleSize.Larger)
        advanceUntilIdle()
        assertEquals(DevicePlayback(StreamQuality.Hd, matchFrameRate = true, audioPassthrough = AudioPassthrough.Off, subtitleSize = SubtitleSize.Larger), vm.state.value.device)
        assertEquals("the Default quality stays", StreamQuality.Auto, vm.state.value.quality)
        vm.setMobileQuality(null)
        advanceUntilIdle()
        assertNull(vm.state.value.device.mobileQuality)
    }

    @Test
    fun `an empty name keeps the old one`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.rename("   ")
        advanceUntilIdle()
        assertEquals("Living room TV", vm.state.value.deviceName)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `a save that fails says why`() = runTest(main.dispatcher) {
        val prefs = object : PreferencesRepository by FakePreferencesRepository() {
            override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) = throw ApiException(503, "Server is busy.")

            override suspend fun setAutoplayNext(enabled: Boolean) = throw IllegalStateException("not for people")
        }
        val vm = vm(prefs = prefs)
        advanceUntilIdle()
        vm.setSkip(SegmentKind.Recap, SkipMode.Auto)
        advanceUntilIdle()
        assertEquals("Server is busy.", vm.state.value.message)
        vm.messageShown()
        vm.setAutoplayNext(false)
        advanceUntilIdle()
        assertEquals("Couldn't save autoplay.", vm.state.value.message)
    }

    @Test
    fun `playback settings that don't load say so`() = runTest(main.dispatcher) {
        val prefs = object : PreferencesRepository by FakePreferencesRepository() {
            override suspend fun refresh() = throw IllegalStateException("not for people")
        }
        val vm = vm(prefs = prefs)
        advanceUntilIdle()
        assertEquals("Couldn't load your playback settings.", vm.state.value.prefsError)
        vm.setQuality(StreamQuality.Hd)
        advanceUntilIdle()
        assertNull("a save that works clears it", vm.state.value.prefsError)
    }

    @Test
    fun `signs another device out`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository()
        val vm = vm(auth = auth)
        advanceUntilIdle()
        val other = vm.state.value.sessions.value!!.first { !it.current }
        vm.revoke(other)
        advanceUntilIdle()
        assertFalse(vm.state.value.sessions.value!!.any { it.id == other.id })
        assertNull(vm.state.value.revoking)
        assertEquals("${other.name} is signed out", vm.state.value.message)
        assertEquals(2, auth.sessions().size)
    }

    @Test
    fun `a device list that fails can be reloaded`() = runTest(main.dispatcher) {
        var fail = true
        val auth = object : AuthRepository by FakeAuthRepository() {
            override suspend fun sessions(all: Boolean): List<DeviceSession> = if (fail) throw ApiException(500, "No list.") else emptyList()
        }
        val vm = vm(auth = auth)
        advanceUntilIdle()
        assertEquals(Load.Failed("No list."), vm.state.value.sessions)
        fail = false
        vm.loadSessions()
        advanceUntilIdle()
        assertEquals(Load.Ready(emptyList<DeviceSession>()), vm.state.value.sessions)

        fail = true
        vm.loadSessions()
        advanceUntilIdle()
        assertEquals("A list that's up stays", Load.Ready(emptyList<DeviceSession>()), vm.state.value.sessions)
        assertEquals("No list.", vm.state.value.message)
    }

    @Test
    fun `signing out clears the user and says so once`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository()
        val vm = vm(auth = auth)
        advanceUntilIdle()
        vm.events.test {
            vm.signOut()
            advanceUntilIdle()
            assertEquals(SettingsViewModel.Event.SignedOut, awaitItem())
            expectNoEvents()
        }
        assertNull(auth.user.value)
        assertNull(vm.state.value.user)
        assertFalse(vm.state.value.signingOut)
    }

    @Test
    fun `the first audio languages turn on subtitles for foreign audio, said until a choice`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val first = vm.state.value
        assertTrue(first.audioLanguagesAvailable)
        assertEquals("Audio & subtitles", first.languagesTitle)
        assertEquals(SubtitleChoice.entries, first.subtitleChoices)
        assertEquals(SubtitleChoice.Forced, first.playback.subtitles.choice)

        vm.addAudioLanguage("es")
        vm.addAudioLanguage("en")
        vm.addAudioLanguage("ES")
        advanceUntilIdle()
        var s = vm.state.value
        assertEquals(listOf("es", "en"), s.playback.audio.languages)
        assertEquals(SubtitleChoice.ForeignAudio, s.playback.subtitles.choice)
        assertTrue(s.showForeignAudioNotice)
        assertTrue("no subtitle language yet", s.subtitlesFollowAudio)
        assertFalse("automatic needs a subtitle language", s.playback.subtitles.automaticAvailable)

        vm.moveAudioLanguage("en", -1)
        advanceUntilIdle()
        assertEquals(listOf("en", "es"), vm.state.value.playback.audio.languages)

        vm.setSubtitleChoice(SubtitleChoice.On)
        advanceUntilIdle()
        s = vm.state.value
        assertFalse(s.showForeignAudioNotice)
        assertEquals(SubtitleChoice.On, s.playback.subtitles.choice)
        assertFalse(s.playback.subtitles.foreignAudio)

        // Removing them all and adding again doesn't choose for the person a second time.
        vm.removeAudioLanguage("en")
        vm.removeAudioLanguage("es")
        vm.addAudioLanguage("ja")
        advanceUntilIdle()
        assertEquals(SubtitleChoice.On, vm.state.value.playback.subtitles.choice)
        assertFalse(vm.state.value.showForeignAudioNotice)
    }

    @Test
    fun `subtitle choices and automatic download`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setSubtitleChoice(SubtitleChoice.ForeignAudio)
        vm.addSubtitleLanguage("en")
        advanceUntilIdle()
        val prefs = vm.state.value.playback.subtitles
        assertEquals(SubtitleMode.Forced, prefs.mode)
        assertTrue(prefs.foreignAudio)
        assertTrue(prefs.automaticAvailable)
        vm.setAutomaticSubtitles(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.playback.subtitles.automaticDownload)
        assertFalse(vm.state.value.subtitleSaving)
    }
}
