import AVFoundation
import Foundation
#if os(tvOS)
import AVKit
#endif

/// ``MediaPlayer`` on AVPlayer, the one the app plays with. AirPlay is on
/// (`allowsExternalPlayback`), and the item's subtitle renditions stay off,
/// since the app draws its own cues. An event playlist, which AVPlayer would
/// start at its live edge, and a resumed play start with a seek to their
/// position before playing. A pause the system makes (a call, a lost route,
/// the app in the background) shows as ``playWhenReady`` turning false.
/// The access log gives the link estimate for Automatic quality, for HLS.
/// A direct-play file starts on the source's audio option: playing waits
/// until it is selected, so the first frame has the right track.
@MainActor
public final class AVMediaPlayer: MediaPlayer {
    public let player: AVPlayer
    public var avPlayer: AVPlayer? { player }
    public private(set) var playWhenReady = false

    private var listener: MediaPlayerListener?
    private var item: AVPlayerItem?
    /// Counts loads, so a callback for an item since replaced is ignored.
    private var loads = 0
    private var itemObservations: [NSKeyValueObservation] = []
    private var itemNotifications: [NSObjectProtocol] = []
    private var playerObservations: [NSKeyValueObservation] = []
    private var timeObserver: Any?
    private var selectionTask: Task<Void, Never>?
    private var audioGroup: AVMediaSelectionGroup?
    /// Player time to seek to once the item is ready, before playing.
    private var pendingStartMs: Int?
    /// The source's audio option isn't selected yet: nothing plays until it is.
    private var awaitingAudio = false
    private var ended = false
    private var failed = false
    /// The item has an access log entry (AVPlayerItemNewAccessLogEntry).
    private var logged = false
    private var hls = false
    /// The log's bytes and seconds of transfer when ``throughputBps`` last
    /// measured, and what it measured.
    private var counted = TransferCount()
    private var recentBps: Int?
    private var transferAt: TimeInterval?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var frameReported = false
    private var loadedAtPositionMs = 0

    public init() {
        player = AVPlayer()
        player.allowsExternalPlayback = true
        #if os(iOS)
        // With the screen mirrored to an Apple TV, send it the video rather than a picture of the phone.
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        #endif
        // Only the app's own cues show: no rendition picked from the system's caption settings.
        player.appliesMediaSelectionCriteriaAutomatically = false
        playerObservations.append(player.observe(\.timeControlStatus) { [weak self] _, _ in
            onMain { self?.timeControlChanged() }
        })
        playerObservations.append(player.observe(\.isExternalPlaybackActive) { [weak self] _, _ in
            onMain { self?.listener?.changed() }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.checkFirstFrame()
                self?.listener?.time()
            }
        }
    }

    public var phase: PlayerPhase {
        guard let item, !failed else { return .idle }
        if ended { return .ended }
        if item.status != .readyToPlay || pendingStartMs != nil || awaitingAudio { return .buffering }
        return player.timeControlStatus == .waitingToPlayAtSpecifiedRate ? .buffering : .ready
    }

    public var externalPlaybackActive: Bool { player.isExternalPlaybackActive }

    /// HLS: the rate of the segment downloads since the last read, from the
    /// access log's bytes and seconds of transfer. Its observed bitrate
    /// averages over the whole item, so it lags a link that just slowed; it
    /// is the answer only until there is a second of transfer to measure.
    /// A file: nil. The log counts a slowed progressive download at a
    /// fraction of the time it takes (a direct play capped at 2 Mbps read
    /// 22–43 Mbps either way), so the server works the link out from the
    /// buffer instead (PLAN-AUTO-QUALITY.md §3.2).
    public var throughputBps: Int? {
        guard hls, logged, let events = item?.accessLog()?.events, let last = events.last else { return nil }
        let now = TransferCount(events: events)
        if let rate = now.rate(since: counted) {
            recentBps = rate
            counted = now
            transferAt = ProcessInfo.processInfo.systemUptime
        } else if now.bytes < counted.bytes {
            counted = now
            recentBps = nil
            transferAt = nil
        }
        return recentBps ?? Self.bitsPerSecond(last.observedBitrate)
    }

    public var transfer: MediaTransfer? {
        _ = throughputBps // Poll the current item's completed access-log transfers.
        guard let recentBps, let transferAt else { return nil }
        let age = max(0, Int((ProcessInfo.processInfo.systemUptime - transferAt) * 1_000))
        return MediaTransfer(bps: recentBps, ageMs: age)
    }

    public var positionMs: Int {
        if let pendingStartMs { return pendingStartMs }
        return Self.ms(player.currentTime()) ?? 0
    }

    public var bufferedPositionMs: Int {
        let now = positionMs
        guard let item, pendingStartMs == nil else { return now }
        let ends = item.loadedTimeRanges.map(\.timeRangeValue).compactMap { range -> Int? in
            guard let start = Self.ms(range.start), let end = Self.ms(range.end), start <= now + 500, end >= now else { return nil }
            return end
        }
        return ends.max() ?? now
    }

    public func setListener(_ listener: MediaPlayerListener?) {
        self.listener = listener
    }

    public func load(_ source: PlayerSource) {
        clearItem()
        var options: [String: Any] = [:]
        if let mimeType = source.mimeType { options[AVURLAssetOverrideMIMETypeKey] = mimeType }
        let asset = AVURLAsset(url: source.url, options: options)
        let item = AVPlayerItem(asset: asset)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)
        ])
        item.add(output)
        videoOutput = output
        loadedAtPositionMs = source.startPositionMs
        #if os(tvOS)
        item.externalMetadata = Self.metadata(title: source.title, subtitle: source.subtitle)
        #endif
        self.item = item
        hls = source.hls
        let load = loads
        playWhenReady = source.playWhenReady
        pendingStartMs = source.startPositionMs > 0 || source.live ? source.startPositionMs : nil
        awaitingAudio = source.audio != nil
        itemObservations.append(item.observe(\.status) { [weak self] _, _ in
            onMain { if self?.loads == load { self?.statusChanged() } }
        })
        itemNotifications.append(NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.loads == load else { return }
                self.ended = true
                self.listener?.changed()
            }
        })
        itemNotifications.append(NotificationCenter.default.addObserver(forName: AVPlayerItem.newAccessLogEntryNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.loads == load else { return }
                self.logged = true
            }
        })
        itemNotifications.append(NotificationCenter.default.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            MainActor.assumeIsolated {
                guard let self, self.loads == load else { return }
                self.fail(error)
            }
        })
        player.replaceCurrentItem(with: item)
        if pendingStartMs == nil && playWhenReady && !awaitingAudio { player.play() }
        selectionTask = Task { [weak self] in
            let legible = try? await asset.loadMediaSelectionGroup(for: .legible)
            let audible = try? await asset.loadMediaSelectionGroup(for: .audible)
            guard let self, loads == load, let item = self.item else { return }
            // The playlist's subtitle renditions stay off: the app draws the selected track itself.
            if let legible { item.select(nil, in: legible) }
            audioGroup = audible
            if let start = source.audio { startAudio(start, in: audible, of: item) }
        }
        listener?.changed()
    }

    public func play() {
        playWhenReady = true
        if item != nil, pendingStartMs == nil, !awaitingAudio, !ended { player.play() }
        listener?.changed()
    }

    public func pause() {
        playWhenReady = false
        player.pause()
        listener?.changed()
    }

    public func seek(toMs positionMs: Int) {
        ended = false
        if pendingStartMs != nil {
            // Not started yet: start there instead (a seek replaces the one in flight).
            pendingStartMs = positionMs
            if item?.status == .readyToPlay { startAtPending() }
            return
        }
        player.seek(to: Self.time(positionMs), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    public func selectAudio(ordinal: Int, of count: Int) -> Bool {
        guard let item, let group = audioGroup, group.options.count == count, group.options.indices.contains(ordinal) else { return false }
        item.select(group.options[ordinal], in: group)
        return true
    }

    public func retitle(_ title: String, subtitle: String?) {
        #if os(tvOS)
        item?.externalMetadata = Self.metadata(title: title, subtitle: subtitle)
        #endif
    }

    public func stop() {
        clearItem()
        listener?.changed()
    }

    public func release() {
        clearItem()
        for o in playerObservations { o.invalidate() }
        playerObservations = []
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        listener = nil
    }

    private func clearItem() {
        loads += 1
        selectionTask?.cancel()
        selectionTask = nil
        for o in itemObservations { o.invalidate() }
        itemObservations = []
        for n in itemNotifications { NotificationCenter.default.removeObserver(n) }
        itemNotifications = []
        item = nil
        audioGroup = nil
        pendingStartMs = nil
        awaitingAudio = false
        ended = false
        failed = false
        logged = false
        counted = TransferCount()
        recentBps = nil
        transferAt = nil
        videoOutput = nil
        frameReported = false
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    private func statusChanged() {
        guard let item else { return }
        switch item.status {
        case .readyToPlay:
            startAtPending()
            listener?.changed()
        case .failed:
            fail(item.error as NSError?)
        default:
            break
        }
    }

    private func checkFirstFrame() {
        guard !frameReported, let item, player.currentItem === item,
              item.status == .readyToPlay, pendingStartMs == nil,
              player.timeControlStatus == .playing, playWhenReady else { return }
        if player.isExternalPlaybackActive {
            // The remote screen owns decoding; advancing playback is the observable proof.
            guard positionMs > loadedAtPositionMs else { return }
        } else {
            guard let videoOutput, videoOutput.hasNewPixelBuffer(forItemTime: player.currentTime()) else { return }
            _ = videoOutput.copyPixelBuffer(forItemTime: player.currentTime(), itemTimeForDisplay: nil)
        }
        frameReported = true
        if let videoOutput {
            item.remove(videoOutput)
            self.videoOutput = nil
        }
        listener?.firstFrame()
    }

    /// Seeks to the start the load asked for, then plays if the viewer wants to.
    private func startAtPending() {
        guard let start = pendingStartMs else { return }
        let load = loads
        player.seek(to: Self.time(start), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            onMain {
                // An interrupted seek was replaced by a newer one, which finishes the start.
                guard let self, finished, self.loads == load, self.pendingStartMs != nil else { return }
                self.pendingStartMs = nil
                if self.playWhenReady && !self.awaitingAudio { self.player.play() }
                self.listener?.changed()
            }
        }
    }

    /// Selects the decision's audio option, matched by position, then lets
    /// the play start. Options that don't number the decision's tracks don't
    /// line up: the file's default plays, and the listener hears so.
    private func startAudio(_ start: AudioStart, in group: AVMediaSelectionGroup?, of item: AVPlayerItem) {
        awaitingAudio = false
        if let group, group.options.count == start.count, group.options.indices.contains(start.ordinal) {
            item.select(group.options[start.ordinal], in: group)
        } else {
            listener?.audioUnmatched()
        }
        if pendingStartMs == nil && playWhenReady && !ended { player.play() }
        listener?.changed()
    }

    /// The selected audio option's position in the file's audible group; nil without one.
    var selectedAudioOrdinal: Int? {
        guard let item, let group = audioGroup, let option = item.currentMediaSelection.selectedMediaOption(in: group) else { return nil }
        return group.options.firstIndex(of: option)
    }

    private func timeControlChanged() {
        switch player.timeControlStatus {
        case .paused:
            // The system paused a play under way (a call, a lost route, the app in the background).
            if playWhenReady, item?.status == .readyToPlay, pendingStartMs == nil, !awaitingAudio, !ended { playWhenReady = false }
        case .playing:
            playWhenReady = true
        default:
            break
        }
        listener?.changed()
    }

    private func fail(_ error: NSError?) {
        guard !failed else { return }
        failed = true
        let failure = Self.failure(error, log: item?.errorLog())
        listener?.failed(failure)
        listener?.changed()
    }

    /// What went wrong, as the controller words it: an HTTP status from the
    /// error log when there is one (HLS failures carry it there), else the error's domain.
    nonisolated static func failure(_ error: NSError?, log: AVPlayerItemErrorLog?) -> PlayerFailure {
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

    /// The title and subtitle as AVKit reads them, for the transport bar's title view and Now Playing.
    nonisolated static func metadata(title: String, subtitle: String?) -> [AVMetadataItem] {
        var items: [AVMetadataItem] = []
        func add(_ identifier: AVMetadataIdentifier, _ value: String) {
            let item = AVMutableMetadataItem()
            item.identifier = identifier
            item.value = value as NSString
            item.extendedLanguageTag = "und"
            items.append(item)
        }
        if !title.isEmpty { add(.commonIdentifierTitle, title) }
        if let subtitle, !subtitle.isEmpty { add(.iTunesMetadataTrackSubTitle, subtitle) }
        return items
    }

    /// A positive, finite bitrate as whole bits per second; nil otherwise (the log says -1 or 0 when it has none).
    nonisolated static func bitsPerSecond(_ value: Double) -> Int? {
        value.isFinite && value >= 1 ? Int(value) : nil
    }

    nonisolated static func ms(_ time: CMTime) -> Int? {
        guard time.isNumeric else { return nil }
        let seconds = time.seconds
        return seconds.isFinite ? Int((seconds * 1000).rounded()) : nil
    }

    nonisolated static func time(_ ms: Int) -> CMTime { CMTime(value: CMTimeValue(ms), timescale: 1000) }
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

/// Runs `body` on the main actor: at once when called there (AVFoundation
/// usually calls back on the main thread), else as soon as it can.
private func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
    if Thread.isMainThread {
        MainActor.assumeIsolated(body)
    } else {
        Task { @MainActor in body() }
    }
}
