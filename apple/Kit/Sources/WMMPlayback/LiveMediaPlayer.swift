import AVFoundation
#if os(tvOS)
import AVKit
#endif
import Foundation

/// Only the transport live playback needs; no file position or event-HLS start offset.
@MainActor
public protocol LiveMediaPlayer: AnyObject {
    var player: AVPlayer? { get }
    var playing: Bool { get }
    var ready: Bool { get }
    var buffering: Bool { get }
    var failed: Bool { get }
    /// A decoded frame from the current item, reset by every load.
    var firstFrameRendered: Bool { get }
    var bufferedMs: Int? { get }
    var transfer: MediaTransfer? { get }
    var stalls: Int { get }
    var position: Double { get }
    var seekable: ClosedRange<Double>? { get }
    func load(url: URL, title: String, play: Bool)
    func play()
    func pause()
    func goLive()
    func stop()
}

extension LiveMediaPlayer {
    public var buffering: Bool { !ready }
    public var firstFrameRendered: Bool { ready && playing }
    public var bufferedMs: Int? { nil }
    public var transfer: MediaTransfer? { nil }
    public var stalls: Int { 0 }
}

/// A live stream's stalls, counted as they happen (PLAN-APPLE-REVIEW.md
/// §3.1.7) rather than when a heartbeat reads the player: from the item's
/// stall notification and the player starting to wait while it played. One
/// stall until it plays again; the wait after a pause or a seek isn't one.
struct LiveStallCounter: Equatable {
    private(set) var count = 0
    private var played = false
    private var stalled = false

    /// The player plays.
    mutating func playing() {
        played = true
        stalled = false
    }

    /// The player waits for data: a stall when it was playing.
    mutating func waiting() {
        guard played, !stalled else { return }
        stalled = true
        count += 1
    }

    /// The viewer paused or moved: the wait that follows is theirs.
    mutating func excuse() {
        played = false
        stalled = false
    }
}

@MainActor
public final class AVLiveMediaPlayer: LiveMediaPlayer {
    public let player: AVPlayer?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var frameSeen = false
    private var counted = TransferCount()
    private var recentBps: Int?
    private var transferAt: TimeInterval?
    private var stallCounter = LiveStallCounter()
    private var statusObservation: NSKeyValueObservation?
    private var stallNotice: NSObjectProtocol?
    private var externalProgressStart: Double?
    public init() {
        let player = AVPlayer()
        self.player = player
        statusObservation = player.observe(\.timeControlStatus) { [weak self] _, _ in
            Task { @MainActor in self?.statusChanged() }
        }
    }

    public var playing: Bool { player?.timeControlStatus != .paused }
    public var ready: Bool { player?.currentItem?.status == .readyToPlay }
    public var buffering: Bool { player?.timeControlStatus == .waitingToPlayAtSpecifiedRate || !ready }
    public var failed: Bool { player?.currentItem?.status == .failed }
    public var firstFrameRendered: Bool {
        if frameSeen { return true }
        guard let player, ready, player.timeControlStatus == .playing else { return false }
        if let output = videoOutput {
            let time = player.currentTime()
            if time.isValid, output.hasNewPixelBuffer(forItemTime: time), output.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil) != nil {
                frameSeen = true; return true
            }
        }
        // AirPlay renders on the external screen, where no local pixel buffer
        // is exposed. Require playback to advance on the new item instead.
        if player.isExternalPlaybackActive {
            let current = player.currentTime().seconds
            guard current.isFinite else { return false }
            if let externalProgressStart, current - externalProgressStart > 0.5 { frameSeen = true; return true }
            if externalProgressStart == nil { externalProgressStart = current }
        }
        return false
    }
    public var bufferedMs: Int? {
        guard let item = player?.currentItem, let current = player?.currentTime().seconds, current.isFinite else { return nil }
        let end = item.loadedTimeRanges.map(\.timeRangeValue).map { $0.end.seconds }
            .filter { $0.isFinite && $0 >= current }.max() ?? current
        return Int(max(0, min((end - current) * 1_000, Double(Int.max))))
    }
    public var transfer: MediaTransfer? {
        guard let events = player?.currentItem?.accessLog()?.events else { return nil }
        let now = TransferCount(events: events)
        if let rate = now.rate(since: counted) {
            recentBps = rate; counted = now; transferAt = ProcessInfo.processInfo.systemUptime
        } else if now.bytes < counted.bytes {
            counted = now; recentBps = nil; transferAt = nil
        }
        guard let recentBps, let transferAt else { return nil }
        let age = max(0, Int((ProcessInfo.processInfo.systemUptime - transferAt) * 1_000))
        return age <= 20_000 ? MediaTransfer(bps: recentBps, ageMs: age) : nil
    }
    public var stalls: Int { stallCounter.count }
    private func statusChanged() {
        guard let player else { return }
        switch player.timeControlStatus {
        case .playing: stallCounter.playing()
        case .waitingToPlayAtSpecifiedRate where ready: stallCounter.waiting()
        case .paused: stallCounter.excuse()
        default: break
        }
    }
    public var position: Double { player?.currentTime().seconds ?? 0 }
    public var seekable: ClosedRange<Double>? {
        guard let range = player?.currentItem?.seekableTimeRanges.last?.timeRangeValue,
              range.start.seconds.isFinite, range.end.seconds.isFinite,
              range.start.seconds <= range.end.seconds else { return nil }
        return range.start.seconds...range.end.seconds
    }
    public func load(url: URL, title: String, play: Bool) {
        #if os(iOS) || os(tvOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        let item = AVPlayerItem(url: url)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA)])
        item.add(output)
        videoOutput = output; frameSeen = false; counted = TransferCount(); recentBps = nil; transferAt = nil
        externalProgressStart = nil
        stallCounter = LiveStallCounter()
        forgetStalls()
        stallNotice = NotificationCenter.default.addObserver(forName: AVPlayerItem.playbackStalledNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.stallCounter.waiting() }
        }
        #if os(tvOS)
        let metadata = AVMutableMetadataItem()
        metadata.identifier = .commonIdentifierTitle
        metadata.value = title as NSString
        metadata.extendedLanguageTag = "und"
        item.externalMetadata = [metadata]
        #endif
        // Let AVPlayer choose the live edge. The file player's event-HLS seek to zero
        // must never be used here, even on the first playlist load.
        player?.replaceCurrentItem(with: item)
        if play { player?.play() }
    }
    public func play() { player?.play() }
    public func pause() { player?.pause() }
    public func goLive() {
        guard let range = seekable,
              let target = Self.forwardLiveEdgeTarget(position: position, seekable: range) else { return }
        stallCounter.excuse()
        player?.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    static func forwardLiveEdgeTarget(position: Double, seekable: ClosedRange<Double>) -> Double? {
        // A little safety behind the newest available boundary avoids requesting
        // an incomplete next segment while still staying within the rolling window.
        let target = max(seekable.lowerBound, seekable.upperBound - 2)
        // AVPlayer can already be ahead of a stale seekable end. Go live then
        // only needs to resume; seeking would rewind the picture.
        return position.isFinite && position >= target ? nil : target
    }
    public func stop() { player?.pause(); player?.replaceCurrentItem(with: nil); videoOutput = nil; frameSeen = false; forgetStalls() }
    private func forgetStalls() {
        if let stallNotice { NotificationCenter.default.removeObserver(stallNotice) }
        stallNotice = nil
    }
}
