import Foundation

// How arranged rows and lists are worded, word for word as
// the boards say it, the same on iPhone, iPad and Apple TV.

/// Under Add a row's note (``listsMadeOnTheWeb``) on iPhone and iPad.
public let listsMadeOnTheWebDetail = "A list made there shows up here, ready to add."

/// "From TMDB", "From MDBList", "From your library", "From an import"; nil for a source this app doesn't know.
public func listSourceLine(kind: String) -> String? {
    switch kind {
    case "tmdb": "From TMDB"
    case "mdblist": "From MDBList"
    case "library": "From your library"
    case "import": "From an import"
    default: nil
    }
}

/// "18 of 50 in your library", or "Nothing on this list yet".
public func listCountLine(inLibrary: Int, total: Int) -> String {
    total > 0 ? "\(formatCount(inLibrary)) of \(formatCount(total)) in your library" : "Nothing on this list yet"
}

/// Whose list it is: "Your list" or "For everyone".
public func listOwnerLine(_ owner: RowLayoutEntry.Owner?) -> String? {
    switch owner {
    case .me: "Your list"
    case .everyone: "For everyone"
    case nil: nil
    }
}

/// "Movies", "TV Shows"; nil for a list of both.
public func listMediaTypeLine(_ mediaType: String) -> String? {
    switch mediaType {
    case "movie": "Movies"
    case "tv": "TV Shows"
    default: nil
    }
}

extension MediaList {
    /// "From TMDB", "From an IMDb export": the server's name when it sends one, else by kind.
    public var sourceLine: String? {
        if isWatchlist { return nil }
        if let sourceName, !sourceName.isEmpty { return "From \(sourceName)" }
        return listSourceLine(kind: sourceKind)
    }

    /// "18 of 50", for a row's line; nil while the library has none of it, and
    /// for a list of the library's own titles.
    public var shortCount: String? {
        guard inLibrary > 0, entries > 0, sourceKind != "library" else { return nil }
        return "\(formatCount(inLibrary)) of \(formatCount(entries))"
    }

    /// When it was last read: "Refreshing…", "Updated 3 h ago", or an
    /// import's "Imported Nov 2". Nothing for a watchlist, which is never fetched.
    public func whenLine(now: Date = .now, calendar: Calendar = .current) -> String? {
        guard !isWatchlist else { return nil }
        if fetching { return "Refreshing…" }
        if sourceKind == "import", let made = createdAt ?? fetchedAt {
            return "Imported \(shortDate(made, now: now, calendar: calendar))"
        }
        return fetchedAt.map { "Updated \(formatAgo($0, now: now))" }
    }

    /// "18 of 50 in your library · For everyone"; an import adds what TMDB
    /// couldn't find: "9 of 14 in your library · 2 not found · Your list". A
    /// watchlist: "12 titles · 9 in your library".
    public func countsLine(total: Int? = nil, inLibrary: Int? = nil) -> String {
        if isWatchlist { return WatchlistWords.counts(titles: total ?? entries, inLibrary: inLibrary ?? self.inLibrary) }
        return [
            listCountLine(inLibrary: inLibrary ?? self.inLibrary, total: total ?? entries),
            notFound > 0 ? "\(formatCount(notFound)) not found" : nil,
            listOwnerLine(owner),
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// Why the last fetch failed, over the list it kept:
    /// "Couldn’t update this list: MDBList refused the key. This is the list from 3 days ago."
    public func failureLine(now: Date = .now) -> String? {
        guard let error, !error.isEmpty, !fetching else { return nil }
        let reason = error.hasSuffix(".") ? String(error.dropLast()) : error
        guard let fetchedAt else { return "Couldn’t update this list: \(reason)." }
        return "Couldn’t update this list: \(reason). This is the list from \(formatAgo(fetchedAt, now: now))."
    }
}

/// "Nov 2", or "Nov 2, 2025" in another year.
private func shortDate(_ date: Date, now: Date, calendar: Calendar) -> String {
    var style = Date.FormatStyle.dateTime.month(.abbreviated).day()
    style.timeZone = calendar.timeZone
    if calendar.component(.year, from: date) != calendar.component(.year, from: now) { style = style.year() }
    return date.formatted(style)
}

extension MediaListEntry {
    /// Under a missing title: its year, then "Requested", "Downloading",
    /// "Not in your library", or "Not found" for an imported title TMDB
    /// couldn't find. The list page draws Request, Requested and Downloading
    /// as pills; this is the spoken and Apple TV line.
    public var missingLine: String {
        let state: String = if !found {
            "Not found"
        } else {
            switch requestState {
            case .requested: "Requested"
            case .downloading: "Downloading"
            case .available: "Available"
            case .requestable, nil: "Not in your library"
            }
        }
        return [year.map(String.init), state].compactMap { $0 }.joined(separator: " · ")
    }

    /// The card's spoken label: "3. Paper Moons, 2021", or with where a missing title stands.
    public var accessibilityLine: String { accessibilityLine(ranked: true) }

    /// A watchlist's cards have no rank: "Paper Moons, 2021".
    public func accessibilityLine(ranked: Bool) -> String {
        let line = item == nil ? missingLine : year.map(String.init)
        return [ranked ? "\(rank). \(title)" : title, line].compactMap { $0 }.joined(separator: ", ")
    }
}

extension RowLayoutEntry {
    /// Why a row that isn't hidden isn't drawn, as the editor says it:
    /// "Not shown: nothing from this list is in your library yet".
    public var notShownLine: String? {
        guard !hidden, !shown, let note, !note.isEmpty else { return nil }
        // "Nothing from…" reads "nothing from…" after the colon; "TMDB…" stays as it is.
        let second = note.dropFirst().prefix(1)
        let first = second.lowercased() == second ? note.prefix(1).lowercased() : String(note.prefix(1))
        return "Not shown: " + first + note.dropFirst()
    }
}
