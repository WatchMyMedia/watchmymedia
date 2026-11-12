import Foundation
import WMMModel

// The words the account screens (server picker, sign-in, Settings, Link a TV)
// share, worded as the Android phone app words them
// (android/mobile/.../servers, signin, settings, link), so the apps read alike.

/// The oldest server release this app works with, as the footers say
/// (Android's MIN_SERVER_VERSION); what it checks is ``requiredAPILevel``.
public let minimumServerVersion = "0.1.0"

/// "Needs server 0.1.0 or newer".
public let needsServerLine = "Needs server \(minimumServerVersion) or newer"

/// The sign-in footer: "Needs server 0.1.0 or newer · This device: Sam’s iPhone".
public func signInFooter(deviceName: String) -> String {
    let name = deviceName.trimmingCharacters(in: .whitespaces)
    return name.isEmpty ? needsServerLine : "\(needsServerLine) · This device: \(name)"
}

/// The privacy policy the app stores require a link to (Android's PRIVACY_POLICY_URL).
public let privacyPolicyURL = URL(string: "https://watchmy.media/privacy")!

/// The project's site, linked from About.
public let projectSiteURL = URL(string: "https://watchmy.media")!

public let tmdbCredit = "Metadata and artwork from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB."

// MARK: - Pairing codes

/// Pairing codes have six characters (PLAN-CLIENTS.md §3.2).
public let pairingCodeLength = 6

/// How long a pairing code lasts on the server.
public let pairingCodeLifetime: TimeInterval = 10 * 60

/// A code as typed loosely ("abc-234", " kq7 f2w "): capitals and digits only,
/// at most six.
public func normalizePairingCode(_ raw: String) -> String {
    String(raw.uppercased().filter { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }.prefix(pairingCodeLength))
}

/// "K7M 4PQ", how a code is shown; anything not six characters long as it is.
public func spacedCode(_ code: String) -> String { spacedPairingCode(code) }

/// What VoiceOver reads for a code: its characters one by one ("K 7 M 4 P Q").
public func spokenCode(_ code: String) -> String {
    code.map(String.init).joined(separator: " ")
}

/// The line under the pairing code while it waits: "Waiting for approval · the
/// code lasts 10 minutes", counting down in whole minutes.
public func pairingWaitingLine(expiresAt: Date, now: Date = .now) -> String {
    let left = expiresAt.timeIntervalSince(now)
    if left <= 0 { return "The code has run out" }
    let minutes = Int((left / 60).rounded(.up))
    return "Waiting for approval · the code lasts \(plural(minutes, "minute"))"
}

// MARK: - Errors

/// A wrong password (401) and throttling (429) get their own words; anything
/// else shows the server's message.
public func signInMessage(_ error: any Error) -> String {
    switch (error as? APIError)?.status {
    case 401: "Wrong name or password."
    case 429: "Too many tries. Wait a minute, then try again."
    default: message(error, fallback: "Couldn't sign in. Check the server is running, then try again.")
    }
}

/// Why connecting to `label` failed: too old says which version and what to
/// do; the server's or the network's words otherwise.
public func connectMessage(_ error: any Error, label: String) -> String {
    message(error, fallback: "Couldn't reach \(label). Check the address, and that the server is running and on this network.")
}

/// The sentence for `error`: the repository's (an ``APIError``, ``ServerTooOldError``), or `fallback`.
func message(_ error: any Error, fallback: String) -> String {
    if error is APIError || error is ServerTooOldError {
        let text = error.displayMessage.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { return text }
    }
    return fallback
}

/// Plain http to a host name (not an IP address or a .local name), which App
/// Transport Security refuses: the server picker says to use https.
public func needsHTTPSNudge(_ address: String) -> Bool {
    let a = address.trimmingCharacters(in: .whitespaces).lowercased()
    guard a.hasPrefix("http://") else { return false }
    let rest = a.dropFirst("http://".count)
    let host = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
    let name = host.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
    return name.contains(".") && !isLocalNetworkHost(name)
}

public let httpsNudge = "This address needs https: plain http only reaches an IP address or a .local name on your network."

// MARK: - Servers

/// A server version as people read it, such as "v0.4.1". Servers stamp their
/// version from the git tag, "v" included, and a build from source says
/// "dev", so a "v" is added only before a bare number.
public func versionLabel(_ version: String) -> String {
    version.first?.isNumber == true ? "v\(version)" : version
}

extension Server {
    /// "v0.9.3".
    public var versionLabel: String { WMMFeatures.versionLabel(version) }

    /// "v0.9.3 · API 2".
    public var versionAndAPI: String { "\(versionLabel) · API \(apiLevel)" }
}

extension DiscoveredServer {
    /// "192.168.1.20:27587": the address without the scheme.
    public var displayAddress: String {
        Server(id: id, name: name, baseURL: baseURL, version: version, apiLevel: apiLevel).displayAddress
    }
}

/// "signed in as Sam" or "not signed in".
public func accountLabel(_ saved: SavedServer?) -> String {
    if let saved, saved.signedIn, let name = saved.userName { return "signed in as \(name)" }
    return "not signed in"
}

// MARK: - Settings

extension StreamQuality {
    /// The quality checklist's title: "Automatic", "Original quality", "4K", "1080p".
    public var checklistTitle: String { self == .original ? label : shortLabel }

    /// The line under it: "Original first, lower when the connection can’t
    /// keep up", "Direct play when the file allows it", "8 Mbps".
    public var checklistDetail: String {
        switch self {
        case .auto: "Original first, lower when the connection can’t keep up"
        case .original: "Direct play when the file allows it"
        default: detail ?? ""
        }
    }
}

/// Under the quality checklist; it explains Automatic where the server offers it.
public func qualityFootnote(device: String, auto: Bool) -> String {
    auto
        ? "Remembered on this \(device). Automatic starts with the original and lowers the quality when your connection can’t keep up. A fixed lower quality has the server convert the video, which saves data away from home."
        : "Remembered on this \(device). A lower quality has the server convert the video, which saves data away from home."
}

/// The Playback row's value: "Automatic · autoplay on".
public func playbackSummary(quality: StreamQuality, autoplayNext: Bool) -> String {
    "\(quality.shortLabel) · autoplay \(autoplayNext ? "on" : "off")"
}

/// "4 signed in".
public func signedInCount(_ n: Int) -> String { "\(formatCount(n)) signed in" }

/// The account card's second line: "Admin · Living room server", or the server alone.
public func accountCardLine(user: User?, serverName: String?) -> String {
    [user?.isAdmin == true ? "Admin" : nil, serverName].compactMap { $0 }.joined(separator: " · ")
}

/// "Sam · admin", the Server page's Signed in as.
public func signedInAs(_ user: User?) -> String {
    guard let user else { return "Not signed in" }
    return user.isAdmin ? "\(user.name) · admin" : user.name
}

/// The line under Sign out: "Sam’s iPhone · signed in as Sam on Living room server".
public func settingsFooter(deviceName: String, user: User?, serverName: String?) -> String {
    var parts = [deviceName]
    if let user {
        parts.append(serverName.map { "signed in as \(user.name) on \($0)" } ?? "signed in as \(user.name)")
    }
    return parts.joined(separator: " · ")
}

/// "iPhone", "iPad" or "Apple TV", from the model ("iPhone 16", "iPad Air",
/// "Apple TV 4K"): This iPhone, the This-iPhone badge.
public func deviceFamily(model: String) -> String {
    if model.hasPrefix("iPad") { return "iPad" }
    if model.hasPrefix("Apple TV") { return "Apple TV" }
    return "iPhone"
}

extension DeviceSession {
    /// The line under a signed-in device: "iPhone 16 · now", "Google TV
    /// Streamer · 1 h ago", "Web browser · 100.101.7.2 · 2 days ago".
    public func detailLine(now: Date = .now) -> String {
        let what = model.flatMap { $0.isEmpty ? nil : $0 } ?? kindLabel
        let address = kind == .browser ? lastIP : nil
        return [what, address, current ? "now" : formatAgo(lastSeenAt, now: now)].compactMap { $0 }.joined(separator: " · ")
    }
}

/// This device first, then the most recently seen (Android's sortSessions).
public func sortSessions(_ list: [DeviceSession]) -> [DeviceSession] {
    list.sorted { a, b in
        if a.current != b.current { return a.current }
        return a.lastSeenAt > b.lastSeenAt
    }
}

/// Sessions grouped by account for the Everyone's view: the signed-in user's
/// first, then the others by name.
public func sessionGroups(_ list: [DeviceSession], me: String?) -> [(user: String, sessions: [DeviceSession])] {
    let byUser = Dictionary(grouping: sortSessions(list), by: \.userName)
    let names = byUser.keys.sorted { a, b in
        if (a == me) != (b == me) { return a == me }
        return a.localizedStandardCompare(b) == .orderedAscending
    }
    return names.map { ($0, byUser[$0] ?? []) }
}

// MARK: - Link a TV

extension PendingDevice {
    /// The App row: "WatchMyMedia for Android TV 1.0.0".
    public var appLabel: String? {
        let text = [product, version].compactMap { $0 }.joined(separator: " ")
        return text.isEmpty ? nil : text
    }

    /// When the TV asked for its code, from when the code runs out.
    public var askedAt: Date? { expiresAt?.addingTimeInterval(-pairingCodeLifetime) }
}

/// The sign-in screen's Link a TV row: linking needs a signed-in phone, so a
/// tap (or a watchmymedia://link) remembers to go on to it after sign-in.
public func linkTVRowSubtitle(pending: Bool, code: String?, codeSignIn: Bool) -> String {
    if pending, let code { return "Sign in to approve the code \(spacedCode(code))" }
    if pending || codeSignIn { return "Sign in first, then enter the TV’s code" }
    return "Enter the code shown on your TV"
}

// MARK: - Apple TV

/// The pairing code's line on Apple TV (TvMain.dc.html): "Waiting for the code
/// · refreshes in 4:52 · this Apple TV signs in by itself once it is entered",
/// counting down to the new code.
public func tvPairingWaitingLine(expiresAt: Date, now: Date = .now) -> String {
    let left = max(Int(expiresAt.timeIntervalSince(now).rounded(.up)), 0)
    let clock = "\(left / 60):\(String(format: "%02d", left % 60))"
    return "Waiting for the code · refreshes in \(clock) · this Apple TV signs in by itself once it is entered"
}

/// "192.168.1.20:27587/link": the server's link page as the Apple TV tells
/// people to open it, without the scheme and the code.
public func linkPageLabel(_ url: URL) -> String {
    var c = URLComponents(url: url, resolvingAgainstBaseURL: false)
    c?.query = nil
    c?.fragment = nil
    var s = c?.url?.absoluteString ?? url.absoluteString
    for prefix in ["https://", "http://"] where s.hasPrefix(prefix) { s.removeFirst(prefix.count) }
    return s
}

extension Server {
    /// "Living room server · 192.168.1.20:27587 · v0.9.3", the sign-in footer on Apple TV.
    public var summaryLine: String { "\(name) · \(displayAddress) · \(versionLabel)" }

    /// "In the web app at 192.168.1.20:27587": Admin tools on Apple TV, which has no browser.
    public var adminToolsLine: String { "In the web app at \(displayAddress)" }

    /// "Living room server · v0.9.3 · API 2 · 192.168.1.20:27587", About on Apple TV.
    public var aboutLine: String { "\(name) · \(versionLabel) · API \(apiLevel) · \(displayAddress)" }
}

/// "This device: Living room Apple TV · Apple TV 4K", the sign-in footer on Apple TV.
public func thisDeviceLine(name: String, model: String) -> String {
    let parts = [name, model].map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    let unique = parts.count == 2 && parts[0] == parts[1] ? [parts[0]] : parts
    return "This device: \(unique.joined(separator: " · "))"
}

extension SettingsModel.DeviceFacts {
    /// "Apple TV 4K · tvOS 18.2 · app 1.0 (12)", under This Apple TV's name.
    public var summaryLine: String { "\(model) · \(system) · app \(appVersionLabel)" }
}

/// The open-source software in the app and its licences, for the privacy
/// and licences sheet (Apple TV has no browser to open a page with).
public let openSourceNotices: [(name: String, licence: String)] = [
    ("Swift OpenAPI Runtime", "Apache License 2.0"),
    ("Swift OpenAPI URLSession", "Apache License 2.0"),
    ("Swift HTTP Types", "Apache License 2.0"),
]
