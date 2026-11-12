import Foundation
import Observation

/// Live TV is additive: older servers keep the established library navigation.
public let liveTVAPILevel = 11
public let liveProfileAPILevel = 25
public let liveAutomaticAPILevel = 26

public struct LiveTVCapabilities: Hashable, Sendable {
    public var enabled: Bool
    public var allowed: Bool
    public var playback: Bool
    public var bufferSeconds: Int
    public var pauseSeconds: Int
    /// Listings are on and some channel has them; false below ``liveGuideAPILevel``.
    public var guide: Bool
    /// Recording is configured and available on a server at API level 19.
    public var recording: Bool
    public var canRecord: Bool
    /// The server keeps favourites and reminders and searches the listings; false below ``liveExtrasAPILevel``.
    public var extras: Bool
    public var automatic: Bool
    public init(enabled: Bool = false, allowed: Bool = false, playback: Bool = false, bufferSeconds: Int = 60, pauseSeconds: Int = 300, guide: Bool = false, extras: Bool = false, recording: Bool = false, canRecord: Bool = false, automatic: Bool = false) {
        self.recording = recording; self.canRecord = canRecord
        self.enabled = enabled; self.allowed = allowed; self.playback = playback
        self.bufferSeconds = max(0, bufferSeconds); self.pauseSeconds = max(0, pauseSeconds); self.guide = guide; self.extras = extras; self.automatic = automatic
    }
    public var available: Bool { enabled && allowed && playback }
    /// Screens show listings only then; otherwise Live TV is the channel list it was before listings.
    public var listings: Bool { available && guide }
}

/// A channel is never a library file or an entry in watched history.
public struct LiveChannel: Hashable, Sendable, Identifiable {
    public var id: String
    public var number: String
    public var name: String
    public var available: Bool
    public var reason: String?
    /// The listings provider's 4:3 logo; without one the number and name stand in.
    public var logoURL: URL?
    /// What is on and what follows, when the server has listings for the channel.
    public var now: LiveProgramme?
    public var next: LiveProgramme?
    /// The signed-in person starred it (API level 15).
    public var favourite: Bool
    public init(id: String, number: String, name: String, available: Bool = true, reason: String? = nil,
                logoURL: URL? = nil, now: LiveProgramme? = nil, next: LiveProgramme? = nil, favourite: Bool = false) {
        self.id = id; self.number = number; self.name = name; self.available = available; self.reason = reason
        self.logoURL = logoURL; self.now = now; self.next = next; self.favourite = favourite
    }
    public var title: String { "\(number) · \(name)" }
}

/// Automatic is offered only when the signed-in server advertises it.
public enum LiveQuality: String, CaseIterable, Hashable, Sendable {
    case auto = "auto", p1080 = "1080", p720 = "720", p480 = "480", p360 = "360"
    public static var fixed: [LiveQuality] { [.p1080, .p720, .p480, .p360] }
    public var title: String { self == .auto ? "Automatic" : rawValue + "p" }
    public func cappedForCellular(_ cellular: Bool) -> LiveQuality { cellular && self == .p1080 ? .p720 : self }
}

public enum LiveSessionState: String, Hashable, Sendable {
    case starting, playing, stopping, stopped, failed, unknown
    public var terminal: Bool { self == .stopped || self == .failed || self == .unknown }
}

public struct LiveRecordingTakeover: Hashable, Sendable {
    public var recordingID: String
    public var title: String
    public var at: Date
    public var canCancel: Bool
    public init(recordingID: String, title: String, at: Date, canCancel: Bool = false) { self.recordingID = recordingID; self.title = title; self.at = at; self.canCancel = canCancel }
    /// The live player's warning: "At 9:00 PM a recording needs this tuner. Watching stops then."
    public func message(clock: LiveClock = .device) -> String {
        "At \(clock.time(at)) a recording needs this tuner. Watching stops then."
    }
}

public struct LiveSession: Hashable, Sendable, Identifiable {
    public var id: String
    public var channel: LiveChannel
    public var state: LiveSessionState
    public var reason: String?
    public var quality: LiveQuality
    /// Same-server URL without a token; the bound gateway supplies the original owner's media token.
    public var streamURL: URL?
    public var takeover: LiveRecordingTakeover?
    public var paused: Bool
    public var pauseRemainingSeconds: Int
    public var bufferSeconds: Int
    public var automatic: Bool
    public var actualQuality: LiveQuality?
    /// The server may report `original`, which is an observed output, not a selectable cap.
    public var actualOutput: String?
    public var outputGeneration: Int
    public var directive: LiveOutputDirective?
    public var switchStatus: String?
    public var mode: String?
    public var modeReason: String?
    public init(id: String, channel: LiveChannel, state: LiveSessionState, reason: String? = nil, quality: LiveQuality = .p1080, streamURL: URL? = nil, paused: Bool = false, pauseRemainingSeconds: Int = 300, bufferSeconds: Int = 60, takeover: LiveRecordingTakeover? = nil,
                automatic: Bool = false, actualQuality: LiveQuality? = nil, actualOutput: String? = nil, outputGeneration: Int = 0, directive: LiveOutputDirective? = nil, switchStatus: String? = nil, mode: String? = nil, modeReason: String? = nil) {
        self.takeover = takeover
        self.id = id; self.channel = channel; self.state = state; self.reason = reason; self.quality = quality
        self.streamURL = streamURL; self.paused = paused
        self.pauseRemainingSeconds = max(0, pauseRemainingSeconds); self.bufferSeconds = max(0, bufferSeconds)
        self.automatic = automatic; self.actualQuality = actualQuality; self.actualOutput = actualOutput; self.outputGeneration = outputGeneration
        self.directive = directive; self.switchStatus = switchStatus; self.mode = mode; self.modeReason = modeReason
    }
}

public struct LiveOutputDirective: Hashable, Sendable {
    public var generation: Int
    /// Nil when the server's proposed URL was not on the signed-in origin.
    public var url: URL?
    public var reason: String
    public init(generation: Int, url: URL?, reason: String) { self.generation = generation; self.url = url; self.reason = reason }
}

public enum LivePlaybackState: String, Hashable, Sendable { case playing, paused, buffering }
public struct LiveMeasurement: Hashable, Sendable {
    public var state: LivePlaybackState
    public var bufferedMs: Int?
    public var throughputBps: Int?
    public var throughputAgeMs: Int?
    public var stalls: Int?
    public var switchGeneration: Int?
    public var switchFailedGeneration: Int?
    public init(state: LivePlaybackState, bufferedMs: Int? = nil, throughputBps: Int? = nil, throughputAgeMs: Int? = nil, stalls: Int? = nil,
                switchGeneration: Int? = nil, switchFailedGeneration: Int? = nil) {
        self.state = state; self.bufferedMs = bufferedMs; self.throughputBps = throughputBps; self.throughputAgeMs = throughputAgeMs
        self.stalls = stalls; self.switchGeneration = switchGeneration; self.switchFailedGeneration = switchFailedGeneration
    }
}

/// Bound before asynchronous work, so late replies and cleanup cannot cross accounts or servers.
public protocol LiveSessionGateway: Sendable {
    func start(channelID: String, quality: LiveQuality, requestID: String) async throws -> LiveSession
    func start(channelID: String, quality: LiveQuality, requestID: String, profile: PlaybackProfile?) async throws -> LiveSession
    func status(sessionID: String) async throws -> LiveSession
    func heartbeat(sessionID: String, paused: Bool, interacted: Bool) async throws -> LiveSession
    func heartbeat(sessionID: String, paused: Bool, interacted: Bool, measurement: LiveMeasurement?) async throws -> LiveSession
    func stop(sessionID: String) async throws
    func mediaToken() async throws -> MediaToken
}

extension LiveSessionGateway {
    public func start(channelID: String, quality: LiveQuality, requestID: String, profile: PlaybackProfile?) async throws -> LiveSession {
        try await start(channelID: channelID, quality: quality, requestID: requestID)
    }
    public func heartbeat(sessionID: String, paused: Bool, interacted: Bool, measurement: LiveMeasurement?) async throws -> LiveSession {
        try await heartbeat(sessionID: sessionID, paused: paused, interacted: interacted)
    }
}

@MainActor
public protocol LiveTVRepository: AnyObject, Observable {
    /// Changes for every server/sign-in replacement, without exposing a credential.
    var contextID: String? { get }
    var capabilities: LiveTVCapabilities? { get }
    var channels: [LiveChannel] { get }
    func refresh() async throws
    func makeSessionGateway() throws -> any LiveSessionGateway
    /// Listings for the viewer's channels from `start` for `hours` (1–6). Only
    /// when ``LiveTVCapabilities/listings``; throws otherwise.
    func guide(start: Date, hours: Int) async throws -> LiveGuide

    // Favourites, search and reminders (LiveExtras.swift): only when
    // ``LiveTVCapabilities/extrasAvailable``; they throw otherwise.

    /// Stars or unstars a channel for the signed-in person.
    func setFavourite(_ favourite: Bool, channelID: String) async throws
    /// Programmes on now and later whose title or episode title has every word of `query`, soonest first.
    func searchListings(_ query: String, limit: Int) async throws -> [LiveSearchHit]
    /// The person's reminders that haven't ended, and their lead time.
    func reminders() async throws -> LiveReminders
    /// Reminds the person before a programme from the guide or search that hasn't started.
    func remind(programmeID: String) async throws -> LiveReminder
    func removeReminder(id: String) async throws
    /// How long before a programme its reminder comes: one of ``LiveReminderLead/choices``.
    func setReminderLead(minutes: Int) async throws
    /// Tells the listener when the person's favourites or reminders changed on
    /// another device (`live.favourites.changed`, `live.reminders.changed`), or
    /// the event stream came back and some may have been missed.
    func recordings(section: LiveRecordingSection, cursor: String?) async throws -> LiveRecordingPage
    func record(programmeID: String, options: LiveRecordingOptions) async throws -> LiveRecording
    func recordManual(_ request: LiveManualRecording) async throws -> LiveRecording
    func editRecording(id: String, padBefore: Int?, padAfter: Int?, prefer: Bool) async throws -> LiveRecording
    /// Record this instead: `over`, one of a clash's holders not yet recording, gives way;
    /// without it, this one goes ahead of every recording not yet recording.
    func preferRecording(id: String, over: String?) async throws -> LiveRecording
    func cancelRecording(id: String) async throws
    func editRecordingRule(id: String, options: LiveRecordingOptions) async throws -> LiveRecordingRule
    func deleteRecordingFile(id: String, recordAgain: Bool) async throws
    func removeRecordingRule(id: String) async throws
    func changes() -> AsyncStream<Void>
}

/// No Live TV calls or navigation on older servers, previews and callers that have no live service.
@MainActor @Observable
public final class UnavailableLiveTVRepository: LiveTVRepository {
    public var contextID: String? { nil }
    public var capabilities: LiveTVCapabilities? { .init() }
    public var channels: [LiveChannel] { [] }
    public init() {}
    public func refresh() async throws {}
    public func makeSessionGateway() throws -> any LiveSessionGateway { throw LiveTVError(reason: "unavailable") }
    public func guide(start: Date, hours: Int) async throws -> LiveGuide { throw LiveTVError(reason: "unavailable") }
}

/// Stable server reason codes become actionable wording without exposing tuner addresses or other viewers.
public struct LiveTVError: Error, LocalizedError, Equatable, Sendable {
    public let reason: String
    public init(reason: String) { self.reason = reason }
    public var errorDescription: String? { Self.message(reason) }
    public static func message(_ reason: String) -> String {
        switch reason.lowercased().trimmingCharacters(in: .punctuationCharacters.union(.whitespacesAndNewlines)) {
        case "tuners_busy": "All tuners are in use. Try again when someone finishes watching."
        case "server_busy": "The server is busy. Try again when another stream finishes."
        case "no_signal": "This channel has no signal. Try another channel."
        case "signal_lost", "stream_stalled": "The channel's signal was lost. Try again or choose another channel."
        case "tuner_unavailable", "source_offline", "offline", "device_changed": "The tuner is offline or unavailable. Ask the server's admin to check it."
        case "access_revoked", "disabled", "live tv is disabled or not allowed": "Live TV access was turned off. Ask the server's admin for access."
        case "source_disabled", "channel_disabled": "This channel was turned off. Choose another channel."
        case "recording": "Stopped for a recording. Watch it from the start in Live TV › Recordings."
        case "stopped_by_admin": "Live TV was stopped by the server's admin."
        case "pause_expired": "The tuner was given back after five minutes paused. Play to return to live TV."
        case "inactive", "inactive_expired", "inactivity_expired": "Live TV stopped because no one answered Still watching."
        case "lease_expired": "The connection expired and the tuner was given back. Play to reconnect."
        case "server_stopped": "The server stopped. Try again when it is available."
        case "protected", "protected_channel", "drm", "unsupported": "This channel cannot be played. Choose another channel."
        case "encoder_failed": "The server couldn't prepare this channel. Try again or choose another channel."
        case "storage_error", "storage_limit": "The server ran out of room for live playback. Ask its admin to check storage."
        case "stopped": "Live TV stopped."
        case "unavailable": "Live TV is not available on this server."
        default: "This channel couldn't be played. Try again or choose another channel."
        }
    }
}
