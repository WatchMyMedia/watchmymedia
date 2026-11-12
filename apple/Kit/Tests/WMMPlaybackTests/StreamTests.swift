import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// android/core/playback/.../StreamTest.kt.
@MainActor
@Suite struct StreamTests {
    let h = Harness()
    let resumeRequest = PlayRequest(fileID: 1, title: "Lighthouse")

    // MARK: Decision and resume

    @Test func asksForVODWithTheProfileAndClient() async throws {
        await h.start(resumeRequest, decision())
        let r = try #require(h.gateway.decisions.first)
        #expect(h.gateway.decisions.count == 1)
        #expect(r.vod)
        #expect(r.playID == nil)
        #expect(r.startMs == nil)
        #expect(r.client == client)
        #expect(r.profile == phoneProfile)
        #expect(r.quality == .original)
        // The settings are loaded for Skip and Next Episode.
        #expect(h.prefs.refreshes == 1)
    }

    @Test func vodResumesBySeekingThePlayer() async {
        await h.start(resumeRequest, decision(resumeMs: 300_000))
        #expect(h.gateway.decisions.count == 1)
        #expect(h.player.loads.map(\.startPositionMs) == [300_000])
        #expect(h.state.positionMs == 300_000)
    }

    @Test func eventPlaylistsResumeWithANewSessionThere() async {
        await h.start(resumeRequest) { r in
            r.startMs == nil ? decision(vod: false, resumeMs: 300_000) : decision(sessionID: "s2", vod: false, startMs: r.startMs!)
        }
        let second = h.gateway.decisions[1]
        #expect(second.startMs == 300_000)
        #expect(second.playID == "a1")
        #expect(h.gateway.stoppedSessions == ["s1"])
        #expect(h.player.loads.map(\.startPositionMs) == [0])
        #expect(h.player.loads.first?.live == true)
        #expect(h.state.positionMs == 300_000)
    }

    @Test func anExplicitStartOrAShortResumeDoesNotResume() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision(resumeMs: 300_000))
        #expect(h.player.loads.last?.startPositionMs == 0)
        await h.start(resumeRequest, decision(resumeMs: 4_000))
        #expect(h.player.loads.last?.startPositionMs == 0)
    }

    // MARK: Event playlists

    @Test func eventPlaylistPositionsAreOffsetAndFarSeeksReRequest() async {
        await h.start(PlayRequest(fileID: 1, startMs: 600_000, title: "Lighthouse")) { r in
            decision(sessionID: r.playID == nil ? "e1" : "e2", vod: false, startMs: r.playID == nil ? 600_000 : r.startMs! - 1_000)
        }
        #expect(h.player.loads.map(\.startPositionMs) == [0])
        #expect(h.state.positionMs == 600_000)
        await h.advance(5_000)
        #expect(h.state.positionMs == 605_000)

        // Within the buffer: the player seeks, no new request.
        h.controller.seek(toMs: 615_000)
        await h.run()
        #expect(h.player.seeks == [15_000])
        #expect(h.gateway.decisions.count == 1)
        #expect(h.state.positionMs == 615_000)

        // Past it: the same play, a new session at the target.
        h.controller.seek(toMs: 1_200_000)
        await h.run()
        let again = h.gateway.decisions.last
        #expect(again?.playID == "a1")
        #expect(again?.startMs == 1_200_000)
        // The server started at a keyframe a second earlier: the player starts a second in.
        #expect(h.player.loads.last?.startPositionMs == 1_000)
        #expect(h.state.positionMs == 1_200_000)
        #expect(h.gateway.stoppedSessions == ["e1"])
        await h.advance(10_000)
        #expect(h.state.positionMs == 1_210_000)

        // Before the session's start also needs a new one.
        h.controller.seek(toMs: 1_000_000)
        await h.run()
        #expect(h.gateway.decisions.last?.startMs == 1_000_000)
    }

    @Test func vodSeeksNeverReRequest() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        h.controller.seek(toMs: 40 * minute)
        h.controller.seek(byMs: -10_000)
        await h.run()
        #expect(h.player.seeks == [40 * minute, 40 * minute - 10_000])
        #expect(h.gateway.decisions.count == 1)
    }

    // MARK: Media token

    @Test func theTokenGoesOnTheStreamAndSubtitleURLs() async throws {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse", subtitleID: "s3"), decision(subtitles: [textSub("s3")]))
        let vod = try #require(h.player.loads.last)
        #expect(vod.url == url("http://srv/api/playback/s1/master.m3u8?access_token=tok1"))
        #expect(vod.hls)
        #expect(!vod.live)
        #expect(vod.mimeType == "application/vnd.apple.mpegurl")
        #expect(h.gateway.subtitleFetches == [url("http://srv/api/files/1/subtitles/s3.vtt?access_token=tok1")])

        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse", subtitleID: "x0"), decision(protocol: .file, vod: false, subtitles: [textSub("s3"), textSub("x0")]))
        let direct = try #require(h.player.loads.last)
        #expect(direct.url == url("http://srv/api/files/1/stream?play=a1&access_token=tok2"))
        #expect(!direct.hls)
        #expect(h.gateway.subtitleFetches.last == url("http://srv/api/files/1/subtitles/x0.vtt?access_token=tok2"))
    }

    @Test func aPlayThatOutlivesItsTokenRestartsWithANewOne() async {
        h.gateway.tokenLifetimeMs = 40 * minute
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(39 * minute - 250)
        #expect(h.gateway.decisions.count == 1)
        await h.advance(250)
        #expect(h.gateway.tokens == 2)
        let again = h.gateway.decisions.last
        #expect(again?.playID == "a1")
        #expect(again?.startMs == 39 * minute)
        #expect(h.player.loads.last?.url == url("http://srv/api/playback/s1/master.m3u8?access_token=tok2"))
        #expect(h.player.loads.last?.startPositionMs == 39 * minute)
        #expect(h.state.status == .playing)
    }

    @Test func aRefusedStreamGetsANewTokenOnce() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(20_000)
        h.player.fail(PlayerFailure(kind: .http, detail: "401", httpStatus: 401))
        await h.run()
        #expect(h.gateway.tokens == 2)
        #expect(h.gateway.decisions.last?.startMs == 20_000)
        #expect(h.state.error == nil)
        h.player.fail(PlayerFailure(kind: .http, detail: "401", httpStatus: 401))
        h.player.fail(PlayerFailure(kind: .http, detail: "401", httpStatus: 401))
        await h.run()
        h.player.fail(PlayerFailure(kind: .http, detail: "401", httpStatus: 401))
        await h.run()
        #expect(h.state.status == .error)
        #expect(h.state.error == "The server refused the stream. Try signing in again.")
    }

    // MARK: Tracks and quality

    @Test func audioChangesReRequestTheSamePlay() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(30_000)
        h.gateway.answer = { decision(sessionID: "s2", selectedAudio: $0.audioIndex) }
        h.controller.selectAudio(2)
        await h.run()
        let r = h.gateway.decisions.last
        #expect(r?.audioIndex == 2)
        #expect(r?.playID == "a1")
        #expect(r?.startMs == 30_000)
        #expect(h.state.selectedAudio == 2)
        #expect(h.player.loads.last?.startPositionMs == 30_000)
        #expect(h.gateway.stoppedSessions == ["s1"])
    }

    @Test func directPlaySwitchesEmbeddedAudioHereWhenItCan() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision(protocol: .file, vod: false))
        h.controller.selectAudio(2)
        await h.run()
        #expect(h.player.audioOrdinal == 1)
        #expect(h.gateway.decisions.count == 1)
        #expect(h.state.selectedAudio == 2)

        // A track this player cannot switch to goes back to the server.
        h.player.playsAudio = { _ in false }
        h.controller.selectAudio(1)
        await h.run()
        #expect(h.gateway.decisions.count == 2)
        #expect(h.gateway.decisions.last?.audioIndex == 1)
    }

    @Test func textSubtitlesSwitchHereAndImageSubtitlesBurnIn() async {
        let subs = [textSub("s3"), textSub("s4", "image"), textSub("x0")]
        h.gateway.subtitleFiles = [
            "/api/files/1/subtitles/s3.vtt": "WEBVTT\n\n01:02.000 --> 01:05.000\nFrom s3\n",
            "/api/files/1/subtitles/x0.vtt": "WEBVTT\n\n01:02.000 --> 01:05.000\nFrom x0\n",
        ]
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse")) { [h] r in
            decision(sessionID: "s\(h.gateway.decisions.count)", subtitles: subs, burnInSubtitleID: r.burnIn ? r.subtitleID : nil)
        }
        #expect(h.state.cues.isEmpty)
        #expect(h.gateway.subtitleFetches.isEmpty)
        h.controller.selectSubtitle("x0")
        await h.run()
        #expect(h.state.selectedSubtitle == "x0")
        #expect(h.gateway.decisions.count == 1)

        await h.advance(62_000)
        #expect(h.state.cues == [SubtitleCue(startMs: 62_000, endMs: 65_000, text: "From x0")])
        h.controller.selectSubtitle("s4")
        await h.run()
        let burn = h.gateway.decisions.last
        #expect(burn?.subtitleID == "s4")
        #expect(burn?.burnIn == true)
        #expect(burn?.playID == "a1")
        #expect(burn?.startMs == 62_000)
        #expect(h.state.selectedSubtitle == "s4")
        // The server draws it into the video.
        #expect(h.state.cues.isEmpty)

        // Back to text: a stream without the burn-in, with the cues drawn here.
        h.controller.selectSubtitle("s3")
        await h.run()
        let text = h.gateway.decisions.last
        #expect(text?.subtitleID == "s3")
        #expect(text?.burnIn == false)
        #expect(h.state.cues == [SubtitleCue(startMs: 62_000, endMs: 65_000, text: "From s3")])

        h.controller.selectSubtitle(nil)
        await h.run()
        #expect(h.state.cues.isEmpty)
        #expect(h.state.selectedSubtitle == nil)
        #expect(h.gateway.decisions.count == 3)
    }

    @Test func qualityChangesReRequestWithTheCapsAndAreRemembered() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(15_000)
        h.controller.selectQuality(.hd)
        await h.run()
        let r = h.gateway.decisions.last
        #expect(r?.quality == .hd)
        #expect(r?.profile.maxWidth == 1280)
        #expect(r?.profile.maxHeight == 720)
        #expect(r?.profile.maxBitrate == 4_000_000)
        #expect(r?.playID == "a1")
        #expect(r?.startMs == 15_000)
        #expect(h.state.quality == .hd)
        #expect(h.prefs.quality == .hd)
    }

    @Test func aPausedPlayStaysPausedAcrossAReRequest() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        h.controller.pause()
        h.controller.selectQuality(.sd)
        await h.run()
        #expect(h.player.loads.last?.playWhenReady == false)
        #expect(h.state.status == .paused)
    }

    @Test func aPlayWhileAReRequestIsOutCarriesIntoTheNewStream() async {
        // The picker pauses while open and plays on closing, after the pick has gone to the server.
        let subs = [textSub("s3"), textSub("s4", "image")]
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse")) { r in
            decision(subtitles: subs, burnInSubtitleID: r.burnIn ? r.subtitleID : nil)
        }
        h.controller.pause()
        h.controller.selectSubtitle("s4")
        h.controller.play()
        await h.run()
        #expect(h.gateway.decisions.last?.burnIn == true)
        #expect(h.player.loads.last?.playWhenReady == true)
        #expect(h.state.status == .playing)

        h.controller.pause()
        h.controller.selectQuality(.sd)
        h.controller.play()
        await h.run()
        #expect(h.player.loads.count == 3)
        #expect(h.player.loads.last?.playWhenReady == true)
    }

    // MARK: Errors

    @Test func aRefusedDecisionIsAnError() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse")) { _ in throw APIError(status: 503, message: "Transcoder not available.") }
        #expect(h.state.status == .error)
        #expect(h.state.error == "Transcoder not available.")
        #expect(h.gateway.reports.isEmpty)
    }

    @Test func playerErrorsAreMappedAndPlayRetries() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse"), decision())
        await h.advance(10_000)
        h.player.fail(PlayerFailure(kind: .decoder, detail: "AVFoundationErrorDomain -11821"))
        await h.run()
        #expect(h.state.status == .error)
        #expect(h.state.error == "This device could not decode the stream.")
        h.controller.play()
        await h.run()
        #expect(h.gateway.decisions.count == 2)
        #expect(h.gateway.decisions.last?.startMs == 10_000)
        #expect(h.state.status == .playing)
        #expect(h.state.error == nil)
    }

    @Test func decisionDetailsReachTheState() async {
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Lighthouse", subtitle: "A film"), decision(subtitles: [textSub("s3")], video: StreamVideo(width: 3840, height: 2160, hdr: "HDR10")))
        let s = h.state
        #expect(s.title == "Lighthouse")
        #expect(s.subtitle == "A film")
        #expect(s.durationMs == 50 * minute)
        #expect(s.audio == [english, commentary])
        #expect(s.selectedAudio == 1)
        #expect(s.subtitles.map(\.id) == ["s3"])
        #expect(s.mode == .remux)
        #expect(s.video == StreamVideo(width: 3840, height: 2160, hdr: "HDR10"))
        #expect(s.bufferedMs == 30_000)
        #expect(h.controller.player == nil)
    }

    @Test func theScrubberShowsTheStartWhileTheServerDecides() async {
        h.gateway.answer = { _ in decision() }
        h.controller.start(PlayRequest(fileID: 1, startMs: 120_000, title: "Lighthouse", subtitle: "A film", backdropPath: "/b.jpg"))
        #expect(h.state.status == .loading)
        #expect(h.state.positionMs == 120_000)
        #expect(h.state.title == "Lighthouse")
        #expect(h.state.backdropPath == "/b.jpg")
        await h.run()
        #expect(h.state.status == .playing)
    }
}
