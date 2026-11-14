#if canImport(TVServices)
import Foundation
import TVServices

extension TopShelfStore {
    /// The shelf the system draws from the snapshot (TvTopShelfSections.dc.html):
    /// a section per row with its heading, 16:9 cards with the system's
    /// progress bar for Continue watching, posters for Recently added, each
    /// card's picture from its file in the App Group, Select opening the page
    /// and Play/Pause playing. Nil (the app's static image) when there is no
    /// snapshot to show (``current(now:)``). What the Top Shelf extension returns.
    public func topShelfContent(now: Date = Date()) -> TVTopShelfSectionedContent? {
        guard let snapshot = current(now: now) else { return nil }
        let sections = snapshot.sections.map { section in
            let collection = TVTopShelfItemCollection(items: section.items.map(sectionedItem))
            collection.title = section.title
            return collection
        }
        return TVTopShelfSectionedContent(sections: sections)
    }

    private func sectionedItem(_ item: TopShelfItem) -> TVTopShelfSectionedItem {
        let card = TVTopShelfSectionedItem(identifier: item.id)
        card.title = item.title
        card.imageShape = item.shape == .wide ? .hdtv : .poster
        // One file for both scales, set for each: a URL set for the two
        // traits together is kept under that combination, which no screen has.
        if let name = item.picture, let file = pictureURL(name) {
            card.setImageURL(file, for: .screenScale1x)
            card.setImageURL(file, for: .screenScale2x)
        }
        if let progress = item.progress {
            card.playbackProgress = progress
        }
        card.displayAction = TVTopShelfAction(url: item.open)
        card.playAction = TVTopShelfAction(url: item.play)
        return card
    }
}
#endif
