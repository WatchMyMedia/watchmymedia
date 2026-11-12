import AppKit
import SwiftUI

@main
struct WatchMyMediaApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        MenuBarExtra {
            MenuContent(server: ServerController.shared)
        } label: {
            Image(nsImage: Self.menuIcon)
        }
        .menuBarExtraStyle(.menu)
    }

    /// The brand mark as a template image, so it follows the menu bar's
    /// light or dark appearance. Falls back to a symbol if the resource is
    /// missing (a `swift run` outside the app bundle).
    static let menuIcon: NSImage = {
        if let url = Bundle.main.url(forResource: "MenuBarIcon", withExtension: "png"), let image = NSImage(contentsOf: url) {
            image.isTemplate = true
            image.size = NSSize(width: 18, height: 18)
            return image
        }
        let image = NSImage(systemSymbolName: "play.tv", accessibilityDescription: "WatchMyMedia")!
        image.isTemplate = true
        return image
    }()
}

/// Starts the server when the app launches and stops it before the app
/// quits, however the quit was asked for (menu, Cmd-Q, log out).
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        ServerController.shared.start()
        _ = Updater.shared // starts Sparkle's scheduled checks when configured
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        ServerController.shared.shutdown {
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
