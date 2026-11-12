import Foundation
import OpenAPIRuntime

/// The server's timestamps are Go's RFC 3339, with fractional seconds only
/// when they are not zero: read both, write without.
struct LenientISO8601: DateTranscoder {
    private static let fractional = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
    private static let whole = Date.ISO8601FormatStyle()

    func encode(_ date: Date) throws -> String {
        date.formatted(Self.whole)
    }

    func decode(_ string: String) throws -> Date {
        if let d = try? Self.fractional.parse(string) { return d }
        if let d = try? Self.whole.parse(string) { return d }
        throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Not an RFC 3339 date: \(string)"))
    }
}
