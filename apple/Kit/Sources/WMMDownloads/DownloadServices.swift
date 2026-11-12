import Foundation
import UserNotifications
import WMMModel

/// Offline downloads for the iPhone and iPad app (PLAN-DOWNLOADS.md D3),
/// made once at launch so the background sessions reconnect:
///
/// ```swift
/// let downloads = DownloadServices.live(gateway: data.offline, online: APIPlaybackGateway(api: { try data.api() }), client: { data.client }, profile: { profile.current() })
/// let playback = PlaybackEngine(gateway: downloads.playback, …)
/// let services = data.makeAppServices(playback: playback, downloads: downloads.repository)
/// ```
@MainActor
public struct DownloadServices {
    public let repository: RealDownloadRepository
    /// The player's gateway: a downloaded item plays from the device, everything else from `online`.
    public let playback: any PlaybackGateway

    #if os(iOS) || os(macOS)
    /// Downloads kept in `Library/Application Support/Downloads`, fetched by the system's background sessions.
    public static func live(
        gateway: any OfflineGateway,
        online: any PlaybackGateway,
        client: @escaping @MainActor () -> ClientInfo,
        profile: @escaping @MainActor () -> PlaybackProfile,
        deviceKind: String
    ) -> DownloadServices {
        let folder = URL.applicationSupportDirectory.appending(path: "Downloads")
        let repository = RealDownloadRepository(
            store: FileOfflineStore(folder: folder), gateway: gateway, engine: SystemDownloadEngine(folder: folder),
            client: client, profile: profile, notices: SystemDownloadNotices(), deviceKind: deviceKind
        )
        return DownloadServices(repository: repository, playback: OfflinePlaybackGateway(online: online, downloads: repository))
    }

    /// The system woke the app for a download session's events
    /// (`application(_:handleEventsForBackgroundURLSession:completionHandler:)`):
    /// `completion` runs once they are delivered. False for another session.
    public static func handleEvents(identifier: String, completion: @escaping () -> Void) -> Bool {
        guard identifier.hasPrefix("media.watchmy.client.downloads") else { return false }
        SystemDownloadEngine.completions[identifier] = completion
        return true
    }
    #endif
}

/// *Downloaded*, *Couldn't download* and a revoked download's notice, as local
/// notifications once the person allowed them (asked when the first download starts).
@MainActor
final class SystemDownloadNotices: DownloadNotices {
    func firstDownload() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func downloaded(_ title: String) { post("Downloaded", title) }

    func failed(_ title: String) { post("Couldn’t download", title) }

    func removedByAdmin(_ title: String) { post(title, "Your server’s admin removed this download") }

    private func post(_ title: String, _ body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
