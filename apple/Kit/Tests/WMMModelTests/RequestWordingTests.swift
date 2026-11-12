import Foundation
import Testing
import WMMModel

/// The wording matches web/src/lib/requests.ts and the Android app's.
@Suite struct RequestWordingTests {
    let now = Date(timeIntervalSince1970: 1_793_448_000) // 2026-10-31 12:00 UTC

    private func episode(aired: Bool = true, airDate: String? = nil, monitored: Bool? = nil, download: QueueDownload? = nil, request: RequestState? = nil) -> EpisodeDetail {
        EpisodeDetail(id: 1, season: 3, number: 1, title: "Fog Signal", airDate: airDate, hasFile: false, aired: aired, monitored: monitored, download: download, request: request)
    }

    private func request(_ status: RequestStatus, mine: Bool = false) -> RequestState {
        RequestState(id: 1, status: status, requestedBy: "Alex", mine: mine, seasons: [3], createdAt: now)
    }

    @Test func aMissingEpisodeSaysItsDownloadFirst() {
        let downloading = episode(monitored: true, download: QueueDownload(state: .downloading, progress: 0.45, finishesAt: now + 12 * 60), request: request(.pending))
        #expect(downloading.missingLine(now: now) == StatusLine("Downloading 45% · 12 min left", tone: .accent, icon: .download, progress: 0.45))
        #expect(episode(download: QueueDownload(state: .downloading, progress: 0.2)).missingLine(now: now).text == "Downloading 20%")
        #expect(episode(download: QueueDownload(state: .importing, progress: 1)).missingLine(now: now).text == "Waiting to import")
        #expect(episode(download: QueueDownload(state: .queued, progress: 0)).missingLine(now: now).text == "Waiting to download")
        #expect(episode(download: QueueDownload(state: .failed, progress: 0.2)).missingLine(now: now) == StatusLine("Download failed", tone: .warning, icon: .failed))
    }

    @Test func thenTheRequest() {
        #expect(episode(request: request(.pending)).missingLine(now: now).text == "Requested by Alex · Waiting for approval")
        #expect(episode(request: request(.pending, mine: true)).missingLine(now: now).text == "Requested by you · Waiting for approval")
        #expect(episode(request: request(.approved)).missingLine(now: now).text == "Approved · Sonarr is searching")
        #expect(episode(monitored: false, request: request(.declined)).missingLine(now: now).text == "Not monitored", "a declined request says nothing about the episode")
    }

    @Test func thenWhenItAirsAndWhatSonarrDoes() {
        #expect(episode(aired: false, airDate: "2026-11-04").missingLine(now: now) == StatusLine("Airs Wed 4 Nov", tone: .muted, icon: .airs))
        #expect(episode(aired: false).missingLine(now: now).text == "Not aired yet")
        #expect(episode(monitored: true).missingLine(now: now).text == "Missing · Sonarr is looking for it")
        #expect(episode(monitored: false).missingLine(now: now).text == "Not monitored")
        #expect(episode().missingLine(now: now).text == "Not in library", "no Sonarr has the show")
    }

    @Test func timeLeftRoundsToMinutes() {
        #expect(timeLeft(until: now + 80 * 60, now: now) == "1 h 20 min left")
        #expect(timeLeft(until: now + 120 * 60, now: now) == "2 h left")
        #expect(timeLeft(until: now - 60, now: now) == "1 min left")
        #expect(timeLeft(until: nil, now: now) == nil)
    }

    @Test func aSeasonCountsWhatHasAired() {
        func ep(_ n: Int, file: Bool, aired: Bool = true) -> EpisodeDetail { EpisodeDetail(id: n, season: 3, number: n, title: "", hasFile: file, aired: aired) }
        let summary = SeasonSummary(id: 3, number: 3, name: "Season 3", episodeCount: 8, availableCount: 4, watchedCount: 0)
        let season = SeasonDetail(showID: 1, showTitle: "Harbor Lights", season: summary, episodes: (1...3).map { ep($0, file: false) } + (4...7).map { ep($0, file: true) } + [ep(8, file: false, aired: false)])
        #expect(season.missingSummary == "4 of 7 episodes · 3 missing")
        #expect(season.missingEpisodes.map(\.number) == [1, 2, 3])
        let whole = SeasonDetail(showID: 1, showTitle: "", season: summary, episodes: (1...8).map { ep($0, file: true) })
        #expect(whole.missingSummary == "8 episodes")
    }

    @Test func aSeasonCanBeAskedForWhenItAiredAndNobodyHasAsked() {
        var s = SeasonSummary(id: 2, number: 2, name: "Season 2", airDate: "2024-03-01", episodeCount: 10, availableCount: 8, watchedCount: 0)
        #expect(s.requestable(today: now))
        s.availableCount = 10
        #expect(!s.requestable(today: now))
        s.availableCount = 8
        s.airDate = "2027-01-01"
        #expect(!s.requestable(today: now))
        s.airDate = "2024-03-01"
        s.request = request(.pending)
        #expect(!s.requestable(today: now) && s.requestOpen)
        s.request = request(.declined)
        #expect(s.requestable(today: now))
    }

    @Test func aRequestsState() {
        func r(_ status: RequestStatus, note: String? = nil, download: QueueDownload? = nil) -> MediaRequest {
            MediaRequest(id: 1, status: status, requestedBy: "Sam", mine: true, note: note, createdAt: now, mediaType: .movie, tmdbID: 1, title: "Low Water", download: download)
        }
        #expect(r(.pending).stateLine.text == "Waiting for approval")
        #expect(r(.declined, note: "Already on the other server").stateLine.text == "Declined · “Already on the other server”")
        #expect(r(.failed, note: "Radarr has no root folder for this library").stateLine == StatusLine("Failed · Radarr has no root folder for this library", tone: .warning, icon: .failed))
        #expect(r(.approved, download: QueueDownload(state: .downloading, progress: 0.45)).stateLine == StatusLine("Downloading 45%", tone: .accent, icon: .download, progress: 0.45))
        #expect(r(.approved).stateLine.text == "Approved")
        #expect(r(.available).stateLine.text == "In your library")
        #expect(r(.declined).stateLine.text == "Declined")
    }

    @Test func whatWasAskedFor() {
        #expect(requestWhat(.tv, seasons: [2]) == "Season 2")
        #expect(requestWhat(.tv, seasons: [0]) == "Specials")
        #expect(requestWhat(.tv, seasons: [3, 1]) == "Seasons 1 and 3")
        #expect(requestWhat(.tv, seasons: [1, 2]) == "Seasons 1 and 2")
        #expect(requestWhat(.tv, seasons: [1, 2, 3, 4]) == "Seasons 1–4")
        #expect(requestWhat(.tv, seasons: [1, 2, 5]) == "Seasons 1, 2 and 5")
        #expect(requestWhat(.movie, seasons: []) == "Movie")
        #expect(requestButtonLabel(.tv, seasons: [2, 3]) == "Request 2 seasons")
        #expect(requestButtonLabel(.tv, seasons: [3]) == "Request season 3")
        #expect(requestButtonLabel(.tv, seasons: [0]) == "Request specials")
        #expect(requestButtonLabel(.movie, seasons: []) == "Request movie")
    }

    @Test func theSheetsLines() {
        let show = Requestable(mediaType: .tv, tmdbID: 1, title: "Harbourside", year: 2019, state: .requestable, seasons: [
            RequestableSeason(number: 0, name: "Specials", episodeCount: 2, state: .missing),
            RequestableSeason(number: 1, name: "Season 1", episodeCount: 8, state: .requested),
            RequestableSeason(number: 2, name: "Season 2", episodeCount: 8, state: .missing),
            RequestableSeason(number: 3, name: "Season 3", episodeCount: 8, state: .partial),
            RequestableSeason(number: 4, name: "Season 4", episodeCount: 6, state: .unaired),
        ])
        #expect(show.searchLine == "TV show · 2019")
        #expect(show.sheetMeta == "2019 · 4 seasons")
        #expect(show.seasons.map(\.state.label) == [nil, "Requested", nil, "Partly in library", "Not aired yet"])
        #expect(show.seasons[1].episodesLine == "8 episodes")
        #expect(show.initialSeasons() == [0, 2, 3])
        #expect(show.initialSeasons(preselect: [3, 1]) == [3], "a season asked for already can't be ticked")
        let movie = Requestable(mediaType: .movie, tmdbID: 2, title: "Harbor Ghosts", year: 2024, state: .requestable)
        #expect(movie.searchLine == "Movie · 2024")
    }

    @Test func theConfirmation() {
        let mine = MediaRequest(id: 1, status: .pending, requestedBy: "Alex", mine: true, createdAt: now, mediaType: .movie, tmdbID: 1, title: "Harbor Ghosts")
        #expect(CreatedRequest(request: mine, created: true).sentMessage(viaSeerr: false) == "Requested. You’ll find it in Your requests.")
        #expect(CreatedRequest(request: mine, created: true).sentMessage(viaSeerr: true) == "Sent to Seerr. You’ll find it in Your requests.")
        var theirs = mine
        theirs.mine = false
        theirs.requestedBy = "Sam"
        #expect(CreatedRequest(request: theirs, created: false).sentMessage(viaSeerr: false) == "Sam asked for this already. It’s in their requests, and you’ll see it here when it arrives.")
    }

    @Test func whenARequestWasMade() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        #expect(formatWhen(now - 2 * 3600, now: now, calendar: calendar) == "2 h ago")
        #expect(formatWhen(now - 26 * 3600, now: now, calendar: calendar) == "yesterday")
        #expect(formatWhen(now - 2 * 86_400, now: now, calendar: calendar) == "2 days ago")
        #expect(formatWhen(now - 5 * 86_400, now: now, calendar: calendar) == "5 days ago")
        #expect(formatWhen(now - 11 * 86_400, now: now, calendar: calendar) == "Oct 20")
    }

    @Test func problemsAndTheInbox() {
        #expect(ProblemKind.allCases.map(\.label) == ["Video", "Audio", "Subtitles", "Other"])
        #expect(declinePrompt("Alex") == "Tell Alex why (optional)")
        #expect(settingsTabLabel(inbox: 4) == "Settings, 4 in the Inbox")
        #expect(settingsTabLabel(inbox: 0) == "Settings")
    }
}
