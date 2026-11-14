import AVFoundation
import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// android/core/playback/.../RulesTest.kt, and the iOS player's own rules.
@Suite struct RulesTests {
    @Test func theTokenGoesOnURLsWithAndWithoutAQuery() {
        let token = MediaToken(token: "m1.abc-DEF_9", expiresAt: .now)
        #expect(token.apply(to: url("http://srv/api/playback/s1/master.m3u8")) == url("http://srv/api/playback/s1/master.m3u8?access_token=m1.abc-DEF_9"))
        #expect(token.apply(to: url("http://srv/api/files/1/stream?play=a1")) == url("http://srv/api/files/1/stream?play=a1&access_token=m1.abc-DEF_9"))
        #expect(token.apply(to: url("http://srv/api/files/1/subtitles/s2.vtt?shift=6000")) == url("http://srv/api/files/1/subtitles/s2.vtt?shift=6000&access_token=m1.abc-DEF_9"))
        #expect(MediaToken(token: "t", expiresAt: .now, param: "mt").apply(to: url("http://srv/x.vtt")) == url("http://srv/x.vtt?mt=t"))
    }

    @Test func vodAndDirectPlayAreFileTime() {
        let vod = StreamClock.of(decision(vod: true, startMs: 0))
        #expect(vod == StreamClock(offsetMs: 0, event: false))
        #expect(vod.filePosition(125_000) == 125_000)
        #expect(vod.canSeekLocally(40 * minute, bufferedPlayerMs: 0))
        // Direct play ignores startMs: the file itself starts at 0.
        #expect(StreamClock.of(decision(protocol: .file, vod: false, startMs: 90_000)) == .zero)
    }

    @Test func eventPlaylistsAreOffsetByTheirStart() {
        let event = StreamClock.of(decision(vod: false, startMs: 600_000))
        #expect(event.event)
        #expect(event.filePosition(5_000) == 605_000)
        #expect(event.playerPosition(610_000) == 10_000)
        #expect(event.playerPosition(500_000) == 0)
        // Within the buffer (and half a second past it) stays; before the start or further ahead re-requests.
        #expect(event.canSeekLocally(620_000, bufferedPlayerMs: 30_000))
        #expect(event.canSeekLocally(630_400, bufferedPlayerMs: 30_000))
        #expect(!event.canSeekLocally(631_000, bufferedPlayerMs: 30_000))
        #expect(!event.canSeekLocally(599_000, bufferedPlayerMs: 30_000))
    }

    @Test func qualityNarrowsTheProfile() {
        let capped = phoneProfile.capped(by: .fullHD)
        #expect(capped.maxWidth == 1920)
        #expect(capped.maxHeight == 1080)
        #expect(capped.maxBitrate == 8_000_000)
        #expect(phoneProfile.capped(by: .original) == phoneProfile)
        var small = phoneProfile
        small.maxWidth = 1280
        small.maxHeight = 720
        #expect(small.capped(by: .fullHD).maxWidth == 1280)
        #expect(small.capped(by: .fullHD).maxHeight == 720)
    }

    @Test func skipActionPerMode() {
        let intro = Segment(kind: .intro, startMs: 60_000, endMs: 105_000)
        let d = 50 * minute
        #expect(skipAction(intro, durationMs: d, mode: .none, seekedInto: false) == .none)
        #expect(skipAction(intro, durationMs: d, mode: .ask, seekedInto: false) == .button)
        #expect(skipAction(intro, durationMs: d, mode: .auto, seekedInto: false) == .autoNow)
        #expect(skipAction(intro, durationMs: d, mode: .autoDelay, seekedInto: false) == .autoDelay)
        // Seeked into: never skipped by itself, a plain button instead.
        #expect(skipAction(intro, durationMs: d, mode: .auto, seekedInto: true) == .button)
        #expect(skipAction(intro, durationMs: d, mode: .autoDelay, seekedInto: true) == .button)
        // A button needs 3 s of segment; skipping by itself does not.
        let short = Segment(kind: .recap, startMs: 10_000, endMs: 12_000)
        #expect(skipAction(short, durationMs: d, mode: .ask, seekedInto: false) == .none)
        #expect(skipAction(short, durationMs: d, mode: .auto, seekedInto: false) == .autoNow)
        #expect(skipAction(short, durationMs: d, mode: .auto, seekedInto: true) == .none)
        // An open-ended segment runs to the end of the file.
        #expect(skipAction(Segment(kind: .credits, startMs: d - 2_000, endMs: nil), durationMs: d, mode: .ask, seekedInto: false) == .none)
    }

    @Test func activeSegmentLeavesTheFinalCreditsToTheNextEpisodeCard() {
        let intro = Segment(kind: .intro, startMs: 60_000, endMs: 105_000)
        let credits = Segment(kind: .credits, startMs: 48 * minute, endMs: nil)
        let d = decision(segments: [intro, credits], creditsMs: 48 * minute, upNextMs: 48 * minute, next: nextEpisode)
        #expect(activeSegment(d, positionMs: 60_000, nextOffered: true) == intro)
        // Not in the last half second, so a skip does not land in it again.
        #expect(activeSegment(d, positionMs: 104_600, nextOffered: true) == nil)
        #expect(activeSegment(d, positionMs: 49 * minute, nextOffered: true) == nil)
        #expect(activeSegment(d, positionMs: 49 * minute, nextOffered: false) == credits)
        // Under a second is not a segment to skip.
        #expect(activeSegment(decision(segments: [Segment(kind: .recap, startMs: 0, endMs: 900)]), positionMs: 100, nextOffered: false) == nil)
    }

    @Test func theNextEpisodeCardShowsFromUpNextUnlessTheViewerWatchesTheCredits() {
        let at = 48 * minute
        #expect(!showsUpNext(offered: true, askingStillWatching: false, status: .playing, positionMs: at - 1, upNextMs: at, creditsDismissed: false))
        #expect(showsUpNext(offered: true, askingStillWatching: false, status: .playing, positionMs: at, upNextMs: at, creditsDismissed: false))
        #expect(!showsUpNext(offered: true, askingStillWatching: false, status: .playing, positionMs: at, upNextMs: at, creditsDismissed: true))
        // At the end it comes back whatever was chosen; never with an error or Still watching up, or nothing next.
        #expect(showsUpNext(offered: true, askingStillWatching: false, status: .ended, positionMs: at, upNextMs: at, creditsDismissed: true))
        #expect(!showsUpNext(offered: true, askingStillWatching: false, status: .error, positionMs: at, upNextMs: at, creditsDismissed: false))
        #expect(!showsUpNext(offered: true, askingStillWatching: true, status: .playing, positionMs: at, upNextMs: at, creditsDismissed: false))
        #expect(!showsUpNext(offered: false, askingStillWatching: false, status: .ended, positionMs: at, upNextMs: at, creditsDismissed: false))
    }

    @Test func theCountdownStartsAtTenSecondsAndHoldsWhilePaused() {
        #expect(upNextCountdown(nil, showing: true, autoplay: true, status: .playing, dtMs: 250) == 10_000)
        #expect(upNextCountdown(10_000, showing: true, autoplay: true, status: .playing, dtMs: 250) == 9_750)
        #expect(upNextCountdown(6_000, showing: true, autoplay: true, status: .paused, dtMs: 250) == 6_000)
        #expect(upNextCountdown(6_000, showing: true, autoplay: true, status: .buffering, dtMs: 250) == 5_750)
        #expect(upNextCountdown(6_000, showing: true, autoplay: true, status: .ended, dtMs: 250) == 5_750)
        #expect(upNextCountdown(100, showing: true, autoplay: true, status: .playing, dtMs: 250) == 0)
        // Autoplay off: the card waits for a choice; no card, no countdown.
        #expect(upNextCountdown(nil, showing: true, autoplay: false, status: .playing, dtMs: 250) == nil)
        #expect(upNextCountdown(4_000, showing: false, autoplay: true, status: .playing, dtMs: 250) == nil)
    }

    @Test func heartbeatsAtEveryChangeThenEveryTenOrThirtySeconds() {
        #expect(playState(for: .playing) == .playing)
        #expect(playState(for: .loading) == .buffering)
        #expect(playState(for: .buffering) == .buffering)
        #expect(playState(for: .paused) == .paused)
        #expect(playState(for: .ended) == .paused)
        #expect(playState(for: .error) == .paused)
        #expect(heartbeatDue(.playing, lastReported: nil, sinceLastBeatMs: 0))
        #expect(heartbeatDue(.paused, lastReported: .playing, sinceLastBeatMs: 0))
        #expect(!heartbeatDue(.playing, lastReported: .playing, sinceLastBeatMs: 9_999))
        #expect(heartbeatDue(.playing, lastReported: .playing, sinceLastBeatMs: 10_000))
        #expect(!heartbeatDue(.paused, lastReported: .paused, sinceLastBeatMs: 29_999))
        #expect(heartbeatDue(.paused, lastReported: .paused, sinceLastBeatMs: 30_000))
        #expect(!heartbeatDue(.buffering, lastReported: .buffering, sinceLastBeatMs: 10_000))
        #expect(heartbeatDue(.buffering, lastReported: .buffering, sinceLastBeatMs: 30_000))
    }

    @Test func playerFailuresAreWorded() {
        #expect(errorMessage(PlayerFailure(kind: .network, detail: "")) == "Lost the connection to the server.")
        #expect(errorMessage(PlayerFailure(kind: .http, detail: "", httpStatus: 403)) == "The server refused the stream. Try signing in again.")
        #expect(errorMessage(PlayerFailure(kind: .http, detail: "", httpStatus: 410)) == "The server no longer has this stream.")
        #expect(errorMessage(PlayerFailure(kind: .http, detail: "", httpStatus: 502)) == "The server answered with HTTP 502.")
        #expect(errorMessage(PlayerFailure(kind: .http, detail: "")) == "The server refused the stream.")
        #expect(errorMessage(PlayerFailure(kind: .decoder, detail: "")) == "This device could not decode the stream.")
        #expect(errorMessage(PlayerFailure(kind: .source, detail: "")) == "The stream could not be read.")
        #expect(errorMessage(PlayerFailure(kind: .other, detail: "x 1")) == "Playback failed (x 1).")
        #expect(PlayerFailure(kind: .network, detail: "").recoverable)
        #expect(PlayerFailure(kind: .http, detail: "", httpStatus: 404).recoverable)
        #expect(!PlayerFailure(kind: .http, detail: "", httpStatus: 500).recoverable)
        #expect(!PlayerFailure(kind: .decoder, detail: "").recoverable)
    }

    @Test func avPlayerFailuresAreSorted() {
        #expect(PlayerFailure.httpStatus(code: 404, comment: nil) == 404)
        #expect(PlayerFailure.httpStatus(code: -12938, comment: "HTTP 404: File Not Found") == 404)
        #expect(PlayerFailure.httpStatus(code: -12660, comment: "HTTP 403: Forbidden") == 403)
        #expect(PlayerFailure.httpStatus(code: -12642, comment: "Playlist File unchanged for longer than 1.5 * target duration") == nil)
        let offline = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
        #expect(PlayerFailure.classify(offline, log: nil).kind == .network)
        let wrapped = NSError(domain: "CoreMediaErrorDomain", code: -1, userInfo: [NSUnderlyingErrorKey: offline])
        #expect(PlayerFailure.classify(wrapped, log: nil).kind == .network)
        #expect(PlayerFailure.classify(NSError(domain: AVFoundationErrorDomain, code: AVError.Code.decoderNotFound.rawValue), log: nil).kind == .decoder)
        #expect(PlayerFailure.classify(NSError(domain: AVFoundationErrorDomain, code: AVError.Code.fileFormatNotRecognized.rawValue), log: nil).kind == .source)
        #expect(PlayerFailure.classify(nil, log: nil).kind == .other)
    }
}
