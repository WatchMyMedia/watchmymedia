import Foundation
import WMMModel

/// The check each iPhone and iPad runs for itself (PLAN-NEW-EPISODES.md §6.1):
/// ask the feed after this device's cursor, post one notification a show,
/// then keep the new cursor. It runs in the background refresh after the
/// downloads' sync, when the app opens or comes back, and three minutes after
/// the last library change while it is open, whenever this device could
/// notify: someone is signed in to a server that offers following, this
/// device's switch is on and the system lets the notifications through. It
/// doesn't go by the follows this device last saw, since a show followed on
/// another device (a TV) notifies here too; the feed for someone who follows
/// nothing is empty, and the cursor kept from it catches a follow made
/// elsewhere from when it was made. Turning the switch off forgets the
/// cursor, so turning it on starts from now; so do a sign-out and another
/// server or person (``accountChanged()``). A first check (no cursor yet)
/// only keeps the cursor. A server that can't be reached, or answers 404 as
/// a proxy does while it restarts, changes nothing, and the next check
/// catches up. The server holds each arrival back two minutes; the check
/// three minutes after a library change comes after that, and a check asked
/// for while one runs runs again once it ends. A check stops when the task
/// that asked is cancelled (the background refresh's time running out),
/// keeping the cursor.
@MainActor
public final class NewEpisodeChecker {
    public enum Outcome: Hashable, Sendable {
        /// Nobody is signed in, or the server is below API level 29.
        case unavailable
        /// This device's switch is off.
        case switchedOff
        /// The system doesn't let the notifications through.
        case notAllowed
        /// No cursor yet: the server's is kept and nothing is posted.
        case started
        /// Asked after the cursor: `posted` notifications.
        case checked(posted: Int)
        /// The server couldn't be reached, the check was cancelled, or the
        /// sign-in or the switch changed meanwhile: the cursor stays.
        case unreachable
    }

    /// How long after the last library change an open app checks: past the
    /// two minutes the server holds an arrival back.
    public static let afterLibraryChange: Duration = .seconds(180)

    private let repository: any FollowsRepository
    private let memory: NewEpisodeMemory
    private let permission: @MainActor () async -> NotificationPermission
    private let post: @MainActor (NewEpisodeNotice) async -> Void
    private let sleep: @MainActor (Duration) async throws -> Void
    private var running: Task<Outcome, Never>?
    /// A check was asked for while one ran: it runs again when that one ends.
    private var again = false

    /// - Parameters:
    ///   - permission: what the system says of the app's notifications now.
    ///   - post: shows one notification; they come in the order to post them.
    ///   - sleep: waits out the library's three minutes; tests pass a clock they move.
    public init(repository: any FollowsRepository, memory: NewEpisodeMemory,
                permission: @escaping @MainActor () async -> NotificationPermission,
                post: @escaping @MainActor (NewEpisodeNotice) async -> Void,
                sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.repository = repository
        self.memory = memory
        self.permission = permission
        self.post = post
        self.sleep = sleep
    }

    /// Checks. A caller while a check runs shares it, and it runs once more
    /// when it ends, since what that caller wants checked may have come after
    /// the running one asked the server. Cancelling the caller cancels the
    /// check, so a background task whose time is up isn't kept waiting on a
    /// server that doesn't answer.
    @discardableResult
    public func check() async -> Outcome {
        if let running {
            again = true
            return await withTaskCancellationHandler { await running.value } onCancel: { running.cancel() }
        }
        var outcome: Outcome
        repeat {
            again = false
            let task = Task { await self.run() }
            running = task
            outcome = await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
        } while again && !Task.isCancelled
        running = nil
        return outcome
    }

    /// A real sign-out, or a sign-in to another server or as another person
    /// (not a Switch server that was cancelled): what was kept for anyone
    /// else goes, so the next sign-in starts from now.
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
        // Nobody signed in (a sign-out, or Switch server between servers):
        // nothing is asked and nothing forgotten here; accountChanged() forgets.
        guard repository.available, let context = repository.contextID, let serverID = repository.serverID else {
            return .unavailable
        }
        // Signed in to another server or as another person: theirs starts from now.
        memory.forget(unlessFor: context)
        guard memory.notifyHere else {
            memory.forgetCursor()
            return .switchedOff
        }
        guard await permission() == .allowed else { return .notAllowed }
        guard !Task.isCancelled else { return .unreachable }
        let cursor = memory.cursor(for: context)
        let page: NewEpisodesPage
        do {
            page = try await repository.newEpisodes(after: cursor)
        } catch {
            return .unreachable
        }
        // Each time it comes back: the sign-in gone, the switch off, or the time up keeps the cursor and posts no more.
        func stillWanted() -> Bool { context == repository.contextID && memory.notifyHere && !Task.isCancelled }
        guard stillWanted() else { return .unreachable }
        guard let cursor else {
            memory.keep(cursor: page.cursor, for: context)
            return .started
        }
        let notices = newEpisodePostingOrder(newEpisodeNotices(page, serverID: serverID))
        var posted = 0
        for notice in notices {
            guard stillWanted() else { return .unreachable }
            await post(notice)
            posted += 1
        }
        guard stillWanted() else { return .unreachable }
        memory.keep(cursor: page.cursor, for: context)
        return .checked(posted: posted)
    }
}
