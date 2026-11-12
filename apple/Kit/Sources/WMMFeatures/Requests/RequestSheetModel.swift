import Foundation
import Observation
import WMMModel

/// The request sheet (SearchRequest.dc.html, PadShowMissing.dc.html's form
/// sheet, TvSearchRequest.dc.html's dialog): the poster, year and overview,
/// a show's seasons to tick, the approval line, the button, then the
/// confirmation. Opened from search's Not in your library, or from a show
/// page's Request season (`preselect`) and Request missing seasons.
@MainActor
@Observable
public final class RequestSheetModel: Identifiable {
    public let mediaType: MediaType
    public let tmdbID: Int
    /// What is known before the lookup answers, so the header shows at once.
    public let placeholder: Requestable?
    public private(set) var state: Loadable<Requestable> = .loading
    /// The seasons ticked.
    public private(set) var chosen: Set<Int> = []
    public private(set) var sending = false
    /// The request made; the sheet shows its confirmation.
    public private(set) var sent: CreatedRequest?
    /// Why the request was refused.
    public private(set) var error: String?

    @ObservationIgnored private let requests: any RequestsRepository
    @ObservationIgnored private let preselect: [Int]?
    @ObservationIgnored private var firstLoad: Task<Void, Never>?

    public nonisolated var id: String { "\(mediaType.rawValue)-\(tmdbID)" }

    public init(requests: any RequestsRepository, mediaType: MediaType, tmdbID: Int, preselect: [Int]? = nil, placeholder: Requestable? = nil) {
        self.requests = requests
        self.mediaType = mediaType
        self.tmdbID = tmdbID
        self.preselect = preselect
        self.placeholder = placeholder
    }

    /// The item as looked up, else as it was known when the sheet opened.
    public var item: Requestable? { state.value ?? placeholder }

    public var title: String { item?.title ?? "" }

    /// "2019 · 4 seasons".
    public var meta: String { item?.sheetMeta ?? "" }

    public var seasons: [RequestableSeason] { mediaType == .tv ? state.value?.seasons ?? [] : [] }

    /// A season's box is ticked: chosen, or greyed and ticked because it is
    /// in the library or asked for already.
    public func isTicked(_ season: RequestableSeason) -> Bool {
        season.askable ? chosen.contains(season.number) : season.state != .unaired
    }

    public func toggle(_ season: RequestableSeason) {
        guard season.askable, !sending else { return }
        if chosen.contains(season.number) { chosen.remove(season.number) } else { chosen.insert(season.number) }
    }

    /// The movie, or the show with something of it asked for or downloading, can't be asked for again.
    public var alreadyAsked: Bool {
        guard let item = state.value else { return false }
        return mediaType == .movie && (item.state == .requested || item.state == .downloading)
    }

    /// A movie that is in the library already.
    public var inLibrary: Bool { mediaType == .movie && state.value?.state == .available }

    /// Under the seasons: "An admin approves requests before anything downloads."; nil when it goes straight through.
    public var approvalNote: String? {
        guard requests.options.needsApproval else { return nil }
        return requests.options.viaSeerr ? seerrApprovalNote : requestApprovalNote
    }

    /// "Request season 3", "Request 2 seasons", "Request movie"; "Requested" once asked.
    public var sendLabel: String {
        if alreadyAsked { return "Requested" }
        return mediaType == .tv && chosen.isEmpty ? "Request" : requestButtonLabel(mediaType, seasons: chosen.sorted())
    }

    public var canSend: Bool {
        guard state.value != nil, !sending, !alreadyAsked, !inLibrary else { return false }
        return mediaType == .movie || !chosen.isEmpty
    }

    /// The confirmation, once sent.
    public var sentMessage: String? { sent?.sentMessage(viaSeerr: requests.options.viaSeerr) }

    /// The confirmation links to Your requests.
    public var sentToYourRequests: Bool { sent.map { ($0.created || $0.request.mine) } ?? false }

    /// The first load, however many ask for it: the screen that opens the
    /// sheet starts it, so iPad's form sheet can open at its full height, and
    /// the sheet waits for it.
    public func loadOnce() async {
        if firstLoad == nil { firstLoad = Task { await self.load() } }
        await firstLoad?.value
    }

    /// Ready to show at its full height: looked up, or failed with why.
    public var settled: Bool { !state.isLoading }

    public func load() async {
        if state.value == nil { state = .loading }
        guard let result = await Loadable.run({ try await self.requests.lookup(self.mediaType, tmdbID: self.tmdbID) }) else { return }
        if let item = result.value, state.value == nil { chosen = item.initialSeasons(preselect: preselect) }
        state = result
    }

    public func send() async {
        guard canSend else { return }
        sending = true
        error = nil
        defer { sending = false }
        do {
            sent = try await requests.ask(mediaType, tmdbID: tmdbID, seasons: chosen.sorted())
            await requests.refreshOptions()
        } catch is CancellationError {
        } catch {
            self.error = error.displayMessage
        }
    }
}
