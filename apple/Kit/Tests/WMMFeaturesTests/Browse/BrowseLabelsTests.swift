import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@Suite struct BrowseLabelsTests {
    @Test func continueWatchingLines() {
        let items = FakeData.continueWatching
        #expect(items[0].remainingLine == "S02 E04 · 23 min left")
        #expect(items[1].remainingLine == "1 h 12 min left")
        #expect(items[0].pageRoute == .show(id: 101))
        #expect(items[0].pageLabel == "Go to show")
        #expect(items[1].pageRoute == .movie(id: 500))
        #expect(items[1].pageLabel == "Go to movie")
    }

    @Test func homeTotalsAndHeroBadges() throws {
        let home = FakeData.home(now: testToday)
        #expect(home.totalsLine(now: testToday) == "214 movies · 142 shows · 3,418 episodes · 16.7 TB · last scan 12 min ago")
        #expect(home.heroBadges == ["4K", "HDR"])
        #expect(!home.isEmpty)

        var bare = home
        bare.hero?.quality = "1080p"
        bare.hero?.hdr = false
        #expect(bare.heroBadges == ["1080p"])
        // An older server sends neither: Harbor Lights' card in Recently added has them.
        var old = home
        old.hero?.quality = nil
        old.hero?.hdr = false
        #expect(old.heroBadges == ["4K", "HDR"])

        let hero = try #require(home.hero)
        #expect(hero.factParts == ["S03 E07", "The Long Dark", "52 min"])
        #expect(hero.pageRoute == .episode(showID: 100, season: nil, id: 100037))
        #expect(hero.pageLabel == "Go to episode")
        var showHero = hero
        showHero.kind = .show
        #expect(showHero.pageRoute == .show(id: 100))
        #expect(showHero.pageLabel == "Go to show")
    }

    @Test func recentlyAiredAndSearchEpisodeLines() {
        let aired = FakeData.recentlyAired(now: testToday)
        #expect(aired[0].codeBadge == "S03 · E07")
        #expect(aired[0].airedLine(today: testToday) == "S03E07 The Long Dark · yesterday")
        #expect(aired[1].airedLine(today: testToday) == "S01E03 Night Court · 2 days ago")
        #expect(aired[0].searchLine(today: testToday) == "Harbor Lights · S03 E07 · aired yesterday")
        #expect(aired[4].searchLine(today: testToday) == "Glass Country · S01 E06 · watched")
        #expect(aired[0].route == .episode(showID: 100, season: 3, id: 100037))
    }

    @Test func cardLines() {
        #expect(FakeData.shows[0].gridSubtitle == "2022 · 3 seasons")
        #expect(FakeData.movies[0].gridSubtitle == "2026 · 1 h 52 min")
        #expect(unmatchedFolder.gridSubtitle == "Not looked up yet")
        #expect(FakeData.shows[0].searchLine == "2022 · 3 seasons · Drama")
        #expect(FakeData.movies[0].searchLine == "2026 · 1 h 52 min · 4K")
        #expect(FakeData.movies[6].searchLine == "2023 · 1 h 52 min · 1080p · watched")
        #expect(decadeLabel(1990) == "1990s")
        #expect(noMatchesMessage("harbr") == "Nothing is called “harbr”. Check the spelling, or try fewer words.")
        #expect(watchToggleLabel(watched: false) == "Mark watched")
        #expect(watchToggleLabel(watched: true) == "Mark unwatched")
        #expect(markedMessage(watched: true) == "Marked as watched")
    }

    @Test func whatOpeningACardDoes() {
        #expect(FakeData.shows[0].opening == .page(.show(id: 100)))
        #expect(FakeData.movies[0].opening == .page(.movie(id: 500)))
        #expect(unmatchedFilm.opening == .play(PlayRequest(fileID: 7001, title: "Glass Tide (2023).mkv")))
        #expect(unmatchedFolder.opening == .needsMatching)
    }

    @Test func showFactsAndStatus() {
        var show = FakeData.show(id: 100)
        #expect(show.facts == "2022– · 3 seasons · 21 episodes · Drama, Crime · TV-14 · ★ 7.0")
        #expect(show.statusPill == StatusPill(text: "Continuing · next Nov 5", active: true))
        #expect(show.nextUpLabel == "Play next · S03 E05")
        #expect(!show.allWatched)
        show.contentRating = nil
        #expect(show.facts == "2022– · 3 seasons · 21 episodes · Drama, Crime · ★ 7.0", "the episodes on disk")
        let ended = FakeData.show(id: 106)
        #expect(ended.facts.hasPrefix("2020 · 2 seasons"))
        #expect(ended.statusPill == StatusPill(text: "Ended", active: false))
        show.status = "Canceled"
        #expect(show.statusPill == StatusPill(text: "Cancelled", active: false))
        show.status = nil
        #expect(show.statusPill == nil)
        #expect(SeasonSummary(id: 1, number: 0, name: "Specials", episodeCount: 4, availableCount: 4, watchedCount: 0).chipName == "Specials")
        #expect(show.seasons[2].chipName == "Season 3")
        #expect(show.seasons[2].countLabel == "8")
    }

    @Test func aShowListsOnlyWhatIsOnDisk() {
        var season = FakeData.season(showID: 100, number: 3)
        season.episodes[1].hasFile = false
        let upcoming = season.episodes[8].id
        let missing = season.episodes[1].id
        #expect(season.episodesOnDisk().episodes.count == 7, "neither the missing episode nor the one yet to air")
        #expect(season.episodesOnDisk(keep: missing).episodes.map(\.id).contains(missing), "the one opened from Home stays")
        #expect(season.episodesOnDisk(keep: upcoming).episodes.last?.id == upcoming)

        var show = FakeData.show(id: 100)
        show.seasons[0].availableCount = 0
        #expect(show.seasonsOnDisk().seasons.map(\.number) == [2, 3])
        #expect(show.seasonsOnDisk(keep: 1).seasons.map(\.number) == [1, 2, 3], "the season asked for stays")
    }

    @Test func episodeMetaAndTags() throws {
        let season = FakeData.season(showID: 100, number: 2)
        let seventh = season.episodes[6]
        #expect(seventh.rowTitle == "7. Lantern Rock")
        #expect(seventh.kicker(showTitle: "Harbor Lights") == "Harbor Lights · S02 E07")
        #expect(seventh.meta(today: testToday) == "Aired Oct 17 · 49 min")
        #expect(seventh.sheetMeta(today: testToday) == "Aired Oct 17 · 49 min · ★ 7.8")
        #expect(seventh.runtimeLabel == "49 min")
        #expect(seventh.fileNote == "2.2 GB")
        let version = try #require(seventh.versions.first)
        #expect(version.episodeTags == ["4K", "HEVC", "HDR", "EAC3 5.1"])
        let upcoming = FakeData.season(showID: 100, number: 3).episodes[8]
        #expect(upcoming.meta(today: testToday) == "Airs Nov 4")
        #expect(upcoming.fileNote == "Upcoming")
    }

    @Test func episodeSheetActions() {
        let season = FakeData.season(showID: 100, number: 3)
        var episode = season.episodes[0]
        episode.watch = nil
        #expect(episode.actions(today: testToday) == EpisodeActions(play: "Play", unavailable: nil, fromStart: false, watchToggle: "Mark watched"))

        // Part watched: Resume, and From the start.
        #expect(season.episodes[4].actions(today: testToday) == EpisodeActions(play: "Resume · 29 min left", unavailable: nil, fromStart: true, watchToggle: "Mark watched"))

        // Watched: plays again, and can be marked unwatched.
        #expect(season.episodes[0].actions(today: testToday) == EpisodeActions(play: "Play", unavailable: nil, fromStart: false, watchToggle: "Mark unwatched"))

        // Aired but not on disk.
        var missing = episode
        missing.hasFile = false
        missing.versions = []
        #expect(missing.actions(today: testToday) == EpisodeActions(play: nil, unavailable: "Not in library", fromStart: false, watchToggle: "Mark watched"))

        // Yet to air: its date, and nothing to mark.
        #expect(season.episodes[8].actions(today: testToday) == EpisodeActions(play: nil, unavailable: "Airs Nov 4", fromStart: false, watchToggle: nil))
    }

    @Test func movieFactsTagsAndDetails() {
        let movie = FakeData.movie(id: 500)
        #expect(movie.facts == "2026 · 1 h 52 min · Thriller · R · ★ 6.5")
        #expect(movie.tags == ["4K", "Dolby Vision", "7.1"])
        #expect(movie.versions[0].tags == ["MKV", "HEVC", "DV P8", "TrueHD 7.1", "56.8 GB"])
        #expect(movie.versions[1].title == "1080p")
        #expect(movie.details == [
            DetailRow("Released", "Sep 26, 2026"),
            DetailRow("Runtime", "1 h 52 min"),
            DetailRow("Genre", "Thriller"),
            DetailRow("Rated", "R"),
            DetailRow("Files", "2 versions · \(formatBytes(70_800_000_000))"),
        ])
        #expect(!movie.resumable)
        #expect(FakeData.movie(id: 501).resumable)
        #expect(playLabel(FakeData.movie(id: 501).watch) == "Resume · 38 min left")
        #expect(playLabel(nil) == "Play")
        #expect(channelsLabel("TrueHD 7.1 · English") == "7.1")
        #expect(channelsLabel("AAC Stereo") == nil)
    }

    @Test func theSeasonDiskLineCountsTheFilesAndTheirSize() {
        let summary = SeasonSummary(id: 3, number: 3, name: "Season 3", episodeCount: 7, availableCount: 4, watchedCount: 2)
        let gb: Int64 = 1_073_741_824
        let episodes = (1...4).map { n in
            EpisodeDetail(id: n, season: 3, number: n, title: "Episode \(n)", hasFile: true, aired: true, versions: [MediaVersion(fileID: n, label: "e\(n).mkv", sizeBytes: 4 * gb)])
        }
        var season = SeasonDetail(showID: 100, showTitle: "Harbor Lights", season: summary, episodes: episodes)
        #expect(season.diskLine == "On disk 4 of 7 · 16.0 GB")
        season.episodes[0].versions.append(MediaVersion(fileID: 99, label: "e1.1080p.mp4", sizeBytes: gb / 2))
        #expect(season.diskLine == "On disk 4 of 7 · 16.5 GB", "every version counts")
        season.episodes = []
        #expect(season.diskLine == "On disk 4 of 7")
    }

    @Test func howAVersionWouldPlay() throws {
        let remux = try #require(FakeData.plan(fileID: 9900))
        #expect(remux.versionLine() == "Remux to fMP4 · audio to AAC 5.1 on this phone")
        #expect(remux.versionLine(on: "this TV") == "Remux to fMP4 · audio to AAC 5.1 on this TV")
        #expect(try #require(FakeData.plan(fileID: 10000)).versionLine() == "Direct play")
    }
}
