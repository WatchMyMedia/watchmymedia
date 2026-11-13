import Foundation
import WMMModel

/// What a Live TV programme offers, decided once for every menu that shows it
/// (PLAN-APPLE-REVIEW.md §5.4): the Apple TV guide's and search's long-press
/// menus, and the iPhone and iPad guide's card, its details and search's
/// button. Each menu draws what this lists, in its own board's way; a menu
/// may leave a part out (search offers no Record), never add one.
public struct LiveProgrammeActions: Hashable, Sendable {
    /// Recording, while the programme hasn't ended and the server records for this person.
    public enum Recording: Hashable, Sendable {
        /// Set to record already (will record, recording now, or won't record
        /// while the tuners are busy), by this person or someone else: no
        /// second Record. ``LiveProgrammeActions/cancel`` says whether they may stop it.
        case marked(LiveRecordingMark)
        /// Record, or Record the rest while it is on. A programme of a series
        /// `asks` which (just this episode, every new one); a film or a
        /// special records at once. `note` is what an earlier attempt left to
        /// say beside it: "Already recorded", "Not a new episode".
        case record(rest: Bool, asks: Bool, note: String?)
    }

    /// A reminder, for a programme that hasn't started.
    public enum Reminder: Hashable, Sendable {
        /// Reminded: the bell, and Remove reminder.
        case set
        /// Remind me.
        case offered
        /// Neither: it starts within the lead time, or the server has no
        /// reminders. A card says when it is on instead.
        case unavailable
    }

    public var recording: Recording?
    /// Nil once the programme has started.
    public var reminder: Reminder?
    /// Watch the channel: false while the channel can't be played.
    public var watch: Bool
    /// On now, rather than later or over.
    public var onNow: Bool

    /// The decision from what the stores know, as plain values (the tests' way in).
    public init(programme: LiveProgramme, channel: LiveChannel, now: Date,
                recordingAvailable: Bool, mark: LiveRecordingMark?,
                reminded: Bool, canRemind: Bool) {
        onNow = programme.isOn(at: now)
        watch = channel.available
        if recordingAvailable, !programme.hasEnded(at: now) {
            if let mark, mark.state.marked {
                recording = .marked(mark)
            } else {
                recording = .record(rest: onNow, asks: programme.seriesKey != nil, note: mark?.note)
            }
        }
        if programme.start > now {
            reminder = reminded ? .set : canRemind ? .offered : .unavailable
        }
    }

    /// The mark this person may stop: Stop recording, or Don't record.
    public var cancel: LiveRecordingMark? {
        guard case .marked(let mark) = recording, mark.canCancel, mark.state.mayCancel else { return nil }
        return mark
    }

    /// Recording now: Watch from the start plays the recording.
    public var recordingNow: LiveRecordingMark? {
        guard case .marked(let mark) = recording, mark.state == .recording else { return nil }
        return mark
    }

    /// Won't record while the tuners are busy: Resolve opens Recordings.
    public var clashes: Bool {
        guard case .marked(let mark) = recording else { return false }
        return mark.state == .conflict
    }
}

extension LiveProgrammeActions {
    /// What `programme` on `channel` offers now, from the stores. Without
    /// `recordings` (search, whose boards offer no Record) it offers none.
    @MainActor
    public init(_ programme: LiveProgramme, on channel: LiveChannel, now: Date,
                extras: LiveExtrasStore, recordings: LiveRecordingsStore? = nil) {
        self.init(programme: programme, channel: channel, now: now,
                  recordingAvailable: recordings?.available == true, mark: recordings?.mark(for: programme),
                  reminded: extras.isReminded(programme), canRemind: extras.canRemind(programme))
    }
}

/// What a row in Recordings offers (DvrRecordings), decided once for both apps.
public struct LiveRecordingActions: Hashable, Sendable {
    /// Watch a finished recording, or Watch from the start while it records.
    public enum Watch: Hashable, Sendable { case recorded, fromStart }

    public var watch: Watch?
    /// Delete the file: a finished recording this person may delete.
    public var delete: Bool
    /// Record this instead: one that won't record while the tuners are busy.
    public var recordInstead: Bool
    /// Edit its padding: one this person set that will record (the iPhone's
    /// and iPad's; the Apple TV's board has no Edit for one recording).
    public var edit: Bool
    /// Stop recording, Skip this one or Don't record.
    public var cancel: Bool
    /// "Stop recording" or "Don’t record", the row's button.
    public var cancelTitle: String
    /// "Stop recording", "Skip this one" for a series' episode, else "Don’t record": the iPhone's swipe.
    public var swipeTitle: String

    public init(_ recording: LiveRecording) {
        watch = recording.playable ? (recording.state == .recording ? .fromStart : .recorded) : nil
        delete = recording.canDelete && [.done, .partial].contains(recording.state)
        cancel = recording.canCancel && recording.state.mayCancel
        recordInstead = cancel && recording.state == .conflict
        edit = cancel && recording.state == .scheduled
        cancelTitle = liveCancelRecordingTitle(recording.state)
        swipeTitle = recording.state == .recording ? liveStopRecording : recording.ruleID != nil ? "Skip this one" : liveDontRecord
    }
}

extension LiveRecording {
    /// What its row offers.
    public var actions: LiveRecordingActions { LiveRecordingActions(self) }
}

/// The recording options form (RecordingOptions), for a programme about to
/// be recorded, a series rule or one recording: which parts it shows, and
/// what it starts with. The three forms (the iPhone's sheet, the Apple TV's
/// Options… and its rule editor) draw from it.
public struct LiveRecordingForm: Hashable, Sendable {
    /// What the form saves.
    public var options: LiveRecordingOptions
    /// Record the series (Every new episode, on Apple TV) can be switched.
    public let offersSeries: Bool
    /// Use server padding defaults can be switched; a rule or a recording
    /// keeps its own padding, so theirs is always shown.
    public let offersServerPadding: Bool

    /// The form as `options` now stand, with `form`'s parts: what a view
    /// draws while the person changes them.
    public init(options: LiveRecordingOptions, like form: LiveRecordingForm) {
        self.options = options
        offersSeries = form.offersSeries
        offersServerPadding = form.offersServerPadding
    }

    private init(options: LiveRecordingOptions, offersSeries: Bool, offersServerPadding: Bool) {
        self.options = options
        self.offersSeries = offersSeries
        self.offersServerPadding = offersServerPadding
    }

    /// A programme from the guide: a series' may record every new episode.
    public static func programme(_ programme: LiveProgramme) -> LiveRecordingForm {
        LiveRecordingForm(options: .init(), offersSeries: programme.seriesKey != nil, offersServerPadding: true)
    }

    /// A rule's settings, as it has them.
    public static func rule(_ rule: LiveRecordingRule) -> LiveRecordingForm {
        let series = rule.kind == "series"
        return LiveRecordingForm(options: .init(series: series, newOnly: rule.newOnly, anyChannel: rule.anyChannel, keep: rule.keep,
                                                padBefore: rule.padBefore, padAfter: rule.padAfter, useServerPadding: false),
                                 offersSeries: series, offersServerPadding: false)
    }

    /// One recording's padding.
    public static func recording(_ recording: LiveRecording) -> LiveRecordingForm {
        LiveRecordingForm(options: .init(padBefore: recording.padBefore, padAfter: recording.padAfter, useServerPadding: false),
                          offersSeries: false, offersServerPadding: false)
    }

    /// New episodes only, Any channel and Keep: a series' settings.
    public var showsSeriesSettings: Bool { options.series }

    /// Start early and End late, unless the server's defaults are used.
    public var showsPadding: Bool { !options.useServerPadding }

    /// The note that the oldest recordings are deleted, once Keep has a limit.
    public var showsKeepNote: Bool { options.keep > 0 }

    /// Keep's choices: all, or the latest 1–10.
    public static let keepChoices = [0] + Array(1...10)

    /// Start early's and End late's choices, in minutes.
    public static let paddingChoices = LiveRecordingOptions.paddingChoices
}
