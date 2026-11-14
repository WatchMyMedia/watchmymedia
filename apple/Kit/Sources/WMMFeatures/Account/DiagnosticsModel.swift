import Foundation
import Observation
import WMMModel

/// Settings › About › Diagnostics on iPhone and iPad (PLAN-APPLE-REVIEW.md
/// §6.4, design/mockups/ios/Diagnostics.dc.html): the page made once as it
/// opens, and Copy, which puts ``DiagnosticsPage/text`` on the clipboard,
/// turns into Copied and shows a short Copied note (iOS shows nothing when an
/// app copies). Nothing is sent: there is no Share.
@MainActor
@Observable
public final class DiagnosticsModel {
    /// How long the Copied note stays.
    public static let copiedNoteMs = 1_500

    public private(set) var page: DiagnosticsPage?
    /// Copy has run since the page opened: the button says Copied.
    public private(set) var copied = false
    /// The Copied note over the page, for a moment after each copy.
    public private(set) var showsCopiedNote = false

    @ObservationIgnored private let make: @Sendable () async -> DiagnosticsPage
    @ObservationIgnored private let pasteboard: @MainActor (String) -> Void
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var noteTask: Task<Void, Never>?

    /// `make` builds the page (it reads the log, so off the main actor);
    /// `pasteboard` puts text on the clipboard.
    public init(make: @escaping @Sendable () async -> DiagnosticsPage,
                pasteboard: @escaping @MainActor (String) -> Void,
                sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.make = make
        self.pasteboard = pasteboard
        self.sleep = sleep
    }

    public var copyLabel: String { copied ? "Copied" : "Copy diagnostics" }

    /// Makes the page once; a second call keeps the first page, so what shows is what Copy copies.
    public func load() async {
        guard page == nil else { return }
        page = await make()
    }

    /// Copies exactly the text the page was made with.
    public func copy() {
        guard let page else { return }
        pasteboard(page.text)
        copied = true
        showsCopiedNote = true
        noteTask?.cancel()
        let sleep = sleep
        noteTask = Task { [weak self] in
            do { try await sleep(.milliseconds(Self.copiedNoteMs)) } catch { return }
            guard let self, !Task.isCancelled else { return }
            self.showsCopiedNote = false
        }
    }

    /// The page left: the note goes with it.
    public func dispose() {
        noteTask?.cancel()
        noteTask = nil
        showsCopiedNote = false
    }
}
