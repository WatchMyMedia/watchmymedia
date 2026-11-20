import Foundation
import Testing
import WMMModel

/// New episodes' words, as the boards' wording table has them.
@Suite struct NewEpisodeWordingTests {
    private func episode(_ season: Int, _ number: Int, _ title: String? = nil) -> ArrivedEpisode {
        ArrivedEpisode(id: season * 100 + number, season: season, number: number, title: title, arrivedAt: .now)
    }

    @Test func theNotificationSaysOneEpisodeByItsCodeAndTitle() {
        #expect(NewEpisodeWords.noticeTitle(show: "The Sleuth Files", count: 1) == "New episode · The Sleuth Files")
        #expect(NewEpisodeWords.noticeBody([episode(3, 7, "The Long Night")], count: 1) == "S3 E7 · The Long Night")
        #expect(NewEpisodeWords.noticeBody([episode(3, 7)], count: 1) == "S3 E7", "no title: the code alone")
        #expect(NewEpisodeWords.noticeBody([episode(0, 3, "Holiday Special")], count: 1) == "S0 E3 · Holiday Special", "specials are season 0")
    }

    @Test func severalRunFromTheEarliestToTheLatestNamingTheSeasonOnlyWhenItChanges() {
        #expect(NewEpisodeWords.noticeTitle(show: "The Sleuth Files", count: 3) == "3 new episodes · The Sleuth Files")
        #expect(NewEpisodeWords.noticeBody([episode(3, 7), episode(3, 8), episode(3, 9)], count: 3) == "S3 E7 to E9")
        #expect(NewEpisodeWords.noticeBody([episode(2, 10), episode(3, 1), episode(3, 2)], count: 3) == "S2 E10 to S3 E2")
        // A gap in a run: the count says how many.
        #expect(NewEpisodeWords.noticeTitle(show: "The Sleuth Files", count: 2) == "2 new episodes · The Sleuth Files")
        #expect(NewEpisodeWords.noticeBody([episode(3, 7), episode(3, 9)], count: 2) == "S3 E7 to E9")
        #expect(NewEpisodeWords.noticeBody([], count: 0) == "")
    }

    @Test func moreThanTheServerListsReadsFromTheEarliest() {
        let five = (1...5).map { episode(3, $0) }
        #expect(NewEpisodeWords.noticeTitle(show: "The Sleuth Files", count: 10) == "10 new episodes · The Sleuth Files")
        #expect(NewEpisodeWords.noticeBody(five, count: 10) == "From S3 E1")
        #expect(NewEpisodeWords.noticeBody(five, count: 5) == "S3 E1 to E5")
    }

    @Test func pastFiveShowsOneMoreSaysHowManyMore() {
        #expect(NewEpisodeWords.moreShows(4) == "4 more shows have new episodes")
        #expect(NewEpisodeWords.moreShows(1) == "1 more show has new episodes")
    }

    @Test func theBellAndItsLabels() {
        #expect(NewEpisodeWords.bell(following: false) == "Notify me")
        #expect(NewEpisodeWords.bell(following: true) == "Notifications on")
        #expect(NewEpisodeWords.bellLabel(following: false, show: "The Sleuth Files") == "Notify me of new episodes of The Sleuth Files")
        #expect(NewEpisodeWords.bellLabel(following: true, show: "The Sleuth Files") == "Notifications on for The Sleuth Files. Turn them off")
        #expect(NewEpisodeWords.followFailed("The Sleuth Files") == "Couldn’t turn on notifications for The Sleuth Files. Check your connection and try again.")
        #expect(NewEpisodeWords.unfollowFailed("The Sleuth Files") == "Couldn’t turn off notifications for The Sleuth Files. Check your connection and try again.")
        #expect(NewEpisodeWords.atLimit == "You follow 500 shows, the most you can. Choose Stop notifying for one in Settings › Notifications first.")
        #expect(NewEpisodeWords.tvLine == "Your phone or tablet notifies you. This TV shows new episodes on Home.")
    }

    @Test func theExplainerAndTheLimitsNameTheDeviceAndItsSystem() {
        #expect(NewEpisodeWords.askBody(show: "The Sleuth Files", device: "iPhone")
            == "When a new episode of The Sleuth Files reaches the library, this iPhone shows a notification.")
        #expect(NewEpisodeWords.limits(device: "iPhone")
            == "iOS decides when to check, so it can take a few hours. If you close WatchMyMedia from the app switcher, checks stop until you open it again. Your iPhone has to be able to reach your server.")
        #expect(NewEpisodeWords.limits(device: "iPad")
            == "iPadOS decides when to check, so it can take a few hours. If you close WatchMyMedia from the app switcher, checks stop until you open it again. Your iPad has to be able to reach your server.")
        #expect(NewEpisodeWords.askNotNowKeeps(show: "The Sleuth Files")
            == "Not now still follows The Sleuth Files. Its new episodes show on Home, and your other devices still notify you.")
    }

    @Test func settingsWordsTheSwitchAndEachShow() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let day: TimeInterval = 86_400
        #expect(NewEpisodeWords.rootValue(3) == "3 shows")
        #expect(NewEpisodeWords.rootValue(1) == "1 show")
        #expect(NewEpisodeWords.rootValue(0) == "None")
        #expect(NewEpisodeWords.onThisDevice("iPad") == "On this iPad")
        #expect(NewEpisodeWords.switchSaved(true, device: "iPhone") == "Saved. This iPhone now shows a notification when a show you follow gets a new episode.")
        #expect(NewEpisodeWords.switchSaved(false, device: "iPhone") == "Saved. This iPhone no longer notifies you of new episodes. They still show on Home.")

        let latest = FollowedShow.Latest(season: 3, number: 9, title: "Paper Trail", arrivedAt: now - 2 * day)
        #expect(NewEpisodeWords.followLine(FollowedShow(tmdbID: 1, title: "The Sleuth Files", followedAt: now - 9 * day, showID: 7, latest: latest), now: now)
            == "S3 E9 · 2 days ago")
        let yesterday = FollowedShow.Latest(season: 3, number: 2, arrivedAt: now - 1.5 * day)
        #expect(NewEpisodeWords.followLine(FollowedShow(tmdbID: 2, title: "Harbor Lights", followedAt: now - 9 * day, showID: 8, latest: yesterday), now: now)
            == "S3 E2 · yesterday")
        let hours = FollowedShow.Latest(season: 1, number: 4, arrivedAt: now - 5 * 3600)
        #expect(NewEpisodeWords.followLine(FollowedShow(tmdbID: 3, title: "Northfield", followedAt: now - day, showID: 9, latest: hours), now: now) == "S1 E4 · 5 h ago")
        #expect(NewEpisodeWords.followLine(FollowedShow(tmdbID: 4, title: "Orbital Decay", followedAt: now, showID: 10), now: now) == "No new episodes yet")
        #expect(NewEpisodeWords.followLine(FollowedShow(tmdbID: 6, title: "Copperline", followedAt: now - day, showID: 11, latest: latest), now: now)
            == "No new episodes yet", "its latest arrived before it was followed")
        #expect(NewEpisodeWords.followLine(FollowedShow(tmdbID: 5, title: "Westward Signals", followedAt: now, latest: latest), now: now) == "Not in the library now")
        #expect(NewEpisodeWords.stopNotifyingLabel("Westward Signals") == "Stop notifying me of new episodes of Westward Signals")
        #expect(NewEpisodeWords.removeFailed("The Sleuth Files") == "Couldn’t stop notifying, so you still follow The Sleuth Files. Check your connection and try again.")
    }

    @Test func appleTVsGroupAndHints() {
        #expect(NewEpisodeWords.tvGroupLine(3) == "3 shows you follow")
        #expect(NewEpisodeWords.tvGroupLine(0) == "None")
        #expect(NewEpisodeWords.tvShowHint(inLibrary: true) == "Select offers Go to show or Stop notifying · Menu goes back to the list")
        #expect(NewEpisodeWords.tvShowHint(inLibrary: false) == "Select offers Stop notifying · Menu goes back to the list")
    }
}
