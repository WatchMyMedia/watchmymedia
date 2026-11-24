import SwiftUI
import WMMFeatures
import WMMModel

/// A page from another server signed in to, opened in place: what it holds
/// talks to that server alone, with its sign-in (``AppServices/on(_:)``), and
/// the app stays on the server in use. A server no longer signed in to says so.
struct OnServerPage<Content: View>: View {
    let serverID: String
    @ViewBuilder let content: () -> Content
    @Environment(AppServices.self) private var services

    var body: some View {
        let scoped = services.on(serverID)
        if scoped.scope == serverID {
            content().environment(scoped)
        } else {
            let words = OneCardWords.unavailable(OneCardWords.name(of: serverID, in: services.servers))
            TVEmptyState(systemImage: "server.rack", title: words.title, message: words.message)
        }
    }
}
