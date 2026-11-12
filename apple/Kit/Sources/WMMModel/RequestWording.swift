import Foundation

// How requests, downloads and missing episodes are worded (PLAN-ARR.md §6,
// §7), word for word as the web says them (web/src/lib/requests.ts) and the
// Android app does, so every client reads alike.

/// A status line's colour: gold, light grey, orange or mid grey.
public enum StatusTone: Hashable, Sendable { case accent, plain, warning, muted }

/// The small icon before a status line.
public enum StatusIcon: Hashable, Sendable {
    case download, waiting, failed, requested, approved, looking, notMonitored, airs, missing, declined, inLibrary
}

/// A missing episode's or a request's state, as one line with its icon and colour.
public struct StatusLine: Hashable, Sendable {
    public var text: String
    public var tone: StatusTone
    public var icon: StatusIcon
    /// 0–1 while downloading: the bar under the line.
    public var progress: Double?

    public init(_ text: String, tone: StatusTone, icon: StatusIcon, progress: Double? = nil) {
        self.text = text
        self.tone = tone
        self.icon = icon
        self.progress = progress
    }
}

/// "12 min left", "1 h 20 min left", "1 h left"; nil without a time.
public func timeLeft(until: Date?, now: Date = .now) -> String? {
    guard let until else { return nil }
    let minutes = max(1, Int((until.timeIntervalSince(now) / 60).rounded()))
    if minutes < 60 { return "\(minutes) min left" }
    let h = minutes / 60, m = minutes % 60
    return m > 0 ? "\(h) h \(m) min left" : "\(h) h left"
}

/// "Wed 4 Nov": when a missing episode airs, in the order the web and Android say it.
public func airsOn(_ iso: String?) -> String? {
    guard let day = parseDay(iso) else { return nil }
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_GB")
    f.setLocalizedDateFormatFromTemplate("EEEdMMM")
    return f.string(from: day).replacingOccurrences(of: ",", with: "")
}

/// When a request or report was made: "just now", "2 h ago", "yesterday",
/// "3 days ago", then "Oct 26".
public func formatWhen(_ date: Date, now: Date = .now, calendar: Calendar = .current) -> String {
    if now.timeIntervalSince(date) < 86_400 { return formatAgo(date, now: now) }
    let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
    switch days {
    case ...1: return "yesterday"
    case 2...6: return "\(days) days ago"
    default:
        var style = Date.FormatStyle.dateTime.month(.abbreviated).day()
        style.timeZone = calendar.timeZone
        if calendar.component(.year, from: date) != calendar.component(.year, from: now) { style = style.year() }
        return date.formatted(style)
    }
}

extension QueueDownload {
    /// "Downloading 45% · 12 min left", "Waiting to download", "Waiting to import", "Download failed".
    public func line(now: Date = .now) -> StatusLine {
        switch state {
        case .failed: return StatusLine("Download failed", tone: .warning, icon: .failed)
        case .importing: return StatusLine("Waiting to import", tone: .plain, icon: .waiting)
        case .queued: return StatusLine("Waiting to download", tone: .plain, icon: .waiting)
        case .downloading:
            let text = ["Downloading \(percent)", timeLeft(until: finishesAt, now: now)].compactMap { $0 }.joined(separator: " · ")
            return StatusLine(text, tone: .accent, icon: .download, progress: progress)
        }
    }

    /// "45%".
    public var percent: String { "\(Int((progress * 100).rounded()))%" }
}

extension EpisodeDetail {
    /// Why an episode without a file is missing, most telling first: its
    /// download, a request, its air date, Sonarr's monitoring.
    public func missingLine(now: Date = .now) -> StatusLine {
        if let download { return download.line(now: now) }
        if let request, request.status.isOpen, aired {
            return request.status == .pending
                ? StatusLine("Requested by \(request.mine ? "you" : request.requestedBy) · Waiting for approval", tone: .plain, icon: .requested)
                : StatusLine("Approved · Sonarr is searching", tone: .plain, icon: .approved)
        }
        if !aired { return StatusLine(airsOn(airDate).map { "Airs \($0)" } ?? "Not aired yet", tone: .muted, icon: .airs) }
        switch monitored {
        case true?: return StatusLine("Missing · Sonarr is looking for it", tone: .muted, icon: .looking)
        case false?: return StatusLine("Not monitored", tone: .muted, icon: .notMonitored)
        case nil: return StatusLine("Not in library", tone: .muted, icon: .missing)
        }
    }

    /// Missing or yet to air: drawn greyed, with ``missingLine(now:)``.
    public var isMissing: Bool { !hasFile }
}

extension SeasonDetail {
    /// "4 of 7 episodes · 3 missing", counting the episodes that have aired
    /// (or are here), or "8 episodes" when none is missing.
    public var missingSummary: String {
        let counted = episodes.filter { $0.aired || $0.hasFile }
        let here = counted.filter(\.hasFile).count
        let missing = counted.count - here
        if missing == 0 { return plural(counted.count, "episode") }
        return "\(here) of \(counted.count) episodes · \(missing) missing"
    }

    /// Aired episodes without a file, which Search now and Monitor act on.
    public var missingEpisodes: [EpisodeDetail] { episodes.filter { $0.aired && !$0.hasFile } }
}

extension SeasonSummary {
    /// Somebody asked for it and is still waiting.
    public var requestOpen: Bool { request?.status.isOpen ?? false }

    /// It can be asked for: some of it aired and is missing, and nobody asked already.
    public func requestable(today: Date = .now) -> Bool {
        guard availableCount < episodeCount, let aired = parseDay(airDate), aired <= today else { return false }
        return !requestOpen
    }
}

extension ShowDetail {
    /// Seasons that can be asked for, for Request missing seasons.
    public func requestableSeasons(today: Date = .now) -> [SeasonSummary] {
        seasons.filter { $0.requestable(today: today) }
    }
}

/// "Movie", "Season 2", "Specials", "Seasons 1 and 3", "Seasons 1–4".
public func requestWhat(_ type: MediaType, seasons: [Int]) -> String {
    guard type == .tv else { return "Movie" }
    let s = seasons.sorted()
    guard let first = s.first, let last = s.last else { return "Seasons" }
    if s.count == 1 { return first == 0 ? "Specials" : "Season \(first)" }
    let run = zip(s, s.dropFirst()).allSatisfy { $1 == $0 + 1 }
    if run && s.count > 2 { return "Seasons \(first)–\(last)" }
    return "Seasons \(s.dropLast().map(String.init).joined(separator: ", ")) and \(last)"
}

/// The request sheet's button: "Request movie", "Request season 3", "Request specials", "Request 2 seasons".
public func requestButtonLabel(_ type: MediaType, seasons: [Int]) -> String {
    guard type == .tv else { return "Request movie" }
    if seasons.count == 1 { return seasons[0] == 0 ? "Request specials" : "Request season \(seasons[0])" }
    return "Request \(seasons.count) seasons"
}

/// Under the seasons when an admin must approve.
public let requestApprovalNote = "An admin approves requests before anything downloads."

extension MediaRequest {
    /// "Season 2", "Movie".
    public var what: String { requestWhat(mediaType, seasons: seasons) }

    /// Where it stands in Your requests: "Waiting for approval", "Downloading 45%",
    /// "Approved", "Declined · “note”", "Failed · note", "In your library".
    public var stateLine: StatusLine {
        switch status {
        case .pending: return StatusLine("Waiting for approval", tone: .plain, icon: .waiting)
        case .declined: return StatusLine(note.map { "Declined · “\($0)”" } ?? "Declined", tone: .muted, icon: .declined)
        case .failed: return StatusLine(note.map { "Failed · \($0)" } ?? "Failed", tone: .warning, icon: .failed)
        case .available: return StatusLine("In your library", tone: .accent, icon: .inLibrary)
        case .approved:
            if let download, download.state == .downloading {
                return StatusLine("Downloading \(download.percent)", tone: .accent, icon: .download, progress: download.progress)
            }
            return StatusLine("Approved", tone: .plain, icon: .approved)
        }
    }

    /// The Inbox's "Season 2 of " before the title; nil for a movie.
    public var inboxSeasons: String? { mediaType == .tv ? what : nil }
}

extension RequestableSeason.State {
    /// The note at the end of a season's row in the request sheet; nil for a missing season.
    public var label: String? {
        switch self {
        case .available: "In library"
        case .partial: "Partly in library"
        case .requested: "Requested"
        case .unaired: "Not aired yet"
        case .missing: nil
        }
    }
}

extension RequestableSeason {
    /// "Season 3", or "Specials".
    public var title: String { number == 0 ? "Specials" : (name.isEmpty ? "Season \(number)" : name) }

    /// "8 episodes".
    public var episodesLine: String { plural(episodeCount, "episode") }
}

extension Requestable {
    /// "TV show · 2019", "Movie · 2024", in search's Not in your library.
    public var searchLine: String {
        [mediaType == .movie ? "Movie" : "TV show", year.map(String.init)].compactMap { $0 }.joined(separator: " · ")
    }

    /// The request sheet's facts: "2019 · 4 seasons", "2024".
    public var sheetMeta: String {
        let count = seasons.filter { $0.number > 0 }.count
        return [year.map(String.init), mediaType == .tv && count > 0 ? plural(count, "season") : nil].compactMap { $0 }.joined(separator: " · ")
    }

    /// The seasons ticked when the sheet opens: `preselect` (a show page's
    /// Request season) where it can be asked for, else every one that can.
    public func initialSeasons(preselect: [Int]? = nil) -> Set<Int> {
        let askable = seasons.filter(\.askable).map(\.number)
        guard let preselect else { return Set(askable) }
        return Set(preselect.filter(askable.contains))
    }
}

extension CreatedRequest {
    /// The sheet's confirmation: "Requested. You’ll find it in Your requests.",
    /// "Sent to Seerr. You’ll find it in Your requests.", or, when someone else asked first, who did.
    public func sentMessage(viaSeerr: Bool) -> String {
        if created || request.mine {
            return viaSeerr ? "Sent to Seerr. You’ll find it in Your requests." : "Requested. You’ll find it in Your requests."
        }
        return "\(request.requestedBy) asked for this already. It’s in their requests, and you’ll see it here when it arrives."
    }
}

/// Before submitting a request that needs approval in Seerr.
public let seerrApprovalNote = "Requests go to Seerr. Approval, quotas and notifications happen there."

/// Above the note in Report a problem.
public let reportAttachNote = "We’ll attach the file name, codecs and resolution."

/// After a report is sent.
public let reportSentMessage = "Sent. An admin will see it in their Inbox."

extension Problem {
    /// The server's actual destination, including a local fallback after Seerr refuses an issue.
    public var sentMessage: String {
        seerrIssueID == nil ? reportSentMessage : "Sent to Seerr as an issue. Admins hear about it as Seerr is set up to tell them."
    }
}

/// The declining admin's note field: "Tell Alex why (optional)".
public func declinePrompt(_ name: String) -> String { "Tell \(name) why (optional)" }

/// The Settings tab's VoiceOver label: "Settings, 4 in the Inbox".
public func settingsTabLabel(inbox: Int) -> String { inbox > 0 ? "Settings, \(inbox) in the Inbox" : "Settings" }
