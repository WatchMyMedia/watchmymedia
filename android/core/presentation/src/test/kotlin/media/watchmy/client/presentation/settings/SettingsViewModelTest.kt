package media.watchmy.client.presentation.settings

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
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
import media.watchmy.client.model.SaveFailure
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.kindLabel
import media.watchmy.client.model.passwordChangedNotice
import media.watchmy.client.model.revokedNotice
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `a save that fails stays on its row, with why, and the saved value`() = runTest(main.dispatcher) {
        val prefs = object : PreferencesRepository by FakePreferencesRepository() {
            override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) = throw ApiException(503, "Server is busy.")

            override suspend fun setAutoplayNext(enabled: Boolean) = throw IllegalStateException("not for people")
        }
        val vm = vm(prefs = prefs)
        advanceUntilIdle()
        vm.setSkip(SegmentKind.Recap, SkipMode.Auto)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(SaveFailure("Server is busy.", unreachable = false), s.failed[SettingKey.Skip(SegmentKind.Recap)])
        assertEquals("the row shows what is saved", SkipMode.Ask, s.playback.skipMode(SegmentKind.Recap))
        assertTrue(s.saving.isEmpty())
        assertNull("nothing said in passing", s.message)
        vm.setAutoplayNext(false)
        advanceUntilIdle()
        assertEquals("Couldn't save autoplay.", vm.state.value.failed[SettingKey.Autoplay]?.reason)
        assertTrue(vm.state.value.playback.autoplayNext)
    }

    @Test
    fun `a failed save is tried again, and says Saving while it goes`() = runTest(main.dispatcher) {
        var fail = true
        val gate = CompletableDeferred<Unit>()
        val fake = FakePreferencesRepository()
        val prefs = object : PreferencesRepository by fake {
            override suspend fun setAutoplayNext(enabled: Boolean) {
                if (fail) throw ApiException(null, "Couldn't reach the server.", java.io.IOException())
                gate.await()
                fake.setAutoplayNext(enabled)
            }
        }
        val vm = vm(prefs = prefs)
        advanceUntilIdle()
        vm.setAutoplayNext(false)
        advanceUntilIdle()
        assertEquals(SaveFailure("Couldn't reach the server.", unreachable = true), vm.state.value.failed[SettingKey.Autoplay])

        fail = false
        vm.retry(SettingKey.Autoplay)
        advanceUntilIdle()
        assertEquals(setOf<SettingKey>(SettingKey.Autoplay), vm.state.value.saving)
        assertNull("trying again clears the failure", vm.state.value.failed[SettingKey.Autoplay])
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.saving.isEmpty())
        assertFalse(vm.state.value.playback.autoplayNext)
    }

    @Test
    fun `an older change that fails late doesn't mark a newer one that saved`() = runTest(main.dispatcher) {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val fake = FakePreferencesRepository()
        val prefs = object : PreferencesRepository by fake {
            override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) {
                if (++calls == 1) {
                    first.await()
                    throw ApiException(503, "Late.")
                }
                fake.setSkip(kind, mode)
            }
        }
        val vm = vm(prefs = prefs)
        advanceUntilIdle()
        vm.setSkip(SegmentKind.Intro, SkipMode.Auto)
        vm.setSkip(SegmentKind.Intro, SkipMode.None)
        advanceUntilIdle()
        first.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.state.value.failed[SettingKey.Skip(SegmentKind.Intro)])
        assertEquals(SkipMode.None, vm.state.value.playback.skipMode(SegmentKind.Intro))
        assertTrue(vm.state.value.saving.isEmpty())
    }

    @Test
    fun `account settings that don't load can't be changed until they do, and this device's still can`() = runTest(main.dispatcher) {
        var fail = true
        val fake = FakePreferencesRepository()
        val prefs = object : PreferencesRepository by fake {
            override suspend fun refresh() = if (fail) throw IllegalStateException("not for people") else fake.refresh()
        }
        val vm = vm(prefs = prefs)
        advanceUntilIdle()
        assertEquals(AccountLoad.Failed("Couldn't load your settings.", unreachable = false), vm.state.value.account)
        assertFalse(vm.state.value.accountReady)
        assertEquals("Couldn't load your settings.", vm.state.value.prefsError)

        vm.setAutoplayNext(false)
        vm.setSkip(SegmentKind.Intro, SkipMode.Auto)
        vm.addAudioLanguage("es")
        vm.setQuality(StreamQuality.Hd)
        advanceUntilIdle()
        var s = vm.state.value
        assertTrue("held until loaded", s.playback.autoplayNext)
        assertEquals(SkipMode.Ask, s.playback.skipMode(SegmentKind.Intro))
        assertTrue(s.playback.audio.languages.isEmpty())
        assertEquals("this device's settings save", StreamQuality.Hd, s.quality)

        fail = false
        vm.reloadAccount()
        advanceUntilIdle()
        assertTrue(vm.state.value.accountReady)
        vm.setAutoplayNext(false)
        advanceUntilIdle()
        s = vm.state.value
        assertFalse(s.playback.autoplayNext)
        assertNull(s.prefsError)
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
        assertEquals(revokedNotice(other.name), vm.state.value.notice)
        assertEquals(2, auth.sessions().size)
        vm.noticeShown()
        advanceUntilIdle()
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `a device that can't be signed out stays, and its dialog says why`() = runTest(main.dispatcher) {
        val auth = object : AuthRepository by FakeAuthRepository() {
            override suspend fun revoke(sessionId: String) = throw ApiException(500, "No.")
        }
        val vm = vm(auth = auth)
        advanceUntilIdle()
        val other = vm.state.value.sessions.value!!.first { !it.current }
        vm.revoke(other)
        advanceUntilIdle()
        assertEquals(RevokeFailure(other.id, "No."), vm.state.value.revokeFailure)
        assertTrue(vm.state.value.sessions.value!!.any { it.id == other.id })
        assertNull(vm.state.value.notice)
        vm.clearConfirmation()
        advanceUntilIdle()
        assertNull(vm.state.value.revokeFailure)
    }

    @Test
    fun `a changed password says how many devices it signed out and lists them again`() = runTest(main.dispatcher) {
        val auth = FakeAuthRepository()
        val vm = vm(auth = auth)
        advanceUntilIdle()
        assertEquals(3, vm.state.value.sessions.value!!.size)
        auth.changePassword("password", "a new password")
        vm.passwordChanged(2)
        advanceUntilIdle()
        assertEquals(passwordChangedNotice(2), vm.state.value.notice)
        assertEquals(listOf(true), vm.state.value.sessions.value!!.map { it.current })
    }

    @Test
    fun `a sign-out that fails keeps this device signed in and says so in its dialog`() = runTest(main.dispatcher) {
        val auth = object : AuthRepository by FakeAuthRepository() {
            override suspend fun signOut() = throw IllegalStateException("not for people")
        }
        val vm = vm(auth = auth)
        advanceUntilIdle()
        vm.signOut()
        advanceUntilIdle()
        assertEquals("Couldn't sign out. You're still signed in.", vm.state.value.signOutFailure)
        assertFalse(vm.state.value.signingOut)
        assertNotNull(vm.state.value.user)
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

    @Test
    fun `a language list's options move and remove its languages, for either list`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        for (list in LanguageList.entries) {
            vm.addLanguage(list, "en")
            vm.addLanguage(list, "fr")
            vm.addLanguage(list, "de")
            advanceUntilIdle()
            assertEquals(listOf("en", "fr", "de"), vm.languages(list))
            assertEquals(listOf(LanguageAction.MoveDown, LanguageAction.Remove), vm.languageActions(list, "en"))
            assertEquals(LanguageAction.entries, vm.languageActions(list, "fr"))
            assertEquals(listOf(LanguageAction.MoveUp, LanguageAction.Remove), vm.languageActions(list, "de"))
            vm.act(list, "de", LanguageAction.MoveUp)
            advanceUntilIdle()
            assertEquals(listOf("en", "de", "fr"), vm.languages(list))
            vm.act(list, "en", LanguageAction.Remove)
            advanceUntilIdle()
            assertEquals(listOf("de", "fr"), vm.languages(list))
        }
    }
}
