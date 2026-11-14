import TVServices
import WMMTopShelf

/// The Apple TV app's Top Shelf (TvTopShelfSections.dc.html, PLAN-APPLE-REVIEW.md
/// §6.2): Continue watching and Recently added, read from the snapshot the
/// app writes into the App Group whenever Home's rows change. The extension
/// only reads it: it has no sign-in, makes no request and knows no server's
/// address. With no snapshot to show (signed out, or the app not run for two
/// weeks) it returns nothing, and the system shows the app's static image.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        completionHandler(TopShelfStore.appGroup()?.topShelfContent())
    }
}
