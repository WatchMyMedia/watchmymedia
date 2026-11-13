import Foundation
import Network

/// Whether the device has a way to the network, as it changes, so the event
/// stream reconnects the moment one returns (PLAN-APPLE-REVIEW.md §4.1.7)
/// rather than at its next backoff, up to a minute later. Tests drive their own.
protocol NetworkPath: Sendable {
    /// True while a path is satisfied; the current state first, then each change.
    func updates() -> AsyncStream<Bool>
}

/// The system's path, from `NWPathMonitor`.
struct SystemNetworkPath: NetworkPath {
    func updates() -> AsyncStream<Bool> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { continuation.yield($0.status == .satisfied) }
            continuation.onTermination = { _ in monitor.cancel() }
            monitor.start(queue: DispatchQueue(label: "media.watchmy.client.network-path"))
        }
    }
}
