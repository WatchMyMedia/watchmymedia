import Foundation
import Observation
import WMMModel

/// Settings: a short root (the account, Watching, Devices, About, Sign out)
/// and a page per row (Settings*.dc.html). One model serves the root and its
/// pages, so a change on a page shows on the root at once
/// (android/.../SettingsViewModel.kt).
@MainActor
@Observable
public final class SettingsModel {
    /// What the app knows about this device and itself, for This iPhone and About.
    public struct DeviceFacts: Hashable, Sendable {
        /// "iPhone 16".
        public var model: String
        /// "iOS 18.4".
        public var system: String
        /// "1.0".
        public var appVersion: String
        /// "12".
        public var build: String

        public init(model: String, system: String, appVersion: String, build: String) {
            self.model = model
            self.system = system
            self.appVersion = appVersion
            self.build = build
        }

        /// The app's version and build from its Info.plist (`info`), as the
        /// iPhone and Apple TV apps read them for About. A build equal to the
        /// version adds nothing, as in the version the server is sent.
        public init(model: String, system: String, info: [String: Any]) {
            let version = info["CFBundleShortVersionString"] as? String ?? ""
            let build = info["CFBundleVersion"] as? String ?? ""
            self.init(
                model: model, system: system,
                appVersion: version.isEmpty ? build : version,
                build: version.isEmpty || build == version ? "" : build
            )
        }

        /// "1.0 (12)".
        public var appVersionLabel: String { build.isEmpty ? appVersion : "\(appVersion) (\(build))" }

        /// "iPhone" or "iPad".
        public var family: String { deviceFamily(model: model) }
    }

    private let servers: any ServerRepository
    private let auth: any AuthRepository
    private let preferences: any PreferencesRepository
    public let facts: DeviceFacts
    /// The language the device is set to, offered for an empty list ("Use English").
    public let deviceLanguage: String?

    public init(servers: any ServerRepository, auth: any AuthRepository, preferences: any PreferencesRepository, facts: DeviceFacts,
                deviceLanguage: String? = suggestedLanguage(Locale.preferredLanguages)) {
        self.servers = servers
        self.auth = auth
        self.preferences = preferences
        self.facts = facts
        self.deviceLanguage = deviceLanguage
    }

    // MARK: Account

    public var user: User? { auth.user }
    public var server: Server? { servers.current?.server }
    public var deviceName: String { auth.deviceName }

    /// "Admin · Living room server".
    public var accountLine: String { accountCardLine(user: user, serverName: server?.name) }

    /// "Sam’s iPhone · signed in as Sam on Living room server".
    public var footer: String { settingsFooter(deviceName: deviceName, user: user, serverName: server?.name) }

    // MARK: Playback and Skip

    /// Changes shown before the server confirms them, and taken back if it refuses.
    private var pendingAutoplay: Bool?
    private var pendingSkip: [SegmentKind: SkipMode] = [:]

    /// Why the playback settings could not be loaded or saved.
    public private(set) var playbackError: String?
    /// Why a skip setting could not be saved.
    public private(set) var skipError: String?

    /// This device's quality as the server in use plays it: a saved Automatic is Original on a server that cannot pick the step.
    public var quality: StreamQuality { preferences.quality.playable(apiLevel: server?.apiLevel) }

    /// The qualities to offer, Automatic first where the server can do it.
    public var qualities: [StreamQuality] { StreamQuality.offered(apiLevel: server?.apiLevel) }

    /// Under the quality checklist.
    public var qualityNote: String { qualityFootnote(device: facts.family, auto: qualities.contains(.auto)) }
    public var autoplayNext: Bool { pendingAutoplay ?? preferences.playback.autoplayNext }

    public func skipMode(_ kind: SegmentKind) -> SkipMode {
        pendingSkip[kind] ?? preferences.playback.skipMode(kind)
    }

    /// The Playback row's value: "Original · autoplay on".
    public var playbackValue: String { playbackSummary(quality: quality, autoplayNext: autoplayNext) }

    /// The Skip row's value: the intro's mode.
    public var skipValue: String { skipMode(.intro).label }

    /// Loads the playback settings from the server.
    public func loadPreferences() async {
        do {
            try await preferences.refresh()
        } catch is CancellationError {
        } catch {
            playbackError = message(error, fallback: "Couldn't load your playback settings.")
        }
    }

    /// Kept on this device, so it cannot fail.
    public func setQuality(_ quality: StreamQuality) {
        preferences.setQuality(quality)
    }

    /// Shows the change at once; puts it back with the reason if the server refuses it.
    public func setAutoplayNext(_ enabled: Bool) async {
        guard enabled != autoplayNext else { return }
        pendingAutoplay = enabled
        playbackError = nil
        do {
            try await preferences.setAutoplayNext(enabled)
        } catch {
            playbackError = message(error, fallback: "Couldn't save autoplay.")
        }
        if pendingAutoplay == enabled { pendingAutoplay = nil }
    }

    /// Shows the change at once; puts it back with the reason if the server refuses it.
    public func setSkip(_ kind: SegmentKind, mode: SkipMode) async {
        guard mode != skipMode(kind) else { return }
        pendingSkip[kind] = mode
        skipError = nil
        do {
            try await preferences.setSkip(kind, mode: mode)
        } catch {
            skipError = message(error, fallback: "Couldn't save the skip setting.")
        }
        if pendingSkip[kind] == mode { pendingSkip[kind] = nil }
    }

    // MARK: Audio and subtitles

    /// The page's lists and choices shown before the server confirms them,
    /// and taken back if it refuses (PLAN-LANGUAGES.md §5.6).
    private var pendingLanguages: (audio: AudioPreferences, subtitles: SubtitlePreferences)?
    private var languageRevision = 0
    public private(set) var languageError: String?
    /// The server chose subtitles for foreign audio when the person saved
    /// their first audio language: the page says so until the next change.
    public private(set) var foreignAudioNotice = false

    public var supportsSubtitlePreferences: Bool { (server?.apiLevel ?? 0) >= subtitlePreferencesAPILevel }
    /// Audio languages and the foreign-audio choice, from API level 21.
    public var supportsAudioLanguages: Bool { (server?.apiLevel ?? 0) >= audioPreferencesAPILevel }

    public var audio: AudioPreferences { pendingLanguages?.audio ?? preferences.playback.audio }
    public var subtitles: SubtitlePreferences { pendingLanguages?.subtitles ?? preferences.playback.subtitles }
    public var subtitleChoice: SubtitleChoice { subtitles.choice }

    /// The four ways to start subtitles; three on a server before level 21.
    public var subtitleChoices: [SubtitleChoice] { SubtitleChoice.offered(apiLevel: server?.apiLevel) }

    /// "Audio & subtitles", or "Subtitles" on a server without audio languages.
    public var languagesTitle: String { supportsAudioLanguages ? "Audio & subtitles" : "Subtitles" }

    /// The Settings row's value: "Spanish · For foreign audio", "File default · Forced only".
    public var languagesValue: String {
        guard supportsAudioLanguages else { return subtitleChoice.shortLabel }
        return "\(audio.languages.first.map { languageTagName($0) } ?? "File default") · \(subtitleChoice.shortLabel)"
    }

    public func languages(_ list: LanguageList) -> [String] {
        switch list {
        case .audio: audio.languages
        case .subtitles: subtitles.languages
        }
    }

    /// The Add language menu: the common languages not in the list yet.
    public func addable(to list: LanguageList) -> [String] {
        let have = languages(list)
        return commonLanguages.filter { tag in !have.contains { $0.caseInsensitiveCompare(tag) == .orderedSame } }
    }

    /// A list holds at most 16, as the server allows.
    public func canAdd(to list: LanguageList) -> Bool { languages(list).count < maxLanguages }

    /// The device's language, offered while `list` is empty; nothing is saved until it is chosen.
    public func suggestion(for list: LanguageList) -> String? {
        languages(list).isEmpty ? deviceLanguage : nil
    }

    /// Automatic download needs a choice that can start subtitles, and a language to look for.
    public var automaticDownloadAvailable: Bool { subtitleChoice.startsSubtitles && !subtitles.languages.isEmpty }

    /// Under an empty subtitle list: the foreign-audio choice falls back on the audio languages.
    public var subtitlesUseAudioLanguages: Bool { subtitleChoice == .foreign && subtitles.languages.isEmpty }

    /// Saves one change, sending only what it changes.
    public func save(_ change: LanguageChange) async {
        guard supportsSubtitlePreferences else {
            languageError = "Update the server to use subtitle preferences."
            return
        }
        guard supportsAudioLanguages || !change.needsAudioLanguages else {
            languageError = "Update the server to use audio languages."
            return
        }
        var after = PlaybackPreferences(subtitles: subtitles, audio: audio)
        change.apply(to: &after)
        guard after.audio != audio || after.subtitles != subtitles else { return }
        let before = subtitleChoice
        languageRevision += 1
        let mine = languageRevision
        pendingLanguages = (after.audio, after.subtitles)
        languageError = nil
        foreignAudioNotice = false
        do {
            try await preferences.setLanguages(change)
            if mine == languageRevision {
                foreignAudioNotice = change.choice == nil && before != .foreign && preferences.playback.subtitles.choice == .foreign
            }
        } catch {
            if mine == languageRevision { languageError = message(error, fallback: "Couldn’t save your languages.") }
        }
        if mine == languageRevision { pendingLanguages = nil }
    }

    public func setSubtitleChoice(_ choice: SubtitleChoice) async {
        await save(LanguageChange(choice: choice))
    }

    public func setAutomaticSubtitles(_ enabled: Bool) async {
        await save(LanguageChange(automaticDownload: enabled))
    }

    /// Adds `tag` at the end; a repeat or an empty code is ignored. The server canonicalises BCP 47 tags.
    public func addLanguage(_ tag: String, to list: LanguageList) async {
        let tag = tag.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "_", with: "-")
        let current = languages(list)
        guard !tag.isEmpty, current.count < maxLanguages,
              !current.contains(where: { $0.caseInsensitiveCompare(tag) == .orderedSame }) else { return }
        await save(list.change(current + [tag]))
    }

    public func removeLanguage(_ tag: String, from list: LanguageList) async {
        await save(list.change(languages(list).filter { $0 != tag }))
    }

    /// Move up (-1) or Move down (+1), as the language's actions offer.
    public func moveLanguage(_ tag: String, in list: LanguageList, by step: Int) async {
        var value = languages(list)
        guard let i = value.firstIndex(of: tag), value.indices.contains(i + step) else { return }
        value.swapAt(i, i + step)
        await save(list.change(value))
    }

    /// A drag in the list (Edit), as `onMove` gives it.
    public func moveLanguages(in list: LanguageList, from offsets: IndexSet, to destination: Int) async {
        var value = languages(list)
        let moving = offsets.sorted().compactMap { value.indices.contains($0) ? value[$0] : nil }
        guard !moving.isEmpty else { return }
        value = value.enumerated().filter { !offsets.contains($0.offset) }.map(\.element)
        let target = min(max(destination - offsets.filter { $0 < destination }.count, 0), value.count)
        value.insert(contentsOf: moving, at: target)
        await save(list.change(value))
    }

    // MARK: This iPhone

    /// Renames this device; an empty name keeps the old one.
    public func rename(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != deviceName else { return }
        await auth.setDeviceName(trimmed)
    }

    // MARK: Signed-in devices

    public private(set) var sessions: Loadable<[DeviceSession]> = .loading
    /// An admin's "Everyone’s devices": every account's sessions.
    public private(set) var everyone = false
    /// The session being signed out.
    public private(set) var revoking: String?
    /// Why the last sign-out or the Everyone's switch failed.
    public private(set) var devicesError: String?

    /// Admins can see and sign out every account's devices.
    public var canSeeEveryone: Bool { user?.isAdmin == true }

    /// "4 signed in": this account's devices, even while Everyone's is on; nil until loaded.
    public var devicesValue: String? {
        sessions.value.map { list in signedInCount(everyone ? list.filter { $0.userName == user?.name }.count : list.count) }
    }

    /// The sessions under each account's name: the signed-in user's alone
    /// unless Everyone's is on.
    public var sessionGroups: [(user: String, sessions: [DeviceSession])] {
        guard let list = sessions.value else { return [] }
        if everyone { return WMMFeatures.sessionGroups(list, me: user?.name) }
        return list.isEmpty ? [] : [(user?.name ?? "Signed in", sortSessions(list))]
    }

    public func loadSessions() async {
        if sessions.value == nil { sessions = .loading }
        do {
            sessions = .loaded(sortSessions(try await auth.sessions(all: everyone && canSeeEveryone)))
        } catch is CancellationError {
        } catch {
            if sessions.value == nil { sessions = .failed(error.displayMessage) } else { devicesError = error.displayMessage }
        }
    }

    /// Switches Everyone's and reloads; switches back with the reason if the server refuses.
    public func setEveryone(_ on: Bool) async {
        guard on != everyone, canSeeEveryone || !on else { return }
        everyone = on
        devicesError = nil
        do {
            sessions = .loaded(sortSessions(try await auth.sessions(all: on)))
        } catch is CancellationError {
        } catch {
            everyone = !on
            devicesError = message(error, fallback: "Couldn't load everyone's devices.")
        }
    }

    /// Signs another device out.
    public func revoke(_ session: DeviceSession) async {
        guard revoking == nil, !session.current else { return }
        revoking = session.id
        devicesError = nil
        defer { revoking = nil }
        do {
            try await auth.revoke(sessionID: session.id)
            if case .loaded(let list) = sessions { sessions = .loaded(list.filter { $0.id != session.id }) }
        } catch is CancellationError {
        } catch {
            devicesError = message(error, fallback: "Couldn't sign \(session.name) out. Try again.")
        }
    }

    // MARK: Sign out

    public private(set) var signingOut = false

    /// Signs this device out; ``AuthRepository/user`` turns nil and the root shows sign-in.
    public func signOut() async {
        guard !signingOut else { return }
        signingOut = true
        defer { signingOut = false }
        await auth.signOut()
    }
}

/// One of the Audio & subtitles page's two ordered lists.
public enum LanguageList: String, CaseIterable, Hashable, Sendable {
    case audio, subtitles

    /// The change that saves `languages` as this list.
    func change(_ languages: [String]) -> LanguageChange {
        switch self {
        case .audio: LanguageChange(audioLanguages: languages)
        case .subtitles: LanguageChange(subtitleLanguages: languages)
        }
    }
}

/// The Add language menu's languages, as on the boards.
let commonLanguages = ["en", "es", "fr", "de", "it", "pt-BR", "ja", "ko", "zh-Hans", "ar", "hi", "nl", "sv", "pl", "tr"]

/// The server keeps at most 16 languages in a list.
let maxLanguages = 16
