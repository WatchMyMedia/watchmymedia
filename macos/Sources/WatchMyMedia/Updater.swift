import Combine
import Foundation
import Sparkle

/// Sparkle, switched on only when the bundle carries a feed URL and public
/// key: build-app.sh writes SUFeedURL and SUPublicEDKey into Info.plist from
/// SPARKLE_FEED_URL and SPARKLE_PUBLIC_KEY. Without them the menu shows no
/// update item and nothing phones home.
@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()

    @Published private(set) var canCheck = false
    private let controller: SPUStandardUpdaterController?
    private var observation: AnyCancellable?

    private init() {
        guard Bundle.main.object(forInfoDictionaryKey: "SUFeedURL") != nil else {
            controller = nil
            return
        }
        let c = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: nil, userDriverDelegate: nil)
        controller = c
        observation = c.updater.publisher(for: \.canCheckForUpdates).sink { [weak self] can in
            Task { @MainActor in self?.canCheck = can }
        }
    }

    var isAvailable: Bool { controller != nil }

    func check() {
        controller?.updater.checkForUpdates()
    }
}
