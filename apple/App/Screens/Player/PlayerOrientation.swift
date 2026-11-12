import SwiftUI
import UIKit

extension View {
    /// Landscape while this shows, and back to portrait when it goes, on
    /// iPhone. An iPad keeps turning with the device, under the system's
    /// rotation lock (PLAN-IOS.md §11.1), so this does nothing there.
    func landscapeWhileShown() -> some View { modifier(LandscapeLock()) }
}

private struct LandscapeLock: ViewModifier {
    func body(content: Content) -> some View {
        if UIDevice.current.userInterfaceIdiom == .pad {
            content
        } else {
            content
                .onAppear { OrientationLock.allow(.landscape) }
                .onDisappear { OrientationLock.allow(.portrait) }
        }
    }
}

/// Changes what ``AppDelegate`` allows and asks the window to turn now.
@MainActor
enum OrientationLock {
    static func allow(_ mask: UIInterfaceOrientationMask) {
        AppDelegate.orientations = mask
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows {
                var controller = window.rootViewController
                while let c = controller {
                    c.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = c.presentedViewController
                }
            }
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
        }
    }
}
