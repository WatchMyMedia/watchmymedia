import Foundation
import WMMModel

// The words of Settings on iPhone, iPad and Apple TV:
// the web's words where the two say the same thing
// (web/src/lib/youSettings.ts, web/src/pages/settings/), the boards'
// for what only the apps have. Views draw these and
// tests pin them, so a page never words a setting on its own.

/// A heading and what follows it: a banner, a failure above a sheet's button,
/// a kept-out page's reason.
public struct SettingsNotice: Hashable, Sendable {
    public var title: String
    public var body: String

    public init(title: String, body: String) {
        self.title = title
        self.body = body
    }
}

/// What a dialog that asks first says: its title, the lines under it, the
/// button that acts (destructive) and the one that keeps things (cancel).
public struct ConfirmWords: Hashable, Sendable {
    public var title: String
    /// One paragraph each.
    public var lines: [String]
    public var confirm: String
    public var keep: String

    public init(title: String, lines: [String], confirm: String, keep: String) {
        self.title = title
        self.lines = lines
        self.confirm = confirm
        self.keep = keep
    }

    /// The lines as one message, a blank line between paragraphs.
    public var message: String { lines.joined(separator: "\n\n") }
}

/// A page that isn't offered here, opened by a link:
/// why, and who can change that.
public struct KeptOutWords: Hashable, Sendable {
    public var title: String
    public var body: String
    /// Who can change it, and where.
    public var who: String

    public init(title: String, body: String, who: String) {
        self.title = title
        self.body = body
        self.who = who
    }
}

/// "English", "English and Urdu", "English, Urdu and Spanish" (the web's `andList`).
public func andList(_ items: [String]) -> String {
    guard items.count > 1, let last = items.last else { return items.first ?? "" }
    return "\(items.dropLast().joined(separator: ", ")) and \(last)"
}

/// The words of Settings, by page.
public enum SettingsWords {
    // MARK: Names (the web's groups and pages)

    public static let settings = "Settings"
    public static let you = "You"
    public static let playback = "Playback"
    public static let audioAndSubtitles = "Audio & subtitles"
    /// Audio & subtitles on a server without audio languages (API level 18 to 20).
    public static let subtitles = "Subtitles"
    public static let homeRows = "Home rows"
    public static let accountAndDevices = "Account & devices"
    public static let yourRequests = "Your requests"
    public static let downloads = "Downloads"
    public static let server = "Server"
    public static let inbox = "Inbox"
    public static let about = "About"
    /// Was "About this app".
    public static let aboutApp = "About WatchMyMedia"
    /// Was "Admin tools".
    public static let serverSettings = "Server settings"
    public static let openServerSettings = "Open Server settings"
    public static let serverSettingsDetail = "Libraries, people, Live TV, Plex and the rest"
    public static let nextStep = "Next step"
    public static let tryAgain = "Try again"
    public static let done = "Done"
    public static let cancel = "Cancel"

    /// "This iPhone", "This iPad", "This Apple TV": the device's own group.
    public static func thisDevice(_ device: String) -> String { "This \(device)" }

    // MARK: Playback

    /// "Yours, on every device you sign in to. Quality is this iPhone’s alone."
    public static func playbackLede(device: String) -> String {
        "Yours, on every device you sign in to. Quality is this \(device)’s alone."
    }

    /// "Quality · this iPhone": the one section kept on the device.
    public static func qualityHeading(device: String) -> String { "Quality · this \(device)" }
    public static let quality = "Quality"
    /// Apple TV's row.
    public static let defaultQuality = "Default quality"

    /// Under Quality, now that its heading says it is this device's.
    public static func qualityNote(auto: Bool) -> String {
        auto
            ? "Automatic starts with the original and lowers the quality when your connection can’t keep up. A fixed lower quality has the server convert the video, which saves data away from home."
            : "A lower quality has the server convert the video, which saves data away from home."
    }

    public static let skipping = "Skipping"
    public static let skippingNote = "A part you seek into is never skipped for you."

    public static let nextEpisode = "Next episode"
    /// Was "Autoplay next episode".
    public static let nextEpisodeTitle = "Play the next episode by itself"
    public static let nextEpisodeLine = "Counts down 10 seconds from the credits. Off, it waits for you."

    public static let liveTVReminders = "Live TV reminders"
    public static let remindMe = "Remind me"
    public static let reminders = "Reminders"
    public static let notifications = "Notifications"

    /// The Reminders row's value: "3 coming up", or "None" for none.
    public static func remindersValue(_ count: Int) -> String { count == 0 ? "None" : "\(formatCount(count)) coming up" }

    /// "iOS" or "iPadOS": whose Settings a notification switch is in.
    public static func systemName(device: String) -> String { device == "iPad" ? "iPadOS" : "iOS" }

    /// The Notifications row's line: "Allowed in iOS Settings", "Off in iPadOS Settings".
    public static func notificationsLine(allowed: Bool, device: String) -> String {
        "\(allowed ? "Allowed" : "Off") in \(systemName(device: device)) Settings"
    }

    /// Under Live TV reminders.
    public static func notificationsNote(device: String) -> String {
        "This \(device) tells you even when the app is closed. Changing notifications opens \(systemName(device: device)) Settings."
    }

    public static let playbackCantLoad = SettingsNotice(
        title: "Couldn’t load your playback choices",
        body: "The server didn’t answer. Nothing has changed, and your players keep using the choices you saved."
    )

    /// The Apple TV's panel for a Skipping row: what the part is, then what its choice does.
    public static func skipAbout(_ kind: SegmentKind, mode: SkipMode) -> [String] {
        let does = switch mode {
        case .none: "Do nothing plays the part through."
        case .ask: "Show a Skip button shows the button for parts 3 seconds or longer."
        case .autoDelay: "Skip after 3 seconds shows the Skip button, then skips by itself."
        case .auto: "Skip right away skips the part as it starts."
        }
        return [kind.settingsAbout, "\(does) \(skippingNote)", keptOnTheServer]
    }

    /// The Apple TV's panel: a setting every device follows.
    public static let keptOnTheServer = "Kept on the server, so every device you sign in to follows it."

    // MARK: Saved lines: what the change did

    /// "Saved. Credits now skip after 3 seconds, on every device."
    public static func skipSaved(_ kind: SegmentKind, mode: SkipMode) -> String {
        "Saved. \(kind.pluralLabel) now \(mode.does), on every device."
    }

    /// "Saved. The next episode now plays by itself, on every device."
    public static func nextEpisodeSaved(_ on: Bool) -> String {
        on ? "Saved. The next episode now plays by itself, on every device."
            : "Saved. The Next Episode card now waits for you, on every device."
    }

    /// Quality's Saved line, this device's alone: "Saved. This iPhone now plays 1080p, up to 8 Mbps."
    public static func qualitySaved(_ quality: StreamQuality, device: String) -> String {
        switch quality {
        case .auto: "Saved. This \(device) now plays the original first, and lower when the connection can’t keep up."
        case .original: "Saved. This \(device) now plays the original quality where the file allows it."
        default: "Saved. This \(device) now plays \(quality.shortLabel)\(quality.detail.map { ", up to \($0)" } ?? "")."
        }
    }

    /// The device's name's Saved line: "Saved. This iPhone now shows as Den
    /// iPhone in Signed-in devices and the server’s Activity."
    public static func deviceNameSaved(_ name: String, device: String) -> String {
        "Saved. This \(device) now shows as \(name) in Signed-in devices and the server’s Activity."
    }

    /// "Saved. Reminders now come 5 minutes before, on every device."
    public static func reminderLeadSaved(_ minutes: Int) -> String {
        "Saved. Reminders now come \(liveLeadChoice(minutes).lowercased()), on every device."
    }

    // MARK: Audio & subtitles

    /// How things stand, under the title: "Yours, on every device. Videos
    /// start in English, then Urdu. Subtitles start when the audio isn’t in
    /// your languages." (the web's `languageLede`).
    public static func languagesLede(audio: [String], subtitles: [String], choice: SubtitleChoice, locale: Locale = .current) -> String {
        if audio.isEmpty, subtitles.isEmpty, choice == .forced {
            return "Yours, on every device. Until you choose, videos play the file’s default audio and subtitles start only when the file marks them as forced."
        }
        let names = audio.map { languageTagName($0, locale: locale) }
        let start = names.isEmpty ? "Videos play the file’s default audio." : "Videos start in \(names.joined(separator: ", then ") )."
        return "Yours, on every device. \(start) \(subtitleState(choice))"
    }

    /// "Subtitles start when the audio isn’t in your languages."
    public static func subtitleState(_ choice: SubtitleChoice) -> String {
        switch choice {
        case .off: "Subtitles start only when you choose one."
        case .forced: "Subtitles start only when the file marks them as forced."
        case .foreign: "Subtitles start when the audio isn’t in your languages."
        case .on: "Subtitles always start, in your first language that has one."
        }
    }

    public static let audioLanguages = "Audio languages"
    public static let audioLanguagesNote = "Videos start in the first of these languages they have. If they have none, they play the file’s default."
    public static let noAudioLanguage = "No audio language yet. Videos play the file’s default audio."
    public static let audioLanguagesFootnote = "Commentary and audio description are never chosen for you. You can pick them in the player."
    public static let startingSubtitles = "Starting subtitles"
    public static let whenAVideoStarts = "When a video starts"
    public static let playerTrackWins = "A track you choose in the player always wins."
    public static let subtitleLanguages = "Subtitle languages"
    public static let subtitleLanguagesNote = "Use the first of these languages that has a subtitle."
    public static let onlineSubtitles = "Online subtitles"
    public static let automaticDownload = "Automatic download"
    public static let addLanguage = "Add language"

    /// "This iPhone uses English." beside Use English.
    public static func deviceUses(_ tag: String, device: String, locale: Locale = .current) -> String {
        "This \(device) uses \(languageTagName(tag, locale: locale))."
    }

    /// "Use English".
    public static func use(_ tag: String, locale: Locale = .current) -> String { "Use \(languageTagName(tag, locale: locale))" }

    /// "first", "second" … for a place in a list of up to 16.
    public static func ordinal(_ n: Int) -> String {
        let words = ["first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
                     "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth"]
        return words.indices.contains(n - 1) ? words[n - 1] : "number \(n)"
    }

    /// "audio language", "subtitle language".
    static func noun(_ list: LanguageList) -> String { list == .audio ? "audio language" : "subtitle language" }

    /// A language list's Saved line: "Saved. Urdu is your second audio
    /// language, on every device.", "Saved. Urdu is no longer one of your
    /// audio languages, on every device.", or for a list set at once (Use
    /// English) its first.
    public static func listSaved(_ list: LanguageList, before: [String], after: [String], locale: Locale = .current) -> String {
        let name = { languageTagName($0, locale: locale) }
        func same(_ a: String, _ b: String) -> Bool { a.caseInsensitiveCompare(b) == .orderedSame }
        if let added = after.first(where: { tag in !before.contains { same($0, tag) } }),
           let place = after.firstIndex(where: { same($0, added) }) {
            return "Saved. \(name(added)) is your \(ordinal(place + 1)) \(noun(list)), on every device."
        }
        if let removed = before.first(where: { tag in !after.contains { same($0, tag) } }) {
            return after.isEmpty
                ? "Saved. You have no \(noun(list)) now, on every device."
                : "Saved. \(name(removed)) is no longer one of your \(noun(list))s, on every device."
        }
        // A move: the language whose place went up, or the first that changed place.
        let moved = after.enumerated().first { i, tag in before.firstIndex(where: { same($0, tag) }).map { $0 > i } ?? false }
            ?? after.enumerated().first { i, tag in before.indices.contains(i) && !same(before[i], tag) }
        guard let (place, tag) = moved else { return "Saved, on every device." }
        return "Saved. \(name(tag)) is now your \(ordinal(place + 1)) \(noun(list)), on every device."
    }

    /// "Saved. Subtitles now start when the audio isn’t in your languages, on every device."
    public static func subtitleChoiceSaved(_ choice: SubtitleChoice) -> String {
        switch choice {
        case .off: "Saved. Subtitles now start only when you choose one, on every device."
        case .forced: "Saved. Subtitles now start only when the file marks them as forced, on every device."
        case .foreign: "Saved. Subtitles now start when the audio isn’t in your languages, on every device."
        case .on: "Saved. Subtitles now always start, in your first language that has one, on every device."
        }
    }

    /// Automatic download's Saved line.
    public static func automaticDownloadSaved(_ on: Bool) -> String {
        on ? "Saved. When no subtitle in a file matches, one made for that video is looked for online, on every device."
            : "Saved. Subtitles now come only from the file, or from what you pick, on every device."
    }

    /// A language list as the failed line names it: "English and Urdu", or nil when empty.
    static func listValue(_ tags: [String], locale: Locale = .current) -> String? {
        tags.isEmpty ? nil : andList(tags.map { languageTagName($0, locale: locale) })
    }

    // MARK: Account & devices

    /// "Sam, an admin on Living room server. 4 devices are signed in.", or
    /// "Only this iPhone is signed in." for one; the count is left out until
    /// the list has loaded.
    public static func accountLede(user: User?, serverName: String?, devices: Int?, device: String) -> String {
        var who = user.map { $0.isAdmin ? "\($0.name), an admin" : $0.name } ?? "Not signed in"
        if let serverName { who += " on \(serverName)" }
        guard let devices else { return "\(who)." }
        let count = devices <= 1 ? "Only this \(device) is signed in." : "\(formatCount(devices)) devices are signed in."
        return "\(who). \(count)"
    }

    /// Under This iPhone's name: "iPhone 16 · iOS 27.1 · WatchMyMedia 1.8.6.
    /// The name shows in Signed-in devices and in the server’s Activity."
    public static func thisDeviceLine(model: String, system: String, appVersion: String) -> String {
        "\(model) · \(system) · WatchMyMedia \(appVersion). The name shows in Signed-in devices and in the server’s Activity."
    }

    public static let name = "Name"
    public static let password = "Password"
    public static let changePassword = "Change password"
    public static let changePasswordLine = "Changing it signs out every device but this one."
    public static let signedInDevices = "Signed-in devices"
    public static let everyonesDevices = "Everyone’s devices"
    public static let everyonesDevicesLine = "Admins can see and sign out every account"
    public static let linkATV = "Link a TV"
    public static let linkATVLine = "Sign a TV in as you with the code it shows."
    public static let signOut = "Sign out"

    /// "Sign out of this iPhone", the red row at the bottom.
    public static func signOutOfThis(_ device: String) -> String { "Sign out of this \(device)" }

    /// Under it: "Deletes this iPhone’s 7 downloads. Your account, watch history and settings stay on the server."
    public static func signOutOfThisLine(device: String, downloads: Int) -> String {
        let stays = "Your account, watch history and settings stay on the server."
        guard downloads > 0 else { return stays }
        return "Deletes this \(device)’s \(plural(downloads, "download")). \(stays)"
    }

    /// "Sign out Kitchen iPad", a row's button as VoiceOver names it.
    public static func signOutNamed(_ name: String) -> String { "Sign out \(name)" }

    /// Signing another device out: the
    /// device by name, and the person when it is someone else's.
    public static func signOutDevice(_ session: DeviceSession, me: String?) -> ConfirmWords {
        let theirs = me != nil && session.userName != me
        let line = theirs
            ? "\(session.userName) will need their password or a code to sign in on it again. Anything it is playing stops."
            : "It will need a password or a code to sign in again. Anything it is playing stops."
        return ConfirmWords(title: "Sign out \(session.name)?", lines: [line], confirm: "Sign out \(session.name)", keep: "Keep it signed in")
    }

    /// Signing this iPhone or iPad out: what
    /// stays, what is deleted with its count and size, and what signing in
    /// again needs.
    public static func signOutThisDevice(serverName: String?, device: String, downloads: Int, bytes: Int64) -> ConfirmWords {
        let again = "To sign in again you’ll need your password, or a code from a signed-in device."
        let title = signOutQuestion(serverName: serverName)
        guard downloads > 0 else {
            return ConfirmWords(
                title: title,
                lines: ["Your account, watch history and settings stay on the server. Nothing is deleted from this \(device).", again],
                confirm: signOutOfThis(device), keep: "Stay signed in"
            )
        }
        return ConfirmWords(
            title: title,
            lines: [
                "Stays on the server: your account, watch history and settings.",
                "Deleted from this \(device): \(plural(downloads, "download")), \(freeSize(bytes)).",
                again,
            ],
            confirm: "Sign out and delete \(plural(downloads, "download"))", keep: "Stay signed in"
        )
    }

    /// Signing this Apple TV out, which keeps no downloads.
    public static func signOutThisAppleTV(serverName: String?) -> ConfirmWords {
        ConfirmWords(
            title: signOutQuestion(serverName: serverName),
            lines: [
                "Your account, watch history and settings stay on the server.",
                "This Apple TV will need a new code, or your password, to sign in again.",
            ],
            confirm: signOutOfThis("Apple TV"), keep: "Stay signed in"
        )
    }

    /// The note under Signed-in devices once a sign-out is done: "Kitchen
    /// iPad was signed out. It needs a password or a code to sign in again."
    public static func deviceSignedOut(_ name: String) -> String {
        "\(name) was signed out. It needs a password or a code to sign in again."
    }

    /// The server had already forgotten it (404): the list was loaded again.
    public static func deviceAlreadyGone(_ name: String) -> String {
        "\(name) was already signed out. The list is up to date."
    }

    /// "Couldn’t sign Kitchen iPad out, so it’s still signed in. Check your connection and try again."
    public static func deviceSignOutFailed(_ name: String, status: Int?) -> String {
        switch status {
        case 429: "Couldn’t sign \(name) out, so it’s still signed in. Wait a moment, then try again."
        case 403: "Couldn’t sign \(name) out, so it’s still signed in. Only an admin can sign out someone else’s device."
        default: "Couldn’t sign \(name) out, so it’s still signed in. Check your connection and try again."
        }
    }

    /// The Apple TV's panel for another device: "iPhone 16, last used yesterday." and what signing it out does.
    public static func deviceAbout(_ session: DeviceSession, now: Date = .now) -> [String] {
        let what = session.model.flatMap { $0.isEmpty ? nil : $0 } ?? session.kindLabel
        return [
            "\(what), last used \(formatAgo(session.lastSeenAt, now: now)).",
            "Signing it out stops anything it is playing. It will need a password or a code to sign in again.",
        ]
    }

    // MARK: Rename

    /// "Rename this iPhone".
    public static func renameTitle(device: String) -> String { "Rename this \(device)" }
    /// "Name this Apple TV", Apple TV's page of room names.
    public static func nameTitle(device: String) -> String { "Name this \(device)" }
    public static let rename = "Rename"
    public static let deviceNameLabel = "Device name"
    public static let renameHelp = "Shown in Signed-in devices and in the server’s Activity, so you can tell your devices apart."

    /// Why Rename is off: "Rename is off until there is a name. Cancel keeps Sam’s iPhone."
    public static func renameOff(keeping current: String) -> String {
        "Rename is off until there is a name. Cancel keeps \(current)."
    }

    public static let typeAnotherName = "Type another name…"
    public static let typeAnotherNameLine = "Opens the keyboard. An iPhone nearby can type for you, or hold the microphone to say it."

    /// The rooms Apple TV offers as names, before Type another name.
    public static let rooms = ["Living room", "Bedroom", "Kitchen", "Office", "Den", "Basement", "Kids’ room", "Guest room"]

    /// Apple TV's names to choose from: this one's
    /// name first, then each room's that isn't it.
    public static func roomNames(device: String, current: String) -> [String] {
        let names = rooms.map { "\($0) \(device)" }
        return [current] + names.filter { $0.caseInsensitiveCompare(current) != .orderedSame }
    }

    /// The page's footer: "Select a name to save it · Menu goes back to Account & devices and keeps Living room Apple TV".
    public static func roomNamesHint(keeping current: String) -> String {
        "Select a name to save it · Menu goes back to Account & devices and keeps \(current)"
    }

    // MARK: Server

    /// "Living room server, v1.4.5. You’re signed in as Sam, an admin."
    public static func serverLede(_ server: Server, user: User?) -> String {
        let who = user.map { $0.isAdmin ? " You’re signed in as \($0.name), an admin." : " You’re signed in as \($0.name)." } ?? ""
        return "\(server.name), \(server.versionLabel).\(who)"
    }

    public static let thisServer = "This server"
    public static let anotherServer = "Another server"
    public static let address = "Address"
    public static let version = "Version"
    public static let signedInAs = "Signed in as"
    public static let switchServerLine = "Three steps: choose it, sign in, done. You stay signed in here, and switching back needs no password."
    /// Apple TV's row line, with the rest in its panel.
    public static let switchServerShortLine = "Three steps: choose it, sign in, done"
    public static let switchServerAbout = [
        "Choose another server on this network, or enter its address, then sign in there.",
        "You stay signed in here, and switching back needs no password.",
    ]
    public static let inboxLine = "Requests to approve and problems people reported"

    // MARK: Apple TV

    /// The hints at the foot of the panel beside the focused row.
    public static let tvHintChange = "Select changes it · Menu goes back to the list"
    public static let tvHintAsks = "Select asks first · Menu goes back to the list"
    public static let tvHintStarts = "Select starts · Menu goes back to the list"

    /// "Right opens Playback · Menu goes to the top bar", for a group in the list.
    public static func tvHintGroup(_ title: String) -> String { "Right opens \(title) · Menu goes to the top bar" }

    // MARK: Elsewhere

    /// Remove all downloads asks first, with the count on its button and what stays.
    public static func removeAllDownloads(count: Int, bytes: Int64, device: String) -> ConfirmWords {
        let all = count == 1 ? "1 download" : "all \(formatCount(count)) downloads"
        return ConfirmWords(
            title: "Remove \(all)?",
            lines: ["Frees \(freeSize(bytes)) on this \(device). Your watched marks and resume points stay, and you can download \(count == 1 ? "it" : "them") again while \(count == 1 ? "it is" : "they are") in the library."],
            confirm: "Remove \(plural(count, "download"))", keep: "Keep downloads"
        )
    }

    /// Cancel request asks first (the web's `cancelLine`): "Cancel your request for Coastline?"
    public static func cancelRequest(title: String, viaSeerr: Bool) -> ConfirmWords {
        let then = viaSeerr
            ? "It’s deleted in Seerr too, and you can ask again later."
            : "Admins stop seeing it, and you can ask again later."
        return ConfirmWords(
            title: "Cancel your request for \(title)?",
            lines: ["It hasn’t been approved yet, so nothing has been downloaded. \(then)"],
            confirm: "Cancel request", keep: "Keep request"
        )
    }

    // MARK: Kept out

    public static let requestsOff = KeptOutWords(
        title: "Requests are off on this server",
        body: "Nothing is connected to take requests, so there is nothing to ask for or to show here.",
        who: "An admin can set them up in the web app, under Server settings › Integrations."
    )

    public static let downloadsOff = KeptOutWords(
        title: "Downloading is off for your account",
        body: "This server lets only some people download to their devices, and your account isn’t one of them. Streaming works as usual.",
        who: "An admin chooses who can download in the web app, under Server settings › Users."
    )

    /// "Living room server is on v0.9.3. Choosing audio and subtitle
    /// languages needs v1.1.0 or newer. Until then, pick tracks in the player."
    public static func serverTooOld(serverName: String, version: String, doing: String, needs: String, meanwhile: String?) -> KeptOutWords {
        let body = "\(serverName) is on \(versionLabel(version)). \(doing) needs \(versionLabel(needs)) or newer."
        return KeptOutWords(
            title: "This server needs an update first",
            body: meanwhile.map { "\(body) \($0)" } ?? body,
            who: "An admin can update the server. The steps are at watchmy.media/guide."
        )
    }
}
