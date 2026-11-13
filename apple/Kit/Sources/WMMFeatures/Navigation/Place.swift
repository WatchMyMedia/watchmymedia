import Foundation

/// Where the person was: the tab and each tab's pages, kept so the app
/// reopens there after the system evicts it (PLAN-APPLE-REVIEW.md §6.3).
/// Never the player and never Settings, which is a sheet over the tabs on a
/// server with Live TV; restored only for the account and server that left it.
public struct SavedPlace: Equatable, Sendable {
    public var tab: AppTab
    public var paths: [AppTab: [Route]]

    public init(tab: AppTab, paths: [AppTab: [Route]]) {
        self.tab = tab
        self.paths = paths
    }

    /// The tabs a place keeps.
    static func kept(_ tab: AppTab) -> Bool { tab != .settings }
}

extension SavedPlace: Codable {
    private enum CodingKeys: String, CodingKey { case tab, paths }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(tab.rawValue, forKey: .tab)
        try c.encode(Dictionary(uniqueKeysWithValues: paths.map { ($0.key.rawValue, $0.value) }), forKey: .paths)
    }

    /// A tab this version doesn't know is left out, and a tab's pages stop
    /// before the first one that no longer reads (a page a later version
    /// added, or one whose fields changed): the pages after it were opened
    /// from it.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tab = AppTab(rawValue: try c.decode(String.self, forKey: .tab)) ?? .home
        let stored = try c.decode([String: [LenientRoute]].self, forKey: .paths)
        var paths: [AppTab: [Route]] = [:]
        for (name, routes) in stored {
            guard let tab = AppTab(rawValue: name) else { continue }
            paths[tab] = Array(routes.prefix { $0.route != nil }.compactMap(\.route))
        }
        self.paths = paths
    }

    private struct LenientRoute: Decodable {
        let route: Route?
        init(from decoder: any Decoder) throws { route = try? Route(from: decoder) }
    }
}

/// Keeps one ``SavedPlace`` per server and account in `defaults`.
@MainActor
public struct PlaceStore {
    private let defaults: UserDefaults
    static let prefix = "place."

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private static func key(server: String, user: Int) -> String { "\(prefix)\(server).\(user)" }

    public func save(_ place: SavedPlace, server: String, user: Int) {
        guard let data = try? JSONEncoder().encode(place) else { return }
        defaults.set(data, forKey: Self.key(server: server, user: user))
    }

    /// The place this account left on this server; nil when it left none or it no longer reads.
    public func place(server: String, user: Int) -> SavedPlace? {
        guard let data = defaults.data(forKey: Self.key(server: server, user: user)) else { return nil }
        return try? JSONDecoder().decode(SavedPlace.self, from: data)
    }

    public func forget(server: String, user: Int) {
        defaults.removeObject(forKey: Self.key(server: server, user: user))
    }
}

extension Navigator {
    /// Where the person is now, as a ``SavedPlace``: the tab (Home for
    /// Settings) and the other tabs' pages, without the player.
    public var place: SavedPlace {
        SavedPlace(tab: SavedPlace.kept(tab) ? tab : .home,
                   paths: paths.filter { SavedPlace.kept($0.key) && !$0.value.isEmpty })
    }

    /// Goes back to `place`: its tab and its tabs' pages. Settings and the player are left as they are.
    public func restore(_ place: SavedPlace) {
        for (tab, path) in place.paths where SavedPlace.kept(tab) { setPath(path, for: tab) }
        if SavedPlace.kept(place.tab) { tab = place.tab }
    }
}
