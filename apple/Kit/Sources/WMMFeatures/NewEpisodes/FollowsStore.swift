import Foundation
import Observation
import WMMModel

/// The shows the person follows, shared by every screen of one app
/// (PLAN-NEW-EPISODES.md §7.1, §7.2): the bell on a show's page and Settings ›
/// Notifications' list. A change shows at once and goes back when the server
/// refuses it, as stars and reminders do. The server's follows.changed from
/// the person's other devices brings the list up to date (``listen()``), and
/// a new server or sign-in forgets it. Only from servers at
/// ``followsAPILevel``; otherwise it holds nothing and does nothing. The
/// new-episode check doesn't read it: a show followed on another device
/// notifies too.
@MainActor @Observable
public final class FollowsStore {
    /// The list was loaded for this sign-in.
    public var loaded: Bool { context == repository.contextID && listLoaded }
    public var loading: Bool { loads > 0 }
    /// The list couldn't be loaded.
    public private(set) var error: String?
    /// A change the server refused: the show page says it under the actions
    /// until the bell is pressed again or the page is left.
    public private(set) var failure: (tmdbID: Int, line: String)?

    private var loads = 0
    private var items: [FollowedShow] = []
    private var listLoaded = false
    /// Follows changed here (or read from a show's page) since the list was loaded, by TMDB id.
    private var changed: [Int: Bool] = [:]
    /// Changes the server hasn't answered yet, by TMDB id.
    private var saving: Set<Int> = []
    /// Removes from Settings the server refused, by TMDB id: the row says so until it is tried again.
    private var removeFailures: Set<Int> = []
    @ObservationIgnored private var context: String?
    @ObservationIgnored private let repository: any FollowsRepository
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private let flight = SingleFlight<Void>()
    /// Bumped by every load and change, so a slow load never undoes a newer change.
    @ObservationIgnored private var generation = 0
    /// follows.changed heard, so a refresh after one asks again rather than joining an older request.
    @ObservationIgnored private var heard = 0
    /// Changes made here whose echo from the server's events is still to come.
    @ObservationIgnored private var echoes: [Date] = []
    static let echoWindow: TimeInterval = 30

    public init(repository: any FollowsRepository, now: @escaping @MainActor () -> Date = { Date() }) {
        self.repository = repository
        self.now = now
        context = repository.contextID
    }

    /// The server offers following and someone is signed in.
    public var available: Bool { repository.available }

    /// The shows followed, newest first; empty until loaded.
    public var follows: [FollowedShow] { context == repository.contextID ? items : [] }

    /// The bell is offered for this show.
    public func canFollow(_ show: ShowDetail) -> Bool { available && show.tmdbID != nil }

    /// The bell: a change made here, else the list once loaded, else what the show's page said.
    public func isFollowing(_ show: ShowDetail) -> Bool {
        guard canFollow(show), let tmdbID = show.tmdbID else { return false }
        if context == repository.contextID, let state = changed[tmdbID] { return state }
        if loaded { return items.contains { $0.tmdbID == tmdbID } }
        return show.following ?? false
    }

    /// The server hasn't answered this show's change yet.
    public func isSaving(_ show: ShowDetail) -> Bool {
        guard let tmdbID = show.tmdbID, context == repository.contextID else { return false }
        return saving.contains(tmdbID)
    }

    /// Why the bell went back on this show's page, until it is pressed again or the page is left.
    public func failureLine(for show: ShowDetail) -> String? {
        guard context == repository.contextID, let failure, failure.tmdbID == show.tmdbID else { return nil }
        return failure.line
    }

    /// The show's page was left: its failure line goes.
    public func clearFailure() { failure = nil }

    /// A row's line on Settings › Notifications: why Stop notifying failed,
    /// until it is tried again; else its latest new episode.
    public func line(for follow: FollowedShow) -> (text: String, warning: Bool) {
        if context == repository.contextID, removeFailures.contains(follow.tmdbID) {
            return (NewEpisodeWords.removeFailed(follow.title), true)
        }
        return (NewEpisodeWords.followLine(follow, now: now()), !follow.inLibrary)
    }

    /// Notify me or Notifications on. True once the server has the change.
    @discardableResult
    public func toggle(_ show: ShowDetail) async -> Bool {
        await setFollowing(!isFollowing(show), show: show)
    }

    /// Follows or stops following: the bell changes at once and goes back if
    /// the server refuses, with ``failureLine(for:)`` saying so. True once the server has it.
    @discardableResult
    public func setFollowing(_ on: Bool, show: ShowDetail) async -> Bool {
        guard canFollow(show), let tmdbID = show.tmdbID else { return false }
        syncContext()
        guard isFollowing(show) != on else { return true }
        generation += 1
        let identity = context, before = changed[tmdbID]
        changed[tmdbID] = on
        saving.insert(tmdbID)
        failure = nil
        let echo = expectEcho()
        do {
            if on { try await repository.follow(showID: show.id) } else { try await repository.unfollow(showID: show.id) }
            guard identity == repository.contextID else { return false }
            saving.remove(tmdbID)
            if on {
                if !items.contains(where: { $0.tmdbID == tmdbID }) {
                    items.insert(FollowedShow(tmdbID: tmdbID, title: show.title, followedAt: now(), showID: show.id, posterPath: show.posterPath), at: 0)
                }
            } else {
                items.removeAll { $0.tmdbID == tmdbID }
            }
            return true
        } catch {
            forgetEcho(echo)
            guard identity == repository.contextID else { return false }
            saving.remove(tmdbID)
            changed[tmdbID] = before
            if !(error is CancellationError) {
                let line = (error as? APIError)?.status == 409 && on ? NewEpisodeWords.atLimit
                    : on ? NewEpisodeWords.followFailed(show.title) : NewEpisodeWords.unfollowFailed(show.title)
                failure = (tmdbID, line)
            }
            return false
        }
    }

    /// Stop notifying, from Settings' list: the row goes at once and comes back
    /// if the server refuses, saying so in its own line.
    public func remove(_ follow: FollowedShow) async {
        guard available else { return }
        syncContext()
        generation += 1
        let identity = context
        guard let index = items.firstIndex(where: { $0.tmdbID == follow.tmdbID }) else { return }
        items.remove(at: index)
        removeFailures.remove(follow.tmdbID)
        let before = changed[follow.tmdbID]
        changed[follow.tmdbID] = false
        saving.insert(follow.tmdbID)
        let echo = expectEcho()
        do {
            try await repository.remove(tmdbID: follow.tmdbID)
            guard identity == repository.contextID else { return }
            saving.remove(follow.tmdbID)
        } catch {
            forgetEcho(echo)
            guard identity == repository.contextID else { return }
            saving.remove(follow.tmdbID)
            changed[follow.tmdbID] = before
            if !items.contains(where: { $0.tmdbID == follow.tmdbID }) { items.insert(follow, at: min(index, items.count)) }
            if !(error is CancellationError) { removeFailures.insert(follow.tmdbID) }
        }
    }

    /// Loads the list: at launch, on foreground, after an event and on
    /// Settings › Notifications. Callers at once share one request; one after
    /// an event asks again.
    public func refresh() async {
        await flight.run(stamp: "\(repository.contextID ?? "")#\(heard)") { [weak self] in await self?.load() }
    }

    private func load() async {
        syncContext()
        guard available else { return }
        generation += 1
        let mine = generation, identity = context
        loads += 1
        defer { loads -= 1 }
        do {
            let list = try await repository.follows()
            guard mine == generation, identity == repository.contextID else { return }
            items = list
            listLoaded = true
            error = nil
            // What the list says replaces this device's changes, except those still saving.
            changed = changed.filter { saving.contains($0.key) }
            removeFailures = removeFailures.filter { id in list.contains { $0.tmdbID == id } }
        } catch {
            guard mine == generation, identity == repository.contextID, !(error is CancellationError) else { return }
            self.error = error.displayMessage
        }
    }

    /// Follows the server's events for the person's other devices: follows.changed
    /// asks for the list again; the echo of a change made here asks for nothing.
    /// Runs while the app does.
    public func listen() async {
        for await _ in repository.changes() {
            defer { eventsHeard += 1 }
            guard available else { continue }
            syncContext()
            if consumeEcho() { continue }
            heard += 1
            await refresh()
        }
    }

    /// Events ``listen()`` has finished with, so a test can tell one asked for nothing.
    @ObservationIgnored private(set) var eventsHeard = 0

    // MARK: Private

    private func expectEcho() -> Date {
        let date = now()
        echoes.append(date)
        return date
    }

    private func forgetEcho(_ date: Date) {
        if let i = echoes.firstIndex(of: date) { echoes.remove(at: i) }
    }

    private func consumeEcho() -> Bool {
        let date = now()
        echoes.removeAll { date.timeIntervalSince($0) > Self.echoWindow }
        guard !echoes.isEmpty else { return false }
        echoes.removeFirst()
        return true
    }

    /// A new server or sign-in starts with nothing.
    private func syncContext() {
        guard context != repository.contextID else { return }
        context = repository.contextID
        generation += 1
        items = []; listLoaded = false; changed = [:]; saving = []; removeFailures = []; echoes = []
        error = nil; failure = nil
    }
}
