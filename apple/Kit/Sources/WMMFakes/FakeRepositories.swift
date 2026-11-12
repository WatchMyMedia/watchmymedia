import Foundation
import Observation
import WMMModel

/// Delay before fake responses, so loading states show; zero in tests.
@MainActor
public enum FakeLatency {
    public static var duration: Duration = .milliseconds(350)

    static func wait() async {
        if duration > .zero { try? await Task.sleep(for: duration) }
    }
}

public enum FakeServers {
    public static let livingRoom = Server(id: "srv-living", name: "Living room server", baseURL: URL(string: "http://192.168.1.20:27587")!, version: "0.9.3", apiLevel: 6)
    public static let officeMini = Server(id: "srv-office", name: "Office Mac mini", baseURL: URL(string: "http://office-mini.local:27587")!, version: "0.9.1", apiLevel: 3)
    /// Open to the internet, so password sign-in needs a browser check: the phone pairs instead.
    public static let remoteHome = Server(id: "srv-remote", name: "home.watchmy.media", baseURL: URL(string: "https://home.watchmy.media")!, version: "0.9.3", apiLevel: 6, turnstile: true)
    /// Remembered, but older than this app works with.
    public static let oldNAS = Server(id: "srv-nas", name: "Old NAS", baseURL: URL(string: "http://nas.local:27587")!, version: "0.6.0", apiLevel: 0)
}

@MainActor
@Observable
public final class FakeServerRepository: ServerRepository {
    public private(set) var discovered: [DiscoveredServer] = []
    public private(set) var saved: [SavedServer]
    public private(set) var current: SavedServer?
    public var localNetworkDenied = false
    private struct Credential {
        let user: User
        let baseURL: URL
    }
    private var credentials: [String: Credential] = [:]
    /// Reject auth work started before a selection, sign-out or replacement.
    private(set) var accountRevision = 0

    var currentUser: User? { current.flatMap { user(on: $0.server) } }

    public init(signedIn: Bool = true, chosen: Bool = true, admin: Bool = true, liveTV: Bool = false, liveGuide: Bool = false, liveExtras: Bool = false, apiLevel: Int? = nil) {
        let account = admin ? User(id: 1, name: "Sam", isAdmin: true) : User(id: 2, name: "Alex", isAdmin: false)
        var livingRoom = FakeServers.livingRoom
        if liveTV { livingRoom.apiLevel = liveTVAPILevel }
        if liveGuide { livingRoom.apiLevel = liveGuideAPILevel }
        if liveGuide && liveExtras { livingRoom.apiLevel = liveExtrasAPILevel }
        if let apiLevel { livingRoom.apiLevel = apiLevel }
        saved = [
            SavedServer(server: livingRoom, userName: account.name, signedIn: signedIn, lastUsedAt: .now),
            SavedServer(server: FakeServers.remoteHome, userName: "Sam", signedIn: false, lastUsedAt: .now.addingTimeInterval(-3 * 86_400)),
            SavedServer(server: FakeServers.oldNAS, userName: "Sam", signedIn: false, lastUsedAt: .now.addingTimeInterval(-90 * 86_400)),
        ]
        current = chosen ? saved.first : nil
        if signedIn {
            credentials[FakeServers.livingRoom.id] = Credential(user: account, baseURL: FakeServers.livingRoom.baseURL)
        }
    }

    public func startDiscovery() {
        discovered = [FakeServers.livingRoom, FakeServers.officeMini].map {
            DiscoveredServer(id: $0.id, name: $0.name, baseURL: $0.baseURL, version: $0.version, apiLevel: $0.apiLevel)
        }
    }

    public func stopDiscovery() {}

    public func probe(_ address: String) async throws -> Server {
        await FakeLatency.wait()
        let a = address.trimmingCharacters(in: .whitespaces)
        if a.isEmpty || a.contains(" ") { throw APIError(status: nil, message: "That doesn't look like a server address.") }
        return [FakeServers.livingRoom, FakeServers.officeMini, FakeServers.remoteHome, FakeServers.oldNAS].first { $0.baseURL.absoluteString.contains(a) } ?? FakeServers.livingRoom
    }

    public func select(_ server: Server) async throws {
        try Task.checkCancellation()
        if server.tooOld { throw ServerTooOldError(server: server) }
        var entry = saved.first { $0.server.id == server.id } ?? SavedServer(server: server, userName: nil, signedIn: false, lastUsedAt: .now)
        entry.server = server
        entry.signedIn = user(on: server) != nil
        entry.userName = user(on: server)?.name ?? entry.userName
        entry.lastUsedAt = .now
        saved = [entry] + saved.filter { $0.server.id != server.id }
        current = entry
        accountRevision += 1
    }

    public func forget(serverID: String) async {
        saved.removeAll { $0.server.id == serverID }
        credentials[serverID] = nil
        if current?.server.id == serverID { current = nil }
        accountRevision += 1
    }

    /// The server in use, when it is at a LAN address already; the fakes find no other.
    public func refreshCurrent() async {}

    public func localAddress() async -> URL? {
        current.flatMap { isLANHost($0.server.baseURL.host() ?? "") ? $0.server.baseURL : nil }
    }

    /// Marks the current server signed in or out, as the real repository does when the auth changes.
    func setSignedIn(_ user: User?) {
        guard var entry = current else { return }
        credentials[entry.server.id] = user.map { Credential(user: $0, baseURL: entry.server.baseURL) }
        entry.signedIn = user != nil
        entry.userName = user?.name ?? entry.userName
        current = entry
        saved = saved.map { $0.server.id == entry.server.id ? entry : $0 }
        accountRevision += 1
    }

    private func user(on server: Server) -> User? {
        guard let credential = credentials[server.id], credential.baseURL == server.baseURL else { return nil }
        return credential.user
    }
}

@MainActor
@Observable
public final class FakeAuthRepository: AuthRepository {
    private let account: User
    /// Bound fakes mirror real auth: changing servers changes the current
    /// user, without removing another server's remembered sign-in.
    public var user: User? {
        if let servers { return servers.currentUser }
        return standaloneUser
    }
    private var standaloneUser: User?
    private var standaloneRevision = 0
    public private(set) var deviceName: String
    /// How long the fake pairing waits before it is approved.
    public var pairingApproval: Duration = .seconds(8)
    private let servers: FakeServerRepository?
    private var sessionList: [DeviceSession]
    @ObservationIgnored private let waitForSignIn: @MainActor () async -> Void
    @ObservationIgnored private let waitForPairingApproval: (@MainActor () async -> Void)?

    /// `client` is this device (``ClientInfo/fakePhone``, ``ClientInfo/fakePad`` or
    /// ``ClientInfo/fakeAppleTV``): its name, and the session marked as this one.
    /// A bound server repository owns the initial and remembered accounts;
    /// `signedIn` supplies the initial user only for standalone auth fakes.
    /// Optional delays let tests hold a reply without changing global latency.
    public init(signedIn: Bool = true, servers: FakeServerRepository? = nil, client: ClientInfo = .fakePhone, admin: Bool = true,
                signInDelay: (@MainActor () async -> Void)? = nil, pairingDelay: (@MainActor () async -> Void)? = nil) {
        account = admin ? User(id: 1, name: "Sam", isAdmin: true) : User(id: 2, name: "Alex", isAdmin: false)
        standaloneUser = signedIn ? account : nil
        self.servers = servers
        waitForSignIn = signInDelay ?? { await FakeLatency.wait() }
        waitForPairingApproval = pairingDelay
        deviceName = client.name
        let day: TimeInterval = 86_400
        let phone = ClientInfo.fakePhone
        let others = client.platform == phone.platform ? [] : [
            DeviceSession(id: "e5f60718293a4b5c", kind: .device, name: phone.name, product: phone.product, platform: phone.platform, version: "1.0.0", lastIP: "192.168.1.44", createdAt: .now.addingTimeInterval(-9 * day), lastSeenAt: .now.addingTimeInterval(-day), current: false, userName: "Sam", model: phone.model),
        ]
        sessionList = [
            DeviceSession(id: "a1b2c3d4e5f60718", kind: .device, name: client.name, product: client.product, platform: client.platform, version: "1.0.0", lastIP: "192.168.1.44", createdAt: .now.addingTimeInterval(-9 * day), lastSeenAt: .now, current: true, userName: "Sam", model: client.model),
        ] + others + [
            DeviceSession(id: "b2c3d4e5f6071829", kind: .device, name: "Living room TV", product: "WatchMyMedia for Android TV", platform: "androidtv", version: "1.0.0", lastIP: "192.168.1.31", createdAt: .now.addingTimeInterval(-20 * day), lastSeenAt: .now.addingTimeInterval(-3600), current: false, userName: "Sam", model: "Google TV Streamer"),
            DeviceSession(id: "c3d4e5f60718293a", kind: .device, name: "Bedroom Apple TV", product: "WatchMyMedia for Apple TV", platform: "tvos", version: "1.0.0", lastIP: "192.168.1.52", createdAt: .now.addingTimeInterval(-30 * day), lastSeenAt: .now.addingTimeInterval(-3 * day), current: false, userName: "Sam", model: "Apple TV 4K"),
            DeviceSession(id: "d4e5f60718293a4b", kind: .browser, name: "Chrome on macOS", lastIP: "100.101.7.2", createdAt: .now.addingTimeInterval(-3 * day), lastSeenAt: .now.addingTimeInterval(-2 * day), current: false, userName: "Sam"),
        ]
    }

    public func setDeviceName(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { deviceName = trimmed }
    }

    public func signIn(name: String, password: String) async throws -> User {
        let context = try accountContext()
        await waitForSignIn()
        try Task.checkCancellation()
        guard password == "password" else { throw APIError(status: 401, message: "Wrong name or password.") }
        let signedIn = User(id: account.id, name: name, isAdmin: account.isAdmin)
        guard keep(signedIn, context: context) else { throw CancellationError() }
        return signedIn
    }

    /// Shows a code for a few seconds, then signs in, so the pairing flow can be seen end to end.
    public func pair() -> AsyncStream<PairingState> {
        let context: AccountContext
        do { context = try accountContext() }
        catch { return AsyncStream { $0.yield(.failed(error.displayMessage)); $0.finish() } }
        return AsyncStream { continuation in
            let task = Task { @MainActor in
                continuation.yield(.starting)
                await FakeLatency.wait()
                guard !Task.isCancelled, contextIsCurrent(context) else { return continuation.finish() }
                let base = context.server?.baseURL ?? FakeServers.livingRoom.baseURL
                let code = PairingCode(code: "K7M4PQ", expiresAt: .now.addingTimeInterval(600), linkURL: base.appendingPathComponent("link").appending(queryItems: [URLQueryItem(name: "code", value: "K7M4PQ")]), pollInterval: .seconds(2))
                continuation.yield(.waiting(code))
                if let waitForPairingApproval { await waitForPairingApproval() }
                else { try? await Task.sleep(for: pairingApproval) }
                guard keep(account, context: context) else { return continuation.finish() }
                continuation.yield(.approved(account))
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func signOut() async {
        if let servers {
            servers.setSignedIn(nil)
        } else {
            standaloneUser = nil
            standaloneRevision += 1
        }
    }

    private struct AccountContext {
        let server: Server?
        let revision: Int
    }

    private func accountContext() throws -> AccountContext {
        if let servers {
            guard let server = servers.current?.server else { throw APIError(status: nil, message: "Choose a server first.") }
            return AccountContext(server: server, revision: servers.accountRevision)
        }
        return AccountContext(server: nil, revision: standaloneRevision)
    }

    private func contextIsCurrent(_ context: AccountContext) -> Bool {
        if let server = context.server {
            return servers?.accountRevision == context.revision && servers?.current?.server.id == server.id && servers?.current?.server.baseURL == server.baseURL
        }
        return servers == nil && standaloneRevision == context.revision
    }

    private func keep(_ user: User, context: AccountContext) -> Bool {
        guard !Task.isCancelled, contextIsCurrent(context) else { return false }
        if let servers {
            servers.setSignedIn(user)
        } else {
            standaloneUser = user
            standaloneRevision += 1
        }
        return true
    }

    public func sessions(all: Bool) async throws -> [DeviceSession] {
        await FakeLatency.wait()
        return sessionList
    }

    public func revoke(sessionID: String) async throws {
        await FakeLatency.wait()
        sessionList.removeAll { $0.id == sessionID }
    }

    public func lookUpPairing(code: String) async throws -> PendingDevice {
        await FakeLatency.wait()
        let clean = code.uppercased().filter { $0.isLetter || $0.isNumber }
        guard clean.count == 6 else { throw APIError(status: 404, message: "That code is not waiting to be linked.") }
        return PendingDevice(code: clean, name: "Living room TV", product: "WatchMyMedia for Android TV", platform: "androidtv", version: "1.0.0", ip: "192.168.1.44", approved: false, model: "Google TV Streamer", expiresAt: .now.addingTimeInterval(8 * 60))
    }

    public func approvePairing(code: String) async throws -> PendingDevice {
        var device = try await lookUpPairing(code: code)
        device.approved = true
        return device
    }
}

@MainActor
public final class FakeLibraryRepository: LibraryRepository {
    /// Offline page previews: only the held downloads can be read.
    public var offline = false
    /// Replaces the fake movies' YouTube trailer with these keys, best first:
    /// `-fakesTrailerKeys a,b` tries real videos in YouTube's player on a
    /// simulator, such as one whose owner keeps it on YouTube.
    public var trailerKeys: [String]?
    /// First-run previews (PLAN-IOS-COMPLETION), with fictional library errors.
    public var firstRun: FakeFirstRunState?
    /// Arranged rows (PLAN-ROWS.md): from ``rowsAPILevel``, Home comes as its rows in order.
    public var rows: FakeRowsRepository?
    /// Watched marks set here, by target, so the fake reflects a mark after a refetch.
    public private(set) var marks: [WatchTarget: Bool] = [:]
    /// Requests by show and season, set by ``FakeRequestsRepository``.
    var seasonRequests: [SeasonKey: RequestState] = [:]
    /// Sonarr's monitoring, as Monitor changed it, by episode ID.
    var monitored: [Int: Bool] = [:]
    /// Downloads Search now started, by episode ID.
    var downloads: [Int: QueueDownload] = [:]
    /// Episodes whose file was deleted, by ID.
    var deleted: Set<Int> = []
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    struct SeasonKey: Hashable {
        var showID: Int
        var season: Int
    }

    public init() {}

    public func home() async throws -> HomeData {
        await FakeLatency.wait()
        try checkOnline()
        if firstRun != nil {
            return HomeData(hero: nil, continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], totals: LibraryTotals(movies: 0, shows: 0, episodes: 0, files: 0, totalSizeBytes: 0), lastScannedAt: nil, movieLibraryID: nil, tvLibraryID: nil)
        }
        var home = FakeData.home()
        if let rows, rows.available {
            // As the server sends it with arranged=true: the rows, and the fixed arrays empty.
            home.rows = rows.screenRows(.home, home: home)
            home.continueWatching = []
            home.recentlyAddedShows = []
            home.recentlyAired = []
            home.recentlyAddedMovies = []
            home.recentlyReleased = []
        }
        return home
    }

    public func libraries() async throws -> [Library] {
        try checkOnline()
        if let firstRun {
            return switch firstRun {
            case .noLibraries: []
            case .scanning: [Library(id: 1, name: "Movies", type: .movie, scanning: true)]
            case .failed: [Library(id: 1, name: "Movies", type: .movie, lastScanError: "Permission denied.")]
            case .empty: [Library(id: 1, name: "Movies", type: .movie, lastScannedAt: Date(timeIntervalSince1970: 1_800_000_000))]
            }
        }
        return [Library(id: FakeData.movieLibrary, name: "Movies", type: .movie), Library(id: FakeData.tvLibrary, name: "TV Shows", type: .tv)]
    }

    public func items(_ query: ItemQuery) async throws -> ItemPage {
        await FakeLatency.wait()
        let all = query.libraryID == FakeData.movieLibrary ? FakeData.movies : FakeData.shows
        var list = all.filter { item in
            switch query.filter {
            case .all: true
            case .continuing: !item.watched && item.kind == .show
            case .ended: item.watched
            case .fourK: item.quality == "4K"
            case .hdr: item.hdr
            case .unwatched: !item.watched
            }
        }
        if let d = query.decade { list = list.filter { ($0.year ?? 0) / 10 * 10 == d } }
        if let t = query.text, !t.isEmpty { list = list.filter { $0.title.localizedCaseInsensitiveContains(t) } }
        var genreCounts = Dictionary(uniqueKeysWithValues: Set(all.flatMap(\.genres)).map { ($0, 0) })
        for item in list {
            for genre in Set(item.genres) { genreCounts[genre, default: 0] += 1 }
        }
        if let g = query.genre { list = list.filter { $0.genres.contains(g) } }
        switch query.sort {
        case .title: list.sort { sortTitle($0.title) < sortTitle($1.title) }
        case .added: break
        case .year, .released, .aired: list.sort { ($0.year ?? 0) > ($1.year ?? 0) }
        case .rating: list.sort { ($0.rating ?? 0) > ($1.rating ?? 0) }
        case .runtime: list.sort { ($0.runtimeMin ?? 0) > ($1.runtimeMin ?? 0) }
        }
        var letters: [LetterOffset] = []
        for (offset, item) in list.enumerated() {
            let first = String(sortTitle(item.title).prefix(1)).uppercased()
            let letter = first.range(of: "^[A-Z]$", options: .regularExpression) == nil ? "#" : first
            if !letters.contains(where: { $0.letter == letter }) {
                letters.append(LetterOffset(letter: letter, offset: offset))
            }
        }
        return ItemPage(
            items: Array(list.dropFirst(query.offset).prefix(query.limit)), total: list.count, offset: query.offset,
            genres: Array(Set(all.flatMap(\.genres))).sorted(),
            decades: Array(Set(all.compactMap { $0.year.map { $0 / 10 * 10 } })).sorted(by: >),
            summary: FakeData.summary(libraryID: query.libraryID),
            letters: query.sort == .title ? letters : nil,
            genreCounts: genreCounts
        )
    }

    public func show(id: Int) async throws -> ShowDetail {
        await FakeLatency.wait()
        try checkOnline()
        var show = FakeData.show(id: id)
        show.seasons = show.seasons.map { s in
            var copy = s
            copy.request = seasonRequests[SeasonKey(showID: id, season: s.number)]
            // What is watched follows the marks, as the server counts it.
            copy.watchedCount = FakeData.season(showID: id, number: s.number).episodes
                .map { applied($0, showID: id, number: s.number, request: copy.request) }
                .filter { $0.hasFile && $0.watched }.count
            return copy
        }
        return show
    }

    public func season(showID: Int, number: Int) async throws -> SeasonDetail {
        await FakeLatency.wait()
        try checkOnline()
        var season = FakeData.season(showID: showID, number: number)
        let request = seasonRequests[SeasonKey(showID: showID, season: number)]
        season.season.request = request
        season.episodes = season.episodes.map { applied($0, showID: showID, number: number, request: request) }
        return season
    }

    /// An episode with what was deleted, monitored, downloaded and marked here.
    private func applied(_ e: EpisodeDetail, showID: Int, number: Int, request: RequestState?) -> EpisodeDetail {
        var copy = e
        if deleted.contains(e.id) {
            copy.hasFile = false
            copy.versions = []
            copy.monitored = false
        }
        if !copy.hasFile {
            copy.request = request
            if let m = monitored[e.id] { copy.monitored = m }
            if let d = downloads[e.id] { copy.download = d }
        }
        guard let mark = marks[.episode(id: e.id)] ?? marks[.season(showID: showID, number: number)] ?? marks[.show(id: showID)] else { return copy }
        if copy.hasFile { copy.watch = mark ? WatchProgress(positionMs: 0, durationMs: (e.runtimeMin ?? 50) * 60_000, completed: true) : nil }
        return copy
    }

    public func movie(id: Int) async throws -> MovieDetail {
        await FakeLatency.wait()
        try checkOnline()
        var movie = FakeData.movie(id: id)
        if let trailerKeys, movie.trailers.contains(where: { $0.youTube != nil }) {
            movie.trailers = movie.trailers.filter { $0.youTube == nil } + trailerKeys.enumerated().compactMap { i, key in
                YouTubeTrailer(name: "Trailer \(i + 1)", key: key).map { MovieTrailer(name: $0.name, source: .youTube($0)) }
            }
        }
        if let mark = marks[.movie(id: id)] {
            movie.watch = mark ? WatchProgress(positionMs: 0, durationMs: (movie.runtimeMin ?? 100) * 60_000, completed: true) : nil
        }
        return movie
    }

    public func search(_ text: String) async throws -> SearchResults {
        await FakeLatency.wait()
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return SearchResults() }
        return SearchResults(
            movies: FakeData.movies.filter { $0.title.localizedCaseInsensitiveContains(t) },
            shows: FakeData.shows.filter { $0.title.localizedCaseInsensitiveContains(t) },
            episodes: FakeData.recentlyAired().filter { $0.title.localizedCaseInsensitiveContains(t) || $0.showTitle.localizedCaseInsensitiveContains(t) }
        )
    }

    public func setWatched(_ kind: WatchKind, id: Int, watched: Bool) async throws {
        try checkOnline()
        try await record(kind == .movie ? .movie(id: id) : .episode(id: id), watched)
    }

    public func setSeasonWatched(showID: Int, season: Int, watched: Bool) async throws {
        try await record(.season(showID: showID, number: season), watched)
    }

    public func setShowWatched(showID: Int, watched: Bool) async throws {
        try await record(.show(id: showID), watched)
    }

    public func playRequest(fileID: Int) async throws -> PlayRequest {
        await FakeLatency.wait()
        return FakeData.playRequest(fileID: fileID)
    }

    private func checkOnline() throws {
        if offline { throw APIError(status: nil, message: "Couldn’t reach the server. Check the connection and try again.") }
    }

    /// The fake library's only pictures are its backdrops (``FakeBackdrops``);
    /// posters have no path, so they show their placeholders.
    public func imageURL(_ path: String?, size: ImageSize) -> URL? { FakeBackdrops.url(path, size: size) }

    public func changes() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let id = UUID()
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.listeners[id] = nil } }
        }
    }

    private func record(_ target: WatchTarget, _ watched: Bool) async throws {
        await FakeLatency.wait()
        // A season or show mark covers every episode under it, as on the
        // server (a fake episode's ID is show × 1000 + season × 10 + number).
        switch target {
        case .season(let showID, let number):
            marks = marks.filter { key, _ in
                if case .episode(let id) = key { return !(id / 1000 == showID && id % 1000 / 10 == number) }
                return true
            }
        case .show(let showID):
            marks = marks.filter { key, _ in
                switch key {
                case .episode(let id): id / 1000 != showID
                case .season(let show, _): show != showID
                default: true
                }
            }
        default: break
        }
        marks[target] = watched
        changed()
    }

    /// Tells the screens following ``changes()`` to refetch.
    func changed() {
        for listener in listeners.values { listener.yield() }
    }

    private func sortTitle(_ title: String) -> String {
        title.hasPrefix("The ") ? String(title.dropFirst(4)) : title
    }
}

@MainActor
@Observable
public final class FakePreferencesRepository: PreferencesRepository {
    public private(set) var playback = PlaybackPreferences()
    /// Automatic, as on a device that never picked one.
    public private(set) var quality: StreamQuality = .auto

    public init() {}

    public func refresh() async throws { await FakeLatency.wait() }

    public func setSkip(_ kind: SegmentKind, mode: SkipMode) async throws {
        playback.skip[kind] = mode
    }

    public func setAutoplayNext(_ enabled: Bool) async throws {
        playback.autoplayNext = enabled
    }

    /// The server's rule (PLAN-LANGUAGES.md §5.3): someone who saves their
    /// first audio language and never chose how subtitles start gets
    /// subtitles for foreign audio.
    public func setLanguages(_ change: LanguageChange) async throws {
        change.apply(to: &playback)
        if change.choice != nil { choseSubtitles = true }
        if change.audioLanguages?.isEmpty == false, !choseSubtitles {
            playback.subtitles.choice = .foreign
            choseSubtitles = true
        }
    }

    private var choseSubtitles = false

    /// Someone who saved `audio` as their first audio languages: the server
    /// then starts subtitles for foreign audio (`-fakesAudioLanguages es,en`).
    public func seedLanguages(_ audio: [String]) {
        playback.audio.languages = audio
        playback.subtitles.choice = .foreign
        choseSubtitles = true
    }

    public func setQuality(_ quality: StreamQuality) {
        self.quality = quality
    }

    private var missingShown: Set<Int> = []

    public func showsMissing(showID: Int) -> Bool { missingShown.contains(showID) }

    public func setShowsMissing(_ on: Bool, showID: Int) {
        if on { missingShown.insert(showID) } else { missingShown.remove(showID) }
    }
}

/// The fake library's pictures, all drawn in code: its backdrops, the fake
/// player's seek-thumbnail sheets and the fake listings' pictures and logos.
public struct FakeImageLoader: ImageLoader {
    public init() {}

    public func data(for url: URL) async throws -> Data {
        guard let data = FakeSeekSheets.sheet(url) ?? FakeLiveArt.image(url) ?? FakeBackdrops.image(url) else { throw URLError(.fileDoesNotExist) }
        return data
    }
}
