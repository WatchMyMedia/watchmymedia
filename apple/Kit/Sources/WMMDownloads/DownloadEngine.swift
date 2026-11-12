import Foundation
import WMMModel

/// A download for the engine to fetch: the media, and the text subtitles
/// beside it (the player draws WebVTT itself, for an original file and an HLS
/// download alike).
struct EngineRequest: Codable, Hashable, Sendable {
    /// The download's record ID.
    var id: String
    /// The media without a token: the original file, or the HLS master playlist.
    var url: URL
    var hls: Bool
    /// What the system shows for an HLS download.
    var title: String
    /// Text subtitles by track ID.
    var parts: [String: URL] = [:]
    /// An original file's extension ("mp4"), so the player knows its container.
    var fileExtension: String?
}

/// Where the engine has got with a download.
struct EngineDownload: Hashable, Sendable {
    enum State: String, Codable, Hashable, Sendable { case queued, preparing, downloading, paused, completed, failed }

    var id: String
    var state: State
    /// 0–1.
    var fraction: Double
    /// What it takes on the device once completed.
    var bytes: Int64
    /// The media once completed: the file, or the HLS package.
    var location: URL?
    /// The subtitles that finished, by track ID.
    var parts: [String: URL]
}

/// Why the engine gave up on a download.
enum EngineFailure: Hashable, Sendable {
    /// 401 (signed out), 403 (revoked) or 410 (the file changed): the next sync deletes or marks it.
    case refused(Int)
    /// 409: a remade session's init segment differs; delete what came and start over.
    case restart
    /// Anything else, after its retries.
    case failed
}

/// What the engine needs of the downloads, and tells them.
@MainActor
protocol EngineDelegate: AnyObject {
    /// An `o1.` token for the download's routes (HLS, whose playlists carry it to every segment).
    func token(for id: String) async throws -> MediaToken

    /// `url` with the download owner's session as the bearer (an original
    /// file and subtitles), so a transfer resumed hours later still goes
    /// through; nil once signed out.
    func request(for id: String, url: URL) -> URLRequest?

    /// A download changed state, or failed for good; nil for a change of the network.
    func engineChanged(_ id: String?, failure: EngineFailure?)
}

/// Fetches downloads one at a time and keeps their bytes: the system's
/// background sessions on a device, a fake in tests.
@MainActor
protocol DownloadEngine: AnyObject {
    var delegate: (any EngineDelegate)? { get set }

    /// Queues a download, or starts a failed one again, keeping what came.
    func add(_ request: EngineRequest)

    /// Stops it and deletes its bytes.
    func remove(_ id: String)

    func setPaused(_ id: String, _ paused: Bool)

    func downloads() -> [String: EngineDownload]

    func setWifiOnly(_ wifiOnly: Bool)

    /// What every download here takes.
    func usedBytes() -> Int64

    /// Free and total bytes on the volume downloads go to.
    func space() -> (free: Int64, total: Int64)

    /// What holds downloads back now: no network, or no Wi-Fi while Wi-Fi only.
    func notMet() -> DownloadStatus.Reason?
}
