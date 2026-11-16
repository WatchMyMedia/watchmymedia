import Foundation
import WMMModel

/// The check each iPhone and iPad runs for itself (PLAN-NEW-EPISODES.md §6.1):
/// ask the feed after this device's cursor, post one notification a show,
/// then keep the new cursor. It runs in the background refresh after the
/// downloads' sync, when the app opens or comes back, and three minutes after
/// the last library change while it is open, but only when it has something
/// to do: the server offers following, the person follows a show, this
/// device's switch is on and the system lets the notifications through.
/// Otherwise the cursor is forgotten, so the next check that may notify
/// starts from now. A first check (no cursor yet) only keeps the cursor. A
/// server that can't be reached changes nothing, and the next check catches up.
@MainActor
public final class NewEpisodeChecker {
    public enum Outcome: Hashable, Sendable {
        /// Nobody is signed in, or the server is below API level 29.
        case unavailable
        /// This device's switch is off.
        case switchedOff
        /// The system doesn't let the notifications through.
        case notAllowed
        /// The person follows no show.
        case followsNothing
        /// No cursor yet: the server's is kept and nothing is posted.
        case started
        /// Asked after the cursor: `posted` notifications.
        case checked(posted: Int)
        /// The server couldn't be reached, or the sign-in changed meanwhile: the cursor stays.
        case unreachable
    }

    /// How long after the last library change an open app checks.
    public static let afterLibraryChange: Duration = .seconds(180)

    private let repository: any FollowsRepository
    private let memory: NewEpisodeMemory
    private let permission: @MainActor () async -> NotificationPermission
    private let post: @MainActor ([NewEpisodeNotice]) async -> Void
    private let sleep: @MainActor (Duration) async throws -> Void
    private var running: Task<Outcome, Never>?

    /// - Parameters:
    ///   - permission: what the system says of the app's notifications now.
    ///   - post: shows the notifications.
    ///   - sleep: waits out the library's three minutes; tests pass a clock they move.
    public init(repository: any FollowsRepository, memory: NewEpisodeMemory,
                permission: @escaping @MainActor () async -> NotificationPermission,
                post: @escaping @MainActor ([NewEpisodeNotice]) async -> Void,
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.repository = repository
        self.memory = memory
        self.permission = permission
        self.post = post
        self.sleep = sleep
    }

    /// Checks once; a caller while a check runs shares it.
    @discardableResult
    public func check() async -> Outcome {
        if let running { return await running.value }
        let task = Task { await self.run() }
        running = task
        let outcome = await task.value
        running = nil
        return outcome
    }

    /// Sign-out or another server: what was kept for anyone else goes, so
    /// the next sign-in starts from now.
    public func accountChanged() {
        memory.forget(unlessFor: repository.contextID)
    }

    /// While the app is open: checks ``afterLibraryChange`` after the last
    /// change to a library (or after the event stream came back, which may
    /// have missed one). Each change starts the wait again. Runs until the
    /// stream ends or the task is cancelled.
    public func followLibrary(_ changes: AsyncStream<LibraryChange>) async {
        var pending: Task<Void, Never>?
        defer { pending?.cancel() }
        for await change in changes {
            switch change {
            case .library, .unknown: break
            case .watched, .markedShow, .scan: continue
            }
            pending?.cancel()
            pending = Task { [weak self, sleep] in
                guard (try? await sleep(Self.afterLibraryChange)) != nil, !Task.isCancelled else { return }
                await self?.check()
            }
        }
    }

    private func run() async -> Outcome {
        guard repository.available, let context = repository.contextID, let serverID = repository.serverID else {
            memory.forget(unlessFor: repository.contextID)
            return .unavailable
        }
        memory.forget(unlessFor: context)
        guard memory.notifyHere else {
            memory.forgetCursor()
            return .switchedOff
        }
        guard await permission() == .allowed else {
            memory.forgetCursor()
            return .notAllowed
        }
        if memory.followsAny(for: context) == nil {
            do {
                let follows = try await repository.follows()
                guard context == repository.contextID else { return .unreachable }
                memory.setFollowsAny(!follows.isEmpty, for: context)
            } catch {
                return .unreachable
            }
        }
        guard memory.followsAny(for: context) == true else {
            memory.forgetCursor()
            return .followsNothing
        }
        let cursor = memory.cursor(for: context)
        let page: NewEpisodesPage
        do {
            page = try await repository.newEpisodes(after: cursor)
        } catch {
            return .unreachable
        }
        // The reply was for a sign-in that has gone, or the switch went off meanwhile.
        guard context == repository.contextID, memory.notifyHere else { return .unreachable }
        guard let cursor else {
            memory.keep(cursor: page.cursor, for: context)
            return .started
        }
        let notices = newEpisodeNotices(page, serverID: serverID)
        if !notices.isEmpty { await post(notices) }
        memory.keep(cursor: page.cursor, for: context)
        return .checked(posted: notices.count)
    }
}
