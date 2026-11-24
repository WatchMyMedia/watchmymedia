import Foundation
import WMMModel

extension ServerRepository {
    /// The servers one card asks besides the one in use: registered, signed
    /// in and not too old, in the Servers list's order, a server away from
    /// home last.
    public var otherCardServers: [CardServer] {
        let current = current?.server.id
        let others = registered.filter { $0.server.id != current && $0.signedIn && !$0.server.tooOld }.map { $0.cardServer(current: current) }
        return others.filter { !$0.away } + others.filter(\.away)
    }

    /// The server in use as one card names it.
    public var currentCardServer: CardServer? {
        current.map { $0.cardServer(current: $0.server.id) }
    }
}
