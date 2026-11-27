package media.watchmy.client.presentation.settings

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakePreferencesRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.SOUND_API_LEVEL
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.rowLine
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings › Audio & subtitles' Sound and Subtitle size, which save at once as the rest of the page does. */
class SoundSettingsTest {
    @get:Rule val main = MainDispatcherRule()

    private val prefs = FakePreferencesRepository()
    private val servers = FakeServerRepository()

    private fun vm() = SettingsViewModel(FakeAuthRepository(), servers, prefs)

    @Test
    fun `a sound row saves at once, saying Saving meanwhile`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.soundAvailable)
        // The rule puts it back afterwards.
        fakeLatencyMs = 50
        vm.setSound(SoundSetting.DialogueBoost, SoundLevel.High)
        runCurrent()
        assertTrue(SettingKey.Sound(SoundSetting.DialogueBoost) in vm.state.value.saving)
        assertFalse(SettingKey.Sound(SoundSetting.ReduceLoudSounds) in vm.state.value.saving)
        advanceUntilIdle()
        assertFalse(SettingKey.Sound(SoundSetting.DialogueBoost) in vm.state.value.saving)
        assertEquals(SoundLevel.High, vm.state.value.playback.audio.dialogueBoost)
        assertEquals(SoundLevel.Off, vm.state.value.playback.audio.reduceLoudSounds)
    }

    @Test
    fun `a sound row that fails says so, stays as it was, and tries again`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        prefs.failNextSave = true
        vm.setSound(SoundSetting.ReduceLoudSounds, SoundLevel.Medium)
        advanceUntilIdle()
        val key = SettingKey.Sound(SoundSetting.ReduceLoudSounds)
        val failure = vm.state.value.failed[key]!!
        assertEquals("Couldn’t save, so it’s still Off. Check your connection.", failure.rowLine(vm.state.value.playback.audio.reduceLoudSounds.label))
        vm.retry(key)
        advanceUntilIdle()
        assertFalse(key in vm.state.value.failed)
        assertEquals(SoundLevel.Medium, vm.state.value.playback.audio.reduceLoudSounds)
    }

    @Test
    fun `the subtitle size is the person's from level 35, and the TV's own below it`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setAccountSubtitleSize(SubtitleSize.Larger)
        advanceUntilIdle()
        assertEquals(SubtitleSize.Larger, vm.state.value.playback.subtitles.size)
        assertEquals(SubtitleSize.Larger, vm.state.value.subtitleSize)
        assertEquals("the TV's own is untouched", SubtitleSize.Normal, vm.state.value.device.subtitleSize)

        runBlocking { servers.select(LivingRoom.copy(apiLevel = SOUND_API_LEVEL - 1)) }
        advanceUntilIdle()
        assertFalse(vm.state.value.soundAvailable)
        assertEquals(SubtitleSize.Normal, vm.state.value.subtitleSize)
    }

    @Test
    fun `below level 35 the sound and the size are neither offered nor saved`() = runTest(main.dispatcher) {
        runBlocking { servers.select(LivingRoom.copy(apiLevel = SOUND_API_LEVEL - 1)) }
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.soundAvailable)
        vm.setSound(SoundSetting.DialogueBoost, SoundLevel.High)
        vm.setAccountSubtitleSize(SubtitleSize.Largest)
        advanceUntilIdle()
        assertTrue(vm.state.value.saving.isEmpty())
        assertEquals(SoundLevel.Off, prefs.playback.value.audio.dialogueBoost)
        assertEquals(SubtitleSize.Normal, prefs.playback.value.subtitles.size)
    }
}
