import Foundation
import WMMModel

// The Settings root as a hub:
// the
// web's groups in the web's order, each row with how things stand, one Next
// step when there is something to do, and a banner when the server can't be
// reached. Pure functions of what the app knows, so every variant is tested.

/// Where a row of the Settings root goes.
public enum SettingsDestination: String, Hashable, Sendable, CaseIterable {
    case playback, languages, notifications, homeRows, account, requests, downloads, server, inbox, about
}

/// One row of the root: the page's name and how it stands.
public struct SettingsHubRow: Hashable, Sendable, Identifiable {
    public var destination: SettingsDestination
    public var title: String
    /// The state at the row's right ("Automatic", "4 devices"); on Apple TV
    /// the line under its name. Nil until it is known.
    public var value: String?
    /// A value that wants attention, drawn gold: the Inbox's count.
    public var highlighted: Bool
    /// Apple TV's panel beside the list while the group is focused.
    public var about: String?

    public var id: SettingsDestination { destination }

    public init(destination: SettingsDestination, title: String, value: String?, highlighted: Bool = false, about: String? = nil) {
        self.destination = destination
        self.title = title
        self.value = value
        self.highlighted = highlighted
        self.about = about
    }
}

/// A heading and its rows on the iPhone and iPad root.
public struct SettingsHubGroup: Hashable, Sendable, Identifiable {
    public var title: String
    public var rows: [SettingsHubRow]
    public var id: String { title }
}

/// What the root needs from the services the app holds. ``SettingsModel/hubFacts(rows:requests:downloads:reminders:)``
/// fills in what the model knows.
public struct SettingsHubFacts: Hashable, Sendable {
    public var user: User?
    public var server: Server?
    /// "iPhone", "iPad" or "Apple TV".
    public var device: String
    /// "1.8.6": the version alone. The About page adds the build
    /// ("2.2.0 (20200099)"); beside a row's name it wrapped "About
    /// WatchMyMedia" over three lines in the iPad's list.
    public var appVersion: String
    public var quality: StreamQuality
    public var autoplayNext: Bool
    public var audioLanguages: [String]
    public var subtitleChoice: SubtitleChoice
    /// The playback settings have come from the server, so a Next step about them is the person's.
    public var preferencesLoaded: Bool
    /// The device's language, offered when no audio language is chosen.
    public var suggestedLanguage: String?
    /// The server offers following shows (``ServerFeatures/follows``), and how many the person follows once loaded.
    public var followsAvailable: Bool
    public var followCount: Int?
    /// The server arranges rows (``ServerFeatures/rows``); their counts once loaded.
    public var rowsAvailable: Bool
    public var homeRowsShown: Int?
    public var homeRowsHidden: Int?
    /// This account's signed-in devices, this one included, once loaded.
    public var signedInDevices: Int?
    public var requests: RequestOptions
    /// Your requests still waiting for approval, once loaded.
    public var requestsWaiting: Int?
    /// Downloads are offered here (``DownloadRepository/enabled``), with what is kept.
    public var downloadsEnabled: Bool
    public var downloadCount: Int
    public var downloadBytes: Int64
    /// Live TV reminders are offered (``LiveExtrasStore/available``), and how many are set.
    public var remindersAvailable: Bool
    public var reminderCount: Int
    /// The system won't let reminders through (the app asks it).
    public var notificationsOff: Bool
    /// Next steps the person said Not now to.
    public var dismissedSteps: Set<SettingsNextStep.Kind>

    public init(user: User?, server: Server?, device: String, appVersion: String,
                quality: StreamQuality = .auto, autoplayNext: Bool = true,
                audioLanguages: [String] = [], subtitleChoice: SubtitleChoice = .forced,
                preferencesLoaded: Bool = true, suggestedLanguage: String? = nil,
                followsAvailable: Bool = false, followCount: Int? = nil,
                rowsAvailable: Bool = false, homeRowsShown: Int? = nil, homeRowsHidden: Int? = nil,
                signedInDevices: Int? = nil, requests: RequestOptions = .none, requestsWaiting: Int? = nil,
                downloadsEnabled: Bool = false, downloadCount: Int = 0, downloadBytes: Int64 = 0,
                remindersAvailable: Bool = false, reminderCount: Int = 0, notificationsOff: Bool = false,
                dismissedSteps: Set<SettingsNextStep.Kind> = []) {
        self.user = user
        self.server = server
        self.device = device
        self.appVersion = appVersion
        self.quality = quality
        self.autoplayNext = autoplayNext
        self.audioLanguages = audioLanguages
        self.subtitleChoice = subtitleChoice
        self.preferencesLoaded = preferencesLoaded
        self.suggestedLanguage = suggestedLanguage
        self.followsAvailable = followsAvailable
        self.followCount = followCount
        self.rowsAvailable = rowsAvailable
        self.homeRowsShown = homeRowsShown
        self.homeRowsHidden = homeRowsHidden
        self.signedInDevices = signedInDevices
        self.requests = requests
        self.requestsWaiting = requestsWaiting
        self.downloadsEnabled = downloadsEnabled
        self.downloadCount = downloadCount
        self.downloadBytes = downloadBytes
        self.remindersAvailable = remindersAvailable
        self.reminderCount = reminderCount
        self.notificationsOff = notificationsOff
        self.dismissedSteps = dismissedSteps
    }

    var features: ServerFeatures { ServerFeatures(apiLevel: server?.apiLevel) }
    var playableQuality: StreamQuality { quality.playable(apiLevel: server?.apiLevel) }
}

// MARK: - Rows' values

extension SettingsHubFacts {
    /// Audio & subtitles' name: "Subtitles" on a server without audio languages.
    public var languagesTitle: String { features.audioLanguages ? SettingsWords.audioAndSubtitles : SettingsWords.subtitles }

    /// "English", "File default"; on a server without audio languages, how subtitles start.
    public func languagesValue(locale: Locale = .current) -> String {
        guard features.audioLanguages else { return subtitleChoice.shortLabel }
        return audioLanguages.first.map { languageTagName($0, locale: locale) } ?? "File default"
    }

    /// Apple TV's line: "English · For foreign audio".
    public func languagesLine(locale: Locale = .current) -> String {
        guard features.audioLanguages else { return subtitleChoice.shortLabel }
        return "\(languagesValue(locale: locale)) · \(subtitleChoice.shortLabel)"
    }

    /// "4 devices", "1 device".
    var devicesValue: String? { signedInDevices.map { plural($0, "device") } }

    /// "2 waiting"; nothing when none are.
    var requestsValue: String? { requestsWaiting.flatMap { $0 > 0 ? "\(formatCount($0)) waiting" : nil } }

    /// "7 · 66.1 GB", or "None".
    var downloadsValue: String { downloadCount == 0 ? "None" : "\(formatCount(downloadCount)) · \(freeSize(downloadBytes))" }

    var inboxValue: String? { requests.inbox > 0 ? "\(formatCount(requests.inbox)) waiting" : nil }
}

// MARK: - iPhone and iPad

/// The root's groups on iPhone and iPad, in the web's order: You
/// (Playback, Audio & subtitles, Notifications, Home rows, Account & devices,
/// Your requests), This iPhone (Downloads), Server (the server, and the Inbox for
/// an admin allowed here), About. A row the server or account doesn't offer
/// is left out; a group left empty goes too.
public func settingsHub(_ f: SettingsHubFacts, locale: Locale = .current) -> [SettingsHubGroup] {
    var you: [SettingsHubRow] = [
        SettingsHubRow(destination: .playback, title: SettingsWords.playback, value: f.playableQuality.shortLabel),
    ]
    if f.features.subtitlePreferences {
        you.append(SettingsHubRow(destination: .languages, title: f.languagesTitle, value: f.languagesValue(locale: locale)))
    }
    if f.followsAvailable {
        you.append(SettingsHubRow(destination: .notifications, title: NewEpisodeWords.title, value: f.followCount.map(NewEpisodeWords.rootValue)))
    }
    if f.rowsAvailable {
        you.append(SettingsHubRow(destination: .homeRows, title: SettingsWords.homeRows, value: f.homeRowsShown.map { "\(formatCount($0)) shown" }))
    }
    you.append(SettingsHubRow(destination: .account, title: SettingsWords.accountAndDevices, value: f.devicesValue))
    if f.requests.hasYourRequests {
        you.append(SettingsHubRow(destination: .requests, title: SettingsWords.yourRequests, value: f.requestsValue))
    }

    var groups = [SettingsHubGroup(title: SettingsWords.you, rows: you)]
    if f.downloadsEnabled {
        groups.append(SettingsHubGroup(title: SettingsWords.thisDevice(f.device), rows: [
            SettingsHubRow(destination: .downloads, title: SettingsWords.downloads, value: f.downloadsValue),
        ]))
    }
    var server = [SettingsHubRow(destination: .server, title: SettingsWords.server, value: f.server?.name)]
    if f.requests.inboxAllowed {
        server.append(SettingsHubRow(destination: .inbox, title: SettingsWords.inbox, value: f.inboxValue, highlighted: f.requests.inbox > 0))
    }
    groups.append(SettingsHubGroup(title: SettingsWords.server, rows: server))
    groups.append(SettingsHubGroup(title: SettingsWords.about, rows: [
        SettingsHubRow(destination: .about, title: SettingsWords.aboutApp, value: f.appVersion),
    ]))
    return groups
}

// MARK: - Apple TV

/// Apple TV's list of groups beside the page, in the web's order:
/// Playback, Audio & subtitles, Notifications, Home rows, Account & devices, Your requests,
/// Server, About; each `value` is the line under its name and `about` the
/// panel's while it is focused. The Inbox is on Server's page.
public func tvSettingsGroups(_ f: SettingsHubFacts, locale: Locale = .current) -> [SettingsHubRow] {
    let next = f.autoplayNext ? "next episode plays by itself" : "next episode waits"
    let reminders = f.remindersAvailable ? ", the next episode and Live TV reminders" : " and the next episode"
    var rows = [
        SettingsHubRow(destination: .playback, title: SettingsWords.playback, value: "\(f.playableQuality.shortLabel) · \(next)",
                       about: "How videos start and move on: the quality on this \(f.device), what to skip\(reminders)."),
    ]
    if f.features.subtitlePreferences {
        rows.append(SettingsHubRow(destination: .languages, title: f.languagesTitle, value: f.languagesLine(locale: locale),
                                   about: f.features.audioLanguages
                                       ? "The languages videos start in, and when subtitles start. Yours, on every device."
                                       : "When subtitles start, and in which languages. Yours, on every device."))
    }
    if f.followsAvailable {
        rows.append(SettingsHubRow(destination: .notifications, title: NewEpisodeWords.title, value: f.followCount.map(NewEpisodeWords.tvGroupLine),
                                   about: NewEpisodeWords.tvGroupPanel))
    }
    if f.rowsAvailable {
        let line = f.homeRowsShown.map { shown in "\(formatCount(shown)) shown · \(formatCount(f.homeRowsHidden ?? 0)) hidden" }
        rows.append(SettingsHubRow(destination: .homeRows, title: SettingsWords.homeRows, value: line,
                                   about: "Which rows Home shows, and in what order. Yours, on every device."))
    }
    rows.append(SettingsHubRow(destination: .account, title: SettingsWords.accountAndDevices,
                               value: f.signedInDevices.map { "\(plural($0, "device")) signed in" },
                               about: "This \(f.device)’s name, the devices signed in as you, and signing out."))
    if f.requests.hasYourRequests {
        rows.append(SettingsHubRow(destination: .requests, title: SettingsWords.yourRequests,
                                   value: f.requestsWaiting.map { $0 > 0 ? "\(formatCount($0)) waiting" : "None waiting" },
                                   about: "The movies and shows you asked for, and where each one stands."))
    }
    let admin = f.requests.inboxAllowed ? " The Inbox and Server settings are there too." : ""
    rows.append(SettingsHubRow(destination: .server, title: SettingsWords.server,
                               value: f.server.map { "\($0.name) · \($0.versionLabel)" },
                               highlighted: f.requests.inboxAllowed && f.requests.inbox > 0,
                               about: "The server this \(f.device) uses, and switching to another.\(admin)"))
    rows.append(SettingsHubRow(destination: .about, title: SettingsWords.about, value: "Version \(f.appVersion)",
                               about: "This app’s version, the privacy policy and Diagnostics."))
    return rows
}

/// Under Apple TV's list: "Signed in as Sam".
public func tvSignedInLine(_ user: User?) -> String { user.map { "Signed in as \($0.name)" } ?? "Not signed in" }

// MARK: - Next step

/// The one card under the account, when there is something to do:
/// first a language to watch in when none is chosen, then notifications that
/// are off while reminders are set. Not on Apple TV (not drawn).
public enum SettingsNextStep: Hashable, Sendable {
    /// No audio language yet; `suggested` is the device's, when it has one.
    case chooseLanguage(suggested: String?)
    /// Reminders are set and the system lets none through.
    case allowNotifications(reminders: Int)

    public enum Kind: Hashable, Sendable { case chooseLanguage, allowNotifications }

    public var kind: Kind {
        switch self {
        case .chooseLanguage: .chooseLanguage
        case .allowNotifications: .allowNotifications
        }
    }

    /// The card's words on this device.
    public func words(device: String, locale: Locale = .current) -> NextStepWords {
        switch self {
        case .chooseLanguage(let suggested?):
            NextStepWords(
                title: "Choose the language you watch in",
                body: "Videos play each file’s default audio until you choose. \(SettingsWords.deviceUses(suggested, device: device, locale: locale))",
                primary: SettingsWords.use(suggested, locale: locale), secondary: "Choose another"
            )
        case .chooseLanguage(nil):
            NextStepWords(title: "Choose the language you watch in", body: "Videos play each file’s default audio until you choose.",
                          primary: "Choose a language", secondary: nil)
        case .allowNotifications(let n):
            NextStepWords(
                title: "Let reminders reach you",
                body: n == 1
                    ? "You have a Live TV reminder, but notifications are off for WatchMyMedia, so this \(device) can’t tell you when it starts."
                    : "You have \(formatCount(n)) Live TV reminders, but notifications are off for WatchMyMedia, so this \(device) can’t tell you when they start.",
                primary: "Open \(SettingsWords.systemName(device: device)) Settings", secondary: "Not now"
            )
        }
    }
}

/// A Next step card's words: the primary button acts, the secondary is the other way, or Not now.
public struct NextStepWords: Hashable, Sendable {
    public var title: String
    public var body: String
    public var primary: String
    public var secondary: String?
}

/// Which Next step shows, if any: never more than one.
public func settingsNextStep(_ f: SettingsHubFacts) -> SettingsNextStep? {
    if f.preferencesLoaded, f.features.audioLanguages, f.audioLanguages.isEmpty, !f.dismissedSteps.contains(.chooseLanguage) {
        return .chooseLanguage(suggested: f.suggestedLanguage)
    }
    if f.remindersAvailable, f.reminderCount > 0, f.notificationsOff, !f.dismissedSteps.contains(.allowNotifications) {
        return .allowNotifications(reminders: f.reminderCount)
    }
    return nil
}

// MARK: - The server can't be reached

/// The root's banner while the server can't be reached: what shows is what
/// was last saved. Its button is ``SettingsWords/tryAgain`` (``SettingsModel/retry()``).
public func unreachableBanner(serverName: String?) -> SettingsNotice {
    SettingsNotice(title: "Can’t reach \(serverName ?? "the server")",
                   body: "These are the choices you last saved. Changing them needs a connection.")
}

extension SettingsModel {
    /// The server didn't answer its last check, or the playback settings couldn't reach it.
    public var serverUnreachable: Bool { reachable == false || preferencesUnreachable }

    /// ``unreachableBanner(serverName:)`` while ``serverUnreachable``.
    public var banner: SettingsNotice? { serverUnreachable ? unreachableBanner(serverName: server?.name) : nil }

    /// Try again on the banner, or on Playback when it couldn't load: asks
    /// the server who it is, then loads the playback settings again.
    public func retry() async {
        await refreshServer()
        await loadPreferences()
    }

    /// The root's facts as far as this model knows them; the rest come from
    /// the services the app holds.
    public func hubFacts(followsAvailable: Bool = false, followCount: Int? = nil,
                         rowsAvailable: Bool = false, homeRowsShown: Int? = nil, homeRowsHidden: Int? = nil,
                         requests: RequestOptions = .none, requestsWaiting: Int? = nil,
                         downloadsEnabled: Bool = false, downloadCount: Int = 0, downloadBytes: Int64 = 0,
                         remindersAvailable: Bool = false, reminderCount: Int = 0, notificationsOff: Bool = false,
                         dismissedSteps: Set<SettingsNextStep.Kind> = []) -> SettingsHubFacts {
        SettingsHubFacts(
            user: user, server: server, device: facts.family, appVersion: facts.appVersion,
            quality: quality, autoplayNext: autoplayNext, audioLanguages: audio.languages, subtitleChoice: subtitleChoice,
            preferencesLoaded: preferencesLoaded, suggestedLanguage: deviceLanguage,
            followsAvailable: followsAvailable, followCount: followCount,
            rowsAvailable: rowsAvailable, homeRowsShown: homeRowsShown, homeRowsHidden: homeRowsHidden,
            signedInDevices: accountDeviceCount, requests: requests, requestsWaiting: requestsWaiting,
            downloadsEnabled: downloadsEnabled, downloadCount: downloadCount, downloadBytes: downloadBytes,
            remindersAvailable: remindersAvailable, reminderCount: reminderCount, notificationsOff: notificationsOff,
            dismissedSteps: dismissedSteps
        )
    }

    /// This account's devices, this one included, even while Everyone's is on; nil until loaded.
    public var accountDeviceCount: Int? {
        sessions.value.map { list in everyone ? list.filter { $0.userName == user?.name }.count : list.count }
    }
}
