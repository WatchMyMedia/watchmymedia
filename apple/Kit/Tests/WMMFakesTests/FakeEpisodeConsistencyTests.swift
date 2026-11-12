import Testing
import WMMFakes
import WMMModel

@MainActor
@Suite struct FakeEpisodeConsistencyTests {
    @Test func theFeaturedEpisodeKeepsTheDebugFileAndMatchesItsSeason() throws {
        let hero = try #require(FakeData.home().hero)
        let card = try #require(FakeData.recentlyAired().first { $0.fileID == 9101 })
        let episode = try #require(FakeData.season(showID: 100, number: 3).episodes.first { $0.id == hero.episodeID })
        #expect(hero.fileID == 9101)
        #expect(episode.fileID == hero.fileID)
        #expect(episode.title == card.title)
        #expect(episode.title == "The Long Dark")
        #expect(episode.runtimeMin == 52)
        #expect(episode.versions.first?.durationMs == 52 * 60_000)
        let request = FakeData.playRequest(fileID: 9101)
        #expect(request.title == "Harbor Lights")
        #expect(request.subtitle == "S03 E07 · The Long Dark")
        #expect(request.showID == 100)
        #expect(request.seasonNumber == 3)
    }

    @Test func everyRecentlyAiredCardMatchesItsSeasonRow() throws {
        for card in FakeData.recentlyAired() {
            let row = try #require(FakeData.season(showID: card.showID, number: card.season).episodes.first { $0.id == card.episodeID })
            #expect(row.number == card.episode)
            #expect(row.title == card.title)
            #expect(row.fileID == card.fileID)
            #expect(row.hasFile == card.hasFile)
            #expect(row.aired)
            #expect(row.watched == card.watched)
            #expect(row.isNew == card.isNew)
        }
    }

    @Test func everyOneEpisodeRecentlyAddedCardNamesAnEpisodeOnDisk() throws {
        let cards = FakeData.home().recentlyAddedShows.filter { $0.episodeID != nil }
        #expect(cards.map(\.id) == [100, 104, 105, 109])
        for card in cards {
            let season = try #require(card.seasonNumber)
            let row = try #require(FakeData.season(showID: card.id, number: season).episodes.first { $0.id == card.episodeID })
            #expect(row.hasFile)
            #expect(card.subtitle?.hasPrefix(String(format: "S%02d E%02d", season, row.number)) == true)
        }
    }

    @Test func continuingEpisodesResolveToTheSameFileAndWatchProgress() throws {
        for item in FakeData.continueWatching where item.kind == .episode {
            let showID = try #require(item.showID)
            let season = try #require(item.season)
            let row = try #require(FakeData.season(showID: showID, number: season).episodes.first { $0.id == item.episodeID })
            #expect(row.fileID == item.fileID)
            #expect(row.hasFile)
            #expect(item.subtitle == "\(episodeCode(season: season, episode: row.number)) · \(row.title)")
            #expect(row.watch?.positionMs == item.positionMs)
            #expect(row.watch?.durationMs == item.durationMs)
            #expect(row.versions.first?.durationMs == item.durationMs)
            let request = FakeData.playRequest(fileID: item.fileID)
            #expect(request.showID == showID)
            #expect(request.seasonNumber == season)
        }
    }

    @Test func summaryAndNextUpUseTheSamePlayableRowsAndFileIDsStayUnique() throws {
        var seen: Set<Int> = []
        for item in FakeData.shows {
            let show = FakeData.show(id: item.id)
            var total = 0
            for summary in show.seasons {
                let season = FakeData.season(showID: show.id, number: summary.number)
                #expect(season.episodes.count == summary.episodeCount)
                #expect(season.episodes.filter(\.hasFile).count == summary.availableCount)
                #expect(season.episodes.filter { $0.hasFile && $0.watched }.count == summary.watchedCount)
                total += summary.availableCount
                for episode in season.episodes {
                    if let fileID = episode.fileID {
                        #expect(seen.insert(fileID).inserted)
                        let request = FakeData.playRequest(fileID: fileID)
                        #expect(request.showID == show.id)
                        #expect(request.seasonNumber == episode.season)
                    }
                }
            }
            #expect(show.episodesOnDisk == total)
            if let next = show.nextUp {
                let row = try #require(FakeData.season(showID: show.id, number: next.season).episodes.first { $0.id == next.episodeID })
                #expect(next.fileID == row.fileID)
                #expect(next.title == row.title)
                #expect(!row.watched)
            }
        }
    }

    @Test func debugPlaybackAndNextEpisodeStayInHarborLights() async throws {
        let player = FakePlaybackController()
        defer { player.release() }
        var events = player.events.makeAsyncIterator()
        player.start(PlayRequest(fileID: 9101, title: ""))
        #expect(player.state.title == "Harbor Lights")
        #expect(player.state.subtitle == "S03 E07 · The Long Dark")
        #expect(player.state.durationMs == 52 * 60_000)
        player.seek(toMs: 60_000)
        #expect(player.state.skip?.segment.kind == .intro)
        player.seek(toMs: 49 * 60_000 + 30_000)
        let next = try #require(player.state.upNext?.next)
        let row = try #require(FakeData.season(showID: 100, number: 3).episodes.first { $0.id == next.episodeID })
        #expect(next.fileID == 19338)
        #expect(next.fileID == row.fileID)
        #expect(next.title == row.title)
        #expect(next.runtimeMs == row.versions.first?.durationMs)
        #expect(next.fileID != 9102)
        player.playNext()
        let event = await events.next()
        if case .startedNext(let fileID)? = event { #expect(fileID == 19338) }
        else { Issue.record("Expected the actual Harbor next-file event") }
        #expect(player.state.title == "Harbor Lights")
        #expect(player.state.subtitle == "S03 E08 · Squall Line")
        #expect(player.state.durationMs == 48 * 60_000)
        #expect(player.state.nextEpisode == nil, "The following row has not aired")
        let resolved = FakeData.playRequest(fileID: next.fileID)
        #expect(resolved.showID == 100)
        #expect(resolved.seasonNumber == 3)
        #expect(resolved.subtitle == player.state.subtitle)
    }

    @Test func northfieldsFileNoLongerActsAsHarborsNextEpisode() throws {
        let player = FakePlaybackController()
        defer { player.release() }
        player.start(PlayRequest(fileID: 9102, title: ""))
        #expect(player.state.title == "Northfield Precinct")
        #expect(player.state.subtitle == "S01 E03 · Night Court")
        let next = try #require(player.state.nextEpisode)
        #expect(next.showID == 103)
        #expect(next.season == 1)
        #expect(next.episode == 4)
        let row = try #require(FakeData.season(showID: 103, number: 1).episodes.first { $0.fileID == next.fileID })
        #expect(row.id == next.episodeID)
        #expect(row.title == next.title)
    }

    @Test func automaticPreviewKeepsTheChoiceAndShowsRecoveryStates() {
        let player = FakePlaybackController()
        defer { player.release() }
        player.start(PlayRequest(fileID: 9101, title: ""))
        player.previewAutomatic(.slowed)
        #expect(player.state.quality == .auto)
        #expect(player.state.autoStep == .hd)
        #expect(player.state.video?.height == 720)
        player.previewAutomatic(.failed)
        #expect(player.state.autoStep == .hd)
        #expect(player.state.qualityNote == "Still playing at 720p. Quality will retry later.")
        player.previewAutomatic(.recovered)
        #expect(player.state.quality == .auto)
        #expect(player.state.autoStep == .original)
        #expect(player.state.mode == .direct)
        #expect(player.state.video?.height == 2160)
    }
}
