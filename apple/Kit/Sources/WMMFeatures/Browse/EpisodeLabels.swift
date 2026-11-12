import Foundation
import WMMModel

// The episode page's wording (PLAN-EPISODE-PAGE.md §2 and §3; EpisodePage,
// EpisodeMenu, TvEpisodePage and TvEpisodeDelete.dc.html), as the Android app
// words it.

extension EpisodeDetail {
    /// The page's main button: "Play · 52 min", or "Resume from 23:10" partway
    /// through; nil when nothing plays.
    public var pagePlayLabel: String? {
        guard playable else { return nil }
        if resumable, let watch { return "Resume from \(formatClock(ms: watch.positionMs))" }
        return runtimeLabel.map { "Play · \($0)" } ?? "Play"
    }

    /// Under the title: "Season 3 · Episode 7", or "Specials · Episode 2".
    public var seasonLine: String { "\(seasonName(season)) · Episode \(number)" }

    /// "The Long Dark", or "Episode 7" for one without a title.
    public var displayTitle: String { title.isEmpty ? "Episode \(number)" : title }

    /// Previous and next: "E06 Slack Water".
    public var shortTitle: String { String(format: "E%02d ", number) + displayTitle }

    /// After the file's tags: "Subtitles: English, Spanish · 4.2 GB".
    public var pageFileLine: String? {
        guard hasFile, let version = versions.first else { return fileNote }
        var languages: [String] = []
        for subtitle in version.subtitles {
            let name = subtitle.label.components(separatedBy: " · ").first ?? subtitle.label
            if !name.isEmpty && !languages.contains(name) { languages.append(name) }
        }
        let subtitles = languages.isEmpty ? nil : "Subtitles: " + languages.prefix(3).joined(separator: ", ")
        return [subtitles, formatBytes(version.sizeBytes)].compactMap { $0 }.joined(separator: " · ")
    }

    /// Apple TV, partway through: "23:10 of 52 min · 29 min left".
    public var progressLine: String? {
        guard resumable, let watch else { return nil }
        let total = runtimeLabel ?? formatDuration(ms: watch.durationMs)
        return "\(formatClock(ms: watch.positionMs)) of \(total) · \(formatRemaining(ms: watch.remainingMs))"
    }

    /// Under a card in the iPhone's season row: "52 min", or "Airs Nov 4"
    /// (the still shows the check and how far it was watched).
    /// A missing episode, which Show missing lists, is "Not in library".
    public func seasonCardLine(today: Date = .now) -> String {
        if aired && !hasFile { return "Not in library" }
        return aired ? runtimeLabel ?? meta(today: today) : meta(today: today)
    }

    /// Under a card in Apple TV's season row: "Aired yesterday · 52 min", "Airs Nov 4", or "Not in library".
    public func tvSeasonCardLine(today: Date = .now) -> String { aired && !hasFile ? "Not in library" : meta(today: today) }

    /// Over the previous or next episode's title: "Previous", "Next", or
    /// "Next · airs Nov 4" for one yet to air.
    public func neighbourKicker(next: Bool, today: Date = .now) -> String {
        let word = next ? "Next" : "Previous"
        guard !aired else { return word }
        let when = meta(today: today)
        return "\(word) · \(when.prefix(1).lowercased())\(when.dropFirst())"
    }
}

/// "Season 3", or "Specials" for season 0.
public func seasonName(_ number: Int) -> String { number == 0 ? "Specials" : "Season \(number)" }

/// The episode menu's season mark (PLAN-EPISODE-PAGE.md §10): "Mark Season 3
/// watched", or "Mark Specials unwatched" once every episode on disk is watched.
public func markSeasonLabel(_ number: Int, watched: Bool) -> String {
    "Mark \(seasonName(number)) \(watched ? "unwatched" : "watched")"
}

/// "Mark show watched", or "Mark show unwatched" once every episode on disk is watched.
public func markShowLabel(watched: Bool) -> String { watched ? "Mark show unwatched" : "Mark show watched" }

/// The episode menu's Show missing: "Show missing episodes" or "Hide missing episodes".
public func missingEpisodesLabel(shown: Bool) -> String { shown ? "Hide missing episodes" : "Show missing episodes" }

extension ShowDetail {
    /// Under the show's name on the show card: "3 seasons".
    public var seasonCountLine: String? {
        let seasonCount = seasons.filter { $0.number > 0 && $0.availableCount > 0 }.count
        return seasonCount > 0 ? plural(seasonCount, "season") : nil
    }
}

extension SeasonSummary {
    /// Beside the season row's heading: "8 episodes".
    public var episodesLine: String { plural(episodeCount, "episode") }

    /// Apple TV's: "7 of 8 episodes" while some are not on disk, else "8 episodes".
    public var tvEpisodesLine: String {
        availableCount < episodeCount ? "\(formatCount(availableCount)) of \(plural(episodeCount, "episode"))" : episodesLine
    }
}

/// A dialog over the episode page (PLAN-EPISODE-PAGE.md §3): the
/// confirmation before deleting, the one that says the file can't be
/// deleted here, and Replace file's.
public struct EpisodeDialog: Hashable, Sendable, Identifiable {
    public enum Kind: Hashable, Sendable { case delete, cantDelete, replace }

    public var kind: Kind
    /// "Delete “The Long Dark”?".
    public var title: String
    /// "Harbor Lights · S03 E07".
    public var subtitle: String
    /// The file and its size: "Harbor.Lights.S03E07.mkv · 4.2 GB".
    public var file: String?
    /// What happens, a paragraph each.
    public var message: [String]
    /// The destructive button: "Delete episode", "Replace file"; nil when there is only OK.
    public var confirm: String?
    /// "Cancel", or "OK" when nothing can be done.
    public var dismiss: String
    /// A file WatchMyMedia recorded: offer "Don’t record it again if it’s shown again", ticked.
    public var recordAgainChoice = false

    /// The recording's choice, ticked by default (PLAN-LIVE-TV-DVR.md §3.1).
    public static let dontRecordAgain = "Don’t record it again if it’s shown again"

    public var id: String { "\(kind) \(title)" }

    /// The confirmation, from what `previewDelete` said: who deletes the
    /// file and where it goes, another episode the file holds, an app that
    /// didn't answer. With no app answering there is nothing to confirm.
    public static func delete(_ episode: EpisodeDetail, showTitle: String, preview: DeletePreview) -> EpisodeDialog {
        let apps = preview.reachable
        var message: [String] = []
        if preview.recording { message.append("WatchMyMedia permanently deletes the file it recorded.") }
        if !apps.isEmpty { message.append(deletesLine(apps) + " " + binLine(apps)) }
        if let other = otherEpisodesLine(apps) { message.append(other) }
        for app in preview.apps {
            if let error = app.error { message.append("Couldn’t reach \(app.connection): \(error). It can’t delete until it answers.") }
        }
        message.append("Your watch history stays.")
        return EpisodeDialog(
            kind: .delete,
            title: "Delete “\(episode.displayTitle)”?",
            subtitle: episode.kicker(showTitle: showTitle),
            file: fileLine(episode, size: preview.sizeBytes),
            message: message,
            confirm: apps.isEmpty && !preview.recording ? nil : "Delete episode",
            dismiss: apps.isEmpty && !preview.recording ? "OK" : "Cancel",
            recordAgainChoice: preview.recording
        )
    }

    /// "Sonarr deletes the file…", or "Sonarr and Sonarr 4K delete the file…".
    private static func deletesLine(_ apps: [DeleteApp]) -> String {
        let who = listed(apps.map(\.connection))
        return apps.count == 1
            ? "\(who) deletes the file and stops monitoring the episode, so it isn’t downloaded again."
            : "\(who) delete the file and stop monitoring the episode, so it isn’t downloaded again."
    }

    /// Where the file goes: each app's recycling bin, or for good.
    private static func binLine(_ apps: [DeleteApp]) -> String {
        if apps.allSatisfy({ $0.recycleBin != nil }) {
            return apps.count == 1 ? "It goes to \(apps[0].connection)’s recycling bin." : "It goes to \(listed(apps.map { "\($0.connection)’s" })) recycling bins."
        }
        if apps.allSatisfy({ $0.recycleBin == nil }) { return "It is deleted for good." }
        return apps.map { $0.recycleBin != nil ? "\($0.connection) moves it to its recycling bin." : "\($0.connection) deletes it for good." }.joined(separator: " ")
    }

    /// "The file also holds episode 8, which goes too."
    private static func otherEpisodesLine(_ apps: [DeleteApp]) -> String? {
        let numbers = Set(apps.flatMap(\.otherEpisodes)).sorted()
        guard !numbers.isEmpty else { return nil }
        let which = numbers.count == 1 ? "episode \(numbers[0])" : "episodes \(listed(numbers.map(String.init)))"
        return "The file also holds \(which), which \(numbers.count == 1 ? "goes" : "go") too."
    }

    /// No Radarr or Sonarr manages the file: there is nothing to delete it
    /// with. Titled as the confirmation is, with OK alone.
    public static func cantDelete(_ episode: EpisodeDetail, showTitle: String) -> EpisodeDialog {
        EpisodeDialog(
            kind: .cantDelete,
            title: "Delete “\(episode.displayTitle)”?",
            subtitle: episode.kicker(showTitle: showTitle),
            file: fileLine(episode, size: 0),
            message: ["WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk."],
            confirm: nil,
            dismiss: "OK"
        )
    }

    /// Replace file, as the web's dialog words it; `app` is the Sonarr that manages the show.
    public static func replace(_ episode: EpisodeDetail, showTitle: String, app: String) -> EpisodeDialog {
        EpisodeDialog(
            kind: .replace,
            title: "Replace this file?",
            subtitle: episode.kicker(showTitle: showTitle),
            file: fileLine(episode, size: 0),
            message: ["\(app) blocks this release and searches for another. The episode is gone until the new copy arrives."],
            confirm: "Replace file",
            dismiss: "Cancel"
        )
    }

    /// The message as one paragraph, for an alert or action sheet.
    public var text: String { message.joined(separator: " ") }

    private static func fileLine(_ episode: EpisodeDetail, size: Int64) -> String? {
        guard episode.hasFile, let version = episode.versions.first else { return nil }
        let name = version.label.components(separatedBy: "/").last ?? version.label
        return "\(name) · \(formatBytes(size > 0 ? size : version.sizeBytes))"
    }
}

/// "Sonarr", "Sonarr and Sonarr 4K", "A, B and C".
func listed(_ items: [String]) -> String {
    guard let last = items.last else { return "" }
    return items.count == 1 ? last : items.dropLast().joined(separator: ", ") + " and " + last
}

extension ReplaceOutcome {
    /// After Replace file: "Sonarr blocked this release and is searching for
    /// another.", with the server's note when it sends one.
    public var message: String {
        let done = blocklisted ? "\(connection) blocked this release and is searching for another." : "\(connection) is searching for another copy."
        return [done, note].compactMap { $0 }.joined(separator: " ")
    }
}

extension DeleteOutcome {
    /// On the show page after a delete: "Deleted “The Long Dark”", and each app that failed.
    public func toast(title: String) -> String {
        (["Deleted “\(title)”"] + failures).joined(separator: ". ")
    }
}
