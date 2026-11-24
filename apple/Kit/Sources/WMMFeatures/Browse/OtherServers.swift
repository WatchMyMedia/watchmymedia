import Foundation
import Observation
import WMMModel

/// The other servers signed in to, each asked the same question at once
/// for one card (their Home, a page of a library), each given
/// ``cardServerTimeout``. Each server's own API asks the server which one it
/// is before its sign-in goes out (the data layer does). While one is out
/// after ``cardStillAskingAfter`` a screen says so; one that fails or runs
/// out of time is listed as failed, with Try again.
@MainActor
@Observable
public final class OtherServerAnswers<Answer: Sendable> {
    /// Each server's answer, by server id.
    public private(set) var answers: [String: Answer] = [:]
    /// The servers asked, in the order a screen lists them.
    public private(set) var asked: [CardServer] = []
    /// The servers still out.
    public private(set) var out: [CardServer] = []
    /// ``cardStillAskingAfter`` has passed with a server still out.
    public private(set) var slow = false
    /// The servers that didn't answer, or failed.
    public private(set) var failed: [CardServer] = []
    /// The order the answers came in, by server id.
    public private(set) var arrived: [String] = []

    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    @ObservationIgnored private var round = 0
    /// Called as each answer comes in.
    @ObservationIgnored public var onAnswer: (@MainActor () -> Void)?

    public init(sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.sleep = sleep
    }

    /// Asks `servers` (with the services for each) `question`, all at once;
    /// returns when each has answered or failed. Answers kept from before
    /// stay until a server answers again.
    public func ask(_ servers: [(CardServer, AppServices)], _ question: @escaping @MainActor (AppServices) async throws -> Answer) async {
        round += 1
        let mine = round
        asked = servers.map(\.0)
        answers = answers.filter { id, _ in servers.contains { $0.0.id == id } }
        failed = []
        arrived = arrived.filter { answers[$0] != nil }
        out = servers.map(\.0)
        slow = false
        let timer = Task { [weak self, sleep] in
            try? await sleep(cardStillAskingAfter)
            guard let self, !Task.isCancelled, self.round == mine, !self.out.isEmpty else { return }
            self.slow = true
        }
        defer { timer.cancel() }
        var tasks: [Task<Void, Never>] = []
        for (server, services) in servers {
            tasks.append(Task { @MainActor [weak self, sleep] in
                let answer = await withinLimit(cardServerTimeout, sleep: sleep) { try await question(services) }
                guard let self, self.round == mine else { return }
                self.settle(server, answer)
            })
        }
        for task in tasks { await task.value }
        if round == mine { slow = false }
    }

    /// Try again on one server that didn't answer.
    public func retry(_ server: CardServer, services: AppServices, _ question: @escaping @MainActor (AppServices) async throws -> Answer) async {
        failed.removeAll { $0.id == server.id }
        out.append(server)
        let mine = round
        let answer = await withinLimit(cardServerTimeout, sleep: sleep) { try await question(services) }
        guard round == mine else { return }
        settle(server, answer)
    }

    private func settle(_ server: CardServer, _ answer: Answer?) {
        out.removeAll { $0.id == server.id }
        if let answer {
            answers[server.id] = answer
            arrived.removeAll { $0 == server.id }
            arrived.append(server.id)
            onAnswer?()
        } else if !failed.contains(server) {
            failed.append(server)
        }
    }

    /// "Still asking Den server…" while a server is out past ``cardStillAskingAfter``.
    public var stillAsking: String? {
        guard slow, !out.isEmpty else { return nil }
        return SearchWords.stillAsking(out.map(\.name))
    }

    /// Forgets everything (another server came into use).
    public func reset() {
        round += 1
        answers = [:]
        asked = []
        out = []
        failed = []
        arrived = []
        slow = false
    }
}

extension AppServices {
    /// The other servers one card asks, with the services that talk to each,
    /// in the Servers list's order, a server away from home last.
    public var otherCardServers: [(CardServer, AppServices)] {
        servers.otherCardServers.compactMap { server in
            let services = on(server.id)
            return services.scope == server.id ? (server, services) : nil
        }
    }
}

// MARK: Home

/// The Home rows built the same way on every server, which one card fills
/// from all of them: Continue watching, by when each was last played; the
/// Recently rows, newest first.
public enum MergedHomeRow: String, CaseIterable, Sendable {
    case `continue`, recentlyAddedShows, recentlyAired, recentlyAddedMovies, recentlyReleased
}

extension HomeData {
    /// The row `id`'s cards, arranged or fixed.
    func cards(_ row: MergedHomeRow) -> ScreenRow? {
        if let rows { return rows.first { $0.id == row.rawValue } }
        switch row {
        case .continue: return ScreenRow(id: row.rawValue, style: .continue, title: "Continue watching", continueItems: continueWatching)
        case .recentlyAddedShows: return ScreenRow(id: row.rawValue, style: .poster, title: "Recently added · TV Shows", items: recentlyAddedShows)
        case .recentlyAired: return ScreenRow(id: row.rawValue, style: .episode, title: "Recently aired", episodes: recentlyAired)
        case .recentlyAddedMovies: return ScreenRow(id: row.rawValue, style: .poster, title: "Recently added · Movies", items: recentlyAddedMovies)
        case .recentlyReleased: return ScreenRow(id: row.rawValue, style: .wide, title: "Recently released", items: recentlyReleased)
        }
    }

    mutating func setCards(_ row: MergedHomeRow, _ cards: ScreenRow) {
        if let index = rows?.firstIndex(where: { $0.id == row.rawValue }) {
            rows?[index].items = cards.items
            rows?[index].episodes = cards.episodes
            rows?[index].continueItems = cards.continueItems
            return
        }
        guard rows == nil else { return }
        switch row {
        case .continue: continueWatching = cards.continueItems
        case .recentlyAddedShows: recentlyAddedShows = cards.items
        case .recentlyAired: recentlyAired = cards.episodes
        case .recentlyAddedMovies: recentlyAddedMovies = cards.items
        case .recentlyReleased: recentlyReleased = cards.items
        }
    }
}

/// One card's Home: the server in use's Home, with each row built the same
/// way on every server filled from all of them, one card a title. A title
/// on several servers is the server in use's card when it has one (a
/// Continue watching card, the most recently played copy's), named by
/// nothing; another server's card carries its server. Cards already shown
/// keep their places (`shown`, the card keys in order by row); what is
/// new joins in order after them.
public enum HomeMerge {
    public static func merge(_ current: HomeData, others: [(server: String, home: HomeData)], shown: [String: [String]] = [:]) -> HomeData {
        var home = current
        for row in MergedHomeRow.allCases {
            guard var cards = current.cards(row) else { continue }
            let theirs = others.compactMap { other in other.home.cards(row).map { (other.server, $0) } }
            switch row {
            case .continue:
                cards.continueItems = keepPlaces(mergeContinue(cards.continueItems, theirs.map { ($0.0, $0.1.continueItems) }), shown[row.rawValue], key: \.cardKey)
            case .recentlyAired:
                cards.episodes = keepPlaces(mergeEpisodes(cards.episodes, theirs.map { ($0.0, $0.1.episodes) }), shown[row.rawValue], key: \.cardKey)
            case .recentlyAddedShows, .recentlyAddedMovies, .recentlyReleased:
                let byRelease = row == .recentlyReleased
                cards.items = keepPlaces(mergeItems(cards.items, theirs.map { ($0.0, $0.1.items) }, byRelease: byRelease), shown[row.rawValue], key: \.cardID)
            }
            home.setCards(row, cards)
        }
        return home
    }

    /// Continue watching from every server: one card a title, the most
    /// recently played copy's, ordered by when each was last played.
    static func mergeContinue(_ mine: [ContinueItem], _ theirs: [(String, [ContinueItem])]) -> [ContinueItem] {
        var all = mine
        for (server, items) in theirs {
            for var item in items {
                item.server = server
                if let key = TitleKey(item), let index = all.firstIndex(where: { TitleKey($0) == key }) {
                    if (item.updatedAt ?? .distantPast) > (all[index].updatedAt ?? .distantPast) { all[index] = item }
                } else {
                    all.append(item)
                }
            }
        }
        return all.enumerated().sorted { a, b in
            let da = a.element.updatedAt ?? .distantPast, db = b.element.updatedAt ?? .distantPast
            return da != db ? da > db : a.offset < b.offset
        }.map(\.element)
    }

    /// Recently aired from every server, newest first by air day, then by arrival.
    static func mergeEpisodes(_ mine: [EpisodeCard], _ theirs: [(String, [EpisodeCard])]) -> [EpisodeCard] {
        var all = mine
        for (server, cards) in theirs {
            for var card in cards {
                if let key = TitleKey(card), all.contains(where: { TitleKey($0) == key }) { continue }
                card.server = server
                all.append(card)
            }
        }
        return all.enumerated().sorted { a, b in
            let da = a.element.airDate ?? "", db = b.element.airDate ?? ""
            if da != db { return da > db }
            let aa = a.element.addedAt ?? .distantPast, ab = b.element.addedAt ?? .distantPast
            return aa != ab ? aa > ab : a.offset < b.offset
        }.map(\.element)
    }

    /// Recently added (by when each arrived) or released (by its date), newest first.
    static func mergeItems(_ mine: [BrowseItem], _ theirs: [(String, [BrowseItem])], byRelease: Bool) -> [BrowseItem] {
        var all = mine
        for (server, items) in theirs {
            for var item in items {
                if let key = TitleKey(item), all.contains(where: { TitleKey($0) == key }) { continue }
                item.server = server
                all.append(item)
            }
        }
        return all.enumerated().sorted { a, b in
            if byRelease {
                let ra = a.element.releaseDate ?? "", rb = b.element.releaseDate ?? ""
                if ra != rb { return ra > rb }
            } else {
                let da = a.element.addedAt ?? .distantPast, db = b.element.addedAt ?? .distantPast
                if da != db { return da > db }
            }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// `merged` with the cards already shown in their places, the rest after them in order.
    static func keepPlaces<T>(_ merged: [T], _ shown: [String]?, key: KeyPath<T, String>) -> [T] {
        guard let shown, !shown.isEmpty else { return merged }
        let byKey = Dictionary(merged.map { ($0[keyPath: key], $0) }, uniquingKeysWith: { first, _ in first })
        let kept = shown.compactMap { byKey[$0] }
        let keptKeys = Set(kept.map { $0[keyPath: key] })
        return kept + merged.filter { !keptKeys.contains($0[keyPath: key]) }
    }

    /// The card keys of each merged row as shown, for ``merge(_:others:shown:)``.
    public static func shownKeys(_ home: HomeData) -> [String: [String]] {
        var keys: [String: [String]] = [:]
        for row in MergedHomeRow.allCases {
            guard let cards = home.cards(row) else { continue }
            keys[row.rawValue] = cards.continueItems.map(\.cardKey) + cards.episodes.map(\.cardKey) + cards.items.map(\.cardID)
        }
        return keys
    }
}

extension LibraryRepository {
    /// A picture of a card on `server` (nil: the server in use), from that
    /// server, which the image loader signs with its sign-in.
    public func imageURL(_ path: String?, size: ImageSize, on server: String?) -> URL? {
        guard let server, server != serverScope else { return imageURL(path, size: size) }
        return imageURL(path, size: size, serverID: server)
    }
}

extension HomeModel {
    /// The focused card's server in a line, for Apple TV's words above a
    /// row: "On Cabin", "from Cabin"; nil for the server in use's card.
    public func whereLine(row: ScreenRow, card key: String?) -> String? {
        switch row.style {
        case .continue:
            return (row.continueItems.first { $0.cardKey == key } ?? row.continueItems.first).flatMap(whereLine)
        case .episode:
            return (row.episodes.first { $0.cardKey == key } ?? row.episodes.first).flatMap(whereLine)
        case .poster, .wide:
            return (row.items.first { $0.cardID == key } ?? row.items.first).flatMap(whereLine)
        case .live:
            return nil
        }
    }
}
