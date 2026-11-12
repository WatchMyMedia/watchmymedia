import Foundation
import WMMModel

/// An empty Home describes evidence from the library list, rather than asking
/// someone to wait for a scan that has never started (PLAN-ONBOARDING O7).
public enum HomeEmptyState: Hashable, Sendable {
    case noLibraries
    case scanning
    case scanFailed([Library], otherScanRunning: Bool)
    case emptyLibraries
    /// The library list could not be read. Do not claim setup or a scan succeeded.
    case unavailable

    public init(libraries: [Library]) {
        let failures = libraries.filter { $0.lastScanError != nil && !$0.scanning }
        let running = libraries.contains(where: \.scanning)
        if !failures.isEmpty {
            self = .scanFailed(failures, otherScanRunning: running)
        } else if running {
            self = .scanning
        } else if libraries.isEmpty {
            self = .noLibraries
        } else {
            self = .emptyLibraries
        }
    }

    public var title: String {
        switch self {
        case .noLibraries: "This server has no movies or TV yet."
        case .scanning: "Finding your files…"
        case .scanFailed(let libraries, _): libraries.count == 1 ? "Couldn’t read the library" : "Couldn’t read some libraries"
        case .emptyLibraries: "No movies or shows found"
        case .unavailable: "Nothing here yet"
        }
    }

    public func message(isAdmin: Bool) -> String {
        switch self {
        case .noLibraries:
            return isAdmin ? "Add a library on the server’s web page, then come back here." : "Ask the person who runs it to add a library."
        case .scanning:
            return "They appear here as they’re found."
        case .scanFailed(let libraries, let running):
            let reasons = libraries.map { "Couldn’t read \($0.name): \($0.lastScanError ?? "The folder could not be read.")" }.joined(separator: "\n")
            let next = isAdmin ? "Check the library on the server’s web page." : "Ask the person who runs the server to check the library."
            return reasons + "\n" + (running ? "Other libraries are still being scanned.\n" : "") + next
        case .emptyLibraries:
            return isAdmin ? "Check the library’s folders and scan status on the server’s web page." : "Ask the person who runs the server to check its libraries."
        case .unavailable:
            return "Couldn’t check the library’s scan status. Try again."
        }
    }

    public var offersSetup: Bool {
        switch self {
        case .noLibraries, .scanFailed, .emptyLibraries: true
        case .scanning, .unavailable: false
        }
    }
}
