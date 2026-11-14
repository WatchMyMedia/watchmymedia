import SwiftUI
import TVServices
import WMMFeatures
import WMMModel
import WMMTopShelf

/// The Top Shelf (TvTopShelfSections.dc.html, PLAN-APPLE-REVIEW.md §6.2):
/// Home publishes Continue watching and Recently added into the App Group
/// the app shares with its extension (`apple/TopShelf`), with each picture
/// saved there as a file, and tells the system; signing out or switching
/// server or person clears it. The rules are the Kit's (``TopShelfPublisher``,
/// ``TopShelfRules``).
@MainActor
enum TopShelf {
    static let publisher = TopShelfPublisher(store: .appGroup(), notify: { TVTopShelfContentProvider.topShelfContentDidChange() })

    /// Who is signed in, as the shelf knows them; nil when no one is.
    static func owner(_ services: AppServices) -> TopShelfOwner? {
        guard let server = services.servers.current?.server.id, let user = services.auth.user else { return nil }
        return TopShelfOwner(serverID: server, userID: user.id)
    }
}

extension View {
    /// Puts Home's rows on the Top Shelf whenever they change (Home's own hook).
    func publishesTopShelf(_ home: HomeData?) -> some View {
        modifier(PublishesTopShelf(home: home))
    }

    /// Clears the Top Shelf when the person signs out or another person or
    /// server is signed in, and at launch when what it shows isn't theirs.
    func clearsTopShelfOnAccountChange(_ services: AppServices) -> some View {
        task(id: TopShelf.owner(services)) { TopShelf.publisher.signedIn(TopShelf.owner(services)) }
    }
}

private struct PublishesTopShelf: ViewModifier {
    let home: HomeData?
    @Environment(AppServices.self) private var services

    private struct Key: Equatable {
        let plan: TopShelfPlan?
        let owner: TopShelfOwner?
    }

    private var key: Key {
        let owner = TopShelf.owner(services)
        return Key(plan: home.flatMap { home in owner.map { TopShelfPlan(home, serverID: $0.serverID) } }, owner: owner)
    }

    func body(content: Content) -> some View {
        // A newer Home cancels the publishing still loading pictures for the last.
        content.task(id: key) {
            let key = key
            guard let plan = key.plan, let owner = key.owner else { return }
            let library = services.library
            await TopShelf.publisher.publish(plan, owner: owner, images: services.images) { library.imageURL($0, size: $1) }
        }
    }
}
