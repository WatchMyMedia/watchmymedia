import Foundation
import WMMModel

/// What a library's grid was left showing (its sort and filters), as this
/// device keeps them, so Movies and TV Shows open the way the person left
/// them, as every app's libraries do. Kept for each server and person and
/// never sent anywhere; another device keeps its own. A row's See all opens
/// the grid its own way for that visit and changes nothing kept.
public struct KeptLibraryChoices: Hashable, Sendable {
    public var sort: ItemSort
    public var filter: ItemFilter
    public var genre: String?
    public var decade: Int?
    public var contentRatings: [String]

    public init(sort: ItemSort = .added, filter: ItemFilter = .all, genre: String? = nil, decade: Int? = nil, contentRatings: [String] = []) {
        self.sort = sort
        self.filter = filter
        self.genre = genre
        self.decade = decade
        self.contentRatings = contentRatings
    }
}

/// The store for ``KeptLibraryChoices`` on this device.
public final class LibraryChoices {
    private let defaults: UserDefaults
    private let key: String

    /// `scope` keeps one server's and one person's choices apart from
    /// another's: a server's genres and ratings are its own.
    public init(defaults: UserDefaults = .standard, scope: String) {
        self.defaults = defaults
        key = "library.choices.\(scope)"
    }

    /// What `type`'s grid was left showing; nil when nothing was chosen here yet.
    /// Anything unknown or malformed is left out.
    public func kept(_ type: LibraryType) -> KeptLibraryChoices? {
        guard let stored = (defaults.dictionary(forKey: key) as? [String: [String: Any]])?[type.rawValue] else { return nil }
        var kept = KeptLibraryChoices()
        if let sort = (stored["sort"] as? String).flatMap(ItemSort.init(rawValue:)) { kept.sort = sort }
        if let filter = (stored["filter"] as? String).flatMap(ItemFilter.init(rawValue:)) { kept.filter = filter }
        if let genre = stored["genre"] as? String, !genre.isEmpty, genre.count <= 64 { kept.genre = genre }
        if let decade = stored["decade"] as? Int, decade >= 0 { kept.decade = decade }
        if let ratings = stored["ratings"] as? [String] { kept.contentRatings = ratings.filter { !$0.isEmpty && $0.count <= 32 } }
        return kept
    }

    public func keep(_ choices: KeptLibraryChoices, for type: LibraryType) {
        var all = (defaults.dictionary(forKey: key) as? [String: [String: Any]]) ?? [:]
        var stored: [String: Any] = ["sort": choices.sort.rawValue, "filter": choices.filter.rawValue, "ratings": choices.contentRatings]
        if let genre = choices.genre { stored["genre"] = genre }
        if let decade = choices.decade { stored["decade"] = decade }
        all[type.rawValue] = stored
        defaults.set(all, forKey: key)
    }
}

extension LibraryChoices {
    /// This device's choices for the server in use and the person signed in to it.
    @MainActor public convenience init(services: AppServices, defaults: UserDefaults = .standard) {
        let person = services.auth.user.map { String($0.id) } ?? ""
        self.init(defaults: defaults, scope: "\(services.servers.current?.server.id ?? "")/\(person)")
    }
}
