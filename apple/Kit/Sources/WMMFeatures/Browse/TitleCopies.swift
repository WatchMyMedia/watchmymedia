import Foundation
import Observation
import WMMModel

// A title's copies across the servers this device is signed in to, for the
// title page's version chooser: Versions on iPhone and iPad, the button
// after Play on Apple TV. Play stays one button; it plays the copy chosen
// last time for the title on this device, otherwise the copy with the
// latest place (so Resume carries on where the person got furthest), and
// otherwise the page's own first version. Resume starts from the latest
// place on any copy, whichever is chosen.

/// One copy of a title that can play: a version (file) on a server, with
/// the person's place there.
public struct TitleCopy: Hashable, Sendable, Identifiable {
    public var server: CardServer
    public var version: MediaVersion
    /// Where the person is in it on that server.
    public var watch: WatchProgress?
    /// The movie's or the episode's id on that server.
    public var titleID: Int

    public init(server: CardServer, version: MediaVersion, watch: WatchProgress?, titleID: Int) {
        self.server = server
        self.version = version
        self.watch = watch
        self.titleID = titleID
    }

    public var id: String { "\(server.id)/\(version.fileID)" }

    /// "4K HDR", "1080p": the picture, as the chooser names a copy.
    public var quality: String {
        let parts = [version.quality, version.hdr == nil ? nil : "HDR"].compactMap { $0 }
        return parts.isEmpty ? version.choiceLabel : parts.joined(separator: " ")
    }

    /// The person's place there: "52 min left", "watched", or "not started".
    public var placeLine: String {
        guard let watch else { return OneCardWords.notStarted }
        if watch.completed { return OneCardWords.watched }
        return watch.resumable ? formatRemaining(ms: watch.remainingMs) : OneCardWords.notStarted
    }

    /// Where the button after Play names it: "Home · 4K HDR".
    public var label: String { "\(server.shortName) · \(quality)" }

    /// What VoiceOver says of a row: "Cabin server, 1080p, not started".
    public var accessibilityLabel: String { "\(server.name), \(quality), \(placeLine)" }
}

/// One server's copies, under its name in the chooser.
public struct CopyGroup: Hashable, Sendable, Identifiable {
    public var server: CardServer
    public var copies: [TitleCopy]

    public var id: String { server.id }

    /// "Home server", "Cabin server": the server's own name, never the bare
    /// short name, since "Home" alone reads as the Home screen.
    public var heading: String { server.name }

    /// Under the heading of a server away from home.
    public var note: String? { server.away ? OneCardWords.awayNote : nil }
}

/// What a title page's copies are of.
public enum CopySubject: Hashable, Sendable {
    case movie(tmdbID: Int)
    case episode(showTMDBID: Int, season: Int, number: Int)
}

/// A title page's copies on every server this device is signed in to. The
/// page gives its own (``setHere(_:)``); ``load()`` asks each other server
/// for the title by TMDB id, in the background, each one first asked which
/// server it is before its sign-in goes out (the data layer does), each
/// given ``cardServerTimeout``. One that doesn't answer is left out without
/// a word; while one is out after ``cardStillAskingAfter`` the chooser ends
/// with "Still asking Den server…".
@MainActor
@Observable
public final class TitleCopiesModel {
    /// The page's own copies.
    public private(set) var here: [TitleCopy] = []
    /// The other servers' copies, by server id, as they answer.
    public private(set) var found: [String: [TitleCopy]] = [:]
    /// The servers still being asked.
    public private(set) var out: [CardServer] = []
    /// ``cardStillAskingAfter`` has passed with a server still out.
    public private(set) var slow = false
    /// The copy chosen on this page (or last time, on this device).
    public private(set) var chosenID: String?

    public let subject: CopySubject
    @ObservationIgnored private let services: AppServices
    @ObservationIgnored private let choices: CopyChoices
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var asked = false

    /// `services` are the page's: the server it loaded from is theirs
    /// (``AppServices/scope``, else the one in use).
    public init(services: AppServices, subject: CopySubject, choices: CopyChoices,
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.services = services
        self.subject = subject
        self.choices = choices
        self.sleep = sleep
        chosenID = choices.chosen(subject.titleKey)
    }

    /// The server the page loaded from.
    public var pageServer: CardServer? {
        let current = services.servers.current?.server.id
        let id = services.scope ?? current
        return services.servers.saved.first { $0.server.id == id }?.cardServer(current: current)
    }

    /// The servers asked: every one signed in to but the page's, in the
    /// Servers list's order, a server away from home last.
    public var otherServers: [CardServer] {
        let page = pageServer?.id
        var list = services.servers.otherCardServers
        if let current = services.servers.currentCardServer { list.insert(current, at: 0) }
        return list.filter { $0.id != page }
    }

    /// The page's own copies, each time the page loads.
    public func setHere(_ copies: [TitleCopy]) {
        here = copies
    }

    /// Other servers' copies found by someone else (the show page asks for
    /// a season once for every episode in it), and who is still out.
    public func setFound(_ copies: [String: [TitleCopy]], out: [CardServer], slow: Bool) {
        asked = true
        found = copies
        self.out = out
        self.slow = slow
    }

    /// The page's copies from its movie: one a version.
    public static func copies(of movie: MovieDetail, on server: CardServer) -> [TitleCopy] {
        movie.versions.map { TitleCopy(server: server, version: $0, watch: movie.watch, titleID: movie.id) }
    }

    /// The page's copies from its episode.
    public static func copies(of episode: EpisodeDetail, on server: CardServer) -> [TitleCopy] {
        episode.versions.map { TitleCopy(server: server, version: $0, watch: episode.watch, titleID: episode.id) }
    }

    // MARK: Asking

    /// Asks every other server once (again after ``retry()``); returns when
    /// each has answered or run out of time.
    public func load() async {
        guard !asked else { return }
        asked = true
        let servers = otherServers
        guard !servers.isEmpty else { return }
        out = servers
        slow = false
        let timer = Task { [weak self, sleep] in
            try? await sleep(cardStillAskingAfter)
            guard let self, !Task.isCancelled, !self.out.isEmpty else { return }
            self.slow = true
        }
        defer { timer.cancel() }
        var asking: [Task<Void, Never>] = []
        for server in servers {
            let library = services.on(server.id).library
            guard library.serverScope == (server.isCurrent ? nil : server.id) else {
                out.removeAll { $0.id == server.id }
                continue
            }
            asking.append(Task { @MainActor [weak self, subject, sleep] in
                let copies = await withinLimit(cardServerTimeout, sleep: sleep) { try await Self.copies(library, subject: subject, server: server) }
                guard let self else { return }
                self.out.removeAll { $0.id == server.id }
                if let copies, !copies.isEmpty { self.found[server.id] = copies }
            })
        }
        for task in asking { await task.value }
        slow = false
    }

    /// Asks again, for a page loaded again (pull to refresh).
    public func reload() async {
        asked = false
        found = [:]
        await load()
    }

    private static func copies(_ library: any LibraryRepository, subject: CopySubject, server: CardServer) async throws -> [TitleCopy] {
        switch subject {
        case .movie(let tmdbID):
            var all: [TitleCopy] = []
            for movie in try await library.movies(tmdbID: tmdbID) {
                all += Self.copies(of: try await library.movie(id: movie.id), on: server)
            }
            return all
        case .episode(let show, let season, let number):
            var all: [TitleCopy] = []
            for found in try await library.shows(tmdbID: show) {
                let detail = try await library.season(showID: found.id, number: season)
                guard let episode = detail.episodes.first(where: { $0.number == number && $0.hasFile }) else { continue }
                all += Self.copies(of: episode, on: server)
            }
            return all
        }
    }

    // MARK: What the chooser shows

    /// Every copy in groups: the server in use first, then the others in
    /// the Servers list's order, a server away from home last.
    public var groups: [CopyGroup] {
        var byServer: [String: CopyGroup] = [:]
        for copy in here + found.values.flatMap({ $0 }) {
            byServer[copy.server.id, default: CopyGroup(server: copy.server, copies: [])].copies.append(copy)
        }
        let order = ([services.servers.currentCardServer].compactMap { $0 } + services.servers.otherCardServers).map(\.id)
        return byServer.values.sorted { a, b in
            if a.server.away != b.server.away { return !a.server.away }
            return (order.firstIndex(of: a.id) ?? .max) < (order.firstIndex(of: b.id) ?? .max)
        }
    }

    public var copies: [TitleCopy] { groups.flatMap(\.copies) }

    /// The title is on two servers or more: the chooser shows (Apple TV's
    /// button after Play, the Versions section's groups).
    public var onSeveralServers: Bool { groups.count > 1 }

    /// The servers besides the page's that have it.
    public var elsewhere: [CardServer] {
        groups.map(\.server).filter { $0.id != pageServer?.id }
    }

    /// The copy with the latest place: the furthest along, not finished.
    public var latest: TitleCopy? {
        copies.filter { $0.watch?.resumable == true }.max { ($0.watch?.positionMs ?? 0) < ($1.watch?.positionMs ?? 0) }
    }

    /// The person got further on another server than on the page's.
    public var furtherElsewhere: CardServer? {
        guard let latest, latest.server.id != pageServer?.id else { return nil }
        let here = self.here.compactMap { $0.watch?.resumable == true ? $0.watch?.positionMs : nil }.max() ?? 0
        return (latest.watch?.positionMs ?? 0) > here ? latest.server : nil
    }

    /// The copy Play plays: the one chosen (on this page, or last time on
    /// this device), else the one with the latest place on another server,
    /// else the page's first.
    public var chosen: TitleCopy? {
        let all = copies
        if let chosenID, let copy = all.first(where: { $0.id == chosenID }) { return copy }
        if let further = furtherElsewhere, let copy = latest, copy.server.id == further.id { return copy }
        return here.first ?? all.first
    }

    /// Chooses `copy` for this title, on this device: Play plays it.
    public func choose(_ copy: TitleCopy) {
        chosenID = copy.id
        choices.choose(copy.id, for: subject.titleKey)
    }

    /// Where Resume starts, in ms, whichever copy plays: the latest place
    /// on any server; nil when there is none to resume.
    public var resumeMs: Int? { latest?.watch?.positionMs }

    /// `base` (the page's request for its first version) for `copy`, or the
    /// chosen one: that file, from its server, resuming at the latest place
    /// unless `fromStart`.
    public func request(_ base: PlayRequest, copy: TitleCopy? = nil, fromStart: Bool = false) -> PlayRequest? {
        guard let copy = copy ?? chosen else { return nil }
        var request = base
        request.fileID = copy.version.fileID
        request.serverID = copy.server.isCurrent ? nil : copy.server.id
        if copy.server.id != (services.scope ?? services.servers.current?.server.id) {
            // Another server's ids: no season list from the page's server.
            request.showID = nil
        }
        request.startMs = fromStart ? 0 : resumeMs ?? base.startMs
        return request
    }

    // MARK: Words

    /// Under the facts: "Also on Cabin", "Also on Cabin and Den", and
    /// "Also on Cabin · you got further there"; nil on one server.
    public var alsoLine: String? {
        let names = elsewhere.map(\.shortName)
        guard !names.isEmpty else { return nil }
        let also = "Also on \(liveListed(names))"
        return furtherElsewhere == nil ? also : "\(also) · you got further there"
    }

    /// The chooser's first line, where the person got further elsewhere:
    /// "Resume starts at 1:06:12, where you got to on Cabin, whichever you choose."
    public var resumeNote: String? {
        guard let further = furtherElsewhere, let ms = resumeMs else { return nil }
        return OneCardWords.resumeNote(at: ms, on: further.shortName)
    }

    /// The chooser's last line while a server is still out: "Still asking Den server…".
    public var stillAsking: String? {
        guard slow, !out.isEmpty else { return nil }
        return SearchWords.stillAsking(out.map(\.name))
    }

    /// Resume's label when it plays from another server: "Resume · from Cabin".
    public var resumeFrom: String? {
        guard let chosen, chosen.server.id != pageServer?.id, resumeMs != nil else { return nil }
        return "from \(chosen.server.shortName)"
    }
}

extension CopySubject {
    var titleKey: TitleKey {
        switch self {
        case .movie(let id): .movie(tmdbID: id)
        case .episode(let show, let season, let number): .episode(showTMDBID: show, season: season, number: number)
        }
    }
}

/// A show's other servers, for its page: each is asked once for the show by
/// TMDB id, then for a season once as the page shows it, so moving along the
/// episodes asks nothing more. An episode's copies there, and the episodes
/// only another server has, come from what was asked.
@MainActor
@Observable
public final class ShowElsewhere {
    /// The show on each other server that has it: its id there.
    public private(set) var shows: [String: Int] = [:]
    /// The servers that have it, in the chooser's order.
    public private(set) var servers: [CardServer] = []
    /// Each server's seasons asked for, by season number.
    public private(set) var seasons: [String: [Int: SeasonDetail]] = [:]
    /// The servers still being asked, and whether that has gone on past ``cardStillAskingAfter``.
    public private(set) var out: [CardServer] = []
    public private(set) var slow = false

    @ObservationIgnored private let services: AppServices
    @ObservationIgnored private let showTMDBID: Int
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var asked = false
    @ObservationIgnored private var seasonsAsked: Set<Int> = []
    @ObservationIgnored private var others: [CardServer] = []

    public init(services: AppServices, showTMDBID: Int, sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.services = services
        self.showTMDBID = showTMDBID
        self.sleep = sleep
    }

    /// Asks each other server for the show, once.
    public func load(asking servers: [CardServer]) async {
        guard !asked else { return }
        asked = true
        others = servers
        await ask(servers) { (library) -> [Int] in
            try await library.shows(tmdbID: self.showTMDBID).first.map { [$0.id] } ?? []
        } found: { (server: CardServer, ids: [Int]) in
            guard let id = ids.first else { return }
            self.shows[server.id] = id
            self.servers = self.others.filter { self.shows[$0.id] != nil }
        }
    }

    /// Asks each server that has the show for season `number`, once.
    public func loadSeason(_ number: Int) async {
        guard asked, !seasonsAsked.contains(number) else { return }
        seasonsAsked.insert(number)
        let having = servers
        await ask(having) { (library) -> [SeasonDetail] in
            guard let id = self.shows[library.serverScope ?? ""] ?? self.currentShowID(library) else { return [] }
            return [try await library.season(showID: id, number: number)]
        } found: { (server: CardServer, seasons: [SeasonDetail]) in
            guard let season = seasons.first else { return }
            self.seasons[server.id, default: [:]][number] = season
        }
    }

    private func currentShowID(_ library: any LibraryRepository) -> Int? {
        library.serverScope == nil ? services.servers.current.flatMap { shows[$0.server.id] } : nil
    }

    /// Asks `servers` at once, each given ``cardServerTimeout``.
    private func ask<T: Sendable>(_ servers: [CardServer], _ question: @escaping @MainActor (any LibraryRepository) async throws -> [T],
                                  found: @escaping @MainActor (CardServer, [T]) -> Void) async {
        guard !servers.isEmpty else { return }
        out = servers
        slow = false
        let timer = Task { [weak self, sleep] in
            try? await sleep(cardStillAskingAfter)
            guard let self, !Task.isCancelled, !self.out.isEmpty else { return }
            self.slow = true
        }
        defer { timer.cancel() }
        var tasks: [Task<Void, Never>] = []
        for server in servers {
            let library = services.on(server.id).library
            guard library.serverScope == (server.isCurrent ? nil : server.id) else {
                out.removeAll { $0.id == server.id }
                continue
            }
            tasks.append(Task { @MainActor [weak self, sleep] in
                let answer = await withinLimit(cardServerTimeout, sleep: sleep) { try await question(library) }
                guard let self else { return }
                self.out.removeAll { $0.id == server.id }
                if let answer { found(server, answer) }
            })
        }
        for task in tasks { await task.value }
        slow = false
    }

    /// The other servers' copies of an episode, by server id.
    public func copies(season: Int, number: Int) -> [String: [TitleCopy]] {
        var all: [String: [TitleCopy]] = [:]
        for server in servers {
            guard let episode = seasons[server.id]?[season]?.episodes.first(where: { $0.number == number && $0.hasFile }) else { continue }
            all[server.id] = TitleCopiesModel.copies(of: episode, on: server)
        }
        return all
    }

    /// The first server, in the chooser's order, that has an episode with a file.
    public func onlyOn(season: Int, number: Int) -> CardServer? {
        servers.first { server in seasons[server.id]?[season]?.episodes.contains { $0.number == number && $0.hasFile } == true }
    }
}
