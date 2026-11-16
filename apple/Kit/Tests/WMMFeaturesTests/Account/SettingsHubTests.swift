import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The Settings root as a hub (PLAN-APPLE-SETTINGS.md §5.1, §5.9): the web's
/// groups and order with each row's value, for an admin, a member, an older
/// server and servers that don't offer a page; Apple TV's list of groups;
/// the Next step; the banner when the server can't be reached.
@MainActor
@Suite struct SettingsHubTests {
    private let en = Locale(identifier: "en")
    private let gb: Int64 = 1_073_741_824

    private func server(level: Int = ServerFeatures.Level.tokenWithDecision) -> Server {
        Server(id: "s", name: "Living room server", baseURL: URL(string: "https://home.example.net")!, version: "1.4.5", apiLevel: level)
    }

    private func admin(device: String = "iPhone") -> SettingsHubFacts {
        SettingsHubFacts(
            user: sam, server: server(), device: device, appVersion: "1.8.6",
            quality: .auto, autoplayNext: true, audioLanguages: ["en"], subtitleChoice: .foreign,
            rowsAvailable: true, homeRowsShown: 7, homeRowsHidden: 4, signedInDevices: 4,
            requests: RequestOptions(movies: true, shows: true, manage: true, inbox: 3, inboxAllowed: true), requestsWaiting: 2,
            downloadsEnabled: true, downloadCount: 7, downloadBytes: Int64(66.1 * Double(gb)),
            remindersAvailable: true, reminderCount: 3
        )
    }

    private func titles(_ groups: [SettingsHubGroup]) -> [String] { groups.map(\.title) }
    private func rows(_ groups: [SettingsHubGroup]) -> [String] { groups.flatMap(\.rows).map(\.title) }
    private func values(_ groups: [SettingsHubGroup]) -> [String?] { groups.flatMap(\.rows).map(\.value) }

    @Test func anAdminsRootIsTheWebsGroupsWithEachRowsValue() {
        let hub = settingsHub(admin(), locale: en)
        #expect(titles(hub) == ["You", "This iPhone", "Server", "About"])
        #expect(rows(hub) == ["Playback", "Audio & subtitles", "Home rows", "Account & devices", "Your requests",
                              "Downloads", "Server", "Inbox", "About WatchMyMedia"])
        #expect(values(hub) == ["Automatic", "English", "7 shown", "4 devices", "2 waiting",
                                "7 · 66.1 GB", "Living room server", "3 waiting", "1.8.6"])
        #expect(hub.flatMap(\.rows).filter(\.highlighted).map(\.destination) == [.inbox])
        #expect(hub.flatMap(\.rows).map(\.destination) == [.playback, .languages, .homeRows, .account, .requests,
                                                           .downloads, .server, .inbox, .about])
    }

    @Test func aMemberHasNoInboxAndAnIPadItsOwnGroup() {
        var facts = admin(device: "iPad")
        facts.user = viewer
        facts.requests = RequestOptions(movies: true, shows: true, inboxAllowed: false)
        let hub = settingsHub(facts, locale: en)
        #expect(titles(hub) == ["You", "This iPad", "Server", "About"])
        #expect(!rows(hub).contains("Inbox"))
        #expect(accountCardLine(user: viewer, serverName: "Living room server") == "Living room server")
        #expect(accountCardLine(user: sam, serverName: "Living room server") == "Admin · Living room server")
    }

    @Test func valuesBeforeTheyLoadAndWithNothingThere() {
        var facts = admin()
        facts.audioLanguages = []
        facts.homeRowsShown = nil
        facts.signedInDevices = nil
        facts.requestsWaiting = 0
        facts.downloadCount = 0
        facts.downloadBytes = 0
        facts.requests.inbox = 0
        facts.quality = .fullHD
        let hub = settingsHub(facts, locale: en)
        #expect(values(hub) == ["1080p", "File default", nil, nil, nil, "None", "Living room server", nil, "1.8.6"])
        #expect(hub.flatMap(\.rows).filter(\.highlighted).isEmpty)
    }

    @Test func aServerWithoutRequestsDownloadsOrRowsLeavesTheirRowsOut() {
        var facts = admin()
        facts.requests = .none
        facts.downloadsEnabled = false
        facts.rowsAvailable = false
        let hub = settingsHub(facts, locale: en)
        #expect(titles(hub) == ["You", "Server", "About"])
        #expect(rows(hub) == ["Playback", "Audio & subtitles", "Account & devices", "Server", "About WatchMyMedia"])
    }

    @Test func anOlderServerOffersWhatItCan() {
        var facts = admin()
        // Subtitle preferences without audio languages: the row is Subtitles, with how they start.
        facts.server = server(level: ServerFeatures.Level.subtitlePreferences)
        facts.quality = .auto
        var hub = settingsHub(facts, locale: en)
        #expect(hub[0].rows.map(\.title) == ["Playback", "Subtitles", "Home rows", "Account & devices", "Your requests"])
        #expect(hub[0].rows[1].value == "For foreign audio")
        // Below subtitle preferences and rows, and before Automatic: Original.
        facts.server = server(level: 2)
        facts.rowsAvailable = false
        facts.requests = .none
        hub = settingsHub(facts, locale: en)
        #expect(hub[0].rows.map(\.title) == ["Playback", "Account & devices"])
        #expect(hub[0].rows[0].value == "Original")
    }

    @Test func appleTVsGroupsHaveALineUnderEachAndAPanel() {
        let groups = tvSettingsGroups(admin(device: "Apple TV"), locale: en)
        #expect(groups.map(\.title) == ["Playback", "Audio & subtitles", "Home rows", "Account & devices", "Your requests", "Server", "About"])
        #expect(groups.map(\.value) == [
            "Automatic · next episode plays by itself", "English · For foreign audio", "7 shown · 4 hidden",
            "4 devices signed in", "2 waiting", "Living room server · v1.4.5", "Version 1.8.6",
        ])
        #expect(groups[0].about == "How videos start and move on: the quality on this Apple TV, what to skip, the next episode and Live TV reminders.")
        #expect(groups.allSatisfy { $0.about != nil })
        #expect(tvSignedInLine(sam) == "Signed in as Sam")

        var member = admin(device: "Apple TV")
        member.user = viewer
        member.autoplayNext = false
        member.requests = .none
        member.rowsAvailable = false
        member.remindersAvailable = false
        let less = tvSettingsGroups(member, locale: en)
        #expect(less.map(\.title) == ["Playback", "Audio & subtitles", "Account & devices", "Server", "About"])
        #expect(less[0].value == "Automatic · next episode waits")
        #expect(less[0].about == "How videos start and move on: the quality on this Apple TV, what to skip and the next episode.")
        #expect(less.last(where: { $0.destination == .server })?.about == "The server this Apple TV uses, and switching to another.")
    }

    @Test func fromLevel29NotificationsComesAfterAudioAndSubtitlesWithTheShowsFollowed() {
        var facts = admin()
        facts.server = server(level: ServerFeatures.Level.follows)
        facts.followsAvailable = true
        facts.followCount = 3
        let hub = settingsHub(facts, locale: en)
        #expect(hub[0].rows.map(\.title) == ["Playback", "Audio & subtitles", "Notifications", "Home rows", "Account & devices", "Your requests"])
        #expect(hub[0].rows[2].destination == .notifications)
        #expect(hub[0].rows[2].value == "3 shows")
        facts.followCount = 1
        #expect(settingsHub(facts, locale: en)[0].rows[2].value == "1 show")
        facts.followCount = 0
        #expect(settingsHub(facts, locale: en)[0].rows[2].value == "None")
        facts.followCount = nil
        #expect(settingsHub(facts, locale: en)[0].rows[2].value == nil, "not loaded yet")

        facts.device = "Apple TV"
        facts.followCount = 3
        let groups = tvSettingsGroups(facts, locale: en)
        #expect(groups.map(\.title).prefix(4) == ["Playback", "Audio & subtitles", "Notifications", "Home rows"])
        #expect(groups[2].value == "3 shows you follow")
        #expect(groups[2].about == "The shows you follow. Your phone or tablet notifies you. This TV shows new episodes on Home.")

        facts.followsAvailable = false
        #expect(!settingsHub(facts, locale: en)[0].rows.contains { $0.destination == .notifications }, "below 29 the row isn't there")
    }

    // MARK: Next step

    @Test func aLanguageToWatchInComesFirst() {
        var facts = admin()
        facts.audioLanguages = []
        facts.suggestedLanguage = "en"
        facts.notificationsOff = true
        let step = settingsNextStep(facts)
        #expect(step == .chooseLanguage(suggested: "en"))
        let words = step!.words(device: "iPhone", locale: en)
        #expect(words.title == "Choose the language you watch in")
        #expect(words.body == "Videos play each file’s default audio until you choose. This iPhone uses English.")
        #expect(words.primary == "Use English")
        #expect(words.secondary == "Choose another")
        #expect(SettingsNextStep.chooseLanguage(suggested: nil).words(device: "iPhone").primary == "Choose a language")
    }

    @Test func thenNotificationsOffWhileRemindersAreSet() {
        var facts = admin()
        facts.notificationsOff = true
        #expect(settingsNextStep(facts) == .allowNotifications(reminders: 3))
        let words = SettingsNextStep.allowNotifications(reminders: 3).words(device: "iPhone")
        #expect(words.title == "Let reminders reach you")
        #expect(words.body == "You have 3 Live TV reminders, but notifications are off for WatchMyMedia, so this iPhone can’t tell you when they start.")
        #expect(words.primary == "Open iOS Settings")
        #expect(words.secondary == "Not now")
        #expect(SettingsNextStep.allowNotifications(reminders: 1).words(device: "iPad").primary == "Open iPadOS Settings")
        #expect(SettingsNextStep.allowNotifications(reminders: 1).words(device: "iPad").body.contains("when it starts"))
    }

    @Test func noNextStepWhenNothingIsToDo() {
        var facts = admin()
        #expect(settingsNextStep(facts) == nil)
        // Before the settings load, an empty list is only the defaults.
        facts.audioLanguages = []
        facts.preferencesLoaded = false
        #expect(settingsNextStep(facts) == nil)
        // A server without audio languages has none to choose.
        facts.preferencesLoaded = true
        facts.server = server(level: ServerFeatures.Level.subtitlePreferences)
        #expect(settingsNextStep(facts) == nil)
        // Notifications off with no reminders, or reminders not offered.
        facts.notificationsOff = true
        facts.reminderCount = 0
        #expect(settingsNextStep(facts) == nil)
        facts.reminderCount = 2
        facts.remindersAvailable = false
        #expect(settingsNextStep(facts) == nil)
        // Not now.
        facts.remindersAvailable = true
        facts.dismissedSteps = [.allowNotifications]
        #expect(settingsNextStep(facts) == nil)
    }

    // MARK: The model's facts and the banner

    @Test func theModelFillsInWhatItKnows() async {
        let servers = TestServers()
        servers.current = SavedServer(server: server(), userName: "Sam", signedIn: true, lastUsedAt: .now)
        let auth = ScriptedAuth(user: sam)
        auth.sessionList = [
            DeviceSession(id: "a", kind: .device, name: "Sam’s iPhone", createdAt: .now, lastSeenAt: .now, current: true, userName: "Sam"),
            DeviceSession(id: "b", kind: .device, name: "Living room TV", createdAt: .now, lastSeenAt: .now, current: false, userName: "Sam"),
            DeviceSession(id: "c", kind: .device, name: "Robin’s iPad", createdAt: .now, lastSeenAt: .now, current: false, userName: "Robin"),
        ]
        let model = SettingsModel(servers: servers, auth: auth, preferences: FakePreferencesRepository(),
                                  facts: .init(model: "iPhone 16", system: "iOS 27.1", appVersion: "1.8.6", build: ""), deviceLanguage: "en")
        #expect(model.hubFacts().preferencesLoaded == false)
        await model.loadPreferences()
        await model.setEveryone(true)
        let facts = model.hubFacts(downloadsEnabled: true, downloadCount: 2, downloadBytes: gb)
        #expect(facts.signedInDevices == 2, "this account's, even with Everyone's on")
        #expect(facts.device == "iPhone")
        #expect(facts.appVersion == "1.8.6")
        #expect(facts.suggestedLanguage == "en")
        #expect(facts.preferencesLoaded)
        #expect(settingsNextStep(facts) == .chooseLanguage(suggested: "en"))
        #expect(settingsHub(facts, locale: en).first { $0.title == "This iPhone" }?.rows.first?.value == "2 · 1.0 GB")
    }

    @Test func theBannerShowsWhileTheServerCantBeReached() async {
        let servers = TestServers()
        servers.current = SavedServer(server: server(), userName: "Sam", signedIn: true, lastUsedAt: .now)
        let preferences = FailingPreferences()
        let model = SettingsModel(servers: servers, auth: ScriptedAuth(user: sam), preferences: preferences,
                                  facts: .init(model: "iPhone", system: "iOS", appVersion: "1", build: ""))
        #expect(model.banner == nil)
        servers.reachable = false
        #expect(model.banner == SettingsNotice(title: "Can’t reach Living room server", body: "These are the choices you last saved. Changing them needs a connection."))
        servers.reachable = true
        preferences.refreshFailure = APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")
        await model.loadPreferences()
        #expect(model.serverUnreachable)
        #expect(model.preferencesLoadFailed)
        preferences.refreshFailure = nil
        await model.retry()
        #expect(servers.refreshes == 1)
        #expect(model.banner == nil)
        #expect(model.preferencesLoaded)
        // A refusal with an answer isn't the connection.
        preferences.refreshFailure = APIError(status: 500, message: "The server had a problem (500). Try again.")
        await model.loadPreferences()
        #expect(model.preferencesLoadFailed && !model.serverUnreachable)
    }
}
