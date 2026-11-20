import SwiftUI
import UIKit
import WMMUI

extension View {
    /// Landscape while this shows, and back to portrait when it goes, in a
    /// phone's window. A wide window (an iPad's, the iPhone Duo's inner
    /// screen) keeps turning with the device, under the system's rotation
    /// lock.
    func landscapeWhileShown() -> some View { orientationsWhileShown(.landscape) }

    /// `mask` while this shows (the film player: landscape; Live TV: any way
    /// up but upside down), and back to portrait when it goes, in a phone's
    /// window. Decided again when the window changes: the Duo closed is a
    /// phone's window, open a wide one.
    func orientationsWhileShown(_ mask: UIInterfaceOrientationMask) -> some View { modifier(OrientationsWhileShown(mask: mask)) }
}

extension PlayerWindow {
    init(horizontal: UserInterfaceSizeClass?, vertical: UserInterfaceSizeClass?) {
        self = .of(regularWidth: horizontal == .regular, regularHeight: vertical == .regular)
    }

    init(_ traits: UITraitCollection) {
        self = .of(regularWidth: traits.horizontalSizeClass == .regular, regularHeight: traits.verticalSizeClass == .regular)
    }
}

private struct OrientationsWhileShown: ViewModifier {
    let mask: UIInterfaceOrientationMask
    @Environment(\.horizontalSizeClass) private var horizontal
    @Environment(\.verticalSizeClass) private var vertical

    func body(content: Content) -> some View {
        let window = PlayerWindow(horizontal: horizontal, vertical: vertical)
        content
            .onChange(of: window, initial: true) { _, window in OrientationLock.allow(mask, in: window) }
            .onDisappear { OrientationLock.allow(.portrait, in: window) }
    }
}

/// Changes what ``AppDelegate`` allows a phone's window, and turns a
/// phone's window now. A wide window turns with the device and is asked for
/// nothing: iOS refuses to turn it from code ("The current windowing mode
/// does not allow for programmatic changes to interface orientation").
///
/// What a window asks for stays the scene's own preference after it turned,
/// and on the iPhone Duo it goes with the scene to the inner screen, which
/// then stays sideways whatever the device does (it ignores the app's
/// portrait lock, and refuses a new ask). So once a phone's window has
/// turned, the ask is put back to every way, and the mask in
/// ``AppDelegate`` alone keeps it turned (iOS 27.2 simulator).
@MainActor
enum OrientationLock {
    /// The orientations last asked of the window, and the attempt iOS last refused.
    private static var asked: UIInterfaceOrientationMask?
    private static var attempt = 0
    private static var refused = 0

    static func allow(_ mask: UIInterfaceOrientationMask, in kind: PlayerWindow) {
        AppDelegate.orientations = mask
        for scene in scenes {
            for window in scene.windows {
                var controller = window.rootViewController
                while let c = controller {
                    c.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = c.presentedViewController
                }
            }
        }
        if kind == .phone { request(mask, tries: 8) } else { settleWide() }
    }

    /// A wide window asks for nothing. An ask made in a phone's window that
    /// hasn't been put back yet (the Duo unfolded within ``request(_:tries:)``'s
    /// 400 ms) is put back to every way now, rather than going with the scene
    /// to the inner screen; iOS may refuse it there, which leaves things no worse.
    private static func settleWide() {
        guard let pending = asked else { return }
        asked = nil
        guard pending != .all else { return }
        for scene in scenes { scene.requestGeometryUpdate(.iOS(interfaceOrientations: .all)) }
    }

    private static var scenes: [UIWindowScene] {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    }

    /// Asks the window to turn, and again shortly while it is refused; once
    /// it has turned, puts the ask back to every way. While the player's
    /// cover is still presenting, iOS refuses ("None of the requested
    /// orientations are supported by the view controller"): an iPhone turns
    /// by itself once the mask leaves it no other way, but the iPhone Duo's
    /// outer screen stays as it is (iOS 27.2 simulator).
    private static func request(_ mask: UIInterfaceOrientationMask, tries: Int) {
        asked = mask
        attempt += 1
        let this = attempt
        for scene in scenes {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in
                Task { @MainActor in
                    // One retry for the attempt, however many scenes refused it.
                    guard refused != this else { return }
                    refused = this
                    guard tries > 1 else { return }
                    try? await Task.sleep(for: .milliseconds(250))
                    // Only while nothing has asked for something else since.
                    guard asked == mask else { return }
                    request(mask, tries: tries - 1)
                }
            }
        }
        guard mask != .all else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            guard refused != this, attempt == this, asked == mask else { return }
            asked = nil
            for scene in scenes { scene.requestGeometryUpdate(.iOS(interfaceOrientations: .all)) }
        }
    }
}
