import Foundation
import WMMModel

// The Apple TV grid's four controls on one line with its heading: Sort,
// Unwatched, Genre and More filters, which holds the rest of the filters
// and the decade. The phone and iPad keep their chips; these are the TV's.

/// What the Apple TV grid's controls say.
public enum TVLibraryWords {
    public static let unwatched = "Unwatched"
    public static let genre = "Genre"
    public static let anyGenre = "Any genre"
    public static let moreFilters = "More filters"
    public static let decade = "Decade"
    public static let anyDecade = "Any decade"
    public static let clearFilters = "Clear filters"
    public static let rating = "Rating"
}

/// A content rating as a person reads it: the server's "unrated" is "Not rated".
public func contentRatingName(_ rating: String) -> String {
    rating == unratedContentRating ? "Not rated" : rating
}

extension LibraryModel {
    /// The grid's heading: "All movies" under the person's rows, else "Movies".
    public var gridHeading: String { canEditRows ? allTitle : title }

    /// Beside the grid's heading: the whole library ("412 movies", with "6
    /// added this month" where the server counts it), or what matches ("3
    /// shows in Sci-Fi"). No episode count or size: those are for whoever
    /// runs the server, on the web.
    public var gridCountLine: String? {
        if isFiltered { return resultLine }
        guard let count = headerCount else { return nil }
        guard let added = state.value?.summary?.addedThisMonth, added > 0 else { return count }
        return "\(count) · \(formatCount(added)) added this month"
    }

    /// The Unwatched chip: "Unwatched · 38" where the server counts what the
    /// person hasn't finished (API level 34), else "Unwatched".
    public var unwatchedChipLabel: String {
        guard let count = state.value?.summary?.unwatched else { return TVLibraryWords.unwatched }
        return "\(TVLibraryWords.unwatched) · \(formatCount(count))"
    }

    /// A rating's choice in More filters: "PG-13", with how many titles it
    /// has under the other filters where the server says.
    public func ratingChoiceLabel(_ rating: String) -> String {
        guard let count = ratingCount(rating) else { return contentRatingName(rating) }
        return "\(contentRatingName(rating)) · \(formatCount(count))"
    }

    /// More filters' chip: "More filters", or with one rating chosen "More
    /// filters · PG-13", or with several "More filters · 2 ratings".
    public var moreFiltersLabel: String {
        switch contentRatings.count {
        case 0: TVLibraryWords.moreFilters
        case 1: "\(TVLibraryWords.moreFilters) · \(contentRatingName(contentRatings[0]))"
        default: "\(TVLibraryWords.moreFilters) · \(contentRatings.count) ratings"
        }
    }

    /// The Unwatched chip is on.
    public var unwatchedOnly: Bool { filter == .unwatched }

    /// The Unwatched chip: on narrows the grid to what isn't watched, off shows it all.
    public func setUnwatchedOnly(_ on: Bool) async { await setFilter(on ? .unwatched : .all) }

    /// The filters More filters holds: every one but All and Unwatched (for
    /// TV, Continuing and Ended, then 4K and HDR).
    public var moreFilters: [ItemFilter] { filters.filter { $0 != .all && $0 != .unwatched } }

    /// More filters has something chosen: a filter it holds, a decade or a rating.
    public var moreFiltersOn: Bool { moreFilters.contains(filter) || decade != nil || !contentRatings.isEmpty }

    /// A filter in More filters turned on or off; off shows the whole library again.
    public func setMoreFilter(_ filter: ItemFilter, on: Bool) async {
        if on { await setFilter(filter) } else if self.filter == filter { await setFilter(.all) }
    }

    /// The Genre chip: "Genre", or the genre chosen.
    public var genreChipLabel: String { genre ?? TVLibraryWords.genre }

    /// A genre chosen from the Genre menu; nil is Any genre.
    public func chooseGenre(_ genre: String?) async {
        guard genre != self.genre else { return }
        if let genre { await toggleGenre(genre) } else if let current = self.genre { await toggleGenre(current) }
    }

    /// A decade chosen in More filters; nil is Any decade.
    public func chooseDecade(_ decade: Int?) async {
        guard decade != self.decade else { return }
        if let decade { await toggleDecade(decade) } else if let current = self.decade { await toggleDecade(current) }
    }
}
