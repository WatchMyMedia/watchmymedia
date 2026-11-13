import Foundation
import Testing
@testable import WMMModel

@Suite struct FormatsTests {
    @Test func clocksAndDurations() {
        #expect(formatClock(ms: 3_130_000) == "52:10")
        #expect(formatClock(ms: 3_723_000) == "1:02:03")
        #expect(formatClock(ms: -5) == "0:00")
        #expect(formatDuration(ms: 72 * 60_000) == "1 h 12 min")
        #expect(formatDuration(ms: 60 * 60_000) == "1 h")
        #expect(formatDuration(ms: 23 * 60_000) == "23 min")
        #expect(formatRemaining(ms: 23 * 60_000) == "23 min left")
        #expect(episodeCode(season: 3, episode: 7) == "S03 E07")
    }

    @Test func bytes() {
        #expect(formatBytes(512) == "512 B")
        #expect(formatBytes(18_400_000_000_000) == "16.7 TB")
        #expect(formatBytes(740 * 1024 * 1024 * 1024) == "740 GB")
    }

    @Test func ago() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        #expect(formatAgo(now.addingTimeInterval(-30), now: now) == "just now")
        #expect(formatAgo(now.addingTimeInterval(-12 * 60), now: now) == "12 min ago")
        #expect(formatAgo(now.addingTimeInterval(-3 * 3600), now: now) == "3 h ago")
        #expect(formatAgo(now.addingTimeInterval(-30 * 3600), now: now) == "yesterday")
        #expect(formatAgo(now.addingTimeInterval(-4 * 86_400), now: now) == "4 days ago")
        #expect(formatAgo(now.addingTimeInterval(-21 * 86_400), now: now) == "3 weeks ago")
    }

    @Test func daysRelativeToToday() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let today = try #require(parseDay("2026-10-30"))
        #expect(formatDay("2026-10-30", today: today, calendar: calendar) == "today")
        #expect(formatDay("2026-10-29", today: today, calendar: calendar) == "yesterday")
        #expect(formatDay("2026-10-26", today: today, calendar: calendar) == "4 days ago")
        #expect(airedLabel("2026-10-29", today: today, calendar: calendar) == "Aired yesterday")
        #expect(airedLabel("2026-11-04", today: today, calendar: calendar)?.hasPrefix("Airs ") == true)
        #expect(formatDay(nil) == nil)
        #expect(formatDay("soon") == nil)
    }

    @Test func counts() {
        #expect(plural(1, "season") == "1 season")
        #expect(plural(3, "season") == "3 seasons")
        #expect(formatRating(8.06) == "★ 8.1")
        #expect(formatRating(0) == nil)
    }

    @Test func versionHeadingAndTags() {
        let v = MediaVersion(fileID: 1, label: "a.mkv", sizeBytes: 61_000_000_000, container: "mkv", videoCodec: "hevc", width: 3840, height: 2160, hdr: dolbyVision, audioSummary: "TrueHD 7.1 · English", dvProfile: 8)
        #expect(v.title == "4K Dolby Vision")
        #expect(v.tags == ["MKV", "HEVC", "DV P8", "TrueHD 7.1", "56.8 GB"])
        let sd = MediaVersion(fileID: 2, label: "b.mp4", sizeBytes: 1, container: "MP4")
        #expect(sd.title == "MP4")
    }

    @Test func trackLines() {
        let v = MediaVersion(
            fileID: 1, label: "a", sizeBytes: 1,
            audio: (1...5).map { AudioOption(index: $0, label: "English · AC3 5.1\($0 == 1 ? " (Default)" : "")", language: "en", isDefault: $0 == 1) },
            subtitles: [SubtitleOption(id: "s1", label: "English", language: "en", burnInOnly: true, format: "PGS")]
        )
        let lines = v.trackLines()
        #expect(lines.first == TrackLine(kind: "Audio", text: "English · AC3 5.1", isDefault: true))
        #expect(lines.contains(TrackLine(kind: nil, text: "+ 2 more")))
        #expect(lines.last == TrackLine(kind: "Subtitles", text: "English · PGS", burnIn: true))
    }
}

@Suite struct TrackLabelTests {
    let english = Locale(identifier: "en_US")

    @Test func languages() {
        #expect(languageName("eng", locale: english) == "English")
        #expect(languageName("fre", locale: english) == "French")
        #expect(languageName("es", locale: english) == "Spanish")
        #expect(languageName("und", locale: english) == nil)
        #expect(onlineLanguageLabel("pt-br", locale: english) == "Portuguese (Brazil)")
        #expect(onlineLanguageLabel("", locale: english) == "Unknown")
    }

    @Test func audioAndSubtitleLabels() {
        #expect(audioTrackLabel(language: "eng", codec: "truehd", channels: 8, title: nil, isDefault: true).hasSuffix("TrueHD 7.1 (Default)"))
        #expect(audioTrackLabel(language: nil, codec: "aac", channels: 2, title: "Commentary", isDefault: false) == "AAC Stereo · Commentary")
        #expect(subtitleTrackLabel(language: nil, title: nil, forced: true) == "Unknown (Forced)")
        #expect(subtitleFormatName("hdmv_pgs_subtitle") == "PGS")
        #expect(subtitleFormatName("subrip") == "SRT")
    }

    @Test func onlineNotes() {
        #expect(OnlineSubtitles(canDownload: false).note() == "Only admins can download subtitles on this server.")
        #expect(OnlineSubtitles(results: [], remaining: 0).note() == "No downloads left today.")
        #expect(OnlineSubtitles(results: []).note() == "Nothing found online in these languages.")
        let r = OnlineSubtitle(id: "1", language: "en", label: "English", release: "Show.S01E01", hashMatch: true, hearingImpaired: true, forced: false, aiTranslated: false, downloads: 1)
        #expect(r.detail(downloading: false) == "Matches this file · HI · Show.S01E01")
        #expect(OnlineSubtitles(results: [r]).note() == nil)
    }
}

@Suite struct PlaybackModelTests {
    @Test func planLabels() {
        #expect(PlaybackPlan(fileID: 1, mode: .direct).label == "Direct play")
        #expect(PlaybackPlan(fileID: 1, mode: .audioTranscode, audioCodec: "AAC", audioChannels: 6).label == "Remux to fMP4 · audio to AAC 5.1")
        #expect(PlaybackPlan(fileID: 1, mode: .transcode, video: StreamVideo(width: 1920, height: 1080, hdr: nil), videoCodec: "HEVC", audioCodec: "AAC").label == "Transcode to 1080p HEVC · audio to AAC")
    }

    @Test func qualityCapsNarrowAProfile() {
        let p = PlaybackProfile(name: "iPhone", containers: ["mp4"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxWidth: 3840, maxHeight: 2160)
        let capped = p.capped(by: .fullHD)
        #expect(capped.maxWidth == 1920)
        #expect(capped.maxHeight == 1080)
        #expect(capped.maxBitrate == 8_000_000)
        #expect(p.capped(by: .original) == p)
    }

    @Test func upNextCountdown() {
        let next = UpNext(fileID: 1, episodeID: 2, showID: 3, showTitle: "Show", season: 1, episode: 2, title: "Two")
        #expect(UpNextPrompt(next: next, countdownMs: 10_000).countdownProgress == 0)
        #expect(UpNextPrompt(next: next, countdownMs: 2_500).countdownProgress == 0.75)
        #expect(UpNextPrompt(next: next, countdownMs: 6_001).secondsLeft == 7)
        #expect(UpNextPrompt(next: next, countdownMs: nil).countdownProgress == 0)
        #expect(next.playRequest.subtitle == "S01 E02 · Two")
    }

    @Test func qualityLabels() {
        #expect(qualityFor(width: 3840, height: 1600) == "4K")
        #expect(qualityFor(width: 1920, height: 800) == "1080p")
        #expect(qualityFor(width: 1280, height: 720) == "720p")
        #expect(qualityFor(width: 720, height: 480) == "SD")
    }

    @Test func mediaTokensGoOnTheQuery() {
        let t = MediaToken(token: "abc", expiresAt: .now)
        #expect(t.apply(to: URL(string: "http://h/api/files/1/stream")!).absoluteString == "http://h/api/files/1/stream?access_token=abc")
        #expect(t.apply(to: URL(string: "http://h/a.m3u8?x=1")!).absoluteString == "http://h/a.m3u8?x=1&access_token=abc")
    }

    @Test func aScopedMediaTokenGoesOnlyToItsServer() {
        // PLAN-APPLE-REVIEW.md §3.4.8.
        let t = MediaToken(token: "abc", expiresAt: .now, server: URL(string: "https://home.example.net/wmm")!)
        #expect(t.apply(to: URL(string: "https://home.example.net/wmm/api/files/1/stream")!).absoluteString == "https://home.example.net/wmm/api/files/1/stream?access_token=abc")
        #expect(t.apply(to: URL(string: "https://HOME.example.net/wmm")!).absoluteString == "https://HOME.example.net/wmm?access_token=abc")
        for elsewhere in ["https://cdn.example.net/wmm/api/files/1/stream", "http://home.example.net/wmm/api", "https://home.example.net:8443/wmm/api", "https://home.example.net/wmmx/api", "https://home.example.net/api"] {
            #expect(t.apply(to: URL(string: elsewhere)!).absoluteString == elsewhere)
        }
        #expect(t.apply(to: URL(fileURLWithPath: "/tmp/a.mp4")).isFileURL)
        #expect(t.apply(to: URL(fileURLWithPath: "/tmp/a.mp4")).query() == nil)
    }

    @Test func unmatchedFilmsPlayTheirFile() {
        let film = BrowseItem(kind: .unmatched, id: 7, libraryID: 1, title: "Glass Tide (2023).mkv", fileName: "Glass Tide (2023)/Glass Tide (2023).mkv", fileCount: 1)
        #expect(film.unmatchedPlayRequest == PlayRequest(fileID: 7, title: "Glass Tide (2023).mkv"))
        let folder = BrowseItem(kind: .unmatched, id: 4, libraryID: 2, title: "Night Shift Diner (2020)", fileCount: 6)
        #expect(folder.unmatchedPlayRequest == nil)
        #expect(BrowseItem(kind: .movie, id: 500, libraryID: 1, title: "x").unmatchedPlayRequest == nil)
    }

    @Test func playRequestsNameWhatTheyPlay() {
        let c = ContinueItem(kind: .episode, title: "Saltmarsh", fileID: 9, season: 1, episode: 9, positionMs: 1, durationMs: 2)
        #expect(c.playRequest.subtitle == "S01 E09")
        #expect(c.playRequest.startMs == nil)
        let card = EpisodeCard(episodeID: 1, showID: 2, showTitle: "Harbor Lights", season: 3, episode: 7, title: "The Long Dark", hasFile: false, fileID: nil, isNew: false)
        #expect(card.playRequest == nil)
        let e = EpisodeDetail(id: 1, season: 3, number: 7, title: "The Long Dark", hasFile: true, aired: true, versions: [MediaVersion(fileID: 5, label: "a", sizeBytes: 1)])
        #expect(e.playRequest(showTitle: "Harbor Lights", backdropPath: nil, fromStart: true)?.startMs == 0)
    }

    @Test func playRequestsCarryTheStillAndPosterForNowPlaying() {
        let c = ContinueItem(kind: .episode, title: "Harbor Lights", posterPath: "/p.jpg", backdropPath: "/b.jpg", stillPath: "/s.jpg", fileID: 9, positionMs: 1, durationMs: 2)
        #expect((c.playRequest.stillPath, c.playRequest.posterPath, c.playRequest.backdropPath) == ("/s.jpg", "/p.jpg", "/b.jpg"))
        let card = EpisodeCard(episodeID: 1, showID: 2, showTitle: "Harbor Lights", season: 3, episode: 7, title: "The Long Dark", stillPath: "/s.jpg", showPosterPath: "/p.jpg", hasFile: true, fileID: 5, isNew: false)
        #expect((card.playRequest?.stillPath, card.playRequest?.posterPath) == ("/s.jpg", "/p.jpg"))
        let e = EpisodeDetail(id: 1, season: 3, number: 7, title: "The Long Dark", stillPath: "/s.jpg", hasFile: true, aired: true, versions: [MediaVersion(fileID: 5, label: "a", sizeBytes: 1)])
        let request = e.playRequest(showTitle: "Harbor Lights", backdropPath: "/b.jpg", posterPath: "/p.jpg")
        #expect((request?.stillPath, request?.posterPath, request?.backdropPath) == ("/s.jpg", "/p.jpg", "/b.jpg"))
    }
}

@Suite struct LoadableTests {
    @Test func runKeepsTheValueOrTheSentence() async {
        #expect(await Loadable.run { 3 } == .loaded(3))
        #expect(await Loadable<Int>.run { throw APIError(status: 404, message: "Not found.") } == .failed("Not found."))
        #expect(await Loadable<Int>.run { throw CancellationError() } == nil)
    }
}

@Suite struct HostTests {
    @Test func localHostsMayUsePlainHTTP() {
        #expect(isLocalNetworkHost("192.168.1.20"))
        #expect(isLocalNetworkHost("NAS.local"))
        #expect(isLocalNetworkHost("localhost"))
        #expect(isLocalNetworkHost("fe80::1"))
        #expect(!isLocalNetworkHost("home.example.net"))
        #expect(!isIPv4Address("1.2.3"))
        #expect(!isIPv4Address("1.2.3.4444"))
        #expect(spacedPairingCode("K7M4PQ") == "K7M 4PQ")
        #expect(spacedPairingCode("ABC") == "ABC")
    }

    @Test func anAppleTVReachesPrivateIPv4AndLocalNames() {
        for host in ["192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254", "nas.local", "Studio.LOCAL"] {
            #expect(isLANHost(host), "\(host)")
        }
        // Tailscale, public names and addresses, loopback, IPv6, nonsense.
        for host in ["studio.tail1234.ts.net", "100.101.102.103", "home.example.net", "8.8.8.8", "172.32.0.1", "192.169.1.1",
                     "localhost", "127.0.0.1", "fd00::5", "999.168.1.1", ""] {
            #expect(!isLANHost(host), "\(host)")
        }
    }
}

@Suite struct DownloadWordingTests {
    @Test func sizesReadAsTheAndroidAppReadsThem() {
        #expect(downloadSize(4_500_000_000, estimated: false) == "4.2 GB")
        #expect(downloadSize(49_400_000_000, estimated: true) == "about 46 GB")
        #expect(downloadSize(4_700_000_000, estimated: true) == "about 4.4 GB")
        #expect(downloadSize(40_000_000, estimated: true) == "about 38.1 MB")
        #expect(neededSize(49_400_000_000) == "47 GB")
        #expect(neededSize(4_500_000_000) == "5.2 GB")
        #expect(freeSize(103_500_000_000) == "96.4 GB")
    }

    @Test func theButtonAndTheMenu() {
        #expect(downloadLabel(.waiting(.wifi)) == "Waiting for Wi-Fi")
        #expect(downloadLabel(.waiting(.turn)) == "Waiting")
        #expect(downloadLabel(.downloading(percent: 42, bytes: 0)) == "Downloading 42%")
        #expect(downloadLabel(.paused(percent: 42, bytes: 0)) == "Paused · 42%")
        #expect(downloadLabel(.failed) == "Couldn’t download")
        #expect(downloadHelp(.waiting(.turn)) == "Another download is first.")
        #expect(downloadHelp(.preparing) == "The server is getting it ready.")
        #expect(downloadMenuTitle(.paused(percent: 42, bytes: 0)) == "Paused at 42%")

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let item = OfflineItem(kind: .movie, title: "Dead Reckoning Bay")
        var d = OfflineDownload(
            id: "a", fileID: 1, item: item, quality: .original, mode: .direct, protocol: .file, sizeBytes: 49_500_000_000, sizeEstimated: false,
            status: .downloaded, completedAt: Date(timeIntervalSince1970: 1_793_318_400)
        )
        #expect(downloadMenuLine(d, calendar: calendar) == "Original · 46.1 GB · downloaded Oct 30")
        d.status = .downloading(percent: 42, bytes: 20_800_000_000)
        d.sizeEstimated = true
        #expect(downloadMenuLine(d) == "19.4 of about 46 GB · on Wi-Fi")
        d.status = .waiting(.wifi)
        #expect(downloadMenuLine(d, device: "iPad") == "It starts when this iPad is on Wi-Fi.")
        d.mark = .newVersion
        #expect(downloadRowState(d) == "A new version is available")
    }

    @Test func titlesAndQualities() {
        #expect(downloadTitle(OfflineItem(kind: .episode, title: "Keel", season: 3, episode: 5)) == "S03 E05 · Keel")
        #expect(downloadTitle(OfflineItem(kind: .movie, title: "Harbor of Glass")) == "Harbor of Glass")
        #expect(DownloadQuality(id: "720") == .p720)
        #expect(DownloadQuality(id: "2160") == .original)
        #expect(DownloadQuality.p1080.label == "1080p · 8 Mbps")
        #expect(downloadGroupLine(count: 3, bytes: 13_000_000_000, estimated: false) == "3 episodes · 12.1 GB")
    }
}
