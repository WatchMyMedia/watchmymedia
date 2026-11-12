import Foundation
import Observation
import WMMModel

/// Home rows, or a library's rows (PLAN-ROWS.md §5; HomeEditRows, AddRow,
/// Pad2HomeEditRows and TvSettingsHomeRows.dc.html): every row the person
/// can place on the screen, in order, to move, hide and show again. Hidden
/// rows stay in place, greyed, with Show; Add a row lists the same hidden
/// rows by kind (built-in rows, the household's lists, the person's own) and
/// shows one in its place. Reset goes back to the household's order. Lists
/// are made on the web (Decision 10).
///
/// On iPhone and iPad the changes wait for Done (``save()``); on Apple TV
/// (`autosave`) each change is saved as it is made, one save at a time.
@MainActor
@Observable
public final class RowEditorModel {
    public let screen: RowScreen
    /// "Home", "Movies" or "TV Shows".
    public let screenName: String
    /// The layout as the editor opened it, or why it couldn't be read.
    public private(set) var state: Loadable<RowLayout> = .loading
    /// Every row in order, hidden ones too.
    public private(set) var rows: [RowLayoutEntry] = []
    /// The person's and the household's lists, by ID, for the rows' lines.
    public private(set) var lists: [Int: MediaList] = [:]
    /// The person saved their own order (false: it follows the household's).
    public private(set) var customized = false
    /// Something changed since the editor opened, or since the last save.
    public private(set) var changed = false
    public private(set) var saving = false
    /// Why a save or reset failed.
    public var message: String?

    @ObservationIgnored private let repository: any RowsRepository
    @ObservationIgnored private let autosave: Bool
    /// The save running on Apple TV; tests await it.
    @ObservationIgnored public private(set) var pendingSave: Task<Void, Never>?
    @ObservationIgnored private var dirty = false

    public init(rows: any RowsRepository, screen: RowScreen, screenName: String? = nil, autosave: Bool = false) {
        repository = rows
        self.screen = screen
        self.screenName = screenName ?? (screen == .home ? "Home" : "this screen")
        self.autosave = autosave
    }

    // MARK: Wording

    /// "Home rows", "Movies rows".
    public var title: String { "\(screenName) rows" }

    /// The group's heading: "Rows on Home".
    public var groupTitle: String { "Rows on \(screenName)" }

    /// Over the list. `besideHero` is the iPad's, where Continue watching can sit beside the featured title.
    public func intro(besideHero: Bool = false) -> String {
        let stays: String = switch screen {
        case .home where besideHero:
            "The featured title stays above these rows, with Continue watching beside it while Continue watching is the first row."
        case .home: "The featured title stays above these rows."
        case .library: "\(allTitle) stays below these rows."
        }
        return "Drag a row by its handle to move it. \(stays) Your rows are the same on all your devices."
    }

    /// Apple TV's words beside its list.
    public var tvIntro: String {
        screen == .home
            ? "Move rows up and down, and hide the ones you don’t want. The featured title stays above them."
            : "Move rows up and down, and hide the ones you don’t want. \(allTitle) stays below them."
    }

    /// "All movies", "All shows": the grid below a library's rows.
    public var allTitle: String { screenName == "TV Shows" ? "All shows" : "All movies" }

    /// Under the list.
    public var footnote: String {
        "A row with nothing to show stays here and comes back when it has something. \(screenName) shows up to 30 rows; rows past the 30th say Not shown: too many rows."
    }

    /// Under Reset to default.
    public var resetNote: String { "Puts \(screenName) back to the household’s default rows and order." }

    /// The Reset confirmation: "Reset Home rows?"
    public var resetTitle: String { "Reset \(title)?" }

    public var resetMessage: String { "\(screenName) goes back to the household’s default rows and order. Lists stay as they are." }

    /// Settings › Home rows' value: "7 shown · 4 hidden".
    public var summary: String? {
        guard state.value != nil else { return nil }
        let hidden = rows.filter(\.hidden).count
        return "\(formatCount(rows.count - hidden)) shown · \(formatCount(hidden)) hidden"
    }

    /// Under a row in the list: "Built in", "For everyone · 18 of 50 · From TMDB",
    /// "Hidden · Your list · From an import".
    public func line(_ entry: RowLayoutEntry) -> String {
        let base: String
        switch entry.kind {
        case .builtin, .other:
            base = entry.style == .live ? "Built in · On now" : "Built in"
        case .list:
            let list = entry.listID.flatMap { lists[$0] }
            base = [listOwnerLine(entry.owner ?? list?.owner), list?.shortCount, list?.sourceLine].compactMap { $0 }.joined(separator: " · ")
        }
        return entry.hidden ? (base.isEmpty ? "Hidden" : "Hidden · \(base)") : base
    }

    /// Under a row in Add a row: "Built in", "9 of 14 · From an import", "From TMDB · Movies".
    public func addLine(_ entry: RowLayoutEntry) -> String {
        guard entry.kind == .list else { return "Built in" }
        let list = entry.listID.flatMap { lists[$0] }
        return [list?.shortCount, list?.sourceLine, list.flatMap { listMediaTypeLine($0.mediaType) }].compactMap { $0 }.joined(separator: " · ")
    }

    /// On iPad, Continue watching while it is the first row shown: "Beside the featured title".
    public func isBesideHero(_ entry: RowLayoutEntry) -> Bool {
        entry.style == .continue && rows.first { !$0.hidden }?.id == entry.id
    }

    public func load() async {
        if state.value == nil { state = .loading }
        guard let result = await Loadable.run({ try await self.repository.layout(self.screen) }) else { return }
        state = result
        if let layout = result.value { apply(layout) }
        if let lists = try? await repository.lists() {
            self.lists = Dictionary(lists.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        }
    }

    private func apply(_ layout: RowLayout) {
        rows = layout.rows
        customized = layout.customized
        changed = false
    }

    // MARK: Moving, hiding, adding

    public func index(of id: String) -> Int? { rows.firstIndex { $0.id == id } }

    public func canMoveUp(_ id: String) -> Bool { (index(of: id) ?? 0) > 0 }

    public func canMoveDown(_ id: String) -> Bool { index(of: id).map { $0 < rows.count - 1 } ?? false }

    /// Drag and drop (`.onMove`): the rows at `source` go before `destination`.
    public func move(fromOffsets source: IndexSet, toOffset destination: Int) {
        let moving = source.sorted().map { rows[$0] }
        var rest = rows.enumerated().filter { !source.contains($0.offset) }.map(\.element)
        let at = destination - source.filter { $0 < destination }.count
        rest.insert(contentsOf: moving, at: min(max(at, 0), rest.count))
        guard rest != rows else { return }
        rows = rest
        edited()
    }

    public func moveUp(_ id: String) { move(id, by: -1) }

    public func moveDown(_ id: String) { move(id, by: 1) }

    private func move(_ id: String, by step: Int) {
        guard let i = index(of: id), rows.indices.contains(i + step) else { return }
        rows.swapAt(i, i + step)
        edited()
    }

    /// Hide and Show: the row keeps its place.
    public func setHidden(_ id: String, _ hidden: Bool) {
        guard let i = index(of: id), rows[i].hidden != hidden else { return }
        rows[i].hidden = hidden
        if !hidden { rows[i].shown = rows[i].note == nil }
        edited()
    }

    /// Add a row: the hidden row shows in its place, to move from there.
    public func add(_ id: String) { setHidden(id, false) }

    /// What Add a row offers now: the hidden rows.
    public var offered: [RowLayoutEntry] { rows.filter(\.hidden) }

    /// Add a row's groups, from the rows it opened with (`ids`, so a row
    /// just added stays, as Added): built-in rows, the household's lists, the person's.
    public func addGroups(_ ids: [String]) -> [(title: String, rows: [RowLayoutEntry])] {
        let listed = ids.compactMap { id in rows.first { $0.id == id } }
        return [
            ("Built-in rows", listed.filter { $0.kind != .list }),
            ("For everyone", listed.filter { $0.kind == .list && $0.owner == .everyone }),
            ("Your lists", listed.filter { $0.kind == .list && $0.owner != .everyone }),
        ].filter { !$0.1.isEmpty }
    }

    /// The order to save, every row with whether it is hidden (a row left out would come back shown).
    public var placements: [RowPlacement] {
        rows.map { RowPlacement(id: $0.id, hidden: $0.hidden) }
    }

    private func edited() {
        changed = true
        message = nil
        if autosave { scheduleSave() }
    }

    // MARK: Saving

    /// Saves the order; true when it saved (or there was nothing to save), so Done can close the sheet.
    @discardableResult
    public func save() async -> Bool {
        guard changed else { return true }
        saving = true
        defer { saving = false }
        let placements = placements
        do {
            let saved = try await repository.saveLayout(placements, for: screen)
            customized = saved.customized
            if placements == self.placements { changed = false }
            return true
        } catch is CancellationError {
            return false
        } catch {
            message = error.displayMessage
            return false
        }
    }

    /// One save at a time, and one more after it if something changed meanwhile.
    private func scheduleSave() {
        dirty = true
        guard pendingSave == nil else { return }
        // Holds the model until the last change is saved, even after its sheet has gone.
        pendingSave = Task {
            while dirty {
                dirty = false
                await save()
            }
            pendingSave = nil
        }
    }

    /// Back to the household's order: the list shows it at once.
    public func reset() async {
        saving = true
        defer { saving = false }
        do {
            apply(try await repository.resetLayout(screen))
        } catch is CancellationError {
        } catch {
            message = error.displayMessage
        }
    }
}
