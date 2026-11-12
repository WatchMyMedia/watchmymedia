import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The episode page's wording (PLAN-EPISODE-PAGE.md §2, §3), on Harbor
/// Lights' season 3 in the fakes: E04 watched, E05 partway, E07 new, E09 yet to air.
@MainActor
@Suite struct EpisodeLabelsTests {
    let season = FakeData.season(showID: 100, number: 3, now: testToday)

    private func episode(_ number: Int) -> EpisodeDetail { season.episodes[number - 1] }

    @Test func playSaysHowLongOrWhereItResumes() {
        #expect(episode(7).pagePlayLabel == "Play · 52 min")
        #expect(episode(5).pagePlayLabel == "Resume from 21:00")
        #expect(episode(4).pagePlayLabel == "Play · 52 min", "watched plays again")
        #expect(episode(9).pagePlayLabel == nil, "yet to air")
        var unknown = episode(7)
        unknown.runtimeMin = nil
        #expect(unknown.pagePlayLabel == "Play")
    }

    @Test func titlesAndTheSeasonLine() {
        #expect(episode(7).seasonLine == "Season 3 · Episode 7")
        var special = episode(2)
        special.season = 0
        #expect(special.seasonLine == "Specials · Episode 2")
        #expect(episode(6).shortTitle == "E06 Squall Line")
        var untitled = episode(6)
        untitled.title = ""
        #expect(untitled.displayTitle == "Episode 6")
        #expect(seasonName(3) == "Season 3")
    }

    @Test func theShowsGroupNamesTheSeason() {
        #expect(markSeasonLabel(3, watched: false) == "Mark Season 3 watched")
        #expect(markSeasonLabel(0, watched: true) == "Mark Specials unwatched")
        #expect(markShowLabel(watched: false) == "Mark show watched")
        #expect(markShowLabel(watched: true) == "Mark show unwatched")
        #expect(missingEpisodesLabel(shown: false) == "Show missing episodes")
        #expect(missingEpisodesLabel(shown: true) == "Hide missing episodes")
        var missing = episode(7)
        missing.hasFile = false
        #expect(missing.seasonCardLine(today: testToday) == "Not in library")
        #expect(missing.tvSeasonCardLine(today: testToday) == "Not in library")
    }

    @Test func theFileLineNamesTheSubtitlesAndSize() {
        #expect(episode(7).pageFileLine == formatBytes(2_400_000_000))
        var subtitled = episode(7)
        subtitled.versions[0].subtitles = [
            SubtitleOption(id: "s1", label: "English", language: "eng"),
            SubtitleOption(id: "s2", label: "English · SDH", language: "eng"),
            SubtitleOption(id: "s3", label: "Spanish", language: "spa"),
        ]
        #expect(subtitled.pageFileLine == "Subtitles: English, Spanish · \(formatBytes(2_400_000_000))")
        #expect(episode(9).pageFileLine == "Upcoming")
    }

    @Test func partWatchedShowsHowFar() {
        #expect(episode(5).progressLine == "21:00 of 53 min · 29 min left")
        #expect(episode(7).progressLine == nil)
    }

    @Test func theSeasonRowsCards() {
        // The still shows the check and how far it was watched, so the line doesn't.
        #expect(episode(4).seasonCardLine(today: testToday) == "52 min")
        #expect(episode(5).seasonCardLine(today: testToday) == "53 min")
        #expect(episode(7).seasonCardLine(today: testToday) == "52 min")
        #expect(episode(9).seasonCardLine(today: testToday) == "Airs Nov 4")

        #expect(episode(4).tvSeasonCardLine(today: testToday) == "Aired Oct 14 · 52 min")
        #expect(episode(7).tvSeasonCardLine(today: testToday) == "Aired yesterday · 52 min")
        #expect(episode(9).tvSeasonCardLine(today: testToday) == "Airs Nov 4")
    }

    @Test func previousAndNext() {
        #expect(episode(6).neighbourKicker(next: false, today: testToday) == "Previous")
        #expect(episode(7).neighbourKicker(next: true, today: testToday) == "Next")
        #expect(episode(9).neighbourKicker(next: true, today: testToday) == "Next · airs Nov 4")
    }

    @Test func theShowCardAndTheSeasonsCount() {
        let show = FakeData.show(id: 100)
        #expect(show.seasonCountLine == "3 seasons")
        #expect(season.season.episodesLine == "9 episodes")
        #expect(season.season.tvEpisodesLine == "8 of 9 episodes")
        #expect(FakeData.season(showID: 100, number: 1).season.tvEpisodesLine == "8 episodes")
    }

    @Test func theDeleteConfirmationReadsFromThePreview() {
        let preview = DeletePreview(managed: true, files: 1, sizeBytes: 4_500_000_000, apps: [DeleteApp(connection: "Sonarr", kind: .sonarr, recycleBin: "/recycle")])
        let dialog = EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: preview)
        #expect(dialog.title == "Delete “The Long Dark”?")
        #expect(dialog.subtitle == "Harbor Lights · S03 E07")
        #expect(dialog.file == "Harbor Lights - S03E07.mkv · \(formatBytes(4_500_000_000))", "the preview's size")
        #expect(dialog.text == "Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again. It goes to Sonarr’s recycling bin. Your watch history stays.")
        #expect(dialog.confirm == "Delete episode")
        #expect(dialog.dismiss == "Cancel")

        var noBin = preview
        noBin.apps[0].recycleBin = nil
        #expect(EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: noBin).message[0].hasSuffix("It is deleted for good."))
    }

    @Test func severalAppsAreNamedAsTheConnectionsAre() {
        func text(_ apps: [DeleteApp]) -> String {
            EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: DeletePreview(managed: true, apps: apps)).text
        }
        let bins = [DeleteApp(connection: "Sonarr", kind: .sonarr, recycleBin: "/a"), DeleteApp(connection: "Sonarr 4K", kind: .sonarr, recycleBin: "/b")]
        #expect(text(bins) == "Sonarr and Sonarr 4K delete the file and stop monitoring the episode, so it isn’t downloaded again. It goes to Sonarr’s and Sonarr 4K’s recycling bins. Your watch history stays.")
        let mixed = [DeleteApp(connection: "Sonarr", kind: .sonarr, recycleBin: "/a"), DeleteApp(connection: "Sonarr 4K", kind: .sonarr)]
        #expect(text(mixed).contains("Sonarr moves it to its recycling bin. Sonarr 4K deletes it for good."))
        let none = [DeleteApp(connection: "Sonarr", kind: .sonarr), DeleteApp(connection: "Sonarr 4K", kind: .sonarr)]
        #expect(text(none).contains("so it isn’t downloaded again. It is deleted for good."))
    }

    @Test func anotherEpisodeInTheFileAndAnAppThatDidntAnswer() {
        let preview = DeletePreview(managed: true, apps: [
            DeleteApp(connection: "Sonarr", kind: .sonarr, recycleBin: "/a", otherEpisodes: [8]),
            DeleteApp(connection: "Sonarr 4K", kind: .sonarr, error: "timeout"),
        ])
        let dialog = EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: preview)
        #expect(dialog.message == [
            "Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again. It goes to Sonarr’s recycling bin.",
            "The file also holds episode 8, which goes too.",
            "Couldn’t reach Sonarr 4K: timeout. It can’t delete until it answers.",
            "Your watch history stays.",
        ])
        #expect(dialog.confirm == "Delete episode")

        var two = preview
        two.apps[0].otherEpisodes = [8, 9]
        #expect(EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: two).message[1] == "The file also holds episodes 8 and 9, which go too.")

        let nobody = DeletePreview(managed: true, apps: [DeleteApp(connection: "Sonarr", kind: .sonarr, error: "timeout")])
        let stuck = EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: nobody)
        #expect(stuck.confirm == nil, "nothing can delete until an app answers")
        #expect(stuck.dismiss == "OK")
    }

    @Test func cantDeleteReplaceAndAfterwards() {
        let cant = EpisodeDialog.cantDelete(episode(7), showTitle: "Harbor Lights")
        #expect(cant.title == "Delete “The Long Dark”?", "titled as the confirmation is")
        #expect(cant.text == "WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk.")
        #expect(cant.confirm == nil)
        #expect(cant.dismiss == "OK")
        let replace = EpisodeDialog.replace(episode(7), showTitle: "Harbor Lights", app: "Sonarr 4K")
        #expect(replace.text == "Sonarr 4K blocks this release and searches for another. The episode is gone until the new copy arrives.")
        #expect(ReplaceOutcome(connection: "Sonarr", blocklisted: true).message == "Sonarr blocked this release and is searching for another.")
        #expect(ReplaceOutcome(connection: "Sonarr", blocklisted: false, note: "It was imported by hand.").message == "Sonarr is searching for another copy. It was imported by hand.")
        #expect(DeleteOutcome().toast(title: "Lantern Rock") == "Deleted “Lantern Rock”")
        #expect(DeleteOutcome(failures: ["Sonarr 4K couldn’t delete: timeout"]).toast(title: "Lantern Rock") == "Deleted “Lantern Rock”. Sonarr 4K couldn’t delete: timeout")
    }
}

extension EpisodeLabelsTests {
    @Test func aRecordedEpisodeCanBeDeletedWithoutAnArrConnection() {
        let preview = DeletePreview(managed: true, files: 1, sizeBytes: 2_400_000_000, recording: true)
        let dialog = EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: preview)
        #expect(dialog.confirm == "Delete episode")
        #expect(dialog.message.contains { $0.contains("permanently deletes") })
        #expect(dialog.recordAgainChoice, "The ticked choice decides whether it records again")
        #expect(EpisodeDialog.dontRecordAgain == "Don’t record it again if it’s shown again")
        let sonarr = EpisodeDialog.delete(episode(7), showTitle: "Harbor Lights", preview: DeletePreview(managed: true, apps: [DeleteApp(connection: "Sonarr", kind: .sonarr)]))
        #expect(!sonarr.recordAgainChoice)
    }
}
