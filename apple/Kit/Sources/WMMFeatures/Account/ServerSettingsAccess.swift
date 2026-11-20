import Foundation
import WMMModel

// Server settings and the pages that aren't offered.
// Admin tools becomes Server settings, for admins
// only, and says what to expect in the web app; a page opened by a link when
// its row is hidden says why and who can change that.

/// Server settings' row for an admin, as it stands here.
public struct ServerSettingsRow: Hashable, Sendable {
    public var access: AdminAccess
    /// "Open Server settings".
    public var title: String
    /// "Libraries, people, Live TV, Plex and the rest".
    public var detail: String
    /// Off, and dimmed, where the server keeps admins out from here.
    public var enabled: Bool
    /// Under the row: what opening it does, or why it is off and what to do.
    public var note: String
    /// The note says why it is off, drawn as a warning.
    public var noteIsProblem: Bool

    /// The row for `access`; nil for a member, who has no Server settings.
    public init?(_ access: AdminAccess) {
        let note: String
        switch access {
        case .member: return nil
        case .home: note = "Opens the web app in Safari. Managing the server stays there; this app is for watching."
        case .away: note = "Opens the web app in Safari. You’re away from home, so it asks for your password before risky changes."
        case .keptOut(.network):
            note = "Off away from home on this server. Open them on your home network, or turn on Admin settings away from home under Security there."
        case .keptOut(.https):
            note = "Away from home, Server settings open only over HTTPS. Open the server at its https:// address, or from your home network or tailnet."
        }
        self.access = access
        title = SettingsWords.openServerSettings
        detail = SettingsWords.serverSettingsDetail
        enabled = { if case .keptOut = access { false } else { true } }()
        self.note = note
        noteIsProblem = !enabled
    }
}

/// Where Server settings open in the web app: its Settings, for Safari on
/// iPhone and iPad and for the code Apple TV shows.
public func serverSettingsURL(_ server: Server) -> URL {
    server.baseURL.appending(path: "settings")
}

/// Apple TV's card beside the code: Server
/// settings are in the web app, at this address.
public func tvServerSettingsNotice(_ server: Server, access: AdminAccess) -> SettingsNotice? {
    guard let row = ServerSettingsRow(access) else { return nil }
    let open = "\(row.detail). Scan to open them on your phone, or go to \(server.displayAddress) in a browser."
    let body: String = switch access {
    case .keptOut: "\(open) \(row.note)"
    default: "\(open) Away from home, it may ask for your password."
    }
    return SettingsNotice(title: "Server settings are in the web app", body: body)
}

extension SettingsModel {
    /// Server settings' row for an admin, once ``loadAdminAccess()`` has
    /// asked the server; nil for a member, and until then.
    public var serverSettingsRow: ServerSettingsRow? { adminAccess.flatMap(ServerSettingsRow.init) }

    /// The Server page's line under its title: "Living room server, v1.4.5. You’re signed in as Sam, an admin."
    public var serverLede: String? { server.map { SettingsWords.serverLede($0, user: user) } }
}

// MARK: - Kept out

/// What a page needs from the server that it doesn't have.
public enum SettingsFeature: Hashable, Sendable, CaseIterable {
    case languages, requests, downloads, homeRows, notifications

    /// The API level it comes with.
    public var level: Int {
        switch self {
        case .languages: ServerFeatures.Level.subtitlePreferences
        case .requests: ServerFeatures.Level.requests
        case .downloads: ServerFeatures.Level.offline
        case .homeRows: ServerFeatures.Level.rows
        case .notifications: ServerFeatures.Level.follows
        }
    }

    /// The first server release with all of it, as people read versions.
    /// Audio languages (level 21) came with 1.1.0, which is what the page
    /// asks for, though subtitles alone come at level 18.
    public var needsVersion: String {
        switch self {
        case .languages: "1.1.0"
        case .requests: "0.4.6"
        case .downloads: "0.5.0"
        case .homeRows: "1.2.0"
        // The release after 1.4.7, the last before API level 29.
        case .notifications: "1.5.0"
        }
    }

    /// "Choosing audio and subtitle languages", the subject of "needs v1.1.0 or newer".
    var doing: String {
        switch self {
        case .languages: "Choosing audio and subtitle languages"
        case .requests: "Asking for movies and shows"
        case .downloads: "Downloading to watch offline"
        case .homeRows: "Arranging Home’s rows"
        case .notifications: "Getting notified of new episodes"
        }
    }

    /// What works meanwhile.
    var meanwhile: String? {
        switch self {
        case .languages: "Until then, pick tracks in the player."
        case .requests: nil
        case .downloads: "Streaming works as usual."
        case .homeRows: "Until then, Home shows its usual rows."
        case .notifications: nil
        }
    }
}

/// Why a page opened by a link isn't offered here.
public enum KeptOutReason: Hashable, Sendable {
    /// Nothing on the server takes requests.
    case requestsOff
    /// The server lets only some people download, and not this account.
    case downloadsOff
    /// The server is older than the feature: its name and version.
    case serverTooOld(SettingsFeature, serverName: String, version: String)

    /// The page's words: what is off, why, and who can change it.
    public var words: KeptOutWords {
        switch self {
        case .requestsOff: SettingsWords.requestsOff
        case .downloadsOff: SettingsWords.downloadsOff
        case .serverTooOld(let feature, let name, let version):
            SettingsWords.serverTooOld(serverName: name, version: version, doing: feature.doing, needs: feature.needsVersion, meanwhile: feature.meanwhile)
        }
    }
}

/// Why `destination` isn't offered on `server` to this account, or nil when
/// it is. Ask once what the server offers has loaded (``RequestsRepository/refreshOptions()``),
/// since ``RequestOptions/none`` stands in before then.
/// - Parameters:
///   - requests: what the account may do with requests.
///   - downloadsEnabled: ``DownloadRepository/enabled``.
public func keptOutReason(_ destination: SettingsDestination, server: Server?, requests: RequestOptions = .none,
                          downloadsEnabled: Bool = true) -> KeptOutReason? {
    guard let server else { return nil }
    let features = server.features
    func tooOld(_ feature: SettingsFeature) -> KeptOutReason {
        .serverTooOld(feature, serverName: server.name, version: server.version)
    }
    switch destination {
    case .languages: return features.subtitlePreferences ? nil : tooOld(.languages)
    case .homeRows: return features.rows ? nil : tooOld(.homeRows)
    case .requests:
        if !features.requests { return tooOld(.requests) }
        return requests.hasYourRequests ? nil : .requestsOff
    case .downloads:
        if !features.offline { return tooOld(.downloads) }
        return downloadsEnabled ? nil : .downloadsOff
    case .playback, .account, .server, .inbox, .about:
        return nil
    case .notifications:
        // `-settingsPage notifications` and an iPad's kept selection can still open it.
        return features.follows ? nil : tooOld(.notifications)
    }
}
