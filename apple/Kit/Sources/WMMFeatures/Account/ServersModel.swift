import Foundation
import Observation
import WMMModel

/// The most servers one device stays signed in to, as the Samsung TV app keeps.
public let maximumRegisteredServers = 12

extension ServerRepository {
    /// The servers this device is registered with: the ones it has signed in
    /// to and not removed, signed in still or not. The one in use comes
    /// first, then the rest by last use. A server that was chosen and never
    /// signed in to is not one of them.
    public var registered: [SavedServer] {
        let current = current?.server.id
        return saved.filter { $0.signedIn || $0.userName != nil || $0.server.id == current }.sorted { a, b in
            if (a.server.id == current) != (b.server.id == current) { return a.server.id == current }
            if a.lastUsedAt != b.lastUsedAt { return a.lastUsedAt > b.lastUsedAt }
            return a.server.name < b.server.name
        }
    }

    /// Whether `serverID` is registered and this device could use it now:
    /// signed in there, and not too old for this app.
    public func usable(_ serverID: String) -> Bool {
        registered.contains { $0.server.id == serverID && $0.signedIn && !$0.server.tooOld }
    }

    /// How many servers this device is signed in to.
    public var signedInCount: Int { registered.filter(\.signedIn).count }
}

/// Where the app goes once a switch has rebuilt its screens on the new
/// server: Home, or Search with the same words and a title over it (a
/// result from another server), or a link.
public struct ServerArrival: Hashable, Sendable {
    public var serverID: String
    public var tab: AppTab
    /// Search's words, asked again on the new server.
    public var search: String?
    /// The page over `tab`'s root.
    public var route: Route?
    /// A link opened once there.
    public var link: DeepLink?

    public init(serverID: String, tab: AppTab = .home, search: String? = nil, route: Route? = nil, link: DeepLink? = nil) {
        self.serverID = serverID
        self.tab = tab
        self.search = search
        self.route = route
        self.link = link
    }
}

extension Navigator {
    /// The tabs were rebuilt for `serverID`: goes where the switch that led
    /// there was headed (``arrival``), once. Returns the link to open there,
    /// which needs the new server's library. An arrival for another server
    /// (the person went elsewhere meanwhile) is dropped.
    @discardableResult
    public func land(on serverID: String?) -> DeepLink? {
        guard let arrival else { return nil }
        self.arrival = nil
        guard arrival.serverID == serverID else { return nil }
        tab = arrival.tab
        searchWords = arrival.search
        if let route = arrival.route { setPath([route], for: arrival.tab) }
        return arrival.link
    }

    /// The words Search asks when it next shows, once.
    public func takeSearchWords() -> String? {
        defer { searchWords = nil }
        return searchWords
    }
}

/// Servers, the switcher the account button opens: the servers this device
/// is registered with and their states, choosing one, and the failure with
/// Try again and Watch its downloads. One for the app, shared by Servers,
/// Settings, Search, Downloads and links, since each of them switches.
///
/// A switch asks the server which it is (GET /api/server at its kept
/// address, ``askTimeout`` at most) and makes it the one in use when it
/// answers as itself; the apps rebuild their screens on the change. One that
/// doesn't answer leaves the app where it was. Nothing here signs in: a
/// server whose sign-in ended is handed to the Add a server flow
/// (``signInRequest``).
@MainActor
@Observable
public final class ServersModel {
    public struct Row: Identifiable, Hashable, Sendable {
        public enum State: Hashable, Sendable {
            /// The one in use: it carries Current and isn't chosen.
            case current
            /// Signed in: choosing it switches.
            case ready
            /// Being asked which server it is.
            case switching
            /// Its sign-in ended: choosing it opens its sign-in.
            case signedOut
            /// Older than this app works with: not chosen.
            case tooOld
        }

        /// The server's ID.
        public var id: String
        public var name: String
        /// "Sam · home.example.net".
        public var detail: String
        public var state: State

        /// What a row says of its state under its name; nil for one that only waits to be chosen.
        public var stateLine: String? {
            switch state {
            case .current, .ready: nil
            case .switching: ServersWords.switching
            case .signedOut: ServersWords.signedOut
            case .tooOld: ServersWords.tooOld
            }
        }

        /// Choosing it does something.
        public var chosen: Bool { state == .ready || state == .signedOut }
    }

    /// A server that didn't answer, with where the app still is.
    public struct Failure: Hashable, Sendable {
        public var serverID: String
        public var title: String
        public var body: String
        /// How many downloads this device holds from it; with any, Watch its downloads is offered.
        public var downloads: Int

        public var watchDownloadsTitle: String? { downloads > 0 ? ServersWords.watchDownloads(downloads) : nil }
    }

    /// Servers is open: the sheet, the popover, the panel.
    public var presented = false
    /// The server being asked which it is.
    public private(set) var switching: String?
    public private(set) var failure: Failure?
    /// "Now on Cabin server", after a switch; the screens clear it after ``noteDuration``.
    public var note: String?
    /// A server whose sign-in ended was chosen: the app opens its sign-in
    /// (the Add a server flow, started on it) and clears this.
    public var signInRequest: SavedServer?
    /// A server is being removed, or the one in use signed out of, and the
    /// app is on its way to the next: the root shows neither sign-in nor the
    /// server picker meanwhile.
    public private(set) var moving = false
    /// The server being removed.
    public private(set) var removing: String?

    public static let noteDuration: Duration = .seconds(4)

    @ObservationIgnored private let servers: any ServerRepository
    @ObservationIgnored private let downloads: any DownloadRepository
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let askTimeout: Duration
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    /// What the last failed switch was headed for, for Try again and Watch its downloads.
    @ObservationIgnored private var failedArrival: ServerArrival?

    /// - Parameters:
    ///   - askTimeout: how long a server has to say which it is.
    ///   - sleep: how the wait is timed; tests move it by hand.
    public init(servers: any ServerRepository, downloads: any DownloadRepository = UnavailableDownloads(), navigator: Navigator,
                askTimeout: Duration = .seconds(5), sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.servers = servers
        self.downloads = downloads
        self.navigator = navigator
        self.askTimeout = askTimeout
        self.sleep = sleep
    }

    // MARK: What Servers shows

    public var rows: [Row] {
        let current = servers.current?.server.id
        return servers.registered.map { saved in
            let state: Row.State = if saved.server.id == current {
                .current
            } else if saved.server.tooOld {
                .tooOld
            } else if !saved.signedIn {
                .signedOut
            } else if switching == saved.server.id {
                .switching
            } else {
                .ready
            }
            return Row(id: saved.server.id, name: saved.server.name, detail: saved.switcherLine, state: state)
        }
    }

    /// Two or more servers are registered: Home names the one in use, and its name opens Servers.
    public var several: Bool { servers.registered.count > 1 }

    /// The name of the server in use, for Home's title and the account button; nil with one server.
    public var currentName: String? { several ? servers.current?.server.name : nil }

    /// Another server may be added: fewer than ``maximumRegisteredServers`` are registered.
    public var canAdd: Bool { servers.registered.count < maximumRegisteredServers }

    /// Under Add a server: where it looks, or that one must be removed first.
    public func addLine(device: String) -> String {
        canAdd ? ServersWords.addLine : ServersWords.addLimit(device: device)
    }

    /// Whether a switch is under way, so the other rows wait.
    public var busy: Bool { switching != nil || removing != nil }

    // MARK: Settings › Server

    /// A row under Your servers: the server, who is signed in there, its
    /// address and version, and Current or its state.
    public struct SettingsRow: Identifiable, Hashable, Sendable {
        /// The server's ID.
        public var id: String
        public var name: String
        /// "Sam · home.example.net · v1.4.5"; with a state, that follows: "… · Signed out here".
        public var detail: String
        public var current: Bool
    }

    public var settingsRows: [SettingsRow] {
        let current = servers.current?.server.id
        return servers.registered.map { saved in
            let state: String? = saved.server.id == current ? nil : saved.server.tooOld ? ServersWords.tooOld : saved.signedIn ? nil : ServersWords.signedOutHere
            return SettingsRow(id: saved.server.id, name: saved.server.name,
                               detail: [saved.settingsLine, state].compactMap { $0 }.joined(separator: " · "), current: saved.server.id == current)
        }
    }

    /// A server's page in Settings: its facts, what can be done with it,
    /// and the question Remove this server asks.
    public struct Page: Hashable, Sendable {
        public struct Fact: Hashable, Sendable, Identifiable {
            public var label: String
            public var value: String
            public var id: String { label }
        }

        public var serverID: String
        public var name: String
        /// Address, Version, Signed in as, and on a device that downloads, Downloads from it.
        public var facts: [Fact]
        /// Switch to this server is offered: signed in there, new enough, and not the one in use.
        public var canSwitch: Bool
        /// Sign in again is offered: its sign-in ended.
        public var canSignIn: Bool
        public var removeQuestion: ConfirmWords
    }

    /// The page of `serverID`, or nil once it is off the list. `device` is
    /// "iPhone", "iPad" or "Apple TV"; `showsDownloads` is for the devices that download.
    public func page(_ serverID: String, device: String, showsDownloads: Bool) -> Page? {
        guard let saved = servers.registered.first(where: { $0.server.id == serverID }) else { return nil }
        let current = saved.server.id == servers.current?.server.id
        let held = heldDownloads(from: serverID)
        var facts = [
            Page.Fact(label: SettingsWords.address, value: saved.server.displayAddress),
            Page.Fact(label: SettingsWords.version, value: saved.server.versionAndAPI),
            Page.Fact(label: SettingsWords.signedInAs, value: saved.signedIn ? saved.userName ?? "" : ServersWords.signedOutHere),
        ]
        if showsDownloads {
            facts.append(Page.Fact(label: ServersWords.downloadsFromIt, value: ServersWords.downloadsValue(count: held.count, bytes: held.bytes)))
        }
        return Page(
            serverID: serverID, name: saved.server.name, facts: facts,
            canSwitch: !current && saved.signedIn && !saved.server.tooOld,
            canSignIn: !current && !saved.signedIn && !saved.server.tooOld,
            removeQuestion: ServersWords.removeQuestion(server: saved.server.name, device: device, downloads: showsDownloads ? held.count : 0)
        )
    }

    /// What this device holds from `serverID`: the list's own for the server in use, the count kept for any other.
    private func heldDownloads(from serverID: String) -> (count: Int, bytes: Int64) {
        if serverID == servers.current?.server.id {
            let all = downloads.downloads
            return (all.count, all.filter(\.downloaded).reduce(0) { $0 + $1.sizeBytes })
        }
        let other = downloads.elsewhere.first { $0.serverID == serverID }
        return (other?.count ?? 0, other?.bytes ?? 0)
    }

    /// Sign in again, on a server's page: hands the server to the app's sign-in, as its row in Servers does.
    public func signInAgain(_ serverID: String) {
        guard let saved = servers.registered.first(where: { $0.server.id == serverID && !$0.signedIn }) else { return }
        presented = false
        signInRequest = saved
    }

    // MARK: Choosing

    /// A row was chosen: switches to a server signed in to, or asks the app
    /// to open the sign-in of one whose sign-in ended.
    public func choose(_ row: Row) async {
        guard let saved = servers.registered.first(where: { $0.server.id == row.id }) else { return }
        switch row.state {
        case .ready: await switchTo(saved.server.id)
        case .signedOut:
            failure = nil
            presented = false
            signInRequest = saved
        case .current, .switching, .tooOld: break
        }
    }

    /// Switches to a registered server that is signed in. `asking` false
    /// skips asking it which server it is: it answered a moment ago (a
    /// search result), or it is known not to answer (Watch its downloads).
    /// True once it is the one in use; a server that doesn't answer leaves
    /// the app where it was and opens Servers on the failure.
    /// - Parameter quietly: a failure is said as the note and Servers stays
    ///   shut: for a link, which nobody chose from a screen that could answer it.
    @discardableResult
    public func switchTo(_ serverID: String, arriving: ServerArrival? = nil, asking: Bool = true, quietly: Bool = false) async -> Bool {
        guard switching == nil, removing == nil else { return false }
        guard let saved = servers.registered.first(where: { $0.server.id == serverID }) else { return false }
        guard saved.server.id != servers.current?.server.id else {
            // Already there: nothing to switch, and no note.
            presented = false
            return true
        }
        guard saved.signedIn, !saved.server.tooOld else { return false }
        failure = nil
        switching = serverID
        defer { switching = nil }
        var server = saved.server
        if asking {
            do {
                server = try await ask(saved.server)
            } catch is CancellationError {
                return false
            } catch {
                failed(saved, error: error, arriving: arriving, quietly: quietly)
                return false
            }
        }
        // The list changed while it was asked: removed, or signed out there.
        guard servers.usable(serverID) else { return false }
        let from = servers.current?.server
        do {
            navigator.arrival = arriving ?? ServerArrival(serverID: serverID)
            try await servers.select(server)
        } catch {
            navigator.arrival = nil
            failed(saved, error: error, arriving: arriving, quietly: quietly)
            return false
        }
        failedArrival = nil
        presented = false
        if from?.id != serverID { note = ServersWords.nowOn(server.name) }
        return true
    }

    /// Try again, on the failure: asks the same server again.
    public func tryAgain() async {
        guard let failure else { return }
        await switchTo(failure.serverID, arriving: failedArrival)
    }

    /// Watch its downloads, on the failure: switches to a server that can't
    /// be reached, where the app opens as it does offline.
    public func watchDownloads() async {
        guard let failure, failure.downloads > 0 else { return }
        await switchTo(failure.serverID, arriving: nil, asking: false)
    }

    public func dismissFailure() {
        failure = nil
        failedArrival = nil
    }

    /// GET /api/server at the server's kept address, within ``askTimeout``:
    /// the same server as remembered, or it didn't answer.
    private func ask(_ server: Server) async throws -> Server {
        let probe = Task { @MainActor [servers] in try await servers.probe(server.baseURL.absoluteString) }
        let timer = Task { @MainActor [sleep, askTimeout] in
            guard (try? await sleep(askTimeout)) != nil else { return false }
            probe.cancel()
            return true
        }
        defer { timer.cancel() }
        let answered: Server
        do {
            answered = try await withTaskCancellationHandler { try await probe.value } onCancel: { probe.cancel() }
        } catch is CancellationError {
            // Cancelled by its time running out, it didn't answer; cancelled from outside, the switch is off.
            if Task.isCancelled { throw CancellationError() }
            throw NoAnswer()
        }
        // Another server at its address is treated as no answer at all.
        guard answered.id == server.id else { throw NoAnswer() }
        return answered
    }

    private struct NoAnswer: Error {}

    private func failed(_ saved: SavedServer, error: any Error, arriving: ServerArrival?, quietly: Bool) {
        let stillOn = servers.current.map { ServersWords.stillOn($0.server.name) }
        let held = downloads.elsewhere.first { $0.serverID == saved.server.id }?.count ?? 0
        let connect = ConnectFailure(key: saved.server.id, label: saved.server.name, error: error)
        let words: SettingsNotice
        if error is NoAnswer || connect.kind == .unreachable {
            words = SettingsNotice(title: ServersWords.couldNotReach(saved.server.name),
                                   body: [ServersWords.mayBeOff, stillOn].compactMap { $0 }.joined(separator: " "))
        } else if case .tooOld = connect.kind {
            words = ServersWords.tooOldToSwitch(saved.server.name, stillOn: stillOn)
        } else {
            words = connect.notice(after: stillOn)
        }
        guard !quietly else {
            note = [words.title + ".", stillOn].compactMap { $0 }.joined(separator: " ")
            return
        }
        failure = Failure(serverID: saved.server.id, title: words.title, body: words.body, downloads: held)
        failedArrival = arriving
        // A failure from Settings or Downloads is said where it can be answered, as one from Servers is.
        presented = true
    }

    // MARK: Links

    /// Opens a `watchmymedia://` link from outside the app or one it
    /// published (``DeepLink/opening(_:signedInServer:others:)``). One
    /// published for another registered server that is signed in switches to
    /// that server first, asking it which it is, and opens once the screens
    /// are rebuilt there (``Navigator/land(on:)``); when it doesn't answer
    /// the app stays where it was and the note says so. `accepts` leaves out
    /// links a device doesn't take (Link a TV on Apple TV).
    public func open(_ url: URL, library: any LibraryRepository, liveTV: (any LiveTVRepository)? = nil, accepts: (DeepLink) -> Bool = { _ in true }) async {
        let current = servers.current?.server.id
        let others = servers.registered.filter { $0.server.id != current && $0.signedIn && !$0.server.tooOld }.map(\.server.id)
        guard let opening = DeepLink.opening(url, signedInServer: current, others: others), accepts(opening.link) else { return }
        guard let server = opening.server else {
            await navigator.open(opening.link, library: library, liveTV: liveTV)
            return
        }
        await switchTo(server, arriving: ServerArrival(serverID: server, link: opening.link), quietly: true)
    }

    // MARK: Removing, and signing out

    /// Remove this server, after the question: signs this device out of it,
    /// deletes its downloads here and takes it off the list
    /// (``ServerRepository/forget(serverID:)``). When it was the one in use
    /// the app moves to the server used most recently that is still signed
    /// in, and says so; with none left the root shows the server picker.
    public func remove(_ serverID: String) async {
        guard removing == nil, switching == nil else { return }
        let wasCurrent = servers.current?.server.id == serverID
        let next = wasCurrent ? self.next(excluding: serverID) : nil
        removing = serverID
        moving = next != nil
        defer {
            removing = nil
            moving = false
        }
        if failure?.serverID == serverID { dismissFailure() }
        await servers.forget(serverID: serverID)
        await move(to: next)
    }

    /// Signs the server in use out with `signOut`, then moves to the server
    /// used most recently that is still signed in, and says so. With none
    /// left the root shows sign-in, as it did.
    public func signingOut(_ signOut: () async -> Void) async {
        let next = self.next(excluding: servers.current?.server.id)
        moving = next != nil
        defer { moving = false }
        await signOut()
        await move(to: next)
    }

    private func next(excluding serverID: String?) -> SavedServer? {
        servers.registered.first { $0.server.id != serverID && $0.signedIn && !$0.server.tooOld }
    }

    private func move(to next: SavedServer?) async {
        guard let next, servers.usable(next.server.id) else { return }
        navigator.arrival = ServerArrival(serverID: next.server.id)
        do {
            try await servers.select(next.server)
            note = ServersWords.nowOn(next.server.name)
        } catch {
            navigator.arrival = nil
        }
    }
}

extension SavedServer {
    /// "Sam · home.example.net", a row in Servers.
    public var switcherLine: String {
        [userName, server.displayAddress].compactMap { $0 }.joined(separator: " · ")
    }

    /// "Sam · home.example.net · v1.4.5", a row under Your servers.
    public var settingsLine: String {
        [userName, server.displayAddress, server.versionLabel].compactMap { $0 }.joined(separator: " · ")
    }
}

/// Servers' words, the same on every app.
public enum ServersWords {
    public static let title = "Servers"
    public static let current = "Current"
    public static let switching = "Switching…"
    public static let signedOut = "Signed out here · sign in again"
    public static let tooOld = "Too old for this app"
    public static let add = "Add a server"
    public static let addLine = "On this network, or by its address"
    public static let manage = "Manage servers"
    public static let settings = "Settings"
    public static let tryAgain = "Try again"
    public static let mayBeOff = "It may be off, or only reachable on its own network."

    /// Under Add a server with ``maximumRegisteredServers`` registered.
    public static func addLimit(device: String) -> String {
        "This \(device) keeps up to \(maximumRegisteredServers) servers. Remove one in Settings › Server, then add this one."
    }

    /// A switch to a server that answers on a version this app can't use.
    public static func tooOldToSwitch(_ name: String, stillOn: String?) -> SettingsNotice {
        SettingsNotice(title: "\(name) is too old for this app", body: ["Update it, then switch.", stillOn].compactMap { $0 }.joined(separator: " "))
    }

    public static func couldNotReach(_ name: String) -> String { "Couldn’t reach \(name)" }

    /// "You’re still on Living room server."
    public static func stillOn(_ name: String) -> String { "You’re still on \(name)." }

    /// "Watch its 3 downloads".
    public static func watchDownloads(_ count: Int) -> String { "Watch its \(plural(count, "download"))" }

    /// The note after a switch.
    public static func nowOn(_ name: String) -> String { "Now on \(name)" }

    /// Under Servers' title while this device is casting.
    public static func castingLine(_ device: String) -> String { "Switching stops casting to \(device)." }

    /// The account button, spoken: "Servers. Sam, on Cabin server", or "Servers. Sam" with one server.
    public static func accountLabel(user: String?, server: String?) -> String {
        guard let user else { return title }
        return server.map { "\(title). \(user), on \($0)" } ?? "\(title). \(user)"
    }

    /// The server's name under Home's title, spoken.
    public static func nameLabel(_ server: String) -> String { "\(server). Opens Servers" }

    // MARK: Apple TV's panel

    /// The line at the foot of the panel: what Select does on the focused row, and Menu.
    public static func tvHint(_ row: ServersModel.Row?) -> String {
        let menu = "Menu closes this panel"
        guard let row else { return menu }
        switch row.state {
        case .ready: return "Select switches to \(row.name) · \(menu)"
        case .signedOut: return "Select signs in to \(row.name) · \(menu)"
        case .current, .switching, .tooOld: return menu
        }
    }

    public static let tvHintTryAgain = "Select tries again · Menu closes this panel"
    public static let tvHintStart = "Select starts · Menu closes this panel"
    public static let tvHintOpen = "Select opens it · Menu closes this panel"

    // MARK: Settings › Server

    public static let yourServers = "Your servers"

    /// Under Your servers: "This iPhone is signed in to each of these. Search looks in all of them."
    public static func yourServersLede(device: String, count: Int) -> String {
        count > 1 ? "This \(device) is signed in to each of these. Search looks in all of them."
            : "This \(device) is signed in to this server. Add another and search looks in both."
    }

    public static let downloadsFromIt = "Downloads from it"
    public static let switchToThis = "Switch to this server"
    public static let switchToThisLine = "It doesn’t ask for a password again"
    public static let signInAgain = "Sign in again"
    public static let signedOutHere = "Signed out here"
    public static let remove = "Remove this server…"
    public static let removing = "Removing…"

    /// A server's downloads here: "3 · 12.4 GB", or "None".
    public static func downloadsValue(count: Int, bytes: Int64) -> String {
        count > 0 ? "\(count) · \(freeSize(bytes))" : "None"
    }

    /// Remove this server's question: what goes from this device, and what
    /// doesn't change. Remove, or Keep.
    public static func removeQuestion(server: String, device: String, downloads: Int) -> ConfirmWords {
        let here = downloads > 0
            ? "This \(device) is signed out of it, and its \(plural(downloads, "download")) here \(downloads == 1 ? "is" : "are") deleted."
            : "This \(device) is signed out of it."
        return ConfirmWords(
            title: "Remove \(server) from this \(device)?",
            lines: [here, "The server, your account on it and what you’ve watched there aren’t changed. You can add it again."],
            confirm: "Remove", keep: "Keep"
        )
    }

    /// Apple TV's path over a server's page: "Settings › Server › Cabin server".
    public static func tvPath(_ server: String) -> String { "Settings › Server › \(server)" }

    public static func tvPageHint(switching name: String?) -> String {
        name.map { "Select switches to \($0) · Menu goes back to Server" } ?? "Menu goes back to Server"
    }

    /// The panel beside a server's row in Apple TV's Settings.
    public static func tvRowAbout(current: Bool) -> [String] {
        ["Opens its page: the address, the version and who is signed in on this Apple TV.",
         current ? "It is the server in use. Remove it from this Apple TV there." : "Switch to it there, or remove it from this Apple TV."]
    }

    public static let tvAddAbout = [
        "Choose another server on this network, or enter its address, then sign in there.",
        "You stay signed in here, and switching between them needs no password.",
    ]

    // MARK: Downloads

    /// Under the Downloads list: "3 more from Cabin server".
    public static func moreDownloads(_ count: Int, server: String) -> String { "\(count) more from \(server)" }
}
