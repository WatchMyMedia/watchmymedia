package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.core.playback.profile.ProfileProvider
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.Trickplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTest {
    private val h = Harness()

    @After
    fun tearDown() = h.release()

    private val resumeRequest = PlayRequest(1, null, "Lighthouse")

    // --- decision and resume ---

    @Test
    fun asksForVodWithTheProfileAndClient() {
        h.start(resumeRequest, decision())
        val r = h.gateway.decisions.single()
        assertTrue(r.vod)
        assertNull(r.playId)
        assertNull(r.startMs)
        assertEquals(CLIENT, r.client)
        assertEquals(TV_PROFILE, r.profile)
        assertEquals(StreamQuality.Original, r.quality)
    }

    @Test
    fun thePlayerIsGivenTheBackdropForTheNotification() {
        h.start(PlayRequest(1, null, "Lighthouse", backdropPath = "/b.jpg"), decision())
        assertNull("No library to name it", h.player.loads.single().artworkUrl)

        val named = Harness(lookups = object : PlaybackLookups by PlaybackLookups.None {
            override fun artworkUrl(path: String?) = path?.let { "http://srv/api/images/w780$it" }
        })
        named.start(PlayRequest(1, null, "Lighthouse", backdropPath = "/b.jpg"), decision())
        assertEquals("http://srv/api/images/w780/b.jpg", named.player.loads.single().artworkUrl)
        named.release()
    }

    @Test
    fun theFrameRateThePlayerReadsIsShownForMatchFrameRate() {
        h.start(resumeRequest, decision())
        assertNull(h.state.frameRate)
        h.player.readFrameRate(23.976f)
        h.run()
        assertEquals(23.976f, h.state.frameRate)
    }

    @Test
    fun vodResumesBySeekingThePlayer() {
        h.start(resumeRequest, decision(resumeMs = 300_000))
        assertEquals(1, h.gateway.decisions.size)
        assertEquals(300_000L, h.player.loads.single().startPositionMs)
        assertEquals(300_000L, h.progress.positionMs)
    }

    @Test
    fun eventPlaylistsResumeWithANewSessionThere() {
        h.start(resumeRequest) { r ->
            if (r.startMs == null) decision(vod = false, resumeMs = 300_000) else decision(vod = false, startMs = r.startMs!!, sessionId = "s2")
        }
        val second = h.gateway.decisions[1]
        assertEquals(300_000L, second.startMs)
        assertEquals("a1", second.playId)
        assertEquals(listOf("s1"), h.gateway.stoppedSessions)
        assertEquals(0L, h.player.loads.single().startPositionMs)
        assertTrue(h.player.loads.single().live)
        assertEquals(300_000L, h.progress.positionMs)
    }

    @Test
    fun theDecisionsSeekThumbnailsReachTheScrubberAndTheNextFileStartsWithout() {
        val tiles = Trickplay(10_000, 320, 180, 10, 10, 300, 3, "http://srv/api/files/1/trickplay/9c1e07a2/{sheet}.jpg")
        h.start(resumeRequest, decision(trickplay = tiles))
        h.advance(1_000)
        assertEquals(tiles, h.state.trickplay)
        h.start(PlayRequest(2, null, "Squall Line")) { decision() }
        h.advance(1_000)
        assertNull("none until the server has made them", h.state.trickplay)
    }

    @Test
    fun anExplicitStartOrAShortResumeDoesNotResume() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(resumeMs = 300_000))
        assertEquals(0L, h.player.loads.single().startPositionMs)
        h.start(resumeRequest, decision(resumeMs = 4_000))
        assertEquals(0L, h.player.loads.last().startPositionMs)
    }

    // --- event playlists ---

    @Test
    fun eventPlaylistPositionsAreOffsetAndFarSeeksReRequest() {
        h.start(PlayRequest(1, 600_000, "Lighthouse")) { r ->
            decision(vod = false, startMs = if (r.playId == null) 600_000 else r.startMs!! - 1_000, sessionId = if (r.playId == null) "e1" else "e2")
        }
        assertEquals(0L, h.player.loads.single().startPositionMs)
        assertEquals(600_000L, h.progress.positionMs)
        h.advance(5_000)
        assertEquals(605_000L, h.progress.positionMs)

        // Within the buffer: the player seeks, no new request.
        h.controller.seekTo(615_000)
        h.run()
        assertEquals(listOf(15_000L), h.player.seeks)
        assertEquals(1, h.gateway.decisions.size)
        assertEquals(615_000L, h.progress.positionMs)

        // Past it: the same play, a new session at the target.
        h.controller.seekTo(1_200_000)
        h.run()
        val again = h.gateway.decisions.last()
        assertEquals("a1", again.playId)
        assertEquals(1_200_000L, again.startMs)
        // The server started at a keyframe a second earlier: the player starts a second in.
        assertEquals(1_000L, h.player.loads.last().startPositionMs)
        assertEquals(1_200_000L, h.progress.positionMs)
        assertEquals(listOf("e1"), h.gateway.stoppedSessions)
        h.advance(10_000)
        assertEquals(1_210_000L, h.progress.positionMs)

        // Before the session's start also needs a new one.
        h.controller.seekTo(1_000_000)
        h.run()
        assertEquals(1_000_000L, h.gateway.decisions.last().startMs)
    }

    @Test
    fun vodSeeksNeverReRequest() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.controller.seekTo(40 * MIN)
        h.controller.seekBy(-10_000)
        h.run()
        assertEquals(listOf(40 * MIN, 40 * MIN - 10_000), h.player.seeks)
        assertEquals(1, h.gateway.decisions.size)
    }

    // --- media token ---

    @Test
    fun theTokenGoesOnTheStreamAndSubtitleUrls() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(subtitles = listOf(textSub("s3"))))
        val vod = h.player.loads.single()
        assertEquals("http://srv/api/playback/s1/master.m3u8?access_token=tok1", vod.url)
        assertTrue(vod.hls)
        // VOD playlists carry the subtitles as renditions.
        assertTrue(vod.subtitles.isEmpty())

        h.start(PlayRequest(1, 0, "Lighthouse"), decision(protocol = StreamProtocol.File, vod = false, subtitles = listOf(textSub("x1"), textSub("x0", "ass", "http://srv/api/files/1/subtitles/x0.ass"))))
        val direct = h.player.loads.last()
        assertEquals("http://srv/api/files/1/stream?play=a1&access_token=tok2", direct.url)
        assertFalse(direct.hls)
        assertEquals("ASS is offered through server burn-in, not a flattened side-loaded track", listOf("http://srv/api/files/1/subtitles/x1.vtt?access_token=tok2"), direct.subtitles.map { it.url })
        assertEquals(listOf("text/vtt"), direct.subtitles.map { it.mimeType })
    }

    @Test
    fun theTokenStaysOffUrlsOnOtherServers() {
        h.start(
            PlayRequest(1, 0, "Lighthouse"),
            decision(
                protocol = StreamProtocol.File,
                vod = false,
                url = "https://cdn.example/api/files/1/stream?play=a1",
                subtitles = listOf(textSub("x1"), textSub("x0", "webvtt", "https://cdn.example/x0.vtt")),
            ),
        )
        val load = h.player.loads.single()
        assertEquals("https://cdn.example/api/files/1/stream?play=a1", load.url)
        assertEquals(listOf("http://srv/api/files/1/subtitles/x1.vtt?access_token=tok1", "https://cdn.example/x0.vtt"), load.subtitles.map { it.url })
    }

    @Test
    fun aPlayThatOutlivesItsTokenRestartsWithANewOne() {
        h.gateway.tokenLifetimeMs = 40 * MIN
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(39 * MIN - 250)
        assertEquals(1, h.gateway.decisions.size)
        h.advance(250)
        assertEquals(2, h.gateway.tokens)
        val again = h.gateway.decisions.last()
        assertEquals("a1", again.playId)
        assertEquals(39 * MIN, again.startMs)
        assertEquals("http://srv/api/playback/s1/master.m3u8?access_token=tok2", h.player.loads.last().url)
        assertEquals(39 * MIN, h.player.loads.last().startPositionMs)
        assertEquals(PlayerStatus.Playing, h.state.status)
    }

    @Test
    fun aRefusedStreamGetsANewTokenOnce() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(20_000)
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Http, "401", 401))
        h.run()
        assertEquals(2, h.gateway.tokens)
        assertEquals(20_000L, h.gateway.decisions.last().startMs)
        assertNull(h.state.error)
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Http, "401", 401))
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Http, "401", 401))
        h.run()
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Http, "401", 401))
        h.run()
        assertEquals(PlayerStatus.Error, h.state.status)
        assertEquals("The server refused the stream. Try signing in again.", h.state.error)
    }

    // --- tracks and quality ---

    @Test
    fun audioChangesReRequestTheSamePlay() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(30_000)
        h.gateway.decide = { decision(selectedAudio = it.audioIndex, sessionId = "s2") }
        h.controller.selectAudio(2)
        h.run()
        val r = h.gateway.decisions.last()
        assertEquals(2, r.audioIndex)
        assertEquals("a1", r.playId)
        assertEquals(30_000L, r.startMs)
        assertEquals(2, h.state.selectedAudio)
        assertEquals(30_000L, h.player.loads.last().startPositionMs)
        assertEquals(listOf("s1"), h.gateway.stoppedSessions)
    }

    @Test
    fun directPlaySwitchesEmbeddedAudioHereWhenItCan() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(protocol = StreamProtocol.File, vod = false))
        assertEquals(0, h.player.audioOrdinal)
        h.controller.selectAudio(2)
        h.run()
        assertEquals(1, h.player.audioOrdinal)
        assertEquals(1, h.gateway.decisions.size)
        assertEquals(2, h.state.selectedAudio)

        // A track this device cannot play goes back to the server.
        h.player.playsAudio = { false }
        h.controller.selectAudio(1)
        h.run()
        assertEquals(2, h.gateway.decisions.size)
        assertEquals(1, h.gateway.decisions.last().audioIndex)
    }

    @Test
    fun directPlayReadsEmbeddedSubtitlesFromTheFileAndSideloadsTheRest() {
        // The server's copy of an embedded track reads the whole video first: on a long film
        // from a spinning disk that took longer than the player waits, and fought playback.
        val subs = listOf(textSub("s3"), textSub("s4", "image"), textSub("s5", "ass"), textSub("x0"), textSub("d12"))
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(protocol = StreamProtocol.File, vod = false, subtitles = subs).copy(subtitleId = "s3"))
        val load = h.player.loads.single()
        assertEquals(listOf("x0", "d12"), load.subtitles.map { it.id })
        assertEquals(listOf("s3", "s4", "s5"), load.embeddedSubtitles.map { it.id })
        assertEquals(TextSelection.Embedded("s3"), load.text)

        h.controller.selectSubtitle("x0"); h.run()
        assertEquals(TextSelection.Sideloaded("x0"), h.player.text)
        h.controller.selectSubtitle("s3"); h.run()
        assertEquals(TextSelection.Embedded("s3"), h.player.text)
        assertEquals("Switched in the player, not by the server", 1, h.gateway.decisions.size)

        // A remux's event playlist has no tracks of the file's own: it side-loads them.
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(vod = false, subtitles = subs))
        val event = h.player.loads.last()
        assertEquals(listOf("s3", "x0", "d12"), event.subtitles.map { it.id })
        assertTrue(event.embeddedSubtitles.isEmpty())
    }

    @Test
    fun anEmbeddedSubtitleThePlayerCantFindIsDroppedAndPlayGoesOn() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(protocol = StreamProtocol.File, vod = false, subtitles = listOf(textSub("s3"), textSub("x0"))).copy(subtitleId = "s3"))
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Subtitle, "not in the file", subtitleId = "s3")); h.run()
        assertEquals(1, h.player.loads.size)
        assertNull(h.state.error)
        assertNull(h.state.selectedSubtitle)
        assertEquals(TextSelection.Off, h.player.text)
        assertEquals(listOf("x0"), h.state.subtitles.map { it.id })
        assertTrue(h.state.note.orEmpty().contains("keep playing"))
    }

    @Test
    fun textSubtitlesSwitchHereAndImageSubtitlesBurnIn() {
        val subs = listOf(textSub("s3"), textSub("s4", "image"), textSub("x0"))
        h.start(PlayRequest(1, 0, "Lighthouse")) { r ->
            decision(subtitles = subs, burnInSubtitleId = if (r.burnIn) r.subtitleId else null, sessionId = "s${h.gateway.decisions.size}")
        }
        assertEquals(TextSelection.Off, h.player.text)
        h.controller.selectSubtitle("x0")
        h.run()
        assertEquals(TextSelection.Rendition(1), h.player.text)
        assertEquals("x0", h.state.selectedSubtitle)
        assertEquals(1, h.gateway.decisions.size)

        h.advance(60_000)
        h.controller.selectSubtitle("s4")
        h.run()
        val burn = h.gateway.decisions.last()
        assertEquals("s4", burn.subtitleId)
        assertTrue(burn.burnIn)
        assertEquals("a1", burn.playId)
        assertEquals(60_000L, burn.startMs)
        assertEquals("s4", h.state.selectedSubtitle)
        assertEquals(TextSelection.Off, h.player.text)

        // Back to text: a stream without the burn-in, showing the rendition.
        h.controller.selectSubtitle("s3")
        h.run()
        val text = h.gateway.decisions.last()
        assertEquals("s3", text.subtitleId)
        assertFalse(text.burnIn)
        assertEquals(TextSelection.Rendition(0), h.player.text)

        h.controller.selectSubtitle(null)
        h.run()
        assertEquals(TextSelection.Off, h.player.text)
        assertNull(h.state.selectedSubtitle)
    }

    @Test
    fun qualityChangesReRequestWithTheCapsAndAreRemembered() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(15_000)
        h.controller.selectQuality(StreamQuality.Hd)
        h.run()
        val r = h.gateway.decisions.last()
        assertEquals(StreamQuality.Hd, r.quality)
        assertEquals(1280, r.profile.maxWidth)
        assertEquals(720, r.profile.maxHeight)
        assertEquals(4_000_000L, r.profile.maxBitrate)
        assertEquals("a1", r.playId)
        assertEquals(15_000L, r.startMs)
        assertEquals(StreamQuality.Hd, h.state.quality)
        assertEquals(StreamQuality.Hd, h.prefs.quality.value)
    }

    @Test
    fun aPausedPlayStaysPausedAcrossAReRequest() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.controller.pause()
        h.controller.selectQuality(StreamQuality.Sd)
        h.run()
        assertFalse(h.player.loads.last().playWhenReady)
        assertEquals(PlayerStatus.Paused, h.state.status)
    }

    @Test
    fun aPlayWhileAReRequestIsOutCarriesIntoTheNewStream() {
        // The phone picker pauses while open and plays on closing, after the pick has gone to the server.
        val subs = listOf(textSub("s3"), textSub("s4", "image"))
        h.start(PlayRequest(1, 0, "Lighthouse")) { r -> decision(subtitles = subs, burnInSubtitleId = if (r.burnIn) r.subtitleId else null) }
        h.controller.pause()
        h.controller.selectSubtitle("s4")
        h.controller.play()
        h.run()
        assertTrue(h.gateway.decisions.last().burnIn)
        assertTrue(h.player.loads.last().playWhenReady)
        assertEquals(PlayerStatus.Playing, h.state.status)

        h.controller.pause()
        h.controller.selectQuality(StreamQuality.Sd)
        h.controller.play()
        h.run()
        assertEquals(3, h.player.loads.size)
        assertTrue(h.player.loads.last().playWhenReady)
    }

    // --- errors ---

    @Test
    fun aRefusedDecisionIsAnError() {
        h.start(PlayRequest(1, 0, "Lighthouse")) { throw ApiException(503, "transcoder not available") }
        assertEquals(PlayerStatus.Error, h.state.status)
        assertEquals("transcoder not available", h.state.error)
        assertTrue(h.gateway.reports.isEmpty())
    }

    @Test
    fun playerErrorsAreMappedAndPlayRetries() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.advance(10_000)
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Decoder, "ERROR_CODE_DECODING_FAILED"))
        h.run()
        assertEquals(PlayerStatus.Error, h.state.status)
        assertEquals("This device could not decode the stream.", h.state.error)
        h.controller.play()
        h.run()
        assertEquals(2, h.gateway.decisions.size)
        assertEquals(10_000L, h.gateway.decisions.last().startMs)
        assertEquals(PlayerStatus.Playing, h.state.status)
        assertNull(h.state.error)
    }

    @Test
    fun aFailingAudioDecoderIsDroppedAndThePlayAskedForAgain() {
        // As on a Pixel: the profile offers eac3 for a decoder that then fails.
        val profiles = object : ProfileProvider {
            var failed = emptySet<String>()
            override fun current() = TV_PROFILE.copy(audioCodecs = TV_PROFILE.audioCodecs - failed)
            override fun decoderFailed(codec: String) = (codec !in failed).also { failed = failed + codec }
        }
        val ph = Harness(profileProvider = profiles)
        val eac3 = PlayerFailure(PlayerFailure.Kind.Decoder, "ERROR_CODE_DECODING_FAILED", audioMimeType = "audio/eac3")
        ph.start(PlayRequest(1, 0, "Lighthouse"), decision())
        ph.advance(10_000)
        ph.player.fail(eac3)
        ph.run()
        assertNull(ph.state.error)
        val again = ph.gateway.decisions.last()
        assertEquals(2, ph.gateway.decisions.size)
        assertEquals(10_000L, again.startMs)
        assertFalse("eac3" in again.profile.audioCodecs)
        assertEquals(10_000L, ph.player.loads.last().startPositionMs)
        // Failing again on the same codec is an error like any other.
        ph.player.fail(eac3)
        ph.run()
        assertEquals(2, ph.gateway.decisions.size)
        assertEquals("This device could not decode the stream.", ph.state.error)
        ph.release()
    }

    @Test
    fun aVideoDecoderFailureIsNotRetried() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision())
        h.player.fail(PlayerFailure(PlayerFailure.Kind.Decoder, "ERROR_CODE_DECODER_INIT_FAILED"))
        h.run()
        assertEquals(1, h.gateway.decisions.size)
        assertEquals("This device could not decode the stream.", h.state.error)
    }

    @Test
    fun onlyTheProgressMovesWithThePosition() {
        h.start(PlayRequest(1, 0, "Lighthouse"), decision(subtitles = listOf(textSub("s3"))))
        h.advance(1_000)
        val state = h.state
        val subtitles = state.subtitles
        val before = h.progress.positionMs
        h.advance(2_000)
        // Eight ticks later the position has moved, and the screen's state is the same object.
        assertEquals(before + 2_000, h.progress.positionMs)
        assertSame(state, h.state)
        assertSame(subtitles, h.state.subtitles)
        h.controller.pause()
        h.run()
        assertEquals(PlayerStatus.Paused, h.state.status)
    }

    @Test
    fun decisionDetailsReachTheState() {
        h.start(PlayRequest(1, 0, "Lighthouse", "A film"), decision(subtitles = listOf(textSub("s3"))))
        val s = h.state
        assertEquals("Lighthouse", s.title)
        assertEquals("A film", s.subtitle)
        assertEquals(50 * MIN, s.durationMs)
        assertEquals(listOf(ENGLISH, COMMENTARY), s.audio)
        assertEquals(1, s.selectedAudio)
        assertEquals(listOf("s3"), s.subtitles.map { it.id })
        assertEquals(media.watchmy.client.model.PlaybackMode.Remux, s.mode)
        assertEquals(30_000L, h.progress.bufferedMs)
    }
}
