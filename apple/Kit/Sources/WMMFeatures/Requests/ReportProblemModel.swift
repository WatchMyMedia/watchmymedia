import Foundation
import Observation
import WMMModel

/// Report a problem (ReportProblem.dc.html), from a movie's or show's More
/// menu: the kind, a note, and what the server will attach.
@MainActor
@Observable
public final class ReportProblemModel: Identifiable {
    /// What is reported on.
    public struct Target: Hashable, Sendable {
        public var item: WatchKind
        public var id: Int
        /// The file that would play; the server attaches its name, codecs and resolution.
        public var fileID: Int?
        /// That file, when its details are known here.
        public var version: MediaVersion?
        /// Over the heading: "Harbor Lights · S03 E07 The Long Dark", "Dead Reckoning Bay · 4K Dolby Vision".
        public var kicker: String

        public init(item: WatchKind, id: Int, fileID: Int?, version: MediaVersion? = nil, kicker: String) {
            self.item = item
            self.id = id
            self.fileID = fileID
            self.version = version
            self.kicker = kicker
        }

        /// An episode of `showTitle`.
        public static func episode(_ episode: EpisodeDetail, showTitle: String) -> Target {
            let v = episode.versions.first
            return Target(item: .episode, id: episode.id, fileID: v?.fileID, version: v, kicker: episodeKicker(showTitle, season: episode.season, episode: episode.number, title: episode.title))
        }

        /// A show's next episode, when its season is not loaded.
        public static func nextUp(_ next: NextUp, showTitle: String) -> Target {
            Target(item: .episode, id: next.episodeID, fileID: next.fileID, kicker: episodeKicker(showTitle, season: next.season, episode: next.episode, title: next.title))
        }

        /// A movie, its first version.
        public static func movie(_ movie: MovieDetail) -> Target {
            let v = movie.versions.first
            return Target(item: .movie, id: movie.id, fileID: v?.fileID, version: v, kicker: [movie.title, v?.title].compactMap { $0 }.joined(separator: " · "))
        }

        private static func episodeKicker(_ show: String, season: Int, episode: Int, title: String) -> String {
            "\(show) · \(episodeCode(season: season, episode: episode))\(title.isEmpty ? "" : " \(title)")"
        }
    }

    public let target: Target
    public private(set) var kind: ProblemKind?
    public var note = ""
    public private(set) var sending = false
    public private(set) var sent = false
    public private(set) var sentMessage = reportSentMessage
    public private(set) var error: String?

    @ObservationIgnored private let requests: any RequestsRepository

    public nonisolated let id = UUID()

    public init(requests: any RequestsRepository, target: Target) {
        self.requests = requests
        self.target = target
    }

    /// "Harbor.Lights.S03E07.2160p.mkv · HEVC · EAC3 5.1 · 4K": the file, as the server will describe it.
    public var fileLine: String? {
        guard let v = target.version else { return nil }
        return [v.label, codecLabel(v.videoCodec), audioLabel(v.audioSummary), v.quality].compactMap { $0 }.joined(separator: " · ")
    }

    public func pick(_ kind: ProblemKind) {
        guard !sending else { return }
        self.kind = kind
    }

    public var canSend: Bool { kind != nil && !sending && !sent }

    public func send() async {
        guard canSend, let kind else { return }
        sending = true
        error = nil
        defer { sending = false }
        do {
            let report = try await requests.report(target.item, id: target.id, fileID: target.fileID, kind: kind, note: note)
            sentMessage = report.sentMessage
            sent = true
        } catch is CancellationError {
        } catch {
            self.error = error.displayMessage
        }
    }
}
