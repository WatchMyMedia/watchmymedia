import Foundation
import Observation
import WMMModel

/// The person's watchlists, shared by every screen of one app: the
/// Watchlist button on title pages, the long-press menus, Save to
/// watchlist, the Watchlists page and a watchlist's own page, on iPhone,
/// iPad and Apple TV alike.
///
/// - With one watchlist or none, the button and the menus toggle at once:
///   Save makes Watchlist when there is none. With two or more they open
///   Save to watchlist (``picker``). The count comes from the list, read at
///   launch, on foreground and on the server's rows.changed and
///   lists.changed; a 409 from a count gone stale opens the picker.
/// - Every change shows at once and goes back if the server refuses it,
///   with the server's words in ``message``. Which watchlists hold a title
///   is kept here for each title changed on this device (or whose page was
///   read), over what a card's `watchlisted` said, so cards elsewhere on
///   screen follow without a refetch.
/// - Phones and tablets (`confirms`) say "Saved to Watchlist" or "Removed
///   from Watchlist" with Undo (``banner``). A removal waits while the
///   banner shows, so Undo sends nothing; Undo after a save removes the
///   title again. Apple TV says nothing: the button's words change.
/// - A new server or sign-in starts with nothing. Below API level 33 it
///   holds nothing and offers nothing.
@MainActor @Observable
public final class WatchlistStore {
    /// "Saved to Watchlist" with Undo, over the bottom of the screen.
    public struct Banner: Hashable, Identifiable, Sendable {
        public let id: Int
        public let text: String
        /// A save (the bookmark filled), not a removal.
        public let saved: Bool
    }

    /// What a card's long press offers for its title.
    public enum MenuItem: Hashable, Sendable {
        /// "Add to Watchlist": one watchlist (or none yet), the title not on it.
        case add(String)
        /// "Remove from Watchlist".
        case remove(String)
        /// "Save to watchlist…": two or more watchlists.
        case choose

        public var words: String {
            switch self {
            case .add(let name): WatchlistWords.add(to: name)
            case .remove(let name): WatchlistWords.remove(from: name)
            case .choose: WatchlistWords.saveToSeveral
            }
        }

        /// The menu's icon: a bookmark, struck through to remove.
        public var systemImage: String {
            switch self {
            case .add, .choose: "bookmark"
            case .remove: "bookmark.slash"
            }
        }
    }

    /// The person's watchlists in the order made; empty until loaded.
    public var lists: [Watchlist] { context == repository.contextID ? items : [] }
    public var loaded: Bool { context == repository.contextID && listLoaded }
    /// The list couldn't be loaded.
    public private(set) var error: String?
    /// Most watchlists a person can make, as the server says.
    public private(set) var maxLists = 25
    /// The message with Undo, while it shows (phones and tablets).
    public private(set) var banner: Banner?
    /// Why a change went back, in the server's words, for the screen's toast.
    public var message: String?
    /// Save to watchlist, while it is open.
    public var picker: WatchlistPicker?

    /// How long the banner shows, and so how long a removal waits for Undo.
    public static let bannerTime: Duration = .seconds(4)

    private var items: [Watchlist] = []
    private var listLoaded = false
    /// Which watchlists hold each title changed here or read from its page, by title.
    private var holders: [WatchlistKey: [Int]] = [:]
    /// Titles whose change the server hasn't answered yet.
    private var saving: Set<WatchlistKey> = []
    @ObservationIgnored private var context: String?
    @ObservationIgnored private let repository: any WatchlistsRepository
    @ObservationIgnored private let confirms: Bool
    @ObservationIgnored private let sleep: @MainActor (Duration) async -> Void
    @ObservationIgnored private let flight = SingleFlight<Void>()
    /// Bumped by every load and change, so a slow load never undoes a newer change.
    @ObservationIgnored private var generation = 0
    /// Events heard, so a refresh after one asks again rather than joining an older request.
    @ObservationIgnored private var heard = 0
    @ObservationIgnored private var bannerCount = 0
    /// The banner's Undo.
    @ObservationIgnored private var undo: (@MainActor () async -> Void)?
    /// A removal waiting for its banner to go: sent then, unless Undo came first.
    @ObservationIgnored private var held: (banner: Int, key: WatchlistKey, send: @MainActor () async -> Void)?

    /// - Parameters:
    ///   - confirms: phones and tablets say what was saved or removed, with
    ///     Undo, and hold a removal while they do; Apple TV doesn't.
    ///   - sleep: waits out the banner; tests pass their own.
    public init(repository: any WatchlistsRepository, confirms: Bool = true,
                sleep: @escaping @MainActor (Duration) async -> Void = { try? await Task.sleep(for: $0) }) {
        self.repository = repository
        self.confirms = confirms
        self.sleep = sleep
        context = repository.contextID
    }

    /// The server keeps watchlists and someone is signed in.
    public var available: Bool { repository.available }

    /// The person can make another watchlist.
    public var canMakeMore: Bool { lists.count < maxLists }

    public func watchlist(_ id: Int) -> Watchlist? { lists.first { $0.id == id } }

    // MARK: What a title is on

    /// The watchlists that hold the title: as changed here, else as its page or card said.
    public func holding(_ key: WatchlistKey, said: [Int]?) -> [Int] {
        guard context == repository.contextID, let known = holders[key] else { return said ?? [] }
        return known
    }

    /// On any watchlist: as changed here, else as the card said.
    public func isSaved(_ key: WatchlistKey, said: Bool) -> Bool {
        guard context == repository.contextID, let known = holders[key] else { return said }
        return !known.isEmpty
    }

    /// Still on `listID`, for that watchlist's own page: a title removed here leaves it at once.
    public func isOn(_ key: WatchlistKey, listID: Int) -> Bool {
        guard context == repository.contextID, let known = holders[key] else { return true }
        return known.contains(listID)
    }

    /// The server hasn't answered this title's change yet.
    public func isSaving(_ key: WatchlistKey) -> Bool { context == repository.contextID && saving.contains(key) }

    /// A title's page was read: what it says replaces what this device knew,
    /// unless a change of the title is still on its way.
    public func pageRead(_ key: WatchlistKey, holding: [Int]?) {
        guard let holding, available else { return }
        syncContext()
        guard !saving.contains(key), held?.key != key else { return }
        holders[key] = holding
    }

    /// The button's words: "Watchlist", "On Watchlist", "On Halloween", "On 2 watchlists".
    public func buttonWords(_ key: WatchlistKey, said: [Int]?) -> String {
        WatchlistWords.button(holding: holding(key, said: said), names: Dictionary(lists.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a }))
    }

    /// What a card's long press offers; nil for a title TMDB hasn't matched, or below API level 33.
    public func menuItem(_ key: WatchlistKey, said: Bool) -> MenuItem? {
        guard available else { return nil }
        if lists.count >= 2 { return .choose }
        let name = lists.first?.name
        return isSaved(key, said: said) ? .remove(name ?? WatchlistWords.firstName) : .add(name ?? WatchlistWords.firstName)
    }

    // MARK: Pressing

    /// The Watchlist button, or a card's menu item: with two or more
    /// watchlists it opens the picker (`fromButton` says where from), else
    /// it saves or removes at once.
    public func press(_ title: WatchlistTitle, said: [Int]?, fromButton: Bool = false) async {
        guard available else { return }
        syncContext()
        if lists.count >= 2 { return await openPicker(title, fromButton: fromButton) }
        if holding(title.key, said: said).isEmpty {
            await saveToOnly(title, fromButton: fromButton)
        } else {
            await removeFromAll(title.key, said: said)
        }
    }

    /// A card's menu item; `said` is the card's `watchlisted`, which with
    /// one watchlist can only mean that one.
    public func press(_ title: WatchlistTitle, cardSaid said: Bool) async {
        await press(title, said: said ? [lists.first?.id ?? Self.unknownList] : [])
    }

    /// Opens Save to watchlist for the title, and reads which watchlists hold it.
    public func openPicker(_ title: WatchlistTitle, fromButton: Bool) async {
        flushHeld()
        let picker = WatchlistPicker(store: self, title: title, fromButton: fromButton)
        self.picker = picker
        await load(containing: title.key)
    }

    private func saveToOnly(_ title: WatchlistTitle, fromButton: Bool) async {
        flushHeld()
        let key = title.key, before = holders[key]
        let target = lists.first
        generation += 1
        holders[key] = [target?.id ?? Self.unknownList]
        saving.insert(key)
        let identity = context
        let name = target?.name ?? WatchlistWords.firstName
        let repository = repository
        let request = Task { try await repository.saveToOnly(title) }
        let banner = announce(WatchlistWords.saved(to: name), saved: true)
        if let banner {
            // Undo, even before the server has answered: the title comes off the watchlist it went to.
            setUndo(banner) { [weak self] in
                guard let change = try? await request.value, let id = change.watchlist?.id ?? change.holding.first else { return }
                await self?.set(title, on: id, false)
            }
        }
        do {
            let change = try await request.value
            guard identity == repository.contextID else { return }
            saving.remove(key)
            if holders[key] == [target?.id ?? Self.unknownList] { holders[key] = change.holding }
            if let list = change.watchlist { upsert(list) }
        } catch {
            guard identity == repository.contextID else { return }
            saving.remove(key)
            holders[key] = before
            dismissBanner(banner)
            if (error as? APIError)?.status == 409 {
                // Another device made a second watchlist: choose between them.
                await openPicker(title, fromButton: fromButton)
            } else if !(error is CancellationError) {
                message = error.displayMessage
            }
        }
    }

    private func removeFromAll(_ key: WatchlistKey, said: [Int]?) async {
        flushHeld()
        let before = holders[key], was = holding(key, said: said)
        let names = was.compactMap { id in lists.first { $0.id == id }?.name }
        generation += 1
        holders[key] = []
        adjustCounts(was, by: -1)
        let identity = context
        let send: @MainActor () async -> Void = { [weak self] in
            guard let self else { return }
            saving.insert(key)
            defer { saving.remove(key) }
            do {
                let change = try await repository.removeFromAll(key)
                guard identity == repository.contextID else { return }
                if holders[key] == [] { holders[key] = change.holding }
            } catch {
                guard identity == repository.contextID else { return }
                holders[key] = before
                adjustCounts(was, by: 1)
                if !(error is CancellationError) { message = error.displayMessage }
            }
        }
        let restore: @MainActor () -> Void = { [weak self] in
            self?.holders[key] = before
            self?.adjustCounts(was, by: 1)
        }
        await hold(key, WatchlistWords.removed(from: names.isEmpty ? [WatchlistWords.firstName] : names), send: send, restore: restore)
    }

    // MARK: Changing one watchlist (the picker and a watchlist's page)

    /// Saves to or removes from one watchlist, as the picker's checks do:
    /// at once, with no message, put back if the server refuses.
    @discardableResult
    public func set(_ title: WatchlistTitle, on listID: Int, _ on: Bool) async -> Bool {
        guard available else { return false }
        syncContext()
        flushHeld()
        let key = title.key, before = holders[key]
        let was = holding(key, said: before)
        guard was.contains(listID) != on else { return true }
        generation += 1
        holders[key] = on ? was + [listID] : was.filter { $0 != listID }
        adjustCounts([listID], by: on ? 1 : -1)
        saving.insert(key)
        let identity = context
        do {
            let change = on ? try await repository.save(title, to: listID) : try await repository.remove(key, from: listID)
            guard identity == repository.contextID else { return false }
            saving.remove(key)
            holders[key] = change.holding
            if let list = change.watchlist { upsert(list) }
            return true
        } catch {
            guard identity == repository.contextID else { return false }
            saving.remove(key)
            holders[key] = before
            adjustCounts([listID], by: on ? -1 : 1)
            if !(error is CancellationError) { message = error.displayMessage }
            return false
        }
    }

    /// Remove on a watchlist's own page: the title leaves the page at once,
    /// and on phones and tablets waits for the banner's Undo.
    public func remove(_ key: WatchlistKey, from listID: Int) async {
        guard available else { return }
        syncContext()
        flushHeld()
        let before = holders[key]
        let was = holding(key, said: before ?? [listID])
        generation += 1
        holders[key] = was.filter { $0 != listID }
        adjustCounts([listID], by: -1)
        let identity = context
        let send: @MainActor () async -> Void = { [weak self] in
            guard let self else { return }
            saving.insert(key)
            defer { saving.remove(key) }
            do {
                let change = try await repository.remove(key, from: listID)
                guard identity == repository.contextID else { return }
                holders[key] = change.holding
                if let list = change.watchlist { upsert(list) }
            } catch {
                guard identity == repository.contextID else { return }
                holders[key] = before
                adjustCounts([listID], by: 1)
                if !(error is CancellationError) { message = error.displayMessage }
            }
        }
        let restore: @MainActor () -> Void = { [weak self] in
            self?.holders[key] = before
            self?.adjustCounts([listID], by: 1)
        }
        let name = lists.first { $0.id == listID }?.name ?? WatchlistWords.firstName
        await hold(key, WatchlistWords.removed(from: [name]), send: send, restore: restore)
    }

    /// New watchlist, from the picker or the Watchlists page. Nil, with
    /// the reason, when the name is in use, the person is at the limit, or the server refuses.
    public func create(name: String) async -> Result<Watchlist, CreateRefusal> {
        guard available else { return .failure(.init(line: "")) }
        syncContext()
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if let taken = takenName(trimmed, except: nil) { return .failure(.init(line: WatchlistWords.nameTaken(taken))) }
        guard canMakeMore else { return .failure(.init(line: WatchlistWords.atLimit(maxLists))) }
        generation += 1
        let identity = context
        do {
            let list = try await repository.create(name: trimmed)
            guard identity == repository.contextID else { return .failure(.init(line: "")) }
            upsert(list)
            return .success(list)
        } catch {
            return .failure(.init(line: error.displayMessage))
        }
    }

    /// Why New watchlist or Rename didn't happen, as the field says it.
    public struct CreateRefusal: Error, Hashable {
        public let line: String
    }

    /// Rename: nil once done, else why not ("You already have a watchlist called Halloween.").
    public func rename(_ id: Int, to name: String) async -> String? {
        syncContext()
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let taken = takenName(trimmed, except: id) { return WatchlistWords.nameTaken(taken) }
        guard let index = items.firstIndex(where: { $0.id == id }) else { return nil }
        let before = items[index]
        generation += 1
        items[index].name = trimmed
        do {
            upsert(try await repository.update(id: id, name: trimmed, removeWatched: nil))
            return nil
        } catch {
            if let i = items.firstIndex(where: { $0.id == id }) { items[i].name = before.name }
            return error.displayMessage
        }
    }

    /// Remove titles once watched, on or off: at once, put back if refused.
    public func setRemoveWatched(_ id: Int, _ on: Bool) async {
        syncContext()
        guard let index = items.firstIndex(where: { $0.id == id }), items[index].removeWatched != on else { return }
        generation += 1
        items[index].removeWatched = on
        do {
            upsert(try await repository.update(id: id, name: nil, removeWatched: on))
        } catch {
            if let i = items.firstIndex(where: { $0.id == id }) { items[i].removeWatched = !on }
            if !(error is CancellationError) { message = error.displayMessage }
        }
    }

    /// Delete: true once the server has deleted it.
    public func delete(_ id: Int) async -> Bool {
        syncContext()
        flushHeld()
        generation += 1
        do {
            try await repository.delete(id: id)
            items.removeAll { $0.id == id }
            for (key, ids) in holders where ids.contains(id) { holders[key] = ids.filter { $0 != id } }
            return true
        } catch {
            if !(error is CancellationError) { message = error.displayMessage }
            return false
        }
    }

    // MARK: The banner

    /// Undo: a held removal is never sent; a save is removed again.
    public func undoBanner() async {
        guard banner != nil else { return }
        banner = nil
        held = nil
        let action = undo
        undo = nil
        await action?()
    }

    /// Shows a message with Undo, if this app confirms changes. Nil on Apple TV.
    private func announce(_ text: String, saved: Bool) -> Int? {
        guard confirms else { return nil }
        bannerCount += 1
        let id = bannerCount
        banner = Banner(id: id, text: text, saved: saved)
        undo = nil
        Task { [weak self] in
            await self?.sleep(Self.bannerTime)
            self?.bannerEnded(id)
        }
        return id
    }

    private func setUndo(_ id: Int, _ action: @escaping @MainActor () async -> Void) {
        guard banner?.id == id else { return }
        undo = action
    }

    private func dismissBanner(_ id: Int?) {
        guard let id, banner?.id == id else { return }
        banner = nil
        undo = nil
    }

    private func bannerEnded(_ id: Int) {
        if banner?.id == id {
            banner = nil
            undo = nil
        }
        if let held, held.banner == id {
            self.held = nil
            Task { await held.send() }
        }
    }

    /// A removal: on phones and tablets it waits out the banner, and Undo puts it back unsent.
    private func hold(_ key: WatchlistKey, _ text: String, send: @escaping @MainActor () async -> Void, restore: @escaping @MainActor () -> Void) async {
        guard let id = announce(text, saved: false) else { return await send() }
        held = (id, key, send)
        undo = restore
    }

    /// Sends a removal still waiting for Undo, before another change.
    private func flushHeld() {
        guard let held else { return }
        self.held = nil
        if banner?.id == held.banner {
            banner = nil
            undo = nil
        }
        Task { await held.send() }
    }

    // MARK: Loading

    /// Loads the watchlists: at launch, on foreground and after an event.
    /// Callers at once share one request; one after an event asks again.
    public func refresh() async {
        await flight.run(stamp: "\(repository.contextID ?? "")#\(heard)") { [weak self] in await self?.load(containing: nil) }
    }

    private func load(containing key: WatchlistKey?) async {
        syncContext()
        guard available else { return }
        generation += 1
        let mine = generation, identity = context
        do {
            let set = try await repository.watchlists(containing: key)
            guard identity == repository.contextID else { return }
            // A change made meanwhile is newer than this list.
            guard mine == generation else { return }
            items = set.items
            maxLists = set.max
            listLoaded = true
            error = nil
            let ids = Set(set.items.map(\.id))
            for (title, held) in holders { holders[title] = held.filter { ids.contains($0) || $0 == Self.unknownList } }
            if let key, !saving.contains(key) {
                holders[key] = set.items.filter { $0.contains == true }.map(\.id)
            }
        } catch {
            guard identity == repository.contextID, !(error is CancellationError) else { return }
            self.error = error.displayMessage
        }
    }

    /// Follows the server's events (and this device's own changes): the
    /// watchlists are read again. Runs while the app does.
    public func listen() async {
        for await _ in repository.changes() {
            guard available else { continue }
            heard += 1
            await refresh()
        }
    }

    // MARK: Private

    /// The watchlist a save made before the server said which: the first, called Watchlist.
    static let unknownList = -1

    private func upsert(_ list: Watchlist) {
        if let i = items.firstIndex(where: { $0.id == list.id }) {
            items[i] = list
        } else {
            items.append(list)
            for (key, ids) in holders where ids.contains(Self.unknownList) {
                holders[key] = ids.map { $0 == Self.unknownList ? list.id : $0 }
            }
        }
    }

    private func adjustCounts(_ ids: [Int], by step: Int) {
        for id in ids {
            guard let i = items.firstIndex(where: { $0.id == id }) else { continue }
            items[i].titles = max(0, items[i].titles + step)
        }
    }

    private func takenName(_ name: String, except id: Int?) -> String? {
        lists.first { $0.id != id && $0.name.localizedCaseInsensitiveCompare(name) == .orderedSame }?.name
    }

    /// A new server or sign-in starts with nothing.
    private func syncContext() {
        guard context != repository.contextID else { return }
        context = repository.contextID
        generation += 1
        items = []; listLoaded = false; holders = [:]; saving = []; error = nil; maxLists = 25
        banner = nil; undo = nil; held = nil; picker = nil; message = nil
    }
}

/// Save to watchlist, for one title: every watchlist with a check beside
/// it, each saved or removed as it is pressed, and New watchlist… at the
/// foot, which names one and saves the title to it. A sheet on iPhone, a
/// popover from the button on iPad, the whole screen on Apple TV.
@MainActor @Observable
public final class WatchlistPicker: Identifiable {
    public struct Row: Hashable, Identifiable, Sendable {
        public let id: Int
        public let name: String
        public let checked: Bool
        /// "12 titles".
        public let count: String
    }

    public let id = UUID()
    public let title: WatchlistTitle
    /// Opened from the title page's button, so iPad draws it as a popover from there.
    public let fromButton: Bool
    /// Naming a new watchlist.
    public private(set) var naming = false
    public var name = "" {
        didSet { if name != oldValue { problem = nil } }
    }
    /// Why the name can't be used.
    public private(set) var problem: String?
    public private(set) var creating = false

    @ObservationIgnored private unowned let store: WatchlistStore

    init(store: WatchlistStore, title: WatchlistTitle, fromButton: Bool) {
        self.store = store
        self.title = title
        self.fromButton = fromButton
    }

    public var rows: [Row] {
        let holding = store.holding(title.key, said: [])
        return store.lists.map { list in
            Row(id: list.id, name: list.name, checked: holding.contains(list.id),
                count: list.titles == 1 ? "1 title" : "\(formatCount(list.titles)) titles")
        }
    }

    /// At the limit, the line replaces New watchlist….
    public var atLimit: String? { store.canMakeMore ? nil : WatchlistWords.atLimit(store.maxLists) }

    /// Create waits for a name.
    public var canCreate: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !creating }

    public func toggle(_ row: Row) async {
        await store.set(title, on: row.id, !row.checked)
    }

    public func startNaming() {
        name = ""
        problem = nil
        naming = true
    }

    public func cancelNaming() {
        naming = false
        problem = nil
    }

    /// Makes the watchlist and saves the title to it; back to the checks once done.
    public func create() async {
        guard canCreate else { return }
        creating = true
        defer { creating = false }
        switch await store.create(name: name) {
        case .success(let list):
            naming = false
            await store.set(title, on: list.id, true)
        case .failure(let refusal):
            problem = refusal.line.isEmpty ? nil : refusal.line
        }
    }

    /// Done, Menu or a tap outside.
    public func close() {
        if store.picker === self { store.picker = nil }
    }
}
