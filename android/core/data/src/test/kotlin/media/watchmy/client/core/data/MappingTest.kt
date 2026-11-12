package media.watchmy.client.core.data

import media.watchmy.client.core.api.models.AudioTrack
import media.watchmy.client.core.api.models.BrowseItem as ApiBrowseItem
import media.watchmy.client.core.api.models.MovieTrailer as ApiMovieTrailer
import media.watchmy.client.core.api.models.OnlineSubtitle
import media.watchmy.client.core.api.models.SubtitleQuota
import media.watchmy.client.core.api.models.SubtitleSearch
import media.watchmy.client.core.api.models.SubtitleTrack
import media.watchmy.client.core.api.models.VideoPlan
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.onlineLanguageLabel
import media.watchmy.client.model.languageName
import media.watchmy.client.model.subtitleFormatName
import media.watchmy.client.model.videoLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingTest {
    @Test
    fun `a Recently added card for one episode keeps it, and others and older servers have none`() {
        val card = ApiBrowseItem(
            addedAt = "2026-11-03T12:00:00Z", genres = emptyList(), hdr = false, id = 7, isNew = true, kind = ApiBrowseItem.Kind.show,
            libraryId = 2, overview = "", title = "Harbor Lights", totalSize = 0, watched = false, subtitle = "S00E03", episodeId = 70003, seasonNumber = 0,
        )
        val one = card.toModel()!!
        assertEquals(70003L, one.episodeId)
        assertEquals("Specials keep their season", 0, one.episodeSeason)
        val older = card.copy(episodeId = null, seasonNumber = null).toModel()!!
        assertNull(older.episodeId)
        assertNull(older.episodeSeason)
    }

    @Test
    fun `audio labels name the language, codec and layout`() {
        assertEquals("English · TrueHD 7.1 (Default)", AudioTrack(8, "truehd", true, 1, language = "eng").toModel().label)
        assertEquals("German · AC3 5.1 · Commentary", AudioTrack(6, "ac3", false, 2, language = "ger", title = "Commentary").toModel().label)
        assertEquals("AAC Stereo", AudioTrack(2, "aac", false, 3).toModel().label)
    }

    @Test
    fun `subtitle labels say forced`() {
        val forced = SubtitleTrack(false, "subrip", false, false, true, SubtitleTrack.Format.webvtt, "s3", source = SubtitleTrack.Source.embedded, language = "spa").toOption()
        assertEquals("Spanish (Forced)", forced.label)
        val sdh = SubtitleTrack(false, "subrip", false, true, false, SubtitleTrack.Format.webvtt, "x0", source = SubtitleTrack.Source.sidecar, language = "en", title = "SDH").toOption()
        assertEquals("English · SDH", sdh.label)
        assertEquals("Signs", SubtitleTrack(true, "hdmv_pgs_subtitle", false, false, false, SubtitleTrack.Format.image, "s4", source = SubtitleTrack.Source.embedded, title = "Signs").toOption().label)
    }

    @Test
    fun `subtitle tracks say where they come from`() {
        val online = SubtitleTrack(false, "subrip", false, true, false, SubtitleTrack.Format.webvtt, "d3", source = SubtitleTrack.Source.opensubtitles, hearingImpaired = true, language = "en", release = "Film.2020.1080p").toOption()
        assertEquals(SubtitleSource.Online, online.source)
        assertEquals("Film.2020.1080p", online.release)
        assertTrue(online.hearingImpaired)
        assertEquals(SubtitleSource.Sidecar, SubtitleTrack(false, "subrip", false, true, false, SubtitleTrack.Format.webvtt, "x0", source = SubtitleTrack.Source.sidecar).toOption().source)
        // An older server sends no source: the ID says.
        assertEquals(SubtitleSource.Online, subtitleSource(null, "d7", external = true))
        assertEquals(SubtitleSource.Sidecar, subtitleSource(null, "x1", external = true))
        assertEquals(SubtitleSource.Embedded, subtitleSource("unknown_default_open_api", "s2", external = false))
    }

    @Test
    fun `online search results become the picker's model`() {
        val search = SubtitleSearch(
            attribution = "Subtitles provided by OpenSubtitles.com", available = true, canDownload = true, languages = listOf("en", "pt-br"),
            provider = SubtitleSearch.Provider.opensubtitles, quota = SubtitleQuota(remaining = -1, signedIn = false, resetAt = "2026-10-31T00:00:00Z"),
            results = listOf(
                OnlineSubtitle(false, 10, false, true, false, "77", "en", "Film.2020.1080p", true),
                OnlineSubtitle(true, 3, false, false, true, "78", "pt-br", "Film.2020.720p", false, trackId = "d4"),
            ),
        )
        val model = search.toModel()!!
        assertEquals(listOf("77", "78"), model.results.map { it.id })
        assertEquals("English", model.results[0].label)
        assertEquals("Portuguese (Brazil)", onlineLanguageLabel("pt-br"))
        assertTrue(model.results[0].hashMatch)
        assertEquals("d4", model.results[1].trackId)
        assertNull("-1 is not known", model.remaining)
        assertEquals(1_793_404_800_000L, model.resetAtMs)
        assertNull(search.copy(available = false).toModel())
        assertEquals(0, search.copy(quota = search.quota.copy(remaining = 0)).toModel()!!.remaining)
    }

    @Test
    fun `subtitle formats go by their ffprobe codec`() {
        assertEquals("SRT", subtitleFormatName("subrip"))
        assertEquals("PGS", subtitleFormatName("hdmv_pgs_subtitle"))
        assertEquals("ASS", subtitleFormatName("ass"))
        assertEquals("VobSub", subtitleFormatName("dvd_subtitle"))
        assertEquals("EIA_608", subtitleFormatName("eia_608"))
        assertNull(subtitleFormatName(""))
        assertEquals("PGS", SubtitleTrack(true, "hdmv_pgs_subtitle", false, false, false, SubtitleTrack.Format.image, "s4", source = SubtitleTrack.Source.embedded).toOption().format)
    }

    @Test
    fun `the decision's video says HDR only when it is sent`() {
        assertEquals(StreamVideo(3840, 2160, "Dolby Vision"), VideoPlan(copy = true, toneMap = false, hdr = "dolbyvision", width = 3840, height = 2160).toModel())
        assertEquals(StreamVideo(1920, 1080, null), VideoPlan(copy = false, toneMap = true, hdr = "hdr10", width = 1920, height = 1080).toModel())
        assertNull(VideoPlan(copy = true, toneMap = false).toModel())
    }

    @Test
    fun `Dolby Vision kept is named so, and a base layer by what the screen gets`() {
        val kept = VideoPlan(copy = true, toneMap = false, hdr = "dolbyvision", dvProfile = 8, width = 3840, height = 2160).toModel()!!
        assertEquals("4K Dolby Vision", videoLabel(kept))
        // Profile 8.1 and 8.4 without the device's key: their HDR10 and HLG base layers.
        assertEquals("4K HDR", videoLabel(VideoPlan(copy = true, toneMap = false, hdr = "hdr10", width = 3840, height = 2160).toModel()!!))
        assertEquals("4K HDR", videoLabel(VideoPlan(copy = true, toneMap = false, hdr = "hlg", width = 3840, height = 2160).toModel()!!))
        // Profile 5 transcoded for a screen without it goes to SDR.
        assertEquals("1080p", videoLabel(VideoPlan(copy = false, toneMap = true, hdr = "dolbyvision", width = 1920, height = 1080).toModel()!!))
    }

    @Test
    fun `languages, containers and times`() {
        assertEquals("French", languageName("fre"))
        assertEquals("French", languageName("fra"))
        assertNull(languageName("und"))
        assertEquals("qaa", languageName("qaa"))
        assertEquals("MKV", containerName("matroska,webm"))
        assertEquals("MP4", containerName("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals(1_798_650_575_677L, epochMs("2026-12-30T13:09:35.67713-04:00"))
        assertNull(epochMs("not a time"))
        assertNull(epochMs(null))
    }

    @Test
    fun `trailers keep the sources the app plays, and skip entries it can't`() {
        val listed = listOf(
            ApiMovieTrailer(name = "Trailer", source = "file", fileId = 41, durationMs = 30_000),
            ApiMovieTrailer(name = "", source = "file", fileId = 42),
            ApiMovieTrailer(name = "No id", source = "file"),
            ApiMovieTrailer(name = "Official Trailer", source = "youtube", key = "WhWc3b3KhnY"),
            ApiMovieTrailer(name = "Not an id", source = "youtube", key = "a\"><script>"),
            ApiMovieTrailer(name = "No key", source = "youtube"),
            ApiMovieTrailer(name = "Later", source = "vimeo", key = "123"),
        )
        assertEquals(
            listOf(Trailer.File("Trailer", 41, 30_000), Trailer.File("Trailer", 42), Trailer.YouTube("Official Trailer", "WhWc3b3KhnY")),
            listed.mapNotNull { it.toModel() },
        )
    }
}
