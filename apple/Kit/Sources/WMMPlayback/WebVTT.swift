import Foundation
import WMMModel

/// Reads WebVTT into plain cues for the subtitles the app draws itself
/// (PLAN-IOS.md §5): timings, and the text without tags or settings.
/// Lenient, as subtitle files often are: a missing header, SRT-style
/// commas, `\r\n` line ends and a byte order mark are all fine.
public enum WebVTT {
    /// The file's cues, ordered by start.
    public static func parse(_ text: String) -> [SubtitleCue] {
        var body = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
        var cues: [SubtitleCue] = []
        var block: [Substring] = []
        let lines: [Substring] = body.split(separator: "\n", omittingEmptySubsequences: false) + [""]
        for line in lines {
            if line.allSatisfy(\.isWhitespace) {
                if let cue = cue(block) { cues.append(cue) }
                block = []
            } else {
                block.append(line)
            }
        }
        return cues.enumerated().sorted { ($0.element.startMs, $0.offset) < ($1.element.startMs, $1.offset) }.map(\.element)
    }

    /// One block: an optional identifier, the timing line, then the text.
    /// NOTE, STYLE and REGION blocks, and the header, have no timing line.
    private static func cue(_ block: [Substring]) -> SubtitleCue? {
        guard let first = block.first, !isComment(first),
              let t = block.firstIndex(where: { $0.contains("-->") })
        else { return nil }
        let timing = block[t].components(separatedBy: "-->")
        guard timing.count == 2,
              let start = timestamp(timing[0]),
              let endToken = timing[1].split(whereSeparator: \.isWhitespace).first,
              let end = timestamp(endToken),
              end > start
        else { return nil }
        let lines = block[(t + 1)...].map { plainText($0).trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !lines.isEmpty else { return nil }
        return SubtitleCue(startMs: start, endMs: end, text: lines.joined(separator: "\n"))
    }

    private static func isComment(_ line: Substring) -> Bool {
        ["NOTE", "STYLE", "REGION"].contains { word in
            line.hasPrefix(word) && (line.count == word.count || line.dropFirst(word.count).first?.isWhitespace == true)
        }
    }

    /// "01:02:03.456" or "02:03.456" (a comma works too) in milliseconds.
    static func timestamp<S: StringProtocol>(_ text: S) -> Int? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3 else { return nil }
        let seconds = parts[parts.count - 1].split(omittingEmptySubsequences: false, whereSeparator: { $0 == "." || $0 == "," })
        guard seconds.count <= 2,
              let s = Int(seconds[0]), s < 60,
              let m = Int(parts[parts.count - 2]), m < 60 || parts.count == 2,
              let h = parts.count == 3 ? Int(parts[0]) : 0,
              h >= 0, m >= 0, s >= 0
        else { return nil }
        var ms = 0
        if seconds.count == 2 {
            let digits = seconds[1].prefix(3)
            guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }), let fraction = Int(digits) else { return nil }
            ms = fraction * [100, 10, 1][digits.count - 1]
        }
        var total = h
        for component in [m, s] {
            let (minutes, multiplied) = total.multipliedReportingOverflow(by: 60)
            let (sum, added) = minutes.addingReportingOverflow(component)
            guard !multiplied, !added else { return nil }
            total = sum
        }
        let (milliseconds, multiplied) = total.multipliedReportingOverflow(by: 1000)
        let (sum, added) = milliseconds.addingReportingOverflow(ms)
        return multiplied || added ? nil : sum
    }

    /// A cue line without its tags (`<i>`, `<c.yellow>`, `<v Sam>`, `<00:01.000>`) and with its entities decoded.
    static func plainText<S: StringProtocol>(_ line: S) -> String {
        var out = ""
        var rest = Substring(line)
        // A tag opens with a name, a class, a closing slash or a timestamp: `<i>`, `</i>`, `<c.yellow>`, `<00:01.000>`.
        while let open = rest.firstIndex(of: "<") {
            let after = rest.index(after: open)
            guard after < rest.endIndex, rest[after].isLetter || rest[after].isNumber || rest[after] == "/",
                  let close = rest[after...].firstIndex(of: ">")
            else {
                out += rest[...open]
                rest = rest[after...]
                continue
            }
            out += rest[..<open]
            rest = rest[rest.index(after: close)...]
        }
        out += rest
        return decodeEntities(out)
    }

    private static let entities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": "\u{00A0}", "lrm": "\u{200E}", "rlm": "\u{200F}",
    ]

    private static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        var out = ""
        var rest = Substring(text)
        while let amp = rest.firstIndex(of: "&") {
            out += rest[..<amp]
            let after = rest[rest.index(after: amp)...]
            if let semi = after.prefix(10).firstIndex(of: ";"), let decoded = entity(after[..<semi]) {
                out += decoded
                rest = after[after.index(after: semi)...]
            } else {
                out += "&"
                rest = after
            }
        }
        return out + rest
    }

    private static func entity(_ name: Substring) -> String? {
        if let named = entities[String(name)] { return named }
        guard name.first == "#" else { return nil }
        let number = name.dropFirst()
        let value = number.first == "x" || number.first == "X" ? UInt32(number.dropFirst(), radix: 16) : UInt32(number)
        return value.flatMap(Unicode.Scalar.init).map { String(Character($0)) }
    }
}

/// One subtitle track's cues and which of them are on screen at a time.
struct CueTrack {
    /// Ordered by start.
    let cues: [SubtitleCue]

    /// The cues showing at `ms`, in the track's time.
    func active(at ms: Int) -> [SubtitleCue] {
        cues.prefix { $0.startMs <= ms }.filter { ms < $0.endMs }
    }
}
