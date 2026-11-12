package media.watchmy.client.core.playback.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [textGroup] against the group and format IDs Media3 1.11 gives the tracks. */
@OptIn(UnstableApi::class)
class TextGroupTest {
    @Test
    fun renditionsCountOnlyTheSubtitleGroupsInOrder() {
        // An HLS VOD stream: video, CEA-608 captions muxed into it, then the master playlist's renditions.
        val tracks = tracks(
            group("main", "video", MimeTypes.VIDEO_H264),
            group("main:cc:0", "cc", MimeTypes.APPLICATION_CEA608),
            group("subtitle:English", "English", MimeTypes.TEXT_VTT),
            group("subtitle:Spanish", "Spanish", MimeTypes.TEXT_VTT),
            group("subtitle:English (2)", "English (2)", MimeTypes.TEXT_VTT),
        )
        assertEquals("subtitle:English", textGroup(tracks, TextSelection.Rendition(0))?.mediaTrackGroup?.id)
        assertEquals("subtitle:English (2)", textGroup(tracks, TextSelection.Rendition(2))?.mediaTrackGroup?.id)
        assertNull(textGroup(tracks, TextSelection.Rendition(3)))
        assertNull(textGroup(tracks, TextSelection.Off))
    }

    @Test
    fun sideloadedFilesMatchUnderTheMergedSourcesPrefix() {
        // Direct play of a Matroska file, whose own text track is "0:3", with the server's
        // copies of it and a sidecar side-loaded as sources 1 and 2.
        val tracks = tracks(
            group("0:0", "0:1", MimeTypes.VIDEO_H264),
            group("0:1", "0:3", MimeTypes.APPLICATION_SUBRIP),
            group("1:0", "1:s3", MimeTypes.APPLICATION_MEDIA3_CUES),
            group("2:0", "2:x0", MimeTypes.APPLICATION_MEDIA3_CUES),
        )
        assertEquals("1:0", textGroup(tracks, TextSelection.Sideloaded("s3"))?.mediaTrackGroup?.id)
        assertEquals("2:0", textGroup(tracks, TextSelection.Sideloaded("x0"))?.mediaTrackGroup?.id)
        assertNull(textGroup(tracks, TextSelection.Sideloaded("x1")))
        // Unprefixed, as a single source would have it.
        assertEquals("only", textGroup(tracks(group("only", "x1", MimeTypes.TEXT_VTT)), TextSelection.Sideloaded("x1"))?.mediaTrackGroup?.id)
    }

    @Test
    fun aFilesOwnSubtitleIsFoundByItsPlaceAmongTheFilesTracks() {
        // Direct play of a Matroska file with an English SRT, a PGS and a Spanish SRT, and a sidecar
        // side-loaded as source 1. Media3 parses text while extracting, so its formats are cues.
        val loaded = LoadedText(
            sideloaded = listOf("x0"),
            embedded = listOf(EmbeddedSubtitle("s3", "eng"), EmbeddedSubtitle("s4", "eng"), EmbeddedSubtitle("s5", "spa")),
        )
        val tracks = tracks(
            group("0:0", "0:1", MimeTypes.VIDEO_H264),
            group("0:1", "0:2", MimeTypes.AUDIO_AC3),
            group("0:2", "0:4", MimeTypes.APPLICATION_MEDIA3_CUES, "en"),
            group("0:3", "0:5", MimeTypes.APPLICATION_PGS, "en"),
            group("0:4", "0:6", MimeTypes.APPLICATION_MEDIA3_CUES, "es"),
            group("1:0", "1:x0", MimeTypes.APPLICATION_MEDIA3_CUES, "en"),
        )
        assertEquals("0:2", textGroup(tracks, TextSelection.Embedded("s3"), loaded)?.mediaTrackGroup?.id)
        assertEquals("0:4", textGroup(tracks, TextSelection.Embedded("s5"), loaded)?.mediaTrackGroup?.id)
        assertEquals("1:0", textGroup(tracks, TextSelection.Sideloaded("x0"), loaded)?.mediaTrackGroup?.id)
        assertNull("Not one of the file's", textGroup(tracks, TextSelection.Embedded("s9"), loaded))
        assertTrue(embeddedSelectable(tracks, TextSelection.Embedded("s3"), loaded))

        // A single source: no prefixes, and no side-loaded tracks to leave out.
        val single = tracks(group("1", "4", MimeTypes.APPLICATION_MEDIA3_CUES, "en"), group("2", "6", MimeTypes.APPLICATION_MEDIA3_CUES, "es"))
        val two = LoadedText(embedded = listOf(EmbeddedSubtitle("s3", "eng"), EmbeddedSubtitle("s5", "spa")))
        assertEquals("2", textGroup(single, TextSelection.Embedded("s5"), two)?.mediaTrackGroup?.id)
    }

    @Test
    fun closedCaptionsInTheVideoAreNotTheFilesSubtitles() {
        // An MPEG-TS file: Media3 adds a CEA-608 track for captions the video may carry.
        val loaded = LoadedText(embedded = listOf(EmbeddedSubtitle("s2", "eng")))
        val tracks = tracks(group("0", "608", MimeTypes.APPLICATION_CEA608), group("1", "2", MimeTypes.APPLICATION_MEDIA3_CUES, "en"))
        assertEquals("1", textGroup(tracks, TextSelection.Embedded("s2"), loaded)?.mediaTrackGroup?.id)
    }

    @Test
    fun whenThePlayerListsADifferentNumberTheLanguageDecides() {
        // The server lists a track Media3 skips (a codec it doesn't read), so the places differ.
        val loaded = LoadedText(
            embedded = listOf(EmbeddedSubtitle("s2", "ger"), EmbeddedSubtitle("s3", "eng"), EmbeddedSubtitle("s4", "spa"), EmbeddedSubtitle("s5", "eng")),
        )
        val tracks = tracks(
            group("0", "3", MimeTypes.APPLICATION_MEDIA3_CUES, "en"),
            group("1", "4", MimeTypes.APPLICATION_MEDIA3_CUES, "es"),
            group("2", "5", MimeTypes.APPLICATION_MEDIA3_CUES, "en"),
        )
        assertEquals("0", textGroup(tracks, TextSelection.Embedded("s3"), loaded)?.mediaTrackGroup?.id)
        assertEquals("2", textGroup(tracks, TextSelection.Embedded("s5"), loaded)?.mediaTrackGroup?.id)
        assertEquals("1", textGroup(tracks, TextSelection.Embedded("s4"), loaded)?.mediaTrackGroup?.id)
        assertNull("The skipped one", textGroup(tracks, TextSelection.Embedded("s2"), loaded))
        assertFalse(embeddedSelectable(tracks, TextSelection.Embedded("s2"), loaded))

        // Two English tracks listed, one found: which one it is can't be told.
        val oneEnglish = tracks(group("0", "3", MimeTypes.APPLICATION_MEDIA3_CUES, "en"), group("1", "4", MimeTypes.APPLICATION_MEDIA3_CUES, "es"))
        assertNull(textGroup(oneEnglish, TextSelection.Embedded("s3"), loaded))
        // Untagged tracks are matched by place only.
        val untagged = LoadedText(embedded = listOf(EmbeddedSubtitle("s2", null), EmbeddedSubtitle("s3", "und")))
        assertNull(textGroup(tracks(group("0", "3", MimeTypes.APPLICATION_MEDIA3_CUES)), TextSelection.Embedded("s2"), untagged))
    }

    @Test
    fun aFilesSubtitleThisDeviceCantShowIsNotSelectable() {
        val loaded = LoadedText(embedded = listOf(EmbeddedSubtitle("s2", "eng")))
        val format = Format.Builder().setId("2").setSampleMimeType("text/x-unknown").build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup("0", format), false, intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE), booleanArrayOf(false))))
        assertEquals("0", textGroup(tracks, TextSelection.Embedded("s2"), loaded)?.mediaTrackGroup?.id)
        assertFalse(embeddedSelectable(tracks, TextSelection.Embedded("s2"), loaded))
    }

    private fun group(groupId: String, formatId: String, mime: String, language: String? = null): Tracks.Group {
        val format = Format.Builder().setId(formatId).setSampleMimeType(mime).setLanguage(language).build()
        return Tracks.Group(TrackGroup(groupId, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
    }

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())
}
