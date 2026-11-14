import SwiftUI
import UIKit

extension View {
    /// Landscape while this shows, and back to portrait when it goes, on
    /// iPhone. An iPad keeps turning with the device, under the system's
    /// rotation lock (PLAN-IOS.md §11.1), so this does nothing there.
    func landscapeWhileShown() -> some View { orientationsWhileShown(.landscape) }

    /// `mask` while this shows (the film player: landscape; Live TV: any way
    /// up but upside down), and back to portrait when it goes, on iPhone.
    func orientationsWhileShown(_ mask: UIInterfaceOrientationMask) -> some View { modifier(OrientationsWhileShown(mask: mask)) }
}

private struct OrientationsWhileShown: ViewModifier {
    let mask: UIInterfaceOrientationMask

    func body(content: Content) -> some View {
        if UIDevice.current.userInterfaceIdiom == .pad {
            content
        } else {
            content
                .onAppear { OrientationLock.allow(mask) }
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
