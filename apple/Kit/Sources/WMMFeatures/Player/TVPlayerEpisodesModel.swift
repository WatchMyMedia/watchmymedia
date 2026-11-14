import Foundation
import Observation
import WMMModel

/// The Apple TV player's Episodes tab (TvPlayerEpisodes.dc.html,
/// PLAN-APPLE-REVIEW.md §6.2): after Info in the system's info panel, for
/// episodes only, the show's seasons above the chosen season's episodes on
/// disk, opening on the one playing. Choosing another plays it from where
/// the person left it; the one playing has nothing to choose.
///
/// What it asks the server, and when:
/// - Nothing to decide whether the tab shows: a request from a show page,
///   Home or a link carries its show and season. A request without them (the
///   next episode started in place) is placed from a season already loaded
///   here, and only otherwise looked up (`playRequest(fileID:)`), and only
///   when the episode before it was a show's.
/// - When the tab first opens: the show (for its seasons) and the playing
///   season, side by side. Each other season once, when it is chosen.
/// - The playing season again when the tab opens after another episode
///   started, since its watched marks have moved on.
@MainActor
@Observable
public final class TVPlayerEpisodesModel {
    /// Where an episode sits: its show and season.
    public struct Place: Hashable, Sendable {
        public var showID: Int
        public var season: Int

        public init(showID: Int, season: Int) {
            self.showID = showID
            self.season = season
        }
    }

    public enum Status: Hashable, Sendable {
        case idle, loading, loaded
        case failed(String)
    }

    /// The show and season of what plays; nil for a film, a recording or a
    /// file with no show, which have no Episodes tab.
    public private(set) var place: Place?
    /// The file playing, marked Now playing in its season.
    public private(set) var playingFileID: Int?
    /// The show's seasons with episodes on disk, and the playing one.
    public private(set) var seasons: [SeasonSummary] = []
    /// The season whose episodes show.
    public private(set) var chosenSeason: Int?
    public private(set) var status: Status = .idle
    public private(set) var showTitle = ""

    /// Each season loaded, by number, with only its episodes on disk.
    @ObservationIgnored private var details: [Int: SeasonDetail] = [:]
    /// Observed copy of the chosen season's detail, so the row redraws when it arrives.
    private var chosenDetail: SeasonDetail?
    @ObservationIgnored private var showBackdropPath: String?
    @ObservationIgnored private var showLoaded = false
    /// The playing season must be asked again the next time the tab opens.
    @ObservationIgnored private var playingStale = false
    @ObservationIgnored private var revision = 0
    @ObservationIgnored private let library: any LibraryRepository

    public init(library: any LibraryRepository) { self.library = library }

    /// The tab shows: what plays is an episode of a show.
    public var hasTab: Bool { place != nil }

    // MARK: - Following the player

    /// What plays now: the request the player opened with, or the episode
    /// that started after it. Places it (see the type's comment for when that
    /// asks the server) and marks it as playing.
    public func follow(_ request: PlayRequest) async {
        playingFileID = request.recordingID == nil ? request.fileID : nil
        guard request.recordingID == nil else { return clear() }
        let next: Place?
        if let showID = request.showID, let season = request.seasonNumber {
            next = Place(showID: showID, season: season)
        } else if let known = loadedPlace(of: request.fileID) {
            next = known
        } else if place != nil {
            // The episode after an episode, started in place without its show.
            let run = bump()
            let resolved = try? await library.playRequest(fileID: request.fileID)
            guard run == revision, playingFileID == request.fileID else { return }
            next = resolved.flatMap { r in r.showID.flatMap { id in r.seasonNumber.map { Place(showID: id, season: $0) } } }
        } else {
            next = nil
        }
        guard let next else { return clear() }
        if next.showID != place?.showID {
            clear()
            place = next
            chosenSeason = next.season
            return
        }
        // Another episode of the same show: its season loads (again) the next time the tab opens.
        place = next
        chosenSeason = next.season
        playingStale = true
        chosenDetail = details[next.season]
    }

    /// The tab opened: loads what it has not yet, or what has moved on since.
    public func open() async {
        guard let place else { return }
        let needSeason = details[place.season] == nil || playingStale
        guard needSeason || !showLoaded else { return }
        let run = bump()
        if details[place.season] == nil { status = .loading }
        // The show (for the season buttons) is asked beside the season, once.
        let showTask: Task<ShowDetail?, Never>? = showLoaded ? nil : Task { [library] in try? await library.show(id: place.showID) }
        do {
            if needSeason {
                let detail = try await library.season(showID: place.showID, number: place.season).episodesOnDisk(keep: nil)
                guard run == revision else { showTask?.cancel(); return }
                store(detail)
                playingStale = false
            }
            if let showTask {
                let show = await showTask.value
                guard run == revision else { return }
                if let show {
                    showLoaded = true
                    showTitle = show.title
                    showBackdropPath = show.backdropPath ?? showBackdropPath
                    seasons = show.seasonsOnDisk(keep: place.season).seasons
                }
            }
            if seasons.isEmpty, let own = details[place.season]?.season { seasons = [own] }
            if chosenSeason == nil || details[chosenSeason ?? place.season] == nil { chosenSeason = place.season }
            chosenDetail = details[chosenSeason ?? place.season]
            status = .loaded
        } catch is CancellationError {
            showTask?.cancel()
        } catch {
            showTask?.cancel()
            guard run == revision else { return }
            status = details[place.season] == nil ? .failed(error.displayMessage) : .loaded
        }
    }

    /// Try again after the tab failed to load.
    public func retry() async {
        await open()
    }

    /// Focus reached a season's button: its episodes show, loaded the first time.
    public func choose(season number: Int) async {
        guard let place, number != chosenSeason || chosenDetail == nil else { return }
        chosenSeason = number
        if let detail = details[number] {
            chosenDetail = detail
            return
        }
        chosenDetail = nil
        let run = bump()
        do {
            let detail = try await library.season(showID: place.showID, number: number).episodesOnDisk(keep: nil)
            guard run == revision else { return }
            store(detail)
            if chosenSeason == number { chosenDetail = detail }
        } catch {
            // The row stays empty with its note; focusing the season again asks again.
            guard run == revision else { return }
            if chosenSeason == number { chosenDetail = nil }
        }
    }

    // MARK: - What the tab shows

    /// The chosen season's episodes on disk; empty while it loads.
    public var episodes: [EpisodeDetail] { chosenDetail?.episodes ?? [] }

    /// The chosen season is still loading (or failed and will be asked again).
    public var chosenSeasonPending: Bool { chosenDetail == nil && status == .loaded }

    public func isPlaying(_ episode: EpisodeDetail) -> Bool {
        guard let playingFileID else { return false }
        return episode.versions.contains { $0.fileID == playingFileID }
    }

    /// The episode on disk after the one playing, in its season: "Next".
    public var nextEpisodeID: Int? {
        guard let place, let season = details[place.season],
              let index = season.episodes.firstIndex(where: isPlaying), index + 1 < season.episodes.count else { return nil }
        return season.episodes[index + 1].id
    }

    /// The card focus starts on: the one playing, or the season's first.
    public var initialFocusID: Int? {
        episodes.first(where: isPlaying)?.id ?? episodes.first?.id
    }

    /// Watched, and not the one playing, which shows its progress instead.
    public func showsWatched(_ episode: EpisodeDetail) -> Bool {
        episode.watched && !isPlaying(episode)
    }

    /// The bar under a card: the playing one's place now, or where the person left another.
    public func progress(_ episode: EpisodeDetail, playingPositionMs: Int, playingDurationMs: Int) -> Double? {
        if isPlaying(episode) {
            guard playingDurationMs > 0 else { return nil }
            return min(max(Double(playingPositionMs) / Double(playingDurationMs), 0), 1)
        }
        guard let watch = episode.watch, watch.resumable else { return nil }
        return watch.progress
    }

    /// "Episode 8", above the title.
    public func numberLabel(_ episode: EpisodeDetail) -> String { "Episode \(episode.number)" }

    /// "52 min" in the card's corner.
    public func durationLabel(_ episode: EpisodeDetail) -> String? {
        let ms = episode.versions.first?.durationMs ?? episode.runtimeMin.map { $0 * 60_000 }
        guard let ms, ms > 0 else { return nil }
        return formatDuration(ms: ms)
    }

    /// "Episode 7, The Long Dark, now playing" or ", watched".
    public func accessibilityLabel(_ episode: EpisodeDetail) -> String {
        var parts = [numberLabel(episode), episode.title]
        if isPlaying(episode) { parts.append("now playing") } else if showsWatched(episode) { parts.append("watched") }
        return parts.joined(separator: ", ")
    }

    /// Under the row for a focused card: "Next · S03 E08 · Squall Line · 48 min",
    /// "Now playing · …" on the one playing.
    public func focusLine(_ episode: EpisodeDetail) -> String {
        let lead = isPlaying(episode) ? "Now playing" : episode.id == nextEpisodeID ? "Next" : nil
        return [lead, episodeCode(season: episode.season, episode: episode.number), episode.title, durationLabel(episode)]
            .compactMap { $0 }.joined(separator: " · ")
    }

    /// Under the row for a focused season: "Season 2 · 6 episodes".
    public func seasonLine(_ season: SeasonSummary) -> String {
        let count = details[season.number]?.episodes.count ?? season.availableCount
        return "\(season.chipName) · \(plural(count, "episode"))"
    }

    /// Under the row while focus is on the tabs: "Season 3 · 8 episodes · you are on episode 7".
    public var panelLine: String? {
        guard let place, let season = details[place.season] else { return nil }
        let playing = season.episodes.first(where: isPlaying).map { "you are on episode \($0.number)" }
        return [season.season.chipName, plural(season.episodes.count, "episode"), playing].compactMap { $0 }.joined(separator: " · ")
    }

    public static let seasonHint = "Move down to the episodes; left and right change the season."
    public static let panelHint = "Move down to choose a season or an episode."
    public static let footnote = "Select or Play/Pause plays the episode from where you left it. Swipe up or press Back to close and keep watching this one."

    /// What choosing `episode` plays: it, resuming; nil for the one playing.
    public func request(for episode: EpisodeDetail) -> PlayRequest? {
        guard !isPlaying(episode), let place else { return nil }
        let season = details[episode.season]
        return episode.playRequest(
            showTitle: season?.showTitle ?? showTitle, backdropPath: season?.showBackdropPath ?? showBackdropPath,
            posterPath: season?.season.posterPath, showID: place.showID
        )
    }

    // MARK: - Private

    private func bump() -> Int {
        revision += 1
        return revision
    }

    private func store(_ detail: SeasonDetail) {
        details[detail.season.number] = detail
        if showTitle.isEmpty { showTitle = detail.showTitle }
        if showBackdropPath == nil { showBackdropPath = detail.showBackdropPath }
    }

    private func loadedPlace(of fileID: Int) -> Place? {
        guard let place else { return nil }
        for detail in details.values where detail.episodes.contains(where: { $0.versions.contains { $0.fileID == fileID } }) {
            return Place(showID: place.showID, season: detail.season.number)
        }
        return nil
    }

    private func clear() {
        _ = bump()
        place = nil
        seasons = []
        chosenSeason = nil
        chosenDetail = nil
        details = [:]
        showLoaded = false
        playingStale = false
        showTitle = ""
        showBackdropPath = nil
        status = .idle
    }
}
