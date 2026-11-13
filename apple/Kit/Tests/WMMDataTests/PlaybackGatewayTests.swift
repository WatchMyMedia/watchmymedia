import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel
import WMMAPI

/// The playback calls against a stub server, the cases of
/// android/core/data/.../PlaybackGatewayTest.kt.
@Suite struct PlaybackGatewayTests {
    let server = StubServer()
    let client = ClientInfo(deviceID: "dev-1", name: "Sam’s iPhone", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0", model: "iPhone 17 Pro")
    let profile = PlaybackProfile(name: "ios", containers: ["mp4", "mov"], videoCodecs: ["h264", "hevc"], audioCodecs: ["aac", "eac3"], maxWidth: 3840, maxHeight: 2160, maxAudioChannels: 6)

    /// The stub's session reads streamed request bodies whole (Support/BodyReadingURLProtocol.swift).
    let session = Router.stubSession()
    let signOut = SignOutFlag()

    var gateway: APIPlaybackGateway { gateway(level: hdrProfileAPILevel) }

    /// The gateway to a server at `level`.
    func gateway(level: Int?) -> APIPlaybackGateway {
        let signOut = signOut
        let api = ServerAPI(baseURL: server.baseURL, session: session, token: { "tok-viewer" }, onSignedOut: { signOut.set() })
        return APIPlaybackGateway(api: { api }, apiLevel: { level })
    }

    /// Answers `routes` ("POST /api/files/4/playback") with JSON text; anything else is 404.
    private func serve(_ routes: [String: (Int, String)]) {
        server.respond { method, path, _ in
            guard let (status, body) = routes["\(method) \(path)"] else { return (404, ["title": "Not Found", "status": 404]) }
            return (status, Data(body.utf8))
        }
    }

    private func requests(_ route: String) -> [URLRequest] {
        server.seen.filter { "\($0.httpMethod ?? "GET") \($0.url?.path ?? "")" == route }
    }

    private func body(_ route: String) throws -> [String: Any] {
        let data = try #require(requests(route).last?.httpBody)
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @Test func aVODDecisionRequestSendsThePlaylistKindQualityCapsClientAndPlay() async throws {
        serve(["POST /api/files/4/playback": (200, decisionHLS)])
        _ = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client, startMs: 30_000, audioIndex: 1, subtitleID: "s2", quality: .fullHD, playID: "d2081dfed30c2b6c"))
        let body = try body("POST /api/files/4/playback")
        #expect(body["playlist"] as? String == "vod")
        #expect(body["quality"] as? String == "1080")
        #expect(body["playId"] as? String == "d2081dfed30c2b6c")
        #expect(body["startMs"] as? Int == 30_000)
        #expect(body["audioIndex"] as? Int == 1)
        #expect(body["subtitleId"] as? String == "s2")
        #expect(body["burnIn"] == nil)
        let sentProfile = try #require(body["profile"] as? [String: Any])
        // The quality narrows the device's caps.
        #expect(sentProfile["maxWidth"] as? Int == 1920)
        #expect(sentProfile["maxHeight"] as? Int == 1080)
        #expect(sentProfile["maxBitrate"] as? Int == 8_000_000)
        #expect(sentProfile["maxAudioChannels"] as? Int == 6)
        #expect(sentProfile["subtitleFormats"] as? [String] == ["webvtt"])
        let sentClient = try #require(body["client"] as? [String: Any])
        #expect(sentClient["deviceId"] as? String == "dev-1")
        #expect(sentClient["platform"] as? String == "ios")
        #expect(sentClient["model"] as? String == "iPhone 17 Pro")
        #expect(requests("POST /api/files/4/playback").last?.value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")

        _ = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client, burnIn: true, vod: false))
        let plain = try self.body("POST /api/files/4/playback")
        // No playlist asks for the default (event).
        #expect(plain["playlist"] == nil)
        #expect(plain["quality"] as? String == "original")
        #expect(plain["burnIn"] as? Bool == true)
        #expect((plain["profile"] as? [String: Any])?["maxWidth"] as? Int == 3840)
    }

    @Test func automaticSendsTheDevicesOwnProfileAndReadsTheStepTheServerPicked() async throws {
        let picked = "{" + #""auto":{"step":"720","reason":"measured 3.1 Mbps"},"bitrate":4128000,"# + decisionHLS.dropFirst()
        serve(["POST /api/files/4/playback": (200, picked)])
        let d = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client, quality: .auto))
        let body = try body("POST /api/files/4/playback")
        #expect(body["quality"] as? String == "auto")
        let sent = try #require(body["profile"] as? [String: Any])
        #expect(sent["maxWidth"] as? Int == 3840)
        #expect(sent["maxHeight"] as? Int == 2160)
        #expect(sent["maxBitrate"] == nil)
        #expect(d.autoStep == .hd)

        // A fixed quality's decision has no step.
        serve(["POST /api/files/4/playback": (200, decisionHLS)])
        #expect(try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client, quality: .low)).autoStep == nil)
        #expect((try self.body("POST /api/files/4/playback")["profile"] as? [String: Any])?["maxBitrate"] as? Int == 1_000_000)
    }

    @Test func anHLSVODDecisionMapsToAbsoluteURLsSegmentsAndFileTime() async throws {
        serve(["POST /api/files/4/playback": (200, decisionHLS)])
        let d = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client))
        #expect(d.mode == .remux)
        #expect(d.reasons == ["client cannot play the mkv container"])
        #expect(d.protocol == .hls)
        #expect(d.vod)
        #expect(d.url.absoluteString == "\(server.baseURL.absoluteString)/api/playback/a75ff047261dcc3e/master.m3u8")
        #expect(d.sessionID == "a75ff047261dcc3e")
        #expect(d.playID == "d2081dfed30c2b6c")
        #expect(d.durationMs == 200_021)
        #expect(d.segments == [Segment(kind: .intro, startMs: 30_000, endMs: 55_000), Segment(kind: .credits, startMs: 170_000, endMs: 200_000, final: true)])
        #expect(d.video == StreamVideo(width: 1280, height: 720, hdr: nil))
        #expect(d.creditsMs == 170_000)
        #expect(d.watchedAtMs == 170_000)
        #expect(d.next == nil)
        #expect(d.audio.map(\.label) == ["AAC Stereo"])
        #expect(d.selectedAudio == 1)
    }

    @Test func aDirectDecisionListsSubtitlesWithAbsoluteURLs() async throws {
        serve(["POST /api/files/8/playback": (200, decisionDirect)])
        let d = try await gateway.decide(DecisionRequest(fileID: 8, profile: profile, client: client))
        #expect(d.mode == .direct)
        #expect(d.protocol == .file)
        // A file's time is file time.
        #expect(d.vod)
        #expect(d.url.absoluteString == "\(server.baseURL.absoluteString)/api/files/8/stream?play=e0b6acbb09202287")
        #expect(d.sessionID == nil)
        #expect(d.mimeType == "video/mp4")
        #expect(d.watchedAtMs == 138_019)
        #expect(d.subtitles.count == 3)
        let en = d.subtitles[0]
        #expect(en.option.id == "s2")
        #expect(en.option.label == "English")
        #expect(en.option.format == "SRT")
        #expect(en.option.source == .embedded)
        #expect(!en.option.burnInOnly)
        #expect(en.format == "webvtt")
        #expect(en.url?.absoluteString == "\(server.baseURL.absoluteString)/api/files/8/subtitles/s2.vtt")
        #expect(d.subtitles[1].option.label == "Spanish")
        let pgs = d.subtitles[2]
        #expect(pgs.option.burnInOnly)
        #expect(pgs.format == "image")
        #expect(pgs.url == nil)
        #expect(pgs.option.detail == "PGS · burned in, restarts playback")
        #expect(d.video?.quality == "1080p")
    }

    @Test func aStreamOutsideTheServerIsRefusedAndASubtitleOrThumbnailsThereLeftOut() async throws {
        // PLAN-APPLE-REVIEW.md §3.4.8: the media token goes on these addresses.
        let elsewhere = decisionDirect.replacingOccurrences(of: #""url":"/api/files/8/stream?play=e0b6acbb09202287""#, with: #""url":"https://elsewhere.example/api/files/8/stream""#)
        serve(["POST /api/files/8/playback": (200, elsewhere)])
        await #expect(throws: APIError.self) { try await gateway.decide(DecisionRequest(fileID: 8, profile: profile, client: client)) }

        let own = "\(server.baseURL.absoluteString)/api/files/8/stream?play=e0b6acbb09202287"
        let absolute = decisionDirect
            .replacingOccurrences(of: #""url":"/api/files/8/stream?play=e0b6acbb09202287""#, with: "\"url\":\"\(own)\"")
            .replacingOccurrences(of: #""url":"/api/files/8/subtitles/s3.vtt""#, with: #""url":"https://elsewhere.example/s3.vtt""#)
        let manifest = #""trickplay":{"intervalMs":10000,"width":320,"height":180,"columns":10,"rows":10,"count":724,"sheets":8,"url":"https://elsewhere.example/{sheet}.jpg"},"#
        serve(["POST /api/files/8/playback": (200, "{" + manifest + absolute.dropFirst())])
        let d = try await gateway(level: trickplayAPILevel).decide(DecisionRequest(fileID: 8, profile: profile, client: client))
        #expect(d.url.absoluteString == own)
        #expect(d.subtitles[0].url != nil)
        #expect(d.subtitles[1].url == nil)
        #expect(d.trickplay == nil)
    }

    @Test func aCopiedVideoNamesItsProfileKey() async throws {
        // PLAN-APPLE-REVIEW.md §3.1.2, as the server classifies (server/internal/playback/decide.go).
        typealias Plan = Components.Schemas.VideoPlan
        #expect(PlaybackMapping.copiedVideoCodec(Plan(codec: "h264", copy: true, toneMap: false)) == "h264")
        #expect(PlaybackMapping.copiedVideoCodec(Plan(bitDepth: 10, codec: "h264", copy: true, toneMap: false)) == "h264")
        #expect(PlaybackMapping.copiedVideoCodec(Plan(bitDepth: 8, codec: "hevc", copy: true, toneMap: false)) == "hevc")
        #expect(PlaybackMapping.copiedVideoCodec(Plan(bitDepth: 10, codec: "hevc", copy: true, hdr: "hdr10", toneMap: false)) == "hevc10")
        #expect(PlaybackMapping.copiedVideoCodec(Plan(bitDepth: 10, codec: "hevc", copy: true, dvProfile: 8, hdr: "dolbyvision", toneMap: false)) == "dovi8")
        #expect(PlaybackMapping.copiedVideoCodec(Plan(codec: "av1", copy: true, toneMap: false)) == "av1")
        // Converted, or not said: none.
        #expect(PlaybackMapping.copiedVideoCodec(Plan(codec: "hevc", copy: false, toneMap: false)) == nil)
        #expect(PlaybackMapping.copiedVideoCodec(Plan(copy: true, toneMap: false)) == nil)
        serve(["POST /api/files/4/playback": (200, decisionHLS)])
        #expect(try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client)).copiedVideoCodec == "h264")
    }

    @Test func futureSegmentKindsAreIgnoredAndFutureSourcesKeepKnownSegments() async throws {
        let future = decisionHLS.replacingOccurrences(of: "\"kind\":\"intro\"", with: "\"kind\":\"sponsor\"")
            .replacingOccurrences(of: "\"source\":\"chapter\"", with: "\"source\":\"new-detector\"")
        serve(["POST /api/files/4/playback": (200, future)])
        let decision = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client))
        #expect(decision.protocol == .hls)
        #expect(decision.segments == [Segment(kind: .credits, startMs: 170_000, endMs: 200_000, final: true)])
    }

    @Test func futureStreamProtocolsAndTimelineKindsAreRejectedRatherThanGuessed() async throws {
        for future in [
            decisionHLS.replacingOccurrences(of: "\"protocol\":\"hls\"", with: "\"protocol\":\"dash\""),
            decisionHLS.replacingOccurrences(of: "\"playlist\":\"vod\"", with: "\"playlist\":\"new-timeline\""),
        ] {
            serve(["POST /api/files/4/playback": (200, future)])
            await #expect(throws: APIError.self) {
                try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client))
            }
        }
    }

    @Test func aFutureDirectiveDoesNotBecomeAStopOrQualityCommand() async throws {
        serve(["PUT /api/files/4/progress": (200, #"{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z","directive":{"action":"new-command","quality":"1440","message":"Future command"}}"#)])
        let reply = try await gateway.reportProgress(ProgressReport(fileID: 4, positionMs: 5000, durationMs: 200000, playID: "d2081dfed30c2b6c", state: .playing, bufferedMs: 0))
        #expect(reply.stopMessage == nil)
        #expect(reply.lowerTo == nil)
        #expect(!reply.completed)
    }

    @Test func anEventDecisionKeepsItsStartAndBurnIn() async throws {
        serve(["POST /api/files/4/playback": (200, decisionEvent)])
        let d = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client, startMs: 600_000))
        #expect(d.protocol == .hls)
        #expect(!d.vod)
        #expect(d.startMs == 598_998)
        #expect(d.mode == .transcode)
        #expect(d.burnInSubtitleID == "s4")
        #expect(d.video == StreamVideo(width: 1920, height: 1080, hdr: nil))
        #expect(d.subtitles.first?.url?.absoluteString == "\(server.baseURL.absoluteString)/api/files/4/subtitles/s2.vtt?shift=598998")
    }

    @Test func theDecisionsNextEpisodeCarriesItsRuntimeAndFinale() async throws {
        let next = #""nextEpisode":{"fileId":12,"episodeId":402,"showId":11,"showTitle":"Harbor Lights","seasonNumber":1,"episodeNumber":2,"title":"Tide","runtimeMin":48,"seasonFinale":true},"#
        serve(["POST /api/files/4/playback": (200, "{" + next + decisionHLS.dropFirst())])
        let d = try await gateway.decide(DecisionRequest(fileID: 4, profile: profile, client: client))
        #expect(d.next == UpNext(fileID: 12, episodeID: 402, showID: 11, showTitle: "Harbor Lights", season: 1, episode: 2, title: "Tide", runtimeMs: 48 * 60_000, seasonFinale: true))
    }

    @Test func theDecisionsSeekThumbnailsComeWithAnAbsoluteSheetURLFromLevel9() async throws {
        let manifest = #""trickplay":{"intervalMs":10000,"width":320,"height":180,"columns":10,"rows":10,"count":724,"sheets":8,"url":"/api/files/42/trickplay/9c1e07a2/{sheet}.jpg"},"#
        serve(["POST /api/files/4/playback": (200, "{" + manifest + decisionHLS.dropFirst())])
        let d = try await gateway(level: trickplayAPILevel).decide(DecisionRequest(fileID: 4, profile: profile, client: client))
        let t = try #require(d.trickplay)
        #expect(t.count == 724 && t.sheets == 8 && t.width == 320 && t.height == 180 && t.columns == 10 && t.rows == 10 && t.intervalMs == 10_000)
        #expect(t.sheetURL(3)?.absoluteString == "\(server.baseURL.absoluteString)/api/files/42/trickplay/9c1e07a2/3.jpg")
        // An older server's would be a surprise: it is not trusted.
        #expect(try await gateway(level: trickplayAPILevel - 1).decide(DecisionRequest(fileID: 4, profile: profile, client: client)).trickplay == nil)
        // Without thumbnails yet, none.
        serve(["POST /api/files/4/playback": (200, decisionHLS)])
        #expect(try await gateway(level: trickplayAPILevel).decide(DecisionRequest(fileID: 4, profile: profile, client: client)).trickplay == nil)
        // One the app cannot draw from (more tiles than the sheets hold) is dropped.
        let broken = manifest.replacingOccurrences(of: #""count":724"#, with: #""count":801"#)
        serve(["POST /api/files/4/playback": (200, "{" + broken + decisionHLS.dropFirst())])
        #expect(try await gateway(level: trickplayAPILevel).decide(DecisionRequest(fileID: 4, profile: profile, client: client)).trickplay == nil)
    }

    @Test func plansSendTheCappedProfileOncePerFileAndMapHowEachWouldPlay() async throws {
        serve([
            "POST /api/playback/plans": (200, """
            {"items":[
            {"fileId":9,"mode":"audio","protocol":"hls","reasons":["client cannot decode truehd audio"],
             "video":{"codec":"hevc","copy":true,"width":3840,"height":2160,"hdr":"dolbyvision","toneMap":false},"audio":{"index":1,"codec":"aac","copy":false,"channels":6}},
            {"fileId":8,"mode":"transcode","protocol":"hls","reasons":[],
             "video":{"codec":"hevc","copy":false,"width":1920,"height":1080,"hdr":"","toneMap":true},"audio":{"index":1,"codec":"eac3","copy":true,"channels":6}},
            {"fileId":7,"mode":"direct","protocol":"file","reasons":[],"video":{"codec":"h264","copy":true,"width":1920,"height":1080,"toneMap":false},"audio":{"index":1,"codec":"aac","copy":true}}]}
            """),
        ])
        let plans = try await gateway.plans(profile: profile, quality: .fullHD, fileIDs: [9, 8, 9, 7])
        let body = try body("POST /api/playback/plans")
        #expect(body["fileIds"] as? [Int] == [9, 8, 7])
        #expect((body["profile"] as? [String: Any])?["maxHeight"] as? Int == 1080)
        try #require(plans.count == 3)
        let (remux, transcode, direct) = (plans[0], plans[1], plans[2])
        #expect(remux.mode == .audioTranscode)
        #expect(remux.video == StreamVideo(width: 3840, height: 2160, hdr: "Dolby Vision"))
        #expect(remux.label == "Remux to fMP4 · audio to AAC 5.1")
        #expect(remux.reasons == ["client cannot decode truehd audio"])
        #expect(transcode.label == "Transcode to 1080p HEVC")
        // Tone-mapped is SDR.
        #expect(transcode.video?.hdr == nil)
        #expect(direct.label == "Direct play")
    }

    @Test func theHDRProfileGoesOnlyToServersThatReadIt() async throws {
        // PLAN-HDR-PROFILE.md decision 9: an older server refuses hdrTypes, even null.
        let tv = PlaybackProfile(name: "tvos", containers: ["mp4", "mov"], videoCodecs: ["h264", "hevc", "hevc10", "dovi5", "dovi8"], audioCodecs: ["aac"], hdr: true, hdrTypes: ["hdr10", "hlg", "dovi"])
        var sdrPhone = tv
        sdrPhone.videoCodecs = ["h264", "hevc", "hevc10"]
        sdrPhone.hdr = false
        sdrPhone.hdrTypes = []
        serve(["POST /api/files/4/playback": (200, decisionHLS), "POST /api/playback/plans": (200, #"{"items":[]}"#)])

        _ = try await gateway.decide(DecisionRequest(fileID: 4, profile: tv, client: client))
        var sent = try #require(try body("POST /api/files/4/playback")["profile"] as? [String: Any])
        #expect(sent["hdrTypes"] as? [String] == ["hdr10", "hlg", "dovi"])
        #expect(sent["videoCodecs"] as? [String] == ["h264", "hevc", "hevc10", "dovi5", "dovi8"])
        _ = try await gateway.decide(DecisionRequest(fileID: 4, profile: sdrPhone, client: client))
        sent = try #require(try body("POST /api/files/4/playback")["profile"] as? [String: Any])
        // Empty is sent: it still says "classify by this plan".
        #expect(sent["hdrTypes"] as? [String] == [])
        #expect(sent["videoCodecs"] as? [String] == ["h264", "hevc", "hevc10"])

        for level in [hdrProfileAPILevel - 1, nil] {
            _ = try await gateway(level: level).decide(DecisionRequest(fileID: 4, profile: tv, client: client))
            sent = try #require(try body("POST /api/files/4/playback")["profile"] as? [String: Any])
            #expect(!sent.keys.contains("hdrTypes"))
            #expect(sent["videoCodecs"] as? [String] == ["h264", "hevc", "hevc10"])
            #expect(sent["hdr"] as? Bool == true)
            _ = try await gateway(level: level).plans(profile: sdrPhone, quality: .original, fileIDs: [9])
            sent = try #require(try body("POST /api/playback/plans")["profile"] as? [String: Any])
            #expect(!sent.keys.contains("hdrTypes"))
            #expect(sent["videoCodecs"] as? [String] == ["h264", "hevc"])
        }
        _ = try await gateway.plans(profile: sdrPhone, quality: .original, fileIDs: [9])
        #expect((try body("POST /api/playback/plans")["profile"] as? [String: Any])?["hdrTypes"] as? [String] == [])
    }

    @Test func selectsAudioTrackGoesOnlyToServersThatReadIt() async throws {
        // PLAN-LANGUAGES.md §4: older servers reject request fields they don't know.
        var phone = profile
        phone.selectsAudioTrack = true
        serve(["POST /api/files/4/playback": (200, decisionHLS), "POST /api/playback/plans": (200, #"{"items":[]}"#)])
        _ = try await gateway(level: audioPreferencesAPILevel).decide(DecisionRequest(fileID: 4, profile: phone, client: client))
        #expect((try body("POST /api/files/4/playback")["profile"] as? [String: Any])?["selectsAudioTrack"] as? Bool == true)
        _ = try await gateway(level: audioPreferencesAPILevel).plans(profile: phone, quality: .original, fileIDs: [9])
        #expect((try body("POST /api/playback/plans")["profile"] as? [String: Any])?["selectsAudioTrack"] as? Bool == true)
        for level in [audioPreferencesAPILevel - 1, nil] {
            _ = try await gateway(level: level).decide(DecisionRequest(fileID: 4, profile: phone, client: client))
            #expect(try #require(try body("POST /api/files/4/playback")["profile"] as? [String: Any]).keys.contains("selectsAudioTrack") == false)
            _ = try await gateway(level: level).plans(profile: phone, quality: .original, fileIDs: [9])
            #expect(try #require(try body("POST /api/playback/plans")["profile"] as? [String: Any]).keys.contains("selectsAudioTrack") == false)
        }
    }

    @Test func audioTracksCarryWhatOfflinePlaysChooseBy() throws {
        let track = Components.Schemas.AudioTrack(channels: 6, codec: "eac3", _default: false, index: 3, language: "spa", title: "Commentary")
        let option = PlaybackMapping.audio(track)
        #expect(option.codec == "eac3")
        #expect(option.channels == 6)
        #expect(option.title == "Commentary")
        #expect(option.language == "spa")
    }

    @MainActor @Test func aBoundGatewayKeepsTheLevelOfTheServerItWasBoundTo() async throws {
        let level = Mutex<Int?>(hdrProfileAPILevel)
        let api = ServerAPI(baseURL: server.baseURL, session: session, token: { "tok-viewer" })
        let bound = APIPlaybackGateway(api: { api }, apiLevel: { level.withLock { $0 } }).bound()
        level.withLock { $0 = 1 }
        serve(["POST /api/files/4/playback": (200, decisionHLS)])
        let tv = PlaybackProfile(name: "tvos", containers: ["mp4"], videoCodecs: ["h264", "dovi8"], audioCodecs: ["aac"], hdr: true, hdrTypes: ["dovi"])
        _ = try await bound.decide(DecisionRequest(fileID: 4, profile: tv, client: client))
        #expect((try body("POST /api/files/4/playback")["profile"] as? [String: Any])?["hdrTypes"] as? [String] == ["dovi"])
    }

    @Test func serversWithoutPlansGiveNone() async throws {
        serve([:])
        #expect(try await gateway.plans(profile: profile, quality: .original, fileIDs: [9]).isEmpty)
        serve(["POST /api/playback/plans": (405, #"{"title":"Method Not Allowed","status":405}"#)])
        #expect(try await gateway.plans(profile: profile, quality: .original, fileIDs: [9]).isEmpty)
        // No IDs, no call.
        #expect(try await gateway.plans(profile: profile, quality: .original, fileIDs: []).isEmpty)
        #expect(requests("POST /api/playback/plans").count == 2)
    }

    @Test func progressSendsTheHeartbeatAndReadsAStopDirectiveAndANewPlay() async throws {
        serve([
            "PUT /api/files/4/progress": (200, #"{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35.67713-04:00"}"#),
            "PUT /api/files/5/progress": (200, #"{"positionMs":5000,"durationMs":200000,"completed":true,"updatedAt":"2026-10-29T13:09:35Z","playId":"aaaaaaaaaaaaaaaa","directive":{"action":"stop","message":"Server maintenance"}}"#),
        ])
        let reply = try await gateway.reportProgress(ProgressReport(fileID: 4, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c", state: .paused, bufferedMs: 3_000))
        let body = try body("PUT /api/files/4/progress")
        #expect(body["positionMs"] as? Int == 5_000)
        #expect(body["durationMs"] as? Int == 200_000)
        #expect(body["bufferedMs"] as? Int == 3_000)
        #expect(body["state"] as? String == "paused")
        #expect(body["playId"] as? String == "d2081dfed30c2b6c")
        #expect(reply == ProgressReply(completed: false))

        let next = try await gateway.reportProgress(ProgressReport(fileID: 5, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c", state: .playing, bufferedMs: 0))
        #expect(next == ProgressReply(completed: true, stopMessage: "Server maintenance", newPlayID: "aaaaaaaaaaaaaaaa"))

        // A play ID the server would refuse is left out rather than failing the beat.
        _ = try await gateway.reportProgress(ProgressReport(fileID: 4, positionMs: 1, durationMs: 2, playID: "not-a-play", state: .stopped, bufferedMs: 0))
        let refused = try self.body("PUT /api/files/4/progress")
        #expect(refused["state"] as? String == "stopped")
        #expect(refused["playId"] == nil)
    }

    @Test func progressSendsWhatThePlayerMeasuredAndReadsAStepDown() async throws {
        let updated = #""positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z","playId":"d2081dfed30c2b6c""#
        serve([
            "PUT /api/files/4/progress": (200, "{\(updated),\"directive\":{\"action\":\"quality\",\"quality\":\"720\",\"message\":\"Lowered to 720p for your connection.\"}}"),
            "PUT /api/files/5/progress": (200, "{\(updated),\"directive\":{\"action\":\"quality\",\"quality\":\"360\"}}"),
        ])
        let reply = try await gateway.reportProgress(ProgressReport(
            fileID: 4, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c", state: .buffering, bufferedMs: 1_200,
            throughputBps: 3_100_000, stalls: 1, stallMs: 2_400, startupMs: 900
        ))
        let body = try body("PUT /api/files/4/progress")
        #expect(body["throughputBps"] as? Int == 3_100_000)
        #expect(body["stalls"] as? Int == 1)
        #expect(body["stallMs"] as? Int == 2_400)
        #expect(body["startupMs"] as? Int == 900)
        #expect(reply == ProgressReply(completed: false, lowerTo: .hd, lowerMessage: "Lowered to 720p for your connection."))
        #expect(reply.stopMessage == nil)

        // What it has not measured stays out; a step without a message is still a step.
        let next = try await gateway.reportProgress(ProgressReport(fileID: 5, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c", state: .playing, bufferedMs: 0, stalls: 0, stallMs: 0))
        let quiet = try self.body("PUT /api/files/5/progress")
        #expect(quiet["throughputBps"] == nil)
        #expect(quiet["startupMs"] == nil)
        #expect(quiet["stalls"] as? Int == 0)
        #expect(next.lowerTo == .low)
        #expect(next.lowerMessage == nil)
    }

    @Test func negotiatedRecoveryIsGatedAndOriginalIsAValidDirective() async throws {
        serve([
            "POST /api/files/4/playback": (200, decisionHLS),
            "PUT /api/files/4/progress": (200, #"{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z","directive":{"action":"quality","quality":"original","generation":7}}"#),
        ])
        let request = DecisionRequest(fileID: 4, profile: profile, client: client, quality: .auto,
                                      autoRecovery: true, switchGeneration: 7)
        _ = try await gateway(level: 24).decide(request)
        let current = try body("POST /api/files/4/playback")
        #expect(current["autoRecovery"] as? Bool == true)
        #expect(current["switchGeneration"] as? Int == 7)

        _ = try await gateway(level: 23).decide(request)
        let older = try body("POST /api/files/4/playback")
        #expect(older["autoRecovery"] == nil)
        #expect(older["switchGeneration"] == nil)

        let reply = try await gateway(level: 24).reportProgress(ProgressReport(
            fileID: 4, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c",
            state: .playing, bufferedMs: 10_000, throughputBps: 20_000_000,
            throughputAgeMs: 1_200, switchGeneration: 6, switchFailedGeneration: 5
        ))
        let progress = try body("PUT /api/files/4/progress")
        #expect(progress["throughputAgeMs"] as? Int == 1_200)
        #expect(progress["switchGeneration"] as? Int == 6)
        #expect(progress["switchFailedGeneration"] as? Int == 5)
        #expect(reply.lowerTo == .original)
        #expect(reply.switchGeneration == 7)

        _ = try await gateway(level: 23).reportProgress(ProgressReport(
            fileID: 4, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c",
            state: .playing, bufferedMs: 10_000, throughputBps: 20_000_000,
            throughputAgeMs: 1_200, switchGeneration: 6, switchFailedGeneration: 5
        ))
        let olderProgress = try body("PUT /api/files/4/progress")
        #expect(olderProgress["throughputAgeMs"] == nil)
        #expect(olderProgress["switchGeneration"] == nil)
        #expect(olderProgress["switchFailedGeneration"] == nil)
    }

    @Test func aDirectiveWithoutAMessageStillStops() async throws {
        serve(["PUT /api/files/4/progress": (200, #"{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-10-29T13:09:35Z","playId":"d2081dfed30c2b6c","directive":{"action":"stop"}}"#)])
        let reply = try await gateway.reportProgress(ProgressReport(fileID: 4, positionMs: 5_000, durationMs: 200_000, playID: "d2081dfed30c2b6c", state: .playing, bufferedMs: 0))
        #expect(reply.stopMessage == "Playback was stopped by the server's admin.")
        // The same play ID back is not a new play.
        #expect(reply.newPlayID == nil)
    }

    @Test func mediaTokensAndStoppingASession() async throws {
        serve([
            "POST /api/auth/media-token": (200, #"{"token":"m1.AAAAAGrj0i9ZUoqhPhehEhzL-dixlAzdgIG0Vdd2ppjq3eHi1swYmw.OianpN3gWfBExO6GWbzuDQ","expiresAt":"2026-10-29T23:09:35Z","param":"access_token"}"#),
            "DELETE /api/playback/a75ff047261dcc3e": (204, ""),
            "DELETE /api/playback/gone": (404, #"{"title":"Not Found","status":404,"detail":"no such session"}"#),
            "DELETE /api/playback/broken": (500, #"{"title":"Internal Server Error","status":500}"#),
        ])
        let token = try await gateway.mediaToken()
        #expect(token.token.hasPrefix("m1."))
        #expect(token.param == "access_token")
        #expect(token.expiresAt == Date(timeIntervalSince1970: 1_793_315_375))
        let stream = "\(server.baseURL.absoluteString)/api/files/1/stream?play=a1"
        #expect(token.apply(to: URL(string: stream)!).absoluteString == "\(stream)&access_token=\(token.token)")
        // PLAN-APPLE-REVIEW.md §3.4.8: it goes to the server that issued it, and nowhere else.
        #expect(token.server == server.baseURL)
        #expect(token.apply(to: URL(string: "http://srv/api/files/1/stream?play=a1")!).absoluteString == "http://srv/api/files/1/stream?play=a1")

        try await gateway.stopSession("a75ff047261dcc3e")
        // Already gone is fine; other failures are not.
        try await gateway.stopSession("gone")
        await #expect(throws: APIError.self) { try await gateway.stopSession("broken") }
        #expect(requests("DELETE /api/playback/a75ff047261dcc3e").count == 1)
    }

    @Test func onlineSubtitlesSearchAndDownloadAndAnOlderServerHasNone() async throws {
        serve([:])
        // A server without the route has no provider.
        #expect(try await gateway.searchSubtitles(fileID: 4) == nil)
        serve([
            "GET /api/files/4/subtitles/search": (200, """
            {"available":true,"provider":"opensubtitles","attribution":"Subtitles provided by OpenSubtitles.com","languages":["en"],
             "results":[{"id":"77","language":"pt-br","release":"Film.2020","hashMatch":true,"hearingImpaired":false,"forced":false,"trusted":true,"aiTranslated":false,"downloads":5,"trackId":"d3"}],
             "canDownload":true,"quota":{"signedIn":true,"remaining":19,"resetAt":"2026-10-30T00:00:00Z"}}
            """),
            "GET /api/files/5/subtitles/search": (200, #"{"available":false,"provider":"opensubtitles","attribution":"","languages":[],"results":[],"canDownload":false,"quota":{"signedIn":false,"remaining":-1}}"#),
            "POST /api/files/4/subtitles/download": (200, #"{"id":"d3","codec":"subrip","format":"webvtt","url":"/api/files/4/subtitles/d3.vtt","forced":false,"default":false,"burnInOnly":false,"external":true,"source":"opensubtitles"}"#),
            "POST /api/files/6/subtitles/download": (429, #"{"title":"Too Many Requests","status":429,"detail":"No subtitle downloads left today."}"#),
        ])
        let found = try #require(try await gateway.searchSubtitles(fileID: 4))
        let result = try #require(found.results.first)
        #expect(result.id == "77")
        #expect(result.language == "pt-br")
        #expect(result.label == onlineLanguageLabel("pt-br"))
        #expect(result.hashMatch)
        #expect(result.trackID == "d3")
        #expect(found.remaining == 19)
        #expect(found.resetAt == Date(timeIntervalSince1970: 1_793_318_400))
        #expect(found.attribution == "Subtitles provided by OpenSubtitles.com")
        // No API key in that build.
        #expect(try await gateway.searchSubtitles(fileID: 5) == nil)

        #expect(try await gateway.downloadSubtitle(fileID: 4, resultID: "77") == "d3")
        #expect(try body("POST /api/files/4/subtitles/download")["resultId"] as? String == "77")
        await #expect(throws: APIError(status: 429, message: "No subtitle downloads left today.")) {
            try await gateway.downloadSubtitle(fileID: 6, resultID: "78")
        }
    }

    @Test func subtitleTextIsFetchedWithTheSignIn() async throws {
        serve(["GET /api/files/8/subtitles/s2.vtt": (200, "WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n")])
        let url = URL(string: "\(server.baseURL.absoluteString)/api/files/8/subtitles/s2.vtt?access_token=m1")!
        let text = try await gateway.subtitleText(url)
        #expect(text.hasPrefix("WEBVTT"))
        #expect(requests("GET /api/files/8/subtitles/s2.vtt").last?.value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        await #expect(throws: APIError.self) { try await gateway.subtitleText(server.baseURL.appending(path: "/api/files/8/subtitles/s9.vtt")) }
        #expect(!signOut.isSet)
    }

    @Test func aRefusedSignInOnSubtitleTextSignsOut() async {
        serve(["GET /api/files/8/subtitles/s2.vtt": (401, #"{"title":"Unauthorized","status":401,"detail":"session expired"}"#)])
        await #expect(throws: SignedOutError()) {
            try await gateway.subtitleText(server.baseURL.appending(path: "/api/files/8/subtitles/s2.vtt"))
        }
        #expect(signOut.isSet)
    }
}

/// Whether the API signed out.
final class SignOutFlag: Sendable {
    private let flag = Mutex(false)

    func set() { flag.withLock { $0 = true } }

    var isSet: Bool { flag.withLock { $0 } }
}

// The Android fixtures (android/core/data/src/test/resources/fixtures).

private let decisionHLS = """
{"mode":"remux","reasons":["client cannot play the mkv container"],"protocol":"hls","playlist":"vod",
 "url":"/api/playback/a75ff047261dcc3e/master.m3u8","mimeType":"application/vnd.apple.mpegurl",
 "playId":"d2081dfed30c2b6c","sessionId":"a75ff047261dcc3e","durationMs":200021,"startMs":0,"resumeMs":0,
 "video":{"codec":"h264","copy":true,"width":1280,"height":720,"bitDepth":8,"toneMap":false},
 "audio":{"index":1,"codec":"aac","copy":true,"channels":2},
 "audioTracks":[{"index":1,"codec":"aac","channels":2,"default":false}],
 "subtitles":[],
 "segments":[
  {"id":1,"kind":"intro","startMs":30000,"endMs":55000,"final":false,"source":"chapter","confidence":1},
  {"id":2,"kind":"credits","startMs":170000,"endMs":200000,"final":true,"source":"chapter","confidence":1}],
 "creditsMs":170000,"watchedAtMs":170000,"upNextMs":0}
"""

private let decisionDirect = """
{"mode":"direct","reasons":[],"protocol":"file","url":"/api/files/8/stream?play=e0b6acbb09202287","mimeType":"video/mp4",
 "playId":"e0b6acbb09202287","durationMs":150021,"startMs":0,"resumeMs":0,
 "video":{"codec":"h264","copy":true,"width":1920,"height":1080,"bitDepth":8,"toneMap":false},
 "audio":{"index":1,"codec":"aac","copy":true,"channels":2},
 "audioTracks":[{"index":1,"codec":"aac","channels":2,"default":false}],
 "subtitles":[
  {"id":"s2","language":"eng","codec":"subrip","format":"webvtt","url":"/api/files/8/subtitles/s2.vtt","forced":false,"default":true,"burnInOnly":false,"external":false},
  {"id":"s3","language":"spa","codec":"subrip","format":"webvtt","url":"/api/files/8/subtitles/s3.vtt","forced":false,"default":false,"burnInOnly":false,"external":false},
  {"id":"s4","language":"eng","codec":"hdmv_pgs_subtitle","format":"image","forced":false,"default":false,"burnInOnly":true,"external":false}],
 "segments":[],"creditsMs":0,"watchedAtMs":138019,"upNextMs":0}
"""

private let decisionEvent = """
{"mode":"transcode","reasons":["subtitle burn-in requested"],"protocol":"hls","playlist":"event",
 "url":"/api/playback/b1/index.m3u8","mimeType":"application/vnd.apple.mpegurl",
 "playId":"d2081dfed30c2b6c","sessionId":"b1","durationMs":3000000,"startMs":598998,"resumeMs":0,
 "video":{"codec":"hevc","copy":false,"width":1920,"height":1080,"bitDepth":10,"hdr":"hdr10","toneMap":true},
 "audio":{"index":1,"codec":"aac","copy":false,"channels":2},
 "audioTracks":[{"index":1,"codec":"eac3","channels":6,"default":true,"language":"eng"}],
 "subtitles":[{"id":"s2","language":"eng","codec":"subrip","format":"webvtt","url":"/api/files/4/subtitles/s2.vtt?shift=598998","forced":false,"default":false,"burnInOnly":false,"external":false},
  {"id":"s4","language":"eng","codec":"hdmv_pgs_subtitle","format":"image","forced":false,"default":false,"burnInOnly":true,"external":false}],
 "burnIn":{"id":"s4","language":"eng","codec":"hdmv_pgs_subtitle","format":"image","forced":false,"default":false,"burnInOnly":true,"external":false},
 "segments":[],"creditsMs":0,"watchedAtMs":2760000,"upNextMs":0}
"""

extension PlaybackGatewayTests {
    @Test func growingRecordingUsesItsOwnPlaybackAndProgressRoutesAndKeepsEventSemantics() async throws {
        let growing = "{" + #""growing":{"startedAt":"2026-11-04T20:00:00Z","endsAt":"2026-11-04T21:00:00Z"},"# + decisionHLS.dropFirst()
        let response = growing.replacingOccurrences(of: #""playlist":"vod""#, with: #""playlist":"event""#)
        serve(["POST /api/live-tv/recordings/r1/playback": (200, response),
               "PUT /api/live-tv/recordings/r1/progress": (200, #"{"positionMs":5000,"durationMs":200000,"completed":false,"updatedAt":"2026-11-04T20:09:35Z"}"#)])
        let gateway = gateway(level: liveRecordingAPILevel)
        let decision = try await gateway.decide(.init(fileID: 0, profile: profile, client: client, startMs: 0, vod: false, recordingID: "r1"))
        #expect(!decision.vod && decision.growing != nil)
        #expect(requests("POST /api/files/0/playback").isEmpty)
        _ = try await gateway.reportProgress(.init(fileID: 0, positionMs: 5000, durationMs: 200000, playID: "d2081dfed30c2b6c", state: .playing, bufferedMs: 3000, recordingID: "r1"))
        #expect(try body("PUT /api/live-tv/recordings/r1/progress")["positionMs"] as? Int == 5000)
        #expect(requests("PUT /api/files/0/progress").isEmpty)
    }
    @Test func partialGrowingMetadataIsIgnoredAndOlderServersNeverGetARecordingCall() async throws {
        let missingEnd = "{" + #""growing":{"startedAt":"2026-11-04T20:00:00Z"},"# + decisionHLS.dropFirst()
        serve(["POST /api/files/4/playback": (200, missingEnd)])
        let d = try await gateway(level: liveRecordingAPILevel).decide(.init(fileID: 4, profile: profile, client: client))
        #expect(d.growing == nil)
        await #expect(throws: LiveTVError.self) {
            _ = try await gateway(level: 18).decide(.init(fileID: 0, profile: profile, client: client, recordingID: "r1"))
        }
        #expect(requests("POST /api/live-tv/recordings/r1/playback").isEmpty)
    }
}
