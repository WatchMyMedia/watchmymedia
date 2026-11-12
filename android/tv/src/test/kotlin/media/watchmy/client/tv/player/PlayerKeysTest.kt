package media.watchmy.client.tv.player

import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import media.watchmy.client.ui.PlayerKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun leftAndRightSeekUnlessFocusIsInTheButtonRow() {
        assertEquals(KeyAction.Seek(forward = false), routeKey(PlayerKey.Left, Overlay.None, controlsVisible = false, FocusArea.Root))
        assertEquals(KeyAction.Seek(forward = true), routeKey(PlayerKey.Right, Overlay.None, controlsVisible = true, FocusArea.Scrubber))
        assertEquals(KeyAction.Seek(forward = true), routeKey(PlayerKey.Right, Overlay.None, controlsVisible = true, FocusArea.Skip))
        assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Right, Overlay.None, controlsVisible = true, FocusArea.Buttons))
        assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Left, Overlay.None, controlsVisible = true, FocusArea.Back))
    }

    @Test
    fun centrePlaysOrPausesUnlessAControlHasFocus() {
        assertEquals(KeyAction.TogglePlayPause, routeKey(PlayerKey.Centre, Overlay.None, controlsVisible = false, FocusArea.Root))
        assertEquals(KeyAction.TogglePlayPause, routeKey(PlayerKey.Centre, Overlay.None, controlsVisible = true, FocusArea.Scrubber))
        assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Centre, Overlay.None, controlsVisible = false, FocusArea.Skip))
        assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Centre, Overlay.None, controlsVisible = true, FocusArea.Buttons))
    }

    @Test
    fun upAndDownBringTheControlsBackThenMoveFocus() {
        assertEquals(KeyAction.ShowControls, routeKey(PlayerKey.Up, Overlay.None, controlsVisible = false, FocusArea.Root))
        assertEquals(KeyAction.ShowControls, routeKey(PlayerKey.Down, Overlay.None, controlsVisible = false, FocusArea.Skip))
        assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Down, Overlay.None, controlsVisible = true, FocusArea.Scrubber))
    }

    @Test
    fun overlaysKeepTheDpadAndMediaKeysStillWorkUnderTheCardAndPicker() {
        for (overlay in listOf(Overlay.UpNext, Overlay.Picker, Overlay.StillWatching, Overlay.Message)) {
            for (key in listOf(PlayerKey.Left, PlayerKey.Right, PlayerKey.Up, PlayerKey.Down, PlayerKey.Centre)) {
                assertEquals("$key under $overlay", KeyAction.PassThrough, routeKey(key, overlay, controlsVisible = false, FocusArea.Root))
            }
        }
        assertEquals(KeyAction.TogglePlayPause, routeKey(PlayerKey.PlayPause, Overlay.UpNext, false, FocusArea.Root))
        assertEquals(KeyAction.TogglePlayPause, routeKey(PlayerKey.PlayPause, Overlay.Picker, false, FocusArea.Root))
        assertEquals(KeyAction.Seek(forward = false), routeKey(PlayerKey.Rewind, Overlay.UpNext, false, FocusArea.Root))
        assertEquals(KeyAction.Swallow, routeKey(PlayerKey.Rewind, Overlay.Picker, false, FocusArea.Root))
        assertEquals(KeyAction.Swallow, routeKey(PlayerKey.PlayPause, Overlay.StillWatching, false, FocusArea.Root))
        assertEquals(KeyAction.Swallow, routeKey(PlayerKey.PlayPause, Overlay.Message, false, FocusArea.Root))
        assertEquals(KeyAction.PlayNext, routeKey(PlayerKey.Next, Overlay.UpNext, false, FocusArea.Root))
    }

    @Test
    fun mediaKeysAndShortcuts() {
        assertEquals(KeyAction.Seek(forward = true), routeKey(PlayerKey.FastForward, Overlay.None, controlsVisible = true, FocusArea.Buttons))
        assertEquals(KeyAction.Play, routeKey(PlayerKey.Play, Overlay.None, false, FocusArea.Root))
        assertEquals(KeyAction.Pause, routeKey(PlayerKey.Pause, Overlay.None, false, FocusArea.Root))
        assertEquals(KeyAction.OpenPicker(PickerSection.Subtitles), routeKey(PlayerKey.Captions, Overlay.None, false, FocusArea.Root))
        assertEquals(KeyAction.OpenPicker(PickerSection.Audio), routeKey(PlayerKey.AudioTrack, Overlay.None, false, FocusArea.Root))
        assertEquals(KeyAction.Leave, routeKey(PlayerKey.Stop, Overlay.Message, false, FocusArea.Root))
    }

    @Test
    fun backIsNeverSwallowedSoTheBackHandlerGetsIt() {
        for (overlay in Overlay.entries) {
            assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Back, overlay, controlsVisible = true, FocusArea.Scrubber))
            assertEquals(KeyAction.PassThrough, routeKey(PlayerKey.Other, overlay, controlsVisible = true, FocusArea.Scrubber))
        }
    }

    @Test
    fun backClosesTheSheetOrCardThenHidesTheControlsThenLeaves() {
        assertEquals(BackAction.ClosePicker, backAction(Overlay.Picker, controlsVisible = false))
        assertEquals(BackAction.WatchCredits, backAction(Overlay.UpNext, controlsVisible = false))
        assertEquals(BackAction.KeepWatching, backAction(Overlay.StillWatching, controlsVisible = true))
        assertEquals(BackAction.HideControls, backAction(Overlay.None, controlsVisible = true))
        assertEquals(BackAction.Leave, backAction(Overlay.None, controlsVisible = false))
        assertEquals(BackAction.Leave, backAction(Overlay.Message, controlsVisible = true))
    }

    @Test
    fun overlaysInOrderOfImportance() {
        val card = UpNextPrompt(UpNext(2, 2, 1, "Show", 1, 2, "Two", null), 10_000)
        val base = PlayerUiState(status = PlayerStatus.Playing)
        assertEquals(Overlay.None, overlayOf(base, null))
        assertEquals(Overlay.UpNext, overlayOf(base.copy(upNext = card), null))
        assertEquals(Overlay.Picker, overlayOf(base.copy(upNext = card), PickerSection.Audio))
        assertEquals(Overlay.StillWatching, overlayOf(base.copy(upNext = card, askStillWatching = true), PickerSection.Audio))
        assertEquals(Overlay.Message, overlayOf(base.copy(askStillWatching = true, stopMessage = "Stopped by admin"), null))
        assertEquals(Overlay.Message, overlayOf(base.copy(status = PlayerStatus.Error, error = "Failed"), null))
        // A non-fatal error is a banner, not an overlay.
        assertEquals(Overlay.None, overlayOf(base.copy(error = "Track change failed", segments = listOf(Segment(SegmentKind.Intro, 0, 1))), null))
    }
}
