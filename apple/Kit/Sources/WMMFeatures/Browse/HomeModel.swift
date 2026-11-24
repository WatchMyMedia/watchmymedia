import Foundation
import Observation
import WMMModel

/// Home: the hero, Continue watching,
/// Recently added shows, Recently aired, Recently added and Recently
/// released movies, and the library totals. From ``rowsAPILevel`` the rows
/// come in the person's order instead (`HomeData.rows`).
/// Refetches when the library or the rows change and on pull to refresh,
/// keeping what is on screen meanwhile.
@MainActor
@Observable
public final class HomeModel {
    public private(set) var state: Loadable<HomeData> = .loading
    public private(set) var emptyState: HomeEmptyState?
    /// A note to show for a moment: "Marked as watched", or why a refresh failed.
    public var message: String?
    /// Shown instead of navigating: an unmatched folder has nowhere to go.
    public var notice: Notice?

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let auth: any AuthRepository
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let rows: (any RowsRepository)?
    @ObservationIgnored private let servers: (any ServerRepository)?
    @ObservationIgnored private let changes: ScreenChanges
    @ObservationIgnored private let loads = LoadController<HomeData>()
    @ObservationIgnored private let oneCard: OneCardContext?
    /// The server in use's Home as it came, before the other servers' cards joined it.
    @ObservationIgnored private var ownHome: HomeData?
    /// The other servers' Homes, for one card.
    public let others = OtherServerAnswers<HomeData>()
    /// The titles of other servers' Continue watching cards that the server in use has too, by their keys.
    @ObservationIgnored private var alsoHere: Set<TitleKey> = []
    /// When the server in use's Home last came in: other servers that answer
    /// within ``cardStillAskingAfter`` of it are merged in order; a slower
    /// one's cards join the ends of rows already on screen, which don't move.
    @ObservationIgnored private var ownArrived: ContinuousClock.Instant?

    /// `rows` brings the person's other devices' changes to the layout and lists (rows.changed, lists.changed).
    /// `servers` says what the server in use offers (Remove from Continue watching).
    /// With `oneCard`, Home's rows built the same way on every server are filled from all of them.
    public init(library: any LibraryRepository, auth: any AuthRepository, navigator: Navigator, rows: (any RowsRepository)? = nil,
                servers: (any ServerRepository)? = nil, oneCard: OneCardContext? = nil, now: @escaping @MainActor () -> Date = { Date() }) {
        self.oneCard = oneCard
        self.library = library
        self.auth = auth
        self.navigator = navigator
        self.rows = rows
        self.servers = servers
        changes = ScreenChanges(now: now)
    }

    /// The signed-in user's name, for the avatar's label.
    public var userName: String? { auth.user?.name }

    /// The avatar's letter: "S".
    public var initial: String? { userName?.first.map { String($0).uppercased() } }

    public var isAdmin: Bool { auth.user?.isAdmin == true }

    public var offersSetup: Bool { isAdmin && emptyState?.offersSetup == true }

    /// The offline Home with `downloads` to watch: Home's
    /// request failed, or, while it is still out, the server check
    /// (``ServerRepository/reachable``) found no server. That check gives up
    /// in seconds where Home's request takes 30;
    /// the request carries on, and Home shows if it answers.
    public func showsOffline(reachable: Bool?, downloads: Bool) -> Bool {
        guard downloads else { return false }
        switch state {
        case .failed: return true
        case .loading: return reachable == false
        case .loaded: return false
        }
    }

    /// Loads Home, then reloads quietly whenever the library, the person's
    /// watch state, or their rows and lists change, until cancelled. Coming
    /// back to Home loads nothing unless something changed meanwhile
    /// (``ScreenChanges``). Scans matter only to an empty Home, which says
    /// what the scan is doing; `library.changed` brings what a scan found.
    public func run() async {
        await changes.run(
            subscribe: { merge(library.libraryChanges(), unknown: rows?.changes()) },
            hasContent: state.value != nil,
            load: { quiet in await load(quiet: quiet) },
            apply: { changes in
                let scansOnly = changes.allSatisfy { if case .scan = $0 { true } else { false } }
                if scansOnly && emptyState == nil { return }
                await load(quiet: true)
            }
        )
    }

    /// Home is arranged (``rowsAPILevel``): Edit Home is offered.
    public var canEditRows: Bool { state.value?.isArranged == true }

    public func retry() async {
        await load(quiet: false)
        changes.loaded()
    }

    /// Pull to refresh: keeps Home on screen, and says why if it fails.
    public func refresh() async {
        await load(quiet: true)
        changes.loaded()
    }

    // MARK: Taps

    public func playHero() {
        guard let home = state.value, let request = home.hero?.playRequest(episodes: home.allEpisodes) else { return }
        navigator.play(request)
    }

    public func openHero() {
        guard let route = state.value?.hero?.pageRoute else { return }
        navigator.open(route)
    }

    /// Continue watching resumes straight into the player: from the server
    /// it was last played on, unless the person chose another copy for the
    /// title on this device, which then starts at the same place.
    public func resume(_ item: ContinueItem) {
        var request = item.playRequest
        if let oneCard, let key = TitleKey(item), let chosen = oneCard.choices.chosen(key) {
            let current = oneCard.services.servers.current?.server.id
            let parts = chosen.split(separator: "/", maxSplits: 1).map(String.init)
            if parts.count == 2, let fileID = Int(parts[1]), chosen != "\(item.server ?? current ?? "")/\(item.fileID)",
               oneCard.services.on(parts[0]).scope == (parts[0] == current ? nil : parts[0]) {
                request.fileID = fileID
                request.serverID = parts[0] == current ? nil : parts[0]
                request.startMs = item.positionMs
                if request.serverID != item.server { request.showID = nil }
            }
        }
        navigator.play(request)
    }

    public func openPage(of item: ContinueItem) {
        guard let route = item.pageRoute else { return }
        navigator.open(route)
    }

    public func open(_ item: BrowseItem) {
        notice = navigator.open(item)
    }

    /// A Recently aired card opens the episode's page.
    public func open(_ episode: EpisodeCard) {
        navigator.open(episode)
    }

    /// A row's See all: the list's page, or the library's tab at its grid, sorted the row's way.
    public func open(_ link: RowLink) {
        navigator.open(link, home: state.value)
    }

    /// Whether a row's See all goes anywhere: a list, or a library this Home knows.
    public func canOpen(_ link: RowLink) -> Bool {
        switch link {
        case .list: true
        case .library(let id, _, _, _, _): state.value?.libraryType(id) != nil
        }
    }

    /// What a row's See all card and heading link say,
    /// or nil when the row has none: no link (Continue watching, Live TV, a
    /// row mixing two libraries of a type), or a library this Home doesn't know.
    public func seeAll(_ row: ScreenRow) -> SeeAllWords? {
        guard let link = row.seeAll, canOpen(link) else { return nil }
        let type: LibraryType? = if case .library(let id, _, _, _, _) = link { state.value?.libraryType(id) } else { nil }
        return SeeAllWords(row: row, libraryType: type)
    }

    public func play(_ episode: EpisodeCard) {
        guard let request = episode.playRequest else { return }
        navigator.play(request)
    }

    /// The mark's own notice reloads Home, as it does every screen showing
    /// it. A title on several servers is marked on each that has it.
    public func setWatched(_ target: WatchTarget, _ watched: Bool) async {
        message = await markWatched(target, watched, in: library, oneCard: oneCard)
        for other in sameTitle(as: target) where other != target {
            _ = await markWatched(other, watched, in: library, oneCard: oneCard)
        }
    }

    // MARK: One card

    /// Asks every other server signed in to for its Home, and fills the rows
    /// built the same way from them as they answer.
    private func askOthers() async {
        guard let oneCard, oneCard.applies else { return }
        others.onAnswer = { [weak self] in self?.remerge() }
        await others.ask(oneCard.services.otherCardServers) { try await $0.library.home() }
        remerge()
        await lookUpAlsoHere()
    }

    /// Try again on a server that didn't answer.
    public func retry(_ server: CardServer) async {
        guard let oneCard else { return }
        let services = oneCard.services.on(server.id)
        guard services.scope == server.id else { return }
        await others.retry(server, services: services) { try await $0.library.home() }
        remerge()
    }

    /// Home with the other servers' cards that are in now; cards on screen keep their places.
    private func remerge() {
        guard let ownHome, case .loaded(let shown) = state else { return }
        let answered = others.arrived.compactMap { id in others.answers[id].map { (server: id, home: $0) } }
        let settled = ownArrived.map { ContinuousClock.now - $0 > cardStillAskingAfter } ?? true
        state = .loaded(HomeMerge.merge(ownHome, others: answered, shown: settled ? HomeMerge.shownKeys(shown) : [:]))
    }

    /// Whether the server in use has each other server's Continue watching
    /// title, so its card says "from Cabin" rather than "On Cabin".
    private func lookUpAlsoHere() async {
        guard let home = state.value else { return }
        let rows = home.rows?.filter { $0.style == .continue }.flatMap(\.continueItems) ?? home.continueWatching
        for item in rows.prefix(20) where item.server != nil {
            guard let key = TitleKey(item), !alsoHere.contains(key), let tmdb = item.tmdbID else { continue }
            let found = (try? await item.kind == .movie ? library.movies(tmdbID: tmdb) : library.shows(tmdbID: tmdb)) ?? []
            if !found.isEmpty { alsoHere.insert(key) }
        }
    }

    /// A card from another server, in a line: "from Cabin" on a Continue
    /// watching card whose Resume plays from there while the server in use
    /// has the title too, "On Cabin" for a title only another server has;
    /// nil for the server in use's.
    public func whereLine(_ item: ContinueItem) -> String? {
        guard let server = item.server.flatMap(serverName) else { return nil }
        return TitleKey(item).map(alsoHere.contains) == true ? OneCardWords.from(server) : OneCardWords.on(server)
    }

    public func whereLine(_ item: BrowseItem) -> String? { item.server.flatMap(serverName).map(OneCardWords.on) }

    public func whereLine(_ episode: EpisodeCard) -> String? { episode.server.flatMap(serverName).map(OneCardWords.on) }

    /// "Cabin" for a server signed in to, as a card names it.
    public func serverShortName(_ id: String) -> String? { serverName(id) }

    /// The first row drawn that is filled from every server: the phones and
    /// tablets end it with the servers still being asked or that didn't answer.
    public var firstMergedRowID: String? {
        guard let home = state.value else { return nil }
        let rows = home.rows ?? MergedHomeRow.allCases.compactMap { home.cards($0) }
        return rows.first { MergedHomeRow(rawValue: $0.id) != nil && !$0.isEmpty }?.id
    }

    /// "Cabin" for a server signed in to.
    private func serverName(_ id: String) -> String? {
        oneCard?.services.servers.saved.first { $0.server.id == id }.map { cardServerShortName($0.server.name) }
    }

    /// A row built the same way on every server: it ends with "Still
    /// asking Den server…" while one is out, and a tile for one that didn't answer.
    public func isMerged(_ row: ScreenRow) -> Bool { oneCard?.applies == true && MergedHomeRow(rawValue: row.id) != nil }

    /// The servers that didn't answer, each with its line and Try again.
    public var unanswered: [(server: CardServer, line: String)] {
        others.failed.map { ($0, OneCardWords.homeDidNotAnswer($0.name)) }
    }

    /// The same title's targets on the other servers' Homes, for a mark made on one card.
    private func sameTitle(as target: WatchTarget) -> [WatchTarget] {
        guard oneCard != nil, let key = titleKey(target) else { return [] }
        var targets: [WatchTarget] = []
        let homes = [(nil as String?, ownHome)] + others.answers.map { (Optional($0.key), Optional($0.value)) }
        for (server, home) in homes {
            guard let home else { continue }
            for row in MergedHomeRow.allCases {
                guard let cards = home.cards(row) else { continue }
                var found: [WatchTarget] = cards.items.filter { TitleKey($0) == key }.compactMap(\.watchTarget)
                found += cards.episodes.filter { TitleKey($0) == key }.map(\.watchTarget)
                found += cards.continueItems.filter { TitleKey($0) == key }.compactMap(\.watchTarget)
                targets += found.map { t in server.map { .onServer($0, t.plain) } ?? t.plain }
            }
        }
        return Array(Set(targets))
    }

    /// The key of the card a target was made from, as Home shows it.
    private func titleKey(_ target: WatchTarget) -> TitleKey? {
        guard let home = state.value else { return nil }
        for row in MergedHomeRow.allCases {
            guard let cards = home.cards(row) else { continue }
            if let item = cards.items.first(where: { $0.watchTarget == target }) { return TitleKey(item) }
            if let episode = cards.episodes.first(where: { $0.watchTarget == target }) { return TitleKey(episode) }
            if let item = cards.continueItems.first(where: { $0.watchTarget == target }) { return TitleKey(item) }
        }
        return nil
    }

    /// Continue watching's cards offer Remove from Continue watching: the
    /// server keeps the place (``ServerFeatures/hideFromContinueWatching``).
    public var canRemoveFromContinueWatching: Bool {
        servers?.current?.server.features.hideFromContinueWatching == true
    }

    /// Takes the card off Continue watching at once; the server keeps the
    /// place, and playing the title again brings it back. A refusal puts the
    /// card back with a note.
    public func removeFromContinueWatching(_ item: ContinueItem) async {
        guard let id = item.watchID else { return }
        let before = state
        if var home = state.value {
            home.removeFromContinue(item)
            state = .loaded(home)
        }
        do {
            // On every server that has it: the card stands for each copy.
            let owner = item.server.flatMap { oneCard?.services.on($0).library } ?? library
            try await owner.setHiddenFromContinueWatching(item.kind, id: id, hidden: true)
            if let key = TitleKey(item) {
                let copies = ([(nil as String?, ownHome)] + others.answers.map { (Optional($0.key), Optional($0.value)) })
                for (server, home) in copies where server != item.server {
                    guard let other = home?.cards(.continue)?.continueItems.first(where: { TitleKey($0) == key }), let otherID = other.watchID else { continue }
                    let repository = server.flatMap { oneCard?.services.on($0).library } ?? library
                    try? await repository.setHiddenFromContinueWatching(other.kind, id: otherID, hidden: true)
                }
            }
            message = continueRemovedNote
        } catch is CancellationError {
            state = before
        } catch {
            state = before
            message = error.displayMessage
        }
    }

    /// `quiet` keeps what is on screen: a failure then is a message, not an
    /// error page. A load still running when another starts is cancelled.
    func load(quiet: Bool) async {
        await loads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
    }

    private func fetch(_ load: LoadController<HomeData>.Load, quiet: Bool) async {
        loads.begin(&state, quiet: quiet)
        guard let result = await loads.fetch(load, { try await self.library.home() }) else { return }
        var empty: HomeEmptyState?
        if result.value?.isEmpty == true {
            do {
                empty = HomeEmptyState(libraries: try await library.libraries())
            } catch is CancellationError {
                return
            } catch {
                empty = .unavailable
            }
            guard loads.isCurrent(load) else { return }
        }
        if let reason = loads.quietFailure(result, quiet: quiet, over: state) {
            message = reason
        } else {
            ownHome = result.value
            ownArrived = .now
            state = result
            emptyState = empty
            if result.value != nil {
                PerformanceSignposts.shared.homeShowedContent()
                // Other servers' cards join what is on screen as they answer.
                remerge()
                Task { await askOthers() }
            }
        }
    }
}
