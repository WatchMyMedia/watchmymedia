import SwiftUI

/// The menu under the menu bar icon. Plain `Text` rows render as disabled
/// items, which is how menus show status.
struct MenuContent: View {
    @ObservedObject var server: ServerController
    @ObservedObject private var updater = Updater.shared

    var body: some View {
        Text("WatchMyMedia \(appVersion)")
        Text(statusLine)
        if server.setupRequired {
            Button("Finish setup…") { server.openInBrowser(path: "setup") }
                .disabled(!server.canOpenInBrowser)
        }
        if case .portInUse = server.state {
            Button("Use another port…") { server.useAnotherPort() }
        }
        Button("Open WatchMyMedia") { server.openInBrowser() }
            .keyboardShortcut("o")
            .disabled(!server.canOpenInBrowser)
        Divider()
        if !server.plays.isEmpty {
            Text(server.plays.count == 1 ? "1 play" : "\(server.plays.count) plays")
            ForEach(server.plays) { p in
                Text("\(p.user) · \(p.title) · \(modeLabel(p.mode))\(p.state == "paused" ? " · paused" : "")")
            }
            Button("Open Activity") { server.openInBrowser(path: "activity") }
                .disabled(!server.canOpenInBrowser)
        } else if server.sessions.isEmpty {
            Text("No streams playing")
        } else {
            // A server from before Activity reports only its ffmpeg sessions.
            Text(server.sessions.count == 1 ? "1 stream playing" : "\(server.sessions.count) streams playing")
            ForEach(server.sessions) { s in
                Text("\(s.mode) · \(s.encoder) · \(s.started, style: .relative)")
            }
        }
        Divider()
        Toggle("Launch at Login", isOn: Binding(
            get: { server.launchAtLogin },
            set: { server.setLaunchAtLogin($0) }))
        Button("Show Logs") { server.showLogs() }
        Button("Restart Server") { server.restart() }
        if updater.isAvailable {
            Button("Check for Updates…") { updater.check() }
                .disabled(!updater.canCheck)
        }
        Divider()
        Button("Quit WatchMyMedia") { server.quit() }
            .keyboardShortcut("q")
    }

    private func modeLabel(_ mode: String) -> String {
        switch mode {
        case "direct": return "Direct play"
        case "remux": return "Direct stream"
        case "audio": return "Audio transcode"
        case "transcode": return "Transcode"
        case "recording": return "Recording"
        default: return mode
        }
    }

    /// The release this app is, which build-app.sh writes into Info.plist
    /// from the tag; "dev" when run outside a bundle.
    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    private var statusLine: String {
        switch server.state {
        case .stopped: return "Server stopped"
        case .portInUse(let port): return "Port \(port) is in use by another app"
        case .starting: return "Server starting…"
        case .running(let version):
            let recordingCount = server.plays.filter { $0.mode == "recording" }.count
            if recordingCount > 0 { return "Recording · \(recordingCount) \(recordingCount == 1 ? "programme" : "programmes") · port \(server.port)" }
            // The server is built with the app, so its version (with the
            // tag's "v") shows only when the two differ.
            if version.trimmingPrefix("v") == appVersion {
                return "Server running · port \(server.port)"
            }
            return "Server running · \(version) · port \(server.port)"
        case .failed(let why): return "Server failed: \(why)"
        }
    }
}
