import Foundation
import Testing
import WMMModel
@testable import WMMFeatures

/// Copy diagnostics (Diagnostics.dc.html): Copy copies exactly the page's
/// text, turns into Copied, and the Copied note goes after a moment.
@MainActor
@Suite struct DiagnosticsModelTests {
    final class Clipboard {
        var copies: [String] = []
    }

    final class Makes: @unchecked Sendable {
        var count = 0
    }

    nonisolated private static func page(_ log: [String]) -> DiagnosticsPage {
        DiagnosticsPage(
            report: DiagnosticsReport(appVersion: "1.9.0 (10500012)", deviceModel: "iPhone17,1", system: "iOS 18.2",
                                      serverVersion: "1.5.0", serverAPILevel: 28, logLines: log,
                                      generatedAt: Date(timeIntervalSince1970: 1_800_000_000)),
            redactor: DiagnosticsRedactor(names: ["Home Server"]), deviceLabel: "iPhone"
        )
    }

    @Test func copyPutsThePagesOwnTextOnTheClipboard() async {
        let clipboard = Clipboard()
        let time = ManualTime()
        let makes = Makes()
        let model = DiagnosticsModel(
            make: {
                makes.count += 1
                return Self.page(["15:00:00 info [x] Home Server answered (\(makes.count))"])
            },
            pasteboard: { clipboard.copies.append($0) },
            sleep: { try await time.sleep($0) }
        )
        model.copy()
        #expect(clipboard.copies.isEmpty, "nothing to copy before the page is made")

        await model.load()
        await model.load()
        #expect(makes.count == 1, "the page is made once, so the copy is what shows")
        #expect(model.copyLabel == "Copy diagnostics")
        #expect(!model.copied)

        model.copy()
        #expect(clipboard.copies == [model.page?.text])
        #expect(clipboard.copies.first?.contains("<name> answered (1)") == true)
        #expect(clipboard.copies.first?.contains("Home Server") == false)
        #expect(model.copied)
        #expect(model.copyLabel == "Copied")
        #expect(model.showsCopiedNote)

        await time.advance(byMs: DiagnosticsModel.copiedNoteMs)
        #expect(!model.showsCopiedNote)
        #expect(model.copyLabel == "Copied", "the button stays Copied while the page shows")
    }

    @Test func leavingThePageTakesTheNoteAway() async {
        let time = ManualTime()
        let model = DiagnosticsModel(make: { Self.page([]) }, pasteboard: { _ in }, sleep: { try await time.sleep($0) })
        await model.load()
        model.copy()
        model.dispose()
        #expect(!model.showsCopiedNote)
    }
}
