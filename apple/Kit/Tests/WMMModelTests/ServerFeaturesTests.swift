import Foundation
import Testing
import WMMModel

@Suite struct ServerFeaturesTests {
    /// Every feature: its name, the level it starts at, words from that
    /// level's entry in the comment above `APILevel` in
    /// server/internal/api/system.go, and how to read it.
    static let table: [(name: String, level: Int, entry: String, has: @Sendable (ServerFeatures) -> Bool)] = [
        ("supported", 1, "device sign-in", { $0.supported }),
        ("castTokens", 2, "cast tokens", { $0.castTokens }),
        ("automaticQuality", 3, "Automatic quality", { $0.automaticQuality }),
        ("requests", 5, "requests", { $0.requests }),
        ("delete", 6, "deleting from the apps", { $0.delete }),
        ("hdrProfile", 8, "Profile.hdrTypes", { $0.hdrProfile }),
        ("trickplay", 9, "seek thumbnails", { $0.trickplay }),
        ("offline", 10, "offline downloads", { $0.offline }),
        ("liveTV", 11, "Live TV", { $0.liveTV }),
        ("liveGuide", 13, "Live TV listings", { $0.liveGuide }),
        ("liveCast", 14, "casting Live TV", { $0.liveCast }),
        ("livePersonal", 15, "Live TV favourites", { $0.livePersonal }),
        ("subtitlePreferences", 18, "Preferences.subtitles", { $0.subtitlePreferences }),
        ("liveRecordings", 19, "DVR", { $0.liveRecordings }),
        ("trailers", 20, "movie trailers", { $0.trailers }),
        ("audioLanguages", 21, "audio languages", { $0.audioLanguages }),
        ("rows", 22, "rows people arrange", { $0.rows }),
        ("autoRecovery", 24, "Automatic recovery", { $0.autoRecovery }),
        ("liveProfile", 25, "Live TV device profiles", { $0.liveProfile }),
        ("liveAutomatic", 26, "Live TV Automatic", { $0.liveAutomatic }),
        ("tokenWithDecision", 28, "mediaToken", { $0.tokenWithDecision }),
        ("liveStatusWaits", 28, "mediaToken", { $0.liveStatusWaits }),
    ]

    @Test(arguments: table.indices)
    func eachFeatureStartsAtItsLevel(_ i: Int) {
        let (name, level, _, has) = Self.table[i]
        #expect(!has(ServerFeatures(apiLevel: level - 1)), "\(name) below \(level)")
        #expect(has(ServerFeatures(apiLevel: level)), "\(name) at \(level)")
        #expect(has(ServerFeatures(apiLevel: level + 1)), "\(name) above \(level)")
    }

    @Test func aServerNotKnownOffersNothing() {
        let none = ServerFeatures(apiLevel: nil)
        #expect(none.apiLevel == 0)
        #expect(Self.table.allSatisfy { !$0.has(none) })
        #expect(Server(id: "s", name: "S", baseURL: URL(string: "http://h")!, version: "1", apiLevel: 22).features.rows)
        #expect(Server(id: "s", name: "S", baseURL: URL(string: "http://h")!, version: "1", apiLevel: 0).tooOld)
    }

    /// The table matches the server's own history of its levels: each
    /// feature's level is the entry that names it. Level 28 is the server's
    /// part of PLAN-APPLE-REVIEW.md, on its own branch: until the server here
    /// is at 28 it is only checked to be beyond the server's level.
    @Test func theLevelsAreTheServersOwn() throws {
        let source = try String(contentsOf: Self.systemGo, encoding: .utf8)
        let (current, entries) = try Self.history(source)
        for (name, level, words, _) in Self.table {
            guard level <= current else {
                #expect(level == ServerFeatures.Level.tokenWithDecision, "\(name) at \(level) is past the server's level \(current)")
                continue
            }
            let entry = try #require(entries[level], "system.go says nothing for level \(level) (\(name))")
            #expect(entry.localizedCaseInsensitiveContains(words), "level \(level) (\(name)) is: \(entry)")
        }
    }

    static var systemGo: URL {
        URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent() // Kit
            .deletingLastPathComponent().deletingLastPathComponent() // the repository
            .appending(path: "server/internal/api/system.go")
    }

    /// `const APILevel = N` and the comment above it, by level ("2: cast tokens and castAppId …").
    static func history(_ source: String) throws -> (current: Int, entries: [Int: String]) {
        let lines = source.components(separatedBy: "\n")
        let constant = try #require(lines.firstIndex { $0.hasPrefix("const APILevel = ") })
        let current = try #require(Int(lines[constant].dropFirst("const APILevel = ".count).trimmingCharacters(in: .whitespaces)))
        var comment: [String] = []
        for line in lines[..<constant].reversed() {
            guard line.hasPrefix("//") else { break }
            comment.insert(String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces), at: 0)
        }
        let text = comment.joined(separator: " ")
        let starts = try Regex(#"(?:^|\s)(\d{1,3}): "#)
        let matches = text.matches(of: starts)
        var entries: [Int: String] = [:]
        for (i, match) in matches.enumerated() {
            guard let level = Int(text[match.range].trimmingCharacters(in: .whitespaces).dropLast()) else { continue }
            let end = i + 1 < matches.count ? matches[i + 1].range.lowerBound : text.endIndex
            entries[level] = String(text[match.range.upperBound..<end])
        }
        return (current, entries)
    }
}
