package media.watchmy.client.core.playback.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import java.io.IOException
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.MediaToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What ExoMediaPlayer makes of Media3's errors ([failureOf]) and the track
 * choices it applies when the tracks appear ([pendingSelection]), on the
 * exceptions and track groups Media3 itself builds.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExoMediaPlayerTest {
    @Test
    fun `a renderer that can't take the audio names its format`() {
        val eac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).build()
        val init = renderer(eac3, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(PlayerFailure.Kind.Decoder, failureOf(init).kind)
        assertEquals(MimeTypes.AUDIO_E_AC3, failureOf(init).audioMimeType)

        val track = renderer(eac3, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        assertEquals(PlayerFailure.Kind.Audio, failureOf(track).kind)
        assertEquals(MimeTypes.AUDIO_E_AC3, failureOf(track).audioMimeType)
        assertTrue(failureOf(track).detail.startsWith("ERROR_CODE_AUDIO_TRACK_INIT_FAILED: "))
    }

    @Test
    fun `a video decoder's failure names no audio`() {
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        val failure = failureOf(renderer(hevc, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlayerFailure.Kind.Decoder, failure.kind)
        assertNull(failure.audioMimeType)
        assertNull("No format at all", failureOf(renderer(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)).audioMimeType)
    }

    @Test
    fun `a refused segment gives its HTTP status`() {
        val forbidden = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse("http://x/a.ts")), ByteArray(0))
        val failure = failureOf(ExoPlaybackException.createForSource(forbidden, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(PlayerFailure(PlayerFailure.Kind.Http, failure.detail, 403), failure)
        assertTrue(failure.detail.startsWith("ERROR_CODE_IO_BAD_HTTP_STATUS"))

        val unknown = failureOf(PlaybackException("bad status", IOException(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(PlayerFailure.Kind.Http, unknown.kind)
        assertNull("The cause carries no status", unknown.httpStatus)
    }

    @Test
    fun `error codes map to the kinds the controller retries on`() {
        fun kindOf(code: Int) = failureOf(PlaybackException("x", null, code)).kind
        assertEquals(PlayerFailure.Kind.Network, kindOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlayerFailure.Kind.Network, kindOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(PlayerFailure.Kind.Network, kindOf(PlaybackException.ERROR_CODE_TIMEOUT))
        assertEquals(PlayerFailure.Kind.BehindLiveWindow, kindOf(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(PlayerFailure.Kind.Source, kindOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(PlayerFailure.Kind.Source, kindOf(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED))
        assertEquals(PlayerFailure.Kind.Decoder, kindOf(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
        assertEquals(PlayerFailure.Kind.Audio, kindOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertEquals(PlayerFailure.Kind.Other, kindOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PlayerFailure.Kind.Other, kindOf(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(PlayerFailure.Kind.Other, kindOf(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
        assertNull("Not a renderer's", failureOf(PlaybackException("x", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)).audioMimeType)
    }

    @Test
    fun `the load's audio track is chosen once it appears and the device can play it`() {
        val tracks = tracks(audio("a0", selected = true), audio("a1"), audio("a2", supported = false))
        val chosen = pendingSelection(tracks, TEXT_OFF, audioOrdinal = 1, text = TextSelection.Off)!!
        assertEquals("a1", chosen.overrides.values.single().mediaTrackGroup.id)

        assertNull("Already playing it", pendingSelection(tracks, TEXT_OFF, audioOrdinal = 0, text = TextSelection.Off))
        assertNull("This device can't decode it", pendingSelection(tracks, TEXT_OFF, audioOrdinal = 2, text = TextSelection.Off))
        assertNull("No such track", pendingSelection(tracks, TEXT_OFF, audioOrdinal = 3, text = TextSelection.Off))
        assertNull("None asked for", pendingSelection(tracks, TEXT_OFF, audioOrdinal = null, text = TextSelection.Off))
    }

    @Test
    fun `an ordinal names the decision's track only when the player lists as many`() {
        val tracks = tracks(audio("a0", selected = true), audio("a1"), audio("a2", supported = false))
        assertTrue(audioSelectable(tracks, 1, expected = 3))
        assertTrue(audioSelectable(tracks, 1, expected = null))
        assertFalse("This device can't decode it", audioSelectable(tracks, 2, expected = 3))
        assertFalse("No such track", audioSelectable(tracks, 3, expected = null))
        // The file has a track the player doesn't list: ordinal 1 may be another track.
        assertFalse(audioSelectable(tracks, 1, expected = 4))
        assertNull(pendingSelection(tracks, TEXT_OFF, audioOrdinal = 1, text = TextSelection.Off, audioTracks = 4))
        assertEquals("a1", pendingSelection(tracks, TEXT_OFF, audioOrdinal = 1, text = TextSelection.Off, audioTracks = 3)!!.overrides.values.single().mediaTrackGroup.id)
    }

    @Test
    fun `subtitles off turns the text tracks off once`() {
        val tracks = tracks(audio("a0", selected = true), text("subtitle:English"))
        val off = pendingSelection(tracks, TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, null, TextSelection.Off)!!
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertNull("Already off", pendingSelection(tracks, off, null, TextSelection.Off))
    }

    @Test
    fun `a subtitle is chosen when its rendition or file is there, and turns text back on`() {
        val tracks = tracks(audio("a0", selected = true), text("subtitle:English"), text("subtitle:Spanish"))
        val spanish = pendingSelection(tracks, TEXT_OFF, null, TextSelection.Rendition(1))!!
        assertFalse(C.TRACK_TYPE_TEXT in spanish.disabledTrackTypes)
        assertEquals("subtitle:Spanish", spanish.overrides.values.single().mediaTrackGroup.id)

        assertNull("Not in the playlist (yet)", pendingSelection(tracks, TEXT_OFF, null, TextSelection.Rendition(2)))
        val showing = tracks(text("subtitle:English", selected = true))
        assertNull("Already showing", pendingSelection(showing, TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, null, TextSelection.Rendition(0)))

        val sideloaded = tracks(text("1:0", formatId = "1:s3"))
        assertEquals("1:0", pendingSelection(sideloaded, TEXT_OFF, null, TextSelection.Sideloaded("s3"))!!.overrides.values.single().mediaTrackGroup.id)
    }

    @Test
    fun `direct play turns on the file's own subtitle track`() {
        val tracks = tracks(audio("0:1", selected = true), text("0:2", formatId = "0:3"), text("0:4", formatId = "0:5"), text("1:0", formatId = "1:x0"))
        val loaded = LoadedText(sideloaded = listOf("x0"), embedded = listOf(EmbeddedSubtitle("s3", "eng"), EmbeddedSubtitle("s5", "eng")))
        val second = pendingSelection(tracks, TEXT_OFF, null, TextSelection.Embedded("s5"), loaded = loaded)!!
        assertFalse(C.TRACK_TYPE_TEXT in second.disabledTrackTypes)
        assertEquals("0:4", second.overrides.values.single().mediaTrackGroup.id)
        assertNull("Without the load's list there is nothing to count by", pendingSelection(tracks, TEXT_OFF, null, TextSelection.Embedded("s5")))
    }

    @Test
    fun `audio and a subtitle chosen together are applied together`() {
        val tracks = tracks(audio("a0", selected = true), audio("a1"), text("subtitle:English"))
        val both = pendingSelection(tracks, TEXT_OFF, audioOrdinal = 1, text = TextSelection.Rendition(0))!!
        assertEquals(setOf("a1", "subtitle:English"), both.overrides.values.map { it.mediaTrackGroup.id }.toSet())
        assertFalse(C.TRACK_TYPE_TEXT in both.disabledTrackTypes)
    }

    @Test
    fun `the frame rate is the selected video track's`() {
        assertEquals(23.976f, frameRateOf(tracks(video("v0", 50f), video("v1", 23.976f, selected = true), audio("a0", selected = true))))
        assertNull("A format that doesn't say", frameRateOf(tracks(video("v0", Format.NO_VALUE.toFloat(), selected = true))))
        assertNull("No video selected", frameRateOf(tracks(video("v0", 25f), audio("a0", selected = true))))
    }

    @Test
    fun `Media3 leaves the display alone only where the TV matches the frame rate itself`() {
        assertTrue(PlayerOptions.of(DevicePlayback(matchFrameRate = true), tv = true).appMatchesFrameRate)
        assertFalse(PlayerOptions.of(DevicePlayback(matchFrameRate = false), tv = true).appMatchesFrameRate)
        assertFalse("Phones have no such setting", PlayerOptions.of(DevicePlayback(matchFrameRate = true), tv = false).appMatchesFrameRate)
    }

    @Test
    fun `the notification shows the title, the episode and the artwork`() {
        val source = PlayerSource(
            url = "http://srv/api/playback/s1/master.m3u8", hls = true, live = false, startPositionMs = 0, playWhenReady = true,
            subtitles = emptyList(), text = TextSelection.Off, audioOrdinal = null,
            title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark", artworkUrl = "http://srv/api/images/w780/b.jpg",
        )
        val metadata = mediaMetadata(source)
        assertEquals("Harbor Lights", metadata.title)
        assertEquals("S03 E07 · The Long Dark", metadata.artist)
        assertEquals(Uri.parse("http://srv/api/images/w780/b.jpg"), metadata.artworkUri)
        assertNull(mediaMetadata(source.copy(artworkUrl = null)).artworkUri)
    }

    @Test
    fun `phones and tablets keep played media for jumps back, TVs and low-RAM devices don't`() {
        assertEquals(15_000, PlayerOptions.of(DevicePlayback(), tv = false).backBufferMs)
        assertEquals("A TV's 4K remux needs the bytes ahead", 0, PlayerOptions.of(DevicePlayback(), tv = true).backBufferMs)
        assertEquals(0, PlayerOptions.of(DevicePlayback(), tv = false, lowRam = true).backBufferMs)
    }

    @Test
    fun `live token renewal replaces the playlist token on future reads without leaking to another server`() {
        val token = MediaToken("fresh token", 999_999, serverUrl = "https://server.example/media")
        val before = Uri.parse("https://server.example/media/api/live-tv/sessions/one/hls/1.ts?access_token=expired&part=2")
        val after = renewableMediaUri(before, token)
        assertEquals(listOf("fresh token"), after.getQueryParameters("access_token"))
        assertEquals("2", after.getQueryParameter("part"))
        val other = Uri.parse("https://other.example/media/api/live-tv/sessions/one/hls/1.ts")
        assertEquals(other, renewableMediaUri(other, token))
        val outsidePrefix = Uri.parse("https://server.example/unrelated/1.ts")
        assertEquals(outsidePrefix, renewableMediaUri(outsidePrefix, token))
    }

    private fun renderer(format: Format?, code: Int) =
        ExoPlaybackException.createForRenderer(RuntimeException("boom"), "MediaCodecAudioRenderer", 1, format, C.FORMAT_HANDLED, false, code)

    private fun audio(id: String, selected: Boolean = false, supported: Boolean = true): Tracks.Group {
        val format = Format.Builder().setId(id).setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        val support = if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
        return Tracks.Group(TrackGroup(id, format), false, intArrayOf(support), booleanArrayOf(selected))
    }

    private fun video(id: String, frameRate: Float, selected: Boolean = false): Tracks.Group {
        val format = Format.Builder().setId(id).setSampleMimeType(MimeTypes.VIDEO_H264).setFrameRate(frameRate).build()
        return Tracks.Group(TrackGroup(id, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
    }

    private fun text(groupId: String, formatId: String = groupId, selected: Boolean = false): Tracks.Group {
        val format = Format.Builder().setId(formatId).setSampleMimeType(MimeTypes.TEXT_VTT).build()
        return Tracks.Group(TrackGroup(groupId, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
    }

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())

    private companion object {
        /** As a load leaves the parameters when its subtitles start off. */
        val TEXT_OFF: TrackSelectionParameters =
            TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }
}
