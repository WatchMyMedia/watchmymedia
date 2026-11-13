import Foundation
import Observation
import WMMModel

/// Settings › Your requests (MyRequests.dc.html): each request's state,
/// newest first, with Cancel request while it waits.
@MainActor
@Observable
public final class MyRequestsModel {
    public private(set) var state: Loadable<[MediaRequest]> = .loading
    /// The request being cancelled.
    public private(set) var cancelling: Int?
    public var message: String?

    @ObservationIgnored private let requests: any RequestsRepository
    /// An older reply never overwrites a newer one. A load runs to its end,
    /// as Cancel and the Inbox's actions wait for theirs.
    @ObservationIgnored private let loads = LoadController<[MediaRequest]>(cancelsOlder: false)

    public init(requests: any RequestsRepository) {
        self.requests = requests
    }

    public var emptyMessage: String {
        requests.options.viaSeerr && !requests.options.canRequest
            ? "Your requests will appear here."
            : "Ask for a movie or show from Search, or a missing season from its show."
    }

    /// Loads the list, then again whenever requests change, until cancelled.
    public func run() async {
        let changes = requests.changes()
        await load(quiet: state.value != nil)
        for await _ in changes { await load(quiet: true) }
    }

    public func retry() async { await load(quiet: false) }

    public func cancel(_ request: MediaRequest) async {
        guard cancelling == nil, request.status == .pending else { return }
        cancelling = request.id
        defer { cancelling = nil }
        do {
            try await requests.cancel(requestID: request.id)
            if case .loaded(let list) = state { state = .loaded(list.filter { $0.id != request.id }) }
        } catch is CancellationError {
        } catch {
            message = error.displayMessage
        }
    }

    private func load(quiet: Bool) async {
        await loads.run { [weak self] load in
            guard let self else { return }
            loads.begin(&state, quiet: quiet)
            guard let result = await loads.fetch(load, { try await self.requests.mine() }) else { return }
            if let text = loads.quietFailure(result, quiet: quiet, over: state) { message = text } else { state = result }
        }
    }
}

/// Settings › Inbox, for admins (Inbox.dc.html): requests to approve or
/// decline with a note, and reported problems to mark resolved. Each change
/// refreshes the count on the Settings tab.
@MainActor
@Observable
public final class InboxModel {
    public enum Tab: String, CaseIterable, Hashable, Sendable {
        case requests, problems

        public var title: String { self == .requests ? "Requests" : "Problems" }
    }

    public var tab: Tab = .requests
    public private(set) var state: Loadable<Inbox> = .loading
    /// The request whose note field is open.
    public private(set) var declining: Int?
    /// The admin's note to whoever asked, while declining.
    public var note = ""
    /// A request or problem being acted on.
    public private(set) var busy: Int?
    public var message: String?

    @ObservationIgnored private let requests: any RequestsRepository
    /// An older reply never overwrites a newer one. A load runs to its end,
    /// as an approval or a resolve waits for its own.
    @ObservationIgnored private let loads = LoadController<Inbox>(cancelsOlder: false)

    public init(requests: any RequestsRepository, tab: Tab = .requests) {
        self.requests = requests
        self.tab = tab
    }

    /// Approvals happen in Seerr when it carries requests out; the Requests tab says so.
    public var viaSeerr: Bool { requests.options.viaSeerr }

    public var requestList: [MediaRequest] { state.value?.requests ?? [] }

    public var seerrRequestsURL: URL? { state.value?.seerr?.requestsURL }

    public var problems: [Problem] { state.value?.problems ?? [] }

    /// The tabs' counts: waiting for a decision, and open problems; nil for none.
    public func count(_ tab: Tab) -> Int? {
        let n = tab == .requests ? requestList.filter(\.needsDecision).count + (state.value?.seerr?.pending ?? 0) : problems.count
        return n > 0 ? n : nil
    }

    public func run() async {
        let changes = requests.changes()
        await load(quiet: state.value != nil)
        for await _ in changes { await load(quiet: true) }
    }

    public func retry() async { await load(quiet: false) }

    public func approve(_ request: MediaRequest) async {
        await act(request.id) { try await self.requests.decide(requestID: request.id, approve: true, note: "") }
    }

    /// Opens the note field under a request.
    public func startDeclining(_ request: MediaRequest) {
        declining = request.id
        note = ""
    }

    public func stopDeclining() {
        declining = nil
        note = ""
    }

    public func decline(_ request: MediaRequest) async {
        let note = self.note
        await act(request.id) { try await self.requests.decide(requestID: request.id, approve: false, note: note) }
        if declining == request.id, message == nil { stopDeclining() }
    }

    public func resolve(_ problem: Problem) async {
        await act(problem.id) { try await self.requests.resolve(problemID: problem.id) }
    }

    private func act(_ id: Int, _ operation: () async throws -> Void) async {
        guard busy == nil else { return }
        busy = id
        message = nil
        defer { busy = nil }
        do {
            try await operation()
            await load(quiet: true)
            await requests.refreshOptions()
        } catch is CancellationError {
        } catch {
            message = error.displayMessage
        }
    }

    private func load(quiet: Bool) async {
        await loads.run { [weak self] load in
            guard let self else { return }
            loads.begin(&state, quiet: quiet)
            guard let result = await loads.fetch(load, { try await self.requests.inbox() }) else { return }
            if let text = loads.quietFailure(result, quiet: quiet, over: state) { message = text } else { state = result }
        }
    }
}
