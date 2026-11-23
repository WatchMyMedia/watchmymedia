import Foundation

// How watchlists are worded on iPhone, iPad and Apple TV, as the Android app
// words them. "Watchlist" with a capital is the first list's name ("On
// Watchlist"); the common noun has none ("Save to watchlist", "On 2
// watchlists"). The apps say "in your library", never "on the server".

public enum WatchlistWords {
    /// The account menu's row, and the page it opens.
    public static let title = "Watchlists"
    /// Over a watchlist's name on its page.
    public static let kicker = "Watchlist"
    /// The name the first watchlist is given.
    public static let firstName = "Watchlist"

    // MARK: The button

    /// "Watchlist" while no watchlist holds the title; "On Watchlist", "On
    /// Halloween" while one does; "On 2 watchlists" while more do. `names`
    /// are the holders' names where known.
    public static func button(holding: [Int], names: [Int: String]) -> String {
        switch holding.count {
        case 0: "Watchlist"
        case 1: "On \(names[holding[0]] ?? firstName)"
        default: "On \(formatCount(holding.count)) watchlists"
        }
    }

    /// Read out with the button, which is a toggle: "On Halloween, Ninety Fathoms".
    public static func buttonLabel(_ button: String, title: String) -> String { "\(button), \(title)" }

    // MARK: Messages

    /// "Saved to Watchlist".
    public static func saved(to name: String) -> String { "Saved to \(name)" }
    /// "Removed from Watchlist", or "Removed from 2 watchlists".
    public static func removed(from names: [String]) -> String {
        names.count == 1 ? "Removed from \(names[0])" : "Removed from \(formatCount(names.count)) watchlists"
    }
    public static let undo = "Undo"

    // MARK: Long-press menus

    /// With one watchlist (or none yet, when the press makes Watchlist): "Add to Watchlist".
    public static func add(to name: String?) -> String { "Add to \(name ?? firstName)" }
    /// "Remove from Watchlist".
    public static func remove(from name: String?) -> String { "Remove from \(name ?? firstName)" }
    /// With two or more watchlists, both the button and the menu open the picker.
    public static let saveToSeveral = "Save to watchlist…"

    // MARK: The picker

    public static let pickerTitle = "Save to watchlist"
    public static let newWatchlistRow = "New watchlist…"
    public static let newWatchlist = "New watchlist"
    public static let name = "Name"
    public static let cancel = "Cancel"
    public static let create = "Create"
    public static let done = "Done"
    /// Apple TV's line over the checks.
    public static let tvPickerHint = "Each watchlist is saved as you choose it."
    /// Apple TV's line under the Name field.
    public static func tvCreateHint(_ title: String) -> String { "Create makes the watchlist and saves \(title) to it" }

    /// "You already have a watchlist called Halloween."
    public static func nameTaken(_ name: String) -> String { "You already have a watchlist called \(name)." }
    /// In place of New watchlist… at the limit.
    public static func atLimit(_ max: Int) -> String { "You have \(formatCount(max)) watchlists, the most you can make." }

    // MARK: Watchlists and a watchlist's page

    /// "12 titles · 9 in your library", "1 title · 1 in your library", "0 titles".
    public static func counts(titles: Int, inLibrary: Int) -> String {
        let count = titles == 1 ? "1 title" : "\(formatCount(titles)) titles"
        return titles > 0 ? "\(count) · \(formatCount(inLibrary)) in your library" : count
    }
    public static let keepsWatched = "Keeps watched titles"
    /// Read out on a watchlist's row: "Halloween, 6 titles, 6 in your library, keeps watched titles".
    public static func rowLabel(_ list: Watchlist) -> String {
        let counts = counts(titles: list.titles, inLibrary: list.inLibrary).replacingOccurrences(of: " · ", with: ", ")
        return [list.name, counts, list.removeWatched ? nil : keepsWatched.lowercased()].compactMap { $0 }.joined(separator: ", ")
    }
    /// Apple TV's line under Watchlists: "Robin · 3 watchlists".
    public static func tvSummary(person: String, count: Int) -> String {
        "\(person) · \(count == 1 ? "1 watchlist" : "\(formatCount(count)) watchlists")"
    }

    public static let noneTitle = "No watchlists yet"
    public static let noneMessage = "Save a movie or show to watch later. Press Watchlist on its page."
    public static let emptyTitle = "Nothing on this watchlist yet"
    public static let emptyMessage = "Press Watchlist on a movie’s or show’s page to save it here."

    public static let rename = "Rename"
    public static let delete = "Delete"
    public static let removeWatched = "Remove titles once watched"
    public static let removeWatchedDetail = "A movie leaves when you finish it. A show leaves when you’ve watched every episode in your library."
    /// The ⋯ menu's label on iPhone and iPad.
    public static func moreLabel(_ name: String) -> String { "More for \(name)" }

    /// "Delete Halloween?"
    public static func deletePrompt(_ name: String) -> String { "Delete \(name)?" }
    /// "Its 6 titles stay in your library. This can’t be undone."
    public static func deleteMessage(titles: Int) -> String {
        let stay: String? = switch titles {
        case 0: nil
        case 1: "Its 1 title stays in your library."
        default: "Its \(formatCount(titles)) titles stay in your library."
        }
        return [stay, "This can’t be undone."].compactMap { $0 }.joined(separator: " ")
    }

    // MARK: Filters and sorts

    public static let all = "All"
    public static let movies = "Movies"
    public static let shows = "TV shows"

    public static func sortName(_ sort: ListSort) -> String {
        switch sort {
        case .added: "Date added"
        case .title: "Title"
        case .released: "Release year"
        }
    }

    /// A filter chip's words.
    public static func filterName(_ type: MediaType?) -> String {
        switch type {
        case nil: all
        case .movie?: movies
        case .tv?: shows
        }
    }

    // MARK: Apple TV's Servers

    public static let tvServersHint = "Select opens your watchlists"
}

/// Add a row's note: which lists are made on the web (a watchlist is made in the apps).
public let listsMadeOnTheWeb = "Lists from TMDB, MDBList and imports are made in WatchMyMedia on the web."
