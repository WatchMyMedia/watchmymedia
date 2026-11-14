import AVFoundation
#if os(tvOS)
import AVKit
#endif
import Foundation

// What the file player (``AVMediaPlayer``) and the Live TV player
// (``AVLiveMediaPlayer``) do alike with AVPlayer, written once
// (PLAN-APPLE-REVIEW.md §5.1): an item's making and clearing, its first
// frame, the link measured from its access log, the metadata the system
// shows, the audio session, and what a failure was.

/// The audio session the players play in: playback, in movie mode.
public enum PlaybackAudioSession {
    /// Takes the session for playing video, over silent mode.
    @MainActor
    public static func activate() {
        #if os(iOS) || os(tvOS)
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)
        #endif
    }

    /// Lets the audio another app was playing carry on.
    @MainActor
    public static func deactivate() {
        #if os(iOS) || os(tvOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}

/// One item at a time on an AVPlayer, as both players load them. The player
/// that owns it puts the item on the AVPlayer and takes it off; this makes
/// it, sees its first frame and measures its transfers.
@MainActor
final class PlayerCore {
    let player: AVPlayer
    /// Sent as `User-Agent` on the item's requests; nil leaves AVFoundation's own.
    let userAgent: String?
    private(set) var item: AVPlayerItem?
    /// Watches for the item's first decoded frame; off the item once seen.
    private var videoOutput: AVPlayerItemVideoOutput?
    /// The item has shown a frame.
    private(set) var frameShown = false
    private var meter = AccessLogMeter()

    init(player: AVPlayer = AVPlayer(), userAgent: String? = nil) {
        self.player = player
        self.userAgent = userAgent
    }

    /// A new item for `url`, to put on the player: with the MIME type and the
    /// app's user agent on its requests, a video output to see its first
    /// frame by, and `metadata` for the system's player (tvOS).
    /// `AVURLAssetHTTPUserAgentKey` is public from iOS and tvOS 16.
    func makeItem(url: URL, mimeType: String? = nil, metadata: [AVMetadataItem]) -> AVPlayerItem {
        var options: [String: Any] = [:]
        if let mimeType { options[AVURLAssetOverrideMIMETypeKey] = mimeType }
        if let userAgent { options[AVURLAssetHTTPUserAgentKey] = userAgent }
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: options))
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
        ])
        item.add(output)
        videoOutput = output
        frameShown = false
        meter = AccessLogMeter()
        self.item = item
        describe(metadata)
        return item
    }

    /// What the system's player shows about the item (tvOS's transport bar and Info panel).
    func describe(_ metadata: [AVMetadataItem]) {
        #if os(tvOS)
        item?.externalMetadata = metadata
        #endif
    }

    /// Forgets the item; the owner takes it off the player.
    func clear() {
        item = nil
        videoOutput = nil
        frameShown = false
        meter = AccessLogMeter()
    }

    /// Whether the item has decoded a frame on this device. The first time
    /// it has, the video output comes off the item, which then decodes for
    /// the screen alone.
    func decodedFrame() -> Bool {
        if frameShown { return true }
        guard let item, let output = videoOutput, player.currentItem === item else { return false }
        let time = player.currentTime()
        guard time.isValid, output.hasNewPixelBuffer(forItemTime: time),
              output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil) != nil else { return false }
        noteFrame()
        return true
    }

    /// The first frame showed (here, or on an AirPlay screen): the video output comes off the item.
    func noteFrame() {
        frameShown = true
        if let videoOutput {
            item?.remove(videoOutput)
            self.videoOutput = nil
        }
    }

    /// Reads the item's access log again and returns its events: the
    /// transfers since the last read that add up to a second are measured.
    @discardableResult
    func readAccessLog() -> [AVPlayerItemAccessLogEvent]? {
        guard let events = item?.accessLog()?.events else { return nil }
        meter.read(events, at: Self.uptime)
        return events
    }

    /// The last rate measured over the access log, in bits per second.
    var measuredBps: Int? { meter.recentBps }

    /// The last rate measured and its age.
    var measuredTransfer: MediaTransfer? { meter.transfer(at: Self.uptime) }

    private static var uptime: TimeInterval { ProcessInfo.processInfo.systemUptime }
}

/// The link measured over an item's access log: the bytes and seconds of
/// transfer since the last read that measured, and when it did.
struct AccessLogMeter {
    private var counted = TransferCount()
    private(set) var recentBps: Int?
    private var measuredAt: TimeInterval?

    /// Takes the log as it is at `now`. A log that went back (a new item) starts again.
    mutating func read(_ events: [AVPlayerItemAccessLogEvent], at now: TimeInterval) {
        read(TransferCount(events: events), at: now)
    }

    mutating func read(_ total: TransferCount, at now: TimeInterval) {
        if let rate = total.rate(since: counted) {
            recentBps = rate
            counted = total
            measuredAt = now
        } else if total.bytes < counted.bytes {
            counted = total
            recentBps = nil
            measuredAt = nil
        }
    }

    /// The last rate and how long ago it was measured.
    func transfer(at now: TimeInterval) -> MediaTransfer? {
        guard let recentBps, let measuredAt else { return nil }
        return MediaTransfer(bps: recentBps, ageMs: max(0, Int((now - measuredAt) * 1_000)))
    }
}

/// Bytes an item's access log has transferred, and the seconds the transfers took.
struct TransferCount: Equatable {
    /// Less transfer time than this says too little about the link.
    static let minimumSeconds = 1.0

    var bytes: Int64 = 0
    var seconds: Double = 0

    init(bytes: Int64 = 0, seconds: Double = 0) {
        self.bytes = bytes
        self.seconds = seconds
    }

    init(events: [AVPlayerItemAccessLogEvent]) {
        bytes = events.reduce(0) { $0 + max($1.numberOfBytesTransferred, 0) }
        seconds = events.reduce(0) { $0 + max($1.transferDuration, 0) }
    }

    /// Bits per second over what was transferred since `earlier`; nil for under a second of transfer.
    func rate(since earlier: TransferCount) -> Int? {
        let bytes = bytes - earlier.bytes, seconds = seconds - earlier.seconds
        guard bytes > 0, seconds.isFinite, seconds >= Self.minimumSeconds else { return nil }
        return Int(Double(bytes) * 8 / seconds)
    }
}

extension PlayerFailure {
    /// What went wrong, as the controllers word it: an HTTP status from the
    /// error log when there is one (HLS failures carry it there), else the error's domain.
    nonisolated static func classify(_ error: NSError?, log: AVPlayerItemErrorLog?) -> PlayerFailure {
        let detail = error.map { "\($0.domain) \($0.code): \($0.localizedDescription)" } ?? "unknown error"
        let events = log?.events ?? []
        if let status = events.reversed().lazy.compactMap({ httpStatus(code: $0.errorStatusCode, comment: $0.errorComment) }).first {
            return PlayerFailure(kind: .http, detail: detail, httpStatus: status)
        }
        guard let error else { return PlayerFailure(kind: .other, detail: detail) }
        let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError
        if error.domain == NSURLErrorDomain || underlying?.domain == NSURLErrorDomain {
            return PlayerFailure(kind: .network, detail: detail)
        }
        if error.domain == AVFoundationErrorDomain {
            switch AVError.Code(rawValue: error.code) {
            case .decodeFailed?, .decoderNotFound?, .decoderTemporarilyUnavailable?, .formatUnsupported?:
                return PlayerFailure(kind: .decoder, detail: detail)
            case .fileFormatNotRecognized?, .failedToParse?, .mediaDiscontinuity?:
                return PlayerFailure(kind: .source, detail: detail)
            case .contentIsNotAuthorized?:
                return PlayerFailure(kind: .http, detail: detail, httpStatus: 403)
            default:
                break
            }
        }
        return PlayerFailure(kind: .other, detail: detail)
    }

    /// The HTTP status in an error log event: its code when that is one, or "HTTP 404: File Not Found" in its comment.
    nonisolated static func httpStatus(code: Int, comment: String?) -> Int? {
        if (400..<600).contains(code) { return code }
        guard let comment, let match = comment.firstMatch(of: /HTTP (\d{3})/), let status = Int(match.1), (400..<600).contains(status) else { return nil }
        return status
    }

    /// The server refused the media token (it ran out, or was revoked with a
    /// sign-in): a new one may play.
    var refusedToken: Bool { kind == .http && (httpStatus == 401 || httpStatus == 403) }
}

/// What AVKit reads about the item: the title and subtitle for the
/// transport bar's title view and Now Playing, and on Apple TV the Info
/// panel's description, picture, certification, genre and year.
enum PlayerMetadata {
    nonisolated static func items(title: String, subtitle: String?, details: ItemDetails = ItemDetails()) -> [AVMetadataItem] {
        var items: [AVMetadataItem] = []
        func add(_ identifier: AVMetadataIdentifier, _ value: any NSCopying & NSObjectProtocol, dataType: String? = nil) {
            let item = AVMutableMetadataItem()
            item.identifier = identifier
            item.value = value
            if let dataType { item.dataType = dataType }
            item.extendedLanguageTag = "und"
            items.append(item)
        }
        func add(_ identifier: AVMetadataIdentifier, _ text: String?) {
            guard let text, !text.isEmpty else { return }
            add(identifier, text as NSString)
        }
        add(.commonIdentifierTitle, title)
        add(.iTunesMetadataTrackSubTitle, subtitle)
        add(.commonIdentifierDescription, details.overview)
        if let artwork = details.artwork, let type = imageType(artwork.data) {
            add(.commonIdentifierArtwork, artwork.data as NSData, dataType: type)
        }
        add(.iTunesMetadataContentRating, details.contentRating)
        add(.quickTimeMetadataGenre, details.genres.isEmpty ? nil : details.genres.joined(separator: ", "))
        add(.quickTimeMetadataCreationDate, details.year.map(String.init))
        return items
    }

    /// The picture's type for AVKit, from its first bytes: JPEG or PNG; nil for another.
    nonisolated static func imageType(_ data: Data) -> String? {
        if data.starts(with: [0xFF, 0xD8, 0xFF]) { return kCMMetadataBaseDataType_JPEG as String }
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return kCMMetadataBaseDataType_PNG as String }
        return nil
    }
}

/// Runs `body` on the main actor: at once when called there (AVFoundation
/// usually calls back on the main thread), else as soon as it can.
func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
    if Thread.isMainThread {
        MainActor.assumeIsolated(body)
    } else {
        Task { @MainActor in body() }
    }
}
