import Foundation
import Observation
import WMMModel

/// Where a person in a cast row leads: their movies and shows in this
/// library, newest first, one card a title (GET /api/people/{personId}).
/// A card's line says the year, or that it is a show, and who they play
/// (on Apple TV, the focused card's alone).
/// Marks made elsewhere reload it, as on every screen showing those titles.
@MainActor
@Observable
public final class PersonModel {
    public let personID: Int
    /// The name the cast row showed, for the heading while the page loads.
    public let name: String
    public private(set) var state: Loadable<PersonDetail> = .loading
    public var message: String?
    public var notice: Notice?

    @ObservationIgnored private let library: any LibraryRepository
    @ObservationIgnored private let navigator: Navigator
    @ObservationIgnored private let changes: ScreenChanges
    @ObservationIgnored private let loads = LoadController<PersonDetail>()

    public init(library: any LibraryRepository, navigator: Navigator, personID: Int, name: String, now: @escaping @MainActor () -> Date = { Date() }) {
        self.library = library
        self.navigator = navigator
        self.personID = personID
        self.name = name
        changes = ScreenChanges(now: now)
    }

    /// The heading: the name the server has for them once it answers.
    public var heading: String { state.value?.name ?? name }

    /// Their movies and shows together, newest first: by year, the movies
    /// first in a year, then in the server's order.
    public var titles: [PersonTitle] {
        guard let person = state.value else { return [] }
        return Self.newestFirst(person)
    }

    static func newestFirst(_ person: PersonDetail) -> [PersonTitle] {
        let all = person.movies + person.shows
        return all.enumerated().sorted { a, b in
            let ya = a.element.item.year ?? 0, yb = b.element.item.year ?? 0
            return ya != yb ? ya > yb : a.offset < b.offset
        }.map(\.element)
    }

    /// Under the name: "9 titles in your library: 6 movies and 3 shows",
    /// "1 movie in your library".
    public var countLine: String? {
        guard let person = state.value else { return nil }
        return personCountLine(movies: person.movies.count, shows: person.shows.count)
    }

    /// Under the focused poster: "2024 · as Io Vance", "TV show · as Maren Holt".
    public func line(for title: PersonTitle) -> String {
        personTitleLine(title)
    }

    // MARK: Loading

    /// Loads the person's titles, then reloads quietly when one of them is
    /// marked or the library changes, until cancelled.
    public func run() async {
        await changes.run(
            subscribe: { library.libraryChanges() },
            hasContent: state.value != nil,
            load: { quiet in await load(quiet: quiet) },
            apply: { _ in await load(quiet: true) }
        )
    }

    public func retry() async {
        await load(quiet: false)
        changes.loaded()
    }

    // MARK: Taps

    /// Opens a title from the person's server: in place, when the page
    /// itself was opened from another server.
    public func open(_ title: PersonTitle) {
        notice = navigator.open(title.item, on: library)
    }

    public func setWatched(_ target: WatchTarget, _ watched: Bool) async {
        message = await markWatched(target, watched, in: library)
    }

    func load(quiet: Bool) async {
        await loads.run { [weak self] load in await self?.fetch(load, quiet: quiet) }
    }

    private func fetch(_ load: LoadController<PersonDetail>.Load, quiet: Bool) async {
        loads.begin(&state, quiet: quiet)
        guard let result = await loads.fetch(load, { try await self.library.person(id: self.personID) }) else { return }
        if let text = loads.quietFailure(result, quiet: quiet, over: state) { message = text } else { state = result }
    }
}

/// "9 titles in your library: 6 movies and 3 shows"; one kind alone is
/// "6 movies in your library".
public func personCountLine(movies: Int, shows: Int) -> String {
    switch (movies, shows) {
    case (0, 0): return "Nothing in your library"
    case (_, 0): return "\(plural(movies, "movie")) in your library"
    case (0, _): return "\(plural(shows, "show")) in your library"
    default: return "\(plural(movies + shows, "title")) in your library: \(plural(movies, "movie")) and \(plural(shows, "show"))"
    }
}

/// "2024 · as Io Vance" for a film, "TV show · as Maren Holt" for a show;
/// without a part, the year or "TV show" alone.
public func personTitleLine(_ title: PersonTitle) -> String {
    let lead = title.item.kind == .show ? "TV show" : title.item.year.map(String.init)
    let part = title.character.map { "as \($0)" }
    return [lead, part].compactMap { $0 }.joined(separator: " · ")
}

extension Person {
    /// A person whose page can open: the server knows who they are.
    public var opensPage: Bool { personID != nil }
}
