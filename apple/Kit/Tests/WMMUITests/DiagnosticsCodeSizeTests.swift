import Foundation
import Testing
import WMMFeatures
import WMMModel
@testable import WMMUI

/// The Apple TV diagnostics code stays a size a phone reads from a sofa
/// (TvDiagnostics.dc.html, `DiagnosticsCode.maxBytes`): at most a version 25
/// QR code, 117 modules a side, whatever the log holds.
@Suite struct DiagnosticsCodeSizeTests {
    @Test func theLargestTextTheCodeHoldsIsVersion25AtMost() throws {
        // Lower case and spaces: byte mode, the least a module holds.
        let text = String(String(repeating: "log line ", count: 200).prefix(DiagnosticsCode.maxBytes))
        let matrix = try #require(QRMatrix(text))
        #expect(matrix.size <= 117)
        // One byte more needs a larger code: the limit is the version's whole capacity.
        let over = try #require(QRMatrix(text + "x"))
        #expect(over.size > 117)
    }

    @Test func aLongReportsCodeIsVersion25AtMost() throws {
        let lines = (0..<200).map { String(format: "21:%02d:%02d info [player] stall 2.1 s, switched to 1080p, line %d", $0 / 60, $0 % 60, $0) }
        let report = DiagnosticsReport(appVersion: "1.9.0 (10500012)", deviceModel: "AppleTV14,1", system: "tvOS 27.2 (24K150)",
                                       serverVersion: "1.5.0", serverAPILevel: 28, logLines: lines, generatedAt: Date(timeIntervalSince1970: 1_800_000_000))
        let code = DiagnosticsCode(report: report, redactor: DiagnosticsRedactor())
        #expect(code.leftOut > 0)
        let matrix = try #require(QRMatrix(code.text))
        #expect(matrix.size <= 117)
    }
}
