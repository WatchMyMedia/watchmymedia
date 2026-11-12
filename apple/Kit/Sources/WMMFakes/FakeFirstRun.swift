import Foundation

public enum FakeFirstRunState: String, Sendable {
    case noLibraries, scanning, failed, empty

    public static func from(_ arguments: [String]) -> Self? {
        guard let index = arguments.firstIndex(of: "-fakesFirstRun"), arguments.indices.contains(index + 1) else { return nil }
        return Self(rawValue: arguments[index + 1])
    }
}
