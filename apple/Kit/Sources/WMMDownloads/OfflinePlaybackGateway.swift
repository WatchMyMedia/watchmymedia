import Foundation
import WMMModel

/// A downloaded item playing from the device: its download and the stored decision, as the player takes it.
struct OfflinePlay: Hashable, Sendable {
    var download: OfflineDownload
    var decision: PlaybackDecision
}

/// The player's server calls with downloads in (PLAN-DOWNLOADS.md §10.4): a
/// downloaded item always plays from the device, online or not, from the
/// decision stored when it was made, and its heartbeats go to the offline
/// queue rather than the server. Everything else goes to `online`
/// (android/core/downloads/.../OfflinePlaybackGateway.kt).
public final class OfflinePlaybackGateway: PlaybackGateway, @unchecked Sendable {
    private let online: any PlaybackGateway
    private weak var downloads: RealDownloadRepository?
    /// What this player plays now, when from the device.
    private let playing = Locked<OfflinePlay?>(nil)

    init(online: any PlaybackGateway, downloads: RealDownloadRepository?) {
        self.online = online
        self.downloads = downloads
    }

    @MainActor public func bound() -> any PlaybackGateway {
        OfflinePlaybackGateway(online: online.bound(), downloads: downloads)
    }

    public func decide(_ request: DecisionRequest) async throws -> PlaybackDecision {
        let play = await MainActor.run { request.recordingID == nil ? downloads?.offlinePlay(request.fileID) : nil }
        playing.value = play
        if let play { return Self.asked(play.decision, audioIndex: request.audioIndex) }
        return try await online.decide(request)
    }

    /// A copy of the whole file plays the track the player asks for, as the
    /// server would, so a reload keeps the viewer's pick or the person's
    /// language (PLAN-LANGUAGES.md §5.5); an HLS copy has only its own.
    static func asked(_ decision: PlaybackDecision, audioIndex: Int?) -> PlaybackDecision {
        guard decision.protocol == .file, let audioIndex, decision.audio.contains(where: { $0.index == audioIndex }) else { return decision }
        var d = decision
        d.selectedAudio = audioIndex
        return d
    }

    public func reportProgress(_ report: ProgressReport) async throws -> ProgressReply {
        guard let play = playing.value, play.download.fileID == report.fileID else { return try await online.reportProgress(report) }
        await MainActor.run { downloads?.progress(play, report) }
        let watchedAt = play.decision.watchedAtMs > 0 ? play.decision.watchedAtMs : Int(Double(report.durationMs) * 0.92)
        return ProgressReply(completed: report.durationMs > 0 && report.positionMs >= watchedAt)
    }

    public func mediaToken() async throws -> MediaToken {
        try await online.mediaToken()
    }

    public func stopSession(_ sessionID: String) async throws {
        try await online.stopSession(sessionID)
    }

    public func plans(profile: PlaybackProfile, quality: StreamQuality, fileIDs: [Int]) async throws -> [PlaybackPlan] {
        try await online.plans(profile: profile, quality: quality, fileIDs: fileIDs)
    }

    public func searchSubtitles(fileID: Int) async throws -> OnlineSubtitles? {
        if playing.value?.download.fileID == fileID { return nil }
        return try await online.searchSubtitles(fileID: fileID)
    }

    public func downloadSubtitle(fileID: Int, resultID: String) async throws -> String {
        try await online.downloadSubtitle(fileID: fileID, resultID: resultID)
    }

    public func automaticSubtitle(fileID: Int, playID: String) async throws -> DecisionSubtitle? {
        if playing.value?.download.fileID == fileID { return nil }
        return try await online.automaticSubtitle(fileID: fileID, playID: playID)
    }

    public func cancelAutomaticSubtitle(fileID: Int, playID: String) async throws {
        if playing.value?.download.fileID == fileID { return }
        try await online.cancelAutomaticSubtitle(fileID: fileID, playID: playID)
    }

    /// A downloaded subtitle is a file beside the media.
    public func subtitleText(_ url: URL) async throws -> String {
        guard url.isFileURL else { return try await online.subtitleText(url) }
        return String(decoding: try Data(contentsOf: url), as: UTF8.self)
    }

    /// The play ID of a play from the device; heartbeats need one, and it never reaches the server.
    static let offlinePlayID = "offline"

    /// The stored decision as an offline play takes it: the media and the
    /// subtitles that finished, from the device; no play or session of the
    /// server's; it resumes where this device last was; the Next Episode card
    /// only for an episode downloaded too; an HLS download's one audio track.
    static func offlineDecision(_ d: OfflineDownload, _ stored: PlaybackDecision, media: URL, parts: [String: URL], nextDownloaded: Bool) -> PlaybackDecision {
        var decision = stored
        let hls = stored.protocol == .hls
        decision.url = media
        // A package or a file says what it is; a type meant for the network would only confuse AVFoundation.
        decision.mimeType = nil
        decision.playID = offlinePlayID
        decision.sessionID = nil
        decision.startMs = 0
        decision.vod = true
        decision.resumeMs = d.watch.flatMap { $0.completed ? nil : $0.positionMs } ?? 0
        decision.next = nextDownloaded ? stored.next : nil
        decision.upNextMs = nextDownloaded ? stored.upNextMs : 0
        decision.autoStep = nil
        if hls {
            let kept = stored.audio.filter { $0.index == stored.selectedAudio }
            decision.audio = kept.isEmpty ? Array(stored.audio.prefix(1)) : kept
        }
        decision.subtitles = stored.subtitles.compactMap { s in
            guard s.format != "image", s.option.id != stored.burnInSubtitleID, let file = parts[s.option.id] else { return nil }
            var local = s
            local.url = file
            return local
        }
        if let selected = decision.selectedSubtitleID, !decision.subtitles.contains(where: { $0.option.id == selected }) {
            decision.selectedSubtitleID = nil
        }
        return decision
    }
}

/// A value read and written from any thread.
final class Locked<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value

    init(_ value: Value) { stored = value }

    var value: Value {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
