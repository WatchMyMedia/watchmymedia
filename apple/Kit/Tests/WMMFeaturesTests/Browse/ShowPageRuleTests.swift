import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// Which episode goes on top of the show's page, and why (PLAN-SHOW-PAGE.md §3).
@Suite struct ShowPageRuleTests {
    /// An episode of show 1: `watched` finished, `partway` started, `file` on disk.
    private func episode(_ season: Int, _ number: Int, file: Bool = true, watched: Bool = false, partway: Bool = false, aired: Bool = true, airDate: String? = nil) -> EpisodeDetail {
        let watch: WatchProgress? = watched ? WatchProgress(positionMs: 0, durationMs: 60_000 * 50, completed: true)
            : partway ? WatchProgress(positionMs: 60_000 * 20, durationMs: 60_000 * 50, completed: false) : nil
        return EpisodeDetail(id: season * 100 + number, season: season, number: number, title: "E\(number)", airDate: airDate, runtimeMin: 50, hasFile: file, aired: aired,
                             versions: file ? [MediaVersion(fileID: season * 1000 + number, label: "e.mkv", sizeBytes: 1)] : [], watch: file ? watch : nil)
    }

    private func season(_ episodes: [EpisodeDetail], number: Int) -> SeasonDetail {
        SeasonDetail(showID: 1, showTitle: "Harbor Lights", season: summary(number, episodes), episodes: episodes)
    }

    private func summary(_ number: Int, _ episodes: [EpisodeDetail]) -> SeasonSummary {
        let onDisk = episodes.filter(\.hasFile)
        return SeasonSummary(id: number, number: number, name: seasonName(number), episodeCount: episodes.count, availableCount: onDisk.count, watchedCount: onDisk.filter(\.watched).count)
    }

    private func show(_ seasons: [SeasonDetail], next: EpisodeDetail? = nil) -> ShowDetail {
        ShowDetail(id: 1, libraryID: 1, title: "Harbor Lights", year: 2022, overview: "", status: nil, genres: [], rating: nil, posterPath: nil, backdropPath: nil,
                   quality: nil, hdr: false, seasons: seasons.map(\.season), totalEpisodes: 0, episodesOnDisk: seasons.reduce(0) { $0 + $1.season.availableCount },
                   totalSizeBytes: 0, nextAirDate: nil,
                   nextUp: next.map { NextUp(episodeID: $0.id, season: $0.season, episode: $0.number, title: $0.title, fileID: $0.fileID ?? 0) }, cast: [])
    }

    @Test func theShowPutsTheServersNextEpisodeOnTop() throws {
        let one = season([episode(1, 1, watched: true), episode(1, 2, watched: true)], number: 1)
        let two = season([episode(2, 1, watched: true), episode(2, 2), episode(2, 3)], number: 2)
        let show = show([one, two], next: two.episodes[1])
        #expect(ShowPageRule.season(for: .show, show: show) == 2)
        let top = try #require(ShowPageRule.onTop(for: .show, show: show, season: two))
        #expect(top == OnTop(episodeID: 202, reason: .upNext))
        #expect(top.reason?.label == "Up next")
    }

    @Test func partwayThroughItSaysResume() throws {
        let one = season([episode(1, 1, watched: true), episode(1, 2, partway: true)], number: 1)
        let show = show([one], next: one.episodes[1])
        #expect(ShowPageRule.onTop(for: .show, show: show, season: one)?.reason == .resume)
        #expect(OnTopReason.resume.label == "Up next · resume")
    }

    @Test func nothingWatchedSaysStartHere() throws {
        let one = season([episode(1, 1), episode(1, 2)], number: 1)
        let show = show([one], next: one.episodes[0])
        #expect(ShowPageRule.onTop(for: .show, show: show, season: one) == OnTop(episodeID: 101, reason: .startHere))
        #expect(OnTopReason.startHere.label == "Start here")
    }

    @Test func watchedToTheEndPutsTheFirstEpisodeOfTheLowestSeasonOnTop() throws {
        let specials = season([episode(0, 1)], number: 0)
        let one = season([episode(1, 1, watched: true), episode(1, 2, watched: true)], number: 1)
        let two = season([episode(2, 1, watched: true)], number: 2)
        let show = show([specials, one, two])
        #expect(ShowPageRule.season(for: .show, show: show) == 1, "above 0: not the specials")
        #expect(ShowPageRule.onTop(for: .show, show: show, season: one) == OnTop(episodeID: 101, reason: .watchedItAll), "unwatched specials don't count")
        #expect(OnTopReason.watchedItAll.label == "Watched it all")
    }

    @Test func aSeasonPutsItsFirstUnfinishedEpisodeOnTopElseItsFirst() throws {
        let one = season([episode(1, 1, watched: true), episode(1, 2, file: false), episode(1, 3, partway: true), episode(1, 4)], number: 1)
        let two = season([episode(2, 1, watched: true), episode(2, 2, watched: true)], number: 2)
        let show = show([one, two], next: one.episodes[2])
        #expect(ShowPageRule.season(for: .season(2), show: show) == 2)
        #expect(ShowPageRule.onTop(for: .season(1), show: show, season: one) == OnTop(episodeID: 103), "on disk, partway counts as unfinished, no chip")
        #expect(ShowPageRule.onTop(for: .season(2), show: show, season: two) == OnTop(episodeID: 201))
        #expect(ShowPageRule.season(for: .season(9), show: show) == 1, "a season that's gone: as for the show")
    }

    @Test func anEpisodePutsItselfOnTopOrItsSeasonsStartWhenItsGone() throws {
        let one = season([episode(1, 1, watched: true), episode(1, 2)], number: 1)
        let show = show([one], next: one.episodes[1])
        #expect(ShowPageRule.season(for: .episode(id: 101, season: 1), show: show) == 1)
        #expect(ShowPageRule.season(for: .episode(id: 101, season: nil), show: show) == nil, "looked for")
        #expect(ShowPageRule.season(for: .episode(id: 102, season: nil), show: show) == 1, "the next episode's season")
        #expect(ShowPageRule.onTop(for: .episode(id: 101, season: 1), show: show, season: one) == OnTop(episodeID: 101))
        #expect(ShowPageRule.onTop(for: .episode(id: 999, season: 1), show: show, season: one) == OnTop(episodeID: 102, gone: true), "as when arriving at the season")
    }

    @Test func afterADeleteTheNextEpisodeElseTheOneBefore() {
        let row = [episode(1, 1), episode(1, 2), episode(1, 3)]
        #expect(ShowPageRule.afterDelete(102, in: row)! == (103, true))
        #expect(ShowPageRule.afterDelete(103, in: row)! == (102, false))
        #expect(ShowPageRule.afterDelete(101, in: [row[0]]) == nil)
        #expect(ShowPageNote.deleted("Neap", next: true) == "Deleted “Neap”. Showing the next episode.")
        #expect(ShowPageNote.deleted("Neap", next: false) == "Deleted “Neap”. Showing the one before.")
        #expect(ShowPageNote.deleted("Neap", next: nil) == "Deleted “Neap”")
    }

    @Test func specialsAreTheLastTab() {
        let show = show([season([episode(0, 1)], number: 0), season([episode(2, 1)], number: 2), season([episode(1, 1)], number: 1)])
        #expect(show.tabSeasons.map(\.number) == [1, 2, 0])
        #expect(show.tabSeasons.map(\.chipName) == ["Season 1", "Season 2", "Specials"])
    }

    @Test func theArrivalComesFromTheRoute() {
        #expect(ShowArrival(season: nil, episode: nil) == .show)
        #expect(ShowArrival(season: 2, episode: nil) == .season(2))
        #expect(ShowArrival(season: 2, episode: 7) == .episode(id: 7, season: 2))
    }

    @Test func theWording() throws {
        let today = try #require(parseDay("2026-11-01"))
        let three = season([episode(3, 1, watched: true), episode(3, 2), episode(3, 3, file: false, aired: false, airDate: "2026-11-18")], number: 3)
        #expect(three.rowLine(today: today) == "2 episodes · 1 watched · E03 airs \(formatDay("2026-11-18", today: today)!)")
        let done = season([episode(1, 1, watched: true)], number: 1)
        #expect(done.rowLine(today: today) == "1 episode · all watched")

        let gaps = SeasonSummary(id: 1, number: 1, name: "Season 1", episodeCount: 10, availableCount: 4, watchedCount: 0)
        #expect(gaps.tabCount(missingShown: false) == "4")
        #expect(gaps.tabCount(missingShown: true) == "4 of 10")
        #expect(SeasonSummary(id: 4, number: 4, name: "Season 4", episodeCount: 6, availableCount: 0, watchedCount: 0).tabCount(missingShown: true) == "none")

        #expect(three.episodes[0].rowCardLine(today: today) == "Watched")
        #expect(three.episodes[1].rowCardLine(today: today) == "50 min")
        #expect(episode(3, 4, partway: true).rowCardLine(today: today) == formatRemaining(ms: 30 * 60_000))
        #expect(three.episodes[2].rowCardLine(today: today).hasPrefix("Airs "))
        #expect(three.episodes[0].eyebrow(showTitle: "Harbor Lights") == "Harbor Lights · S03 E01")
        #expect(episode(0, 2).eyebrow(showTitle: "Harbor Lights") == "Harbor Lights · Specials E02")
    }
}
