import Foundation

// Boost dialogue and Reduce loud sounds, which the server applies when it
// converts the sound; Subtitle size, which the apps apply to the subtitles
// they draw; and subtitle timing, per person and track (API level 35). The
// words are the same on every app.

/// How strongly Boost dialogue or Reduce loud sounds works.
public enum SoundLevel: String, CaseIterable, Hashable, Sendable {
    case off, medium, high

    /// "Off", "Medium", "High".
    public var label: String {
        switch self {
        case .off: "Off"
        case .medium: "Medium"
        case .high: "High"
        }
    }
}

/// One of the two sound settings, kept per person on the server.
public enum SoundSetting: String, CaseIterable, Hashable, Sendable {
    case dialogueBoost, reduceLoudSounds

    /// "Boost dialogue".
    public var title: String {
        switch self {
        case .dialogueBoost: "Boost dialogue"
        case .reduceLoudSounds: "Reduce loud sounds"
        }
    }

    /// What it does, under its name.
    public var line: String {
        switch self {
        case .dialogueBoost: "Makes voices clearer over music and effects."
        case .reduceLoudSounds: "Brings loud scenes closer to the level of speech, for watching quietly."
        }
    }
}

extension AudioPreferences {
    /// The level of one sound setting.
    public subscript(_ setting: SoundSetting) -> SoundLevel {
        get {
            switch setting {
            case .dialogueBoost: dialogueBoost
            case .reduceLoudSounds: reduceLoudSounds
            }
        }
        set {
            switch setting {
            case .dialogueBoost: dialogueBoost = newValue
            case .reduceLoudSounds: reduceLoudSounds = newValue
            }
        }
    }
}

/// How big the subtitles an app draws are, per person on the server. Each
/// step scales the app's own size, which at Normal follows the device's
/// caption size: the TVs' 22, 28, 34 and 42 over 28.
public enum SubtitleSize: String, CaseIterable, Hashable, Sendable {
    case smaller, normal, larger, largest

    public var label: String {
        switch self {
        case .smaller: "Smaller"
        case .normal: "Normal"
        case .larger: "Larger"
        case .largest: "Largest"
        }
    }

    /// The factor on the app's own cue size.
    public var scale: Double {
        switch self {
        case .smaller: 0.79
        case .normal: 1
        case .larger: 1.21
        case .largest: 1.5
        }
    }
}

/// The words of the sound settings, in Settings and in the player.
public enum SoundWords {
    public static let heading = "Sound"
    public static let note = "While either is on, the server converts the sound. Surround stays; lossless and Dolby Atmos sound come back when both are Off."
    /// A download plays from the device, with the sound it was made with.
    public static let downloadNote = "Downloads keep the film’s own sound."
    /// The row while the play reloads with the new sound.
    public static let changing = "Changing the sound…"
    /// Apple TV's Settings: the system's own controls reach the app's sound too.
    public static let appleTVNote = "Apple TV’s own Enhance Dialogue and Reduce Loud Sounds, in Settings › Video and Audio, also work here."

    /// A change in the player that failed, naming what it stayed at:
    /// "Couldn’t change the sound, so it’s still Off. Check your connection and choose again."
    public static func changeFailed(_ error: any Error, still level: SoundLevel) -> String {
        let still = "Couldn’t change the sound, so it’s still \(level.label)."
        switch (error as? APIError)?.status {
        case 422?: return "\(still) This app may be older than the server: update it and choose again."
        case 429?: return "\(still) Wait a moment and choose again."
        default: return "\(still) Check your connection and choose again."
        }
    }

    /// Settings' Saved line: "Saved. Voices are boosted at Medium, on every device."
    public static func saved(_ setting: SoundSetting, _ level: SoundLevel) -> String {
        switch (setting, level) {
        case (.dialogueBoost, .off): "Saved. Voices are no longer boosted, on every device."
        case (.dialogueBoost, _): "Saved. Voices are boosted at \(level.label), on every device."
        case (.reduceLoudSounds, .off): "Saved. Loud sounds are no longer reduced, on every device."
        case (.reduceLoudSounds, _): "Saved. Loud sounds are reduced at \(level.label), on every device."
        }
    }
}

/// The words of Subtitle size, in Settings.
public enum SubtitleSizeWords {
    public static let title = "Subtitle size"
    public static let preview = "Preview"
    /// The cue the previews draw.
    public static let sample = "Who rang the bell?"
    public static let normalFollows = "Normal follows your device’s caption size."
    public static let burnedInKeep = "Subtitles that are part of the picture keep their own size."
    public static let timingInPlayer = "Subtitle timing is in the player, for each track."

    /// "Saved. Subtitles are now Larger, on every device."
    public static func saved(_ size: SubtitleSize) -> String {
        "Saved. Subtitles are now \(size.label), on every device."
    }
}

/// The words of Subtitle timing, in the player.
public enum TimingWords {
    public static let title = "Subtitle timing"
    public static let earlier = "Earlier"
    public static let later = "Later"
    public static let reset = "Reset"
    public static let inStep = "In step"
    public static let help = "Saved for this subtitle track on all your devices."
    public static let saveForEveryone = "Save for everyone"
    public static let saveForEveryoneLine = "Everyone who plays this file gets this timing. Their own changes still win for them."
    public static let savedForEveryone = "Saved for everyone"
    /// Under a shared timing the person hasn't changed.
    public static let setForEveryone = "Set for everyone"
    public static let burnedIn = "These subtitles are part of the picture, so the video restarts here a second after you stop changing the timing."
    public static let offline = "Offline, this timing is for this play only."

    /// "In step", "0.4 s later", "1.2 s earlier": one decimal, always "s".
    public static func value(_ ms: Int) -> String {
        guard ms != 0 else { return inStep }
        let seconds = String(format: "%.1f", Double(abs(ms)) / 1000)
        return "\(seconds) s \(ms > 0 ? "later" : "earlier")"
    }

    /// A save that failed: the timing stays, for this play only.
    public static func saveFailed(_ error: any Error) -> String {
        let still = "Couldn’t save, so this timing is for this play only."
        switch (error as? APIError)?.status {
        case 429?: return "\(still) Wait a moment and try again."
        default: return "\(still) Check your connection and try again."
        }
    }
}

/// Boost dialogue and Reduce loud sounds as the player shows them: the
/// person's levels, the one changing while the play reloads with it, and
/// the one whose change failed.
public struct PlayerSound: Hashable, Sendable {
    public var dialogueBoost: SoundLevel
    public var reduceLoudSounds: SoundLevel
    /// Saved and reloading the play at its place: its row is busy and both are off until it settles.
    public var busy: SoundSetting?
    /// The setting whose last change failed, and why, until the next change.
    public var failed: SoundSetting?
    public var failure: String?
    /// A download plays its own sound: the choices show, off.
    public var download: Bool

    public init(dialogueBoost: SoundLevel = .off, reduceLoudSounds: SoundLevel = .off, busy: SoundSetting? = nil,
                failed: SoundSetting? = nil, failure: String? = nil, download: Bool = false) {
        self.dialogueBoost = dialogueBoost
        self.reduceLoudSounds = reduceLoudSounds
        self.busy = busy
        self.failed = failed
        self.failure = failure
        self.download = download
    }

    public subscript(_ setting: SoundSetting) -> SoundLevel {
        switch setting {
        case .dialogueBoost: dialogueBoost
        case .reduceLoudSounds: reduceLoudSounds
        }
    }

    /// The choices can be changed now: not for a download, nor while a change reloads the play.
    public var enabled: Bool { !download && busy == nil }

    /// The line under a setting: busy, its failure, or what it does.
    public func line(_ setting: SoundSetting) -> String {
        if busy == setting { return SoundWords.changing }
        if failed == setting, let failure { return failure }
        return setting.line
    }

    /// The line under both: a download's, or what turning either on does.
    public var note: String { download ? SoundWords.downloadNote : SoundWords.note }
}

/// What the server says a subtitle track's timing is after a change:
/// the person's own, or the shared one an admin saved for everyone.
public struct SavedTiming: Hashable, Sendable {
    public var timingMs: Int
    public var shared: Bool

    public init(timingMs: Int, shared: Bool) {
        self.timingMs = timingMs
        self.shared = shared
    }
}

/// Subtitle timing as the player shows it, for the subtitle track that is
/// on. Positive shows each cue later.
public struct SubtitleTimingState: Hashable, Sendable {
    /// One press of Earlier or Later.
    public static let stepMs = 100
    /// The furthest either way the server keeps.
    public static let limitMs = 60_000

    /// How the timing's last save stands.
    public enum Save: Hashable, Sendable {
        case rest
        case saving
        /// Couldn't save: the timing is for this play only, and the line says so.
        case failed(String)
        /// A download playing from the device: the timing is for this play only.
        case playOnly
    }

    public var trackID: String
    public var timingMs: Int
    /// The value is the one an admin saved for everyone, which the person hasn't changed.
    public var shared: Bool
    /// The track is part of the picture: a change restarts the video after the presses stop.
    public var burnedIn: Bool
    /// Reset would change something: the person has a timing of their own over the one it goes back to.
    public var canReset: Bool
    /// An admin may save this timing for everyone.
    public var canSaveForEveryone: Bool
    /// Saved for everyone in this play, until it changes again.
    public var savedForEveryone: Bool
    public var save: Save

    public init(trackID: String, timingMs: Int = 0, shared: Bool = false, burnedIn: Bool = false, canReset: Bool = false,
                canSaveForEveryone: Bool = false, savedForEveryone: Bool = false, save: Save = .rest) {
        self.trackID = trackID
        self.timingMs = timingMs
        self.shared = shared
        self.burnedIn = burnedIn
        self.canReset = canReset
        self.canSaveForEveryone = canSaveForEveryone
        self.savedForEveryone = savedForEveryone
        self.save = save
    }

    /// "In step", "0.4 s later".
    public var value: String { TimingWords.value(timingMs) }

    /// The line under the stepper: how saving stands, else where it is saved.
    public var line: String {
        switch save {
        case .failed(let line): line
        case .playOnly: TimingWords.offline
        case .rest, .saving: TimingWords.help
        }
    }

    public var failed: Bool {
        if case .failed = save { return true }
        return false
    }

    /// Earlier or Later can still move it.
    public var canMoveEarlier: Bool { timingMs > -Self.limitMs }
    public var canMoveLater: Bool { timingMs < Self.limitMs }
}
