import Foundation
import WMMModel
import WMMTopShelf

/// What the Apple TV's Top Shelf shows, made from Home (TvTopShelfSections.dc.html,
/// PLAN-APPLE-REVIEW.md §6.2): Continue watching as 16:9 cards with the
/// system's progress bar, and Recently added as posters, movies and shows in
/// turn. A row the person hid from Home isn't on the shelf either. Each card
/// carries two links the app publishes for its server (§2.5): Select opens
/// the page, Play/Pause plays it, as Play/Pause on the card in the app does
/// (``CardPlayPause``: a show's card opens, having no single episode to play).
public struct TopShelfPlan: Hashable, Sendable {
    public struct Card: Hashable, Sendable {
        /// The card, without its picture's file yet.
        public var item: TopShelfItem
        /// The server's image path and size the picture is made from.
        public var picturePath: String?
        public var pictureSize: ImageSize
    }

    public struct Section: Hashable, Sendable {
        public var kind: TopShelfSection.Kind
        public var cards: [Card]
    }

    public var sections: [Section]

    public var isEmpty: Bool { sections.allSatisfy(\.cards.isEmpty) }

    /// The shelf for `home`, its links published for `serverID`.
    public init(_ home: HomeData, serverID: String) {
        func published(_ link: DeepLink) -> URL { link.url(publishedFor: serverID, source: TopShelfRules.linkSource) }

        let continuing: [ContinueItem]
        let movies: [BrowseItem], shows: [BrowseItem]
        if let rows = home.rows {
            continuing = rows.first { $0.id == "continue" && $0.style == .continue }?.continueItems ?? []
            movies = rows.first { $0.id == "recentlyAddedMovies" }?.items ?? []
            shows = rows.first { $0.id == "recentlyAddedShows" }?.items ?? []
        } else {
            continuing = home.continueWatching
            movies = home.recentlyAddedMovies
            shows = home.recentlyAddedShows
        }

        let continueCards = continuing.prefix(TopShelfRules.limit(.continueWatching)).map { item in
            let open: DeepLink = if let movieID = item.movieID {
                .movie(id: movieID)
            } else if let showID = item.showID {
                .show(id: showID, season: item.season, episode: item.episodeID)
            } else {
                .play(fileID: item.fileID)
            }
            return Card(
                item: TopShelfItem(id: "continue-\(item.fileID)", title: item.topShelfTitle, shape: .wide, progress: item.progress,
                                   open: published(open), play: published(.play(fileID: item.fileID))),
                picturePath: item.backdropPath ?? item.stillPath, pictureSize: .w780
            )
        }

        // Movies and shows in turn, movies first, as the board draws them. An
        // unmatched file has no page to open, so it isn't on the shelf.
        let addedMovies = movies.filter { $0.kind == .movie }, addedShows = shows.filter { $0.kind == .show }
        var added: [BrowseItem] = []
        for i in 0..<max(addedMovies.count, addedShows.count) {
            if i < addedMovies.count { added.append(addedMovies[i]) }
            if i < addedShows.count { added.append(addedShows[i]) }
        }
        var seen = Set<String>()
        let addedCards: [Card] = added.compactMap { item in
            let open: DeepLink, play: DeepLink
            switch item.kind {
            case .movie:
                open = .movie(id: item.id)
                play = .playMovie(id: item.id)
            case .show:
                open = .show(id: item.id, season: item.episodeID == nil ? nil : item.seasonNumber, episode: item.episodeID)
                play = open
            case .unmatched:
                return nil
            }
            let id = "\(item.kind.rawValue)-\(item.id)"
            guard seen.insert(id).inserted else { return nil }
            return Card(
                item: TopShelfItem(id: id, title: item.title, shape: .poster, open: published(open), play: published(play)),
                picturePath: item.posterPath, pictureSize: .w500
            )
        }

        sections = [
            Section(kind: .continueWatching, cards: Array(continueCards)),
            Section(kind: .recentlyAdded, cards: Array(addedCards.prefix(TopShelfRules.limit(.recentlyAdded)))),
        ].filter { !$0.cards.isEmpty }
    }
}

extension ContinueItem {
    /// The card's title on the Top Shelf, which the system draws under the
    /// focused card: "The Quiet Meridian · S2, E4", or the movie's title
    /// (TvTopShelfSections.dc.html).
    public var topShelfTitle: String {
        guard kind == .episode, let season, let episode else { return title }
        return "\(title) · S\(season), E\(episode)"
    }
}

/// Writes the Top Shelf's snapshot into the App Group whenever Home's rows
/// change, saving each picture as a file there (PLAN-APPLE-REVIEW.md §2.2),
/// and clears it when the person signs out or another person or server is
/// signed in. The pictures are fetched with the app's own sign-in and saved
/// as bytes: no address, token or image URL goes into the group, so the
/// extension and the system see none (``TopShelfRules``).
@MainActor
public final class TopShelfPublisher {
    private let store: TopShelfStore?
    private let notify: @MainActor () -> Void
    private let now: @MainActor () -> Date
    private var owner: TopShelfOwner?
    /// What was last written for ``owner``, and when.
    private var published: (plan: TopShelfPlan, at: Date)?

    /// A plan already written is written again after this, so a shelf
    /// left on screen for days doesn't age out (``TopShelfRules/maxAge``).
    static let rewriteAfter: TimeInterval = 24 * 60 * 60

    /// - Parameters:
    ///   - store: the App Group's folder; nil (no App Group) publishes nothing.
    ///   - notify: tells the system the shelf changed (`TVTopShelfContentProvider.topShelfContentDidChange()`).
    public init(store: TopShelfStore?, notify: @escaping @MainActor () -> Void, now: @escaping @MainActor () -> Date = { Date() }) {
        self.store = store
        self.notify = notify
        self.now = now
    }

    /// Who is signed in now; nil when no one is. A snapshot that isn't
    /// theirs (a sign-out, another person, another server) is cleared, and
    /// the system shows the app's static image until Home publishes theirs.
    public func signedIn(_ owner: TopShelfOwner?) {
        if owner != self.owner {
            self.owner = owner
            published = nil
        }
        guard let store, let stored = store.storedOwner(), stored != owner?.key else { return }
        if store.clear() { notify() }
    }

    /// Publishes `plan` for `owner`: fetches the pictures it hasn't saved yet,
    /// then writes the snapshot and tells the system. Nothing is written if
    /// the person changed meanwhile or the task was cancelled (Home changed
    /// again). A plan with nothing in it clears the shelf.
    public func publish(_ plan: TopShelfPlan, owner: TopShelfOwner, images: any ImageLoader,
                        imageURL: @MainActor (String?, ImageSize) -> URL?) async {
        signedIn(owner)
        guard let store else { return }
        if let published, published.plan == plan, now().timeIntervalSince(published.at) < Self.rewriteAfter { return }
        if plan.isEmpty {
            published = (plan, now())
            if store.clear() { notify() }
            return
        }
        var sections: [TopShelfSection] = []
        for section in plan.sections {
            var items: [TopShelfItem] = []
            for card in section.cards {
                var item = card.item
                if let path = card.picturePath {
                    item.picture = await picture(path: path, size: card.pictureSize, store: store, images: images, imageURL: imageURL)
                }
                items.append(item)
            }
            sections.append(TopShelfSection(kind: section.kind, items: items))
        }
        guard self.owner == owner else {
            // Signed out or switched while the pictures loaded: none of theirs is kept.
            if store.storedOwner() == nil { store.clear() }
            return
        }
        guard !Task.isCancelled else { return }
        let at = now()
        do {
            try store.write(TopShelfSnapshot(writtenAt: at, owner: owner.key, sections: sections))
            published = (plan, at)
            notify()
        } catch {
            // The App Group couldn't be written; the shelf keeps what it had.
        }
    }

    /// The saved picture's file name, fetching and saving it first if needed; nil when it can't be had.
    private func picture(path: String, size: ImageSize, store: TopShelfStore, images: any ImageLoader,
                         imageURL: @MainActor (String?, ImageSize) -> URL?) async -> String? {
        if let saved = TopShelfRules.savedPictureName(path: path, size: size.rawValue, in: store) { return saved }
        guard let url = imageURL(path, size), let data = try? await images.data(for: url), !data.isEmpty, !Task.isCancelled else { return nil }
        let name = TopShelfRules.pictureName(path: path, size: size.rawValue, data: data)
        do {
            try store.savePicture(data, as: name)
            return name
        } catch {
            return nil
        }
    }
}
