import Foundation
import WMMModel

// The words of Apple TV's sign-in, its server picker and Servers (the
// account tab's page). They call a server by its name and say where it is
// in words; the address shows only where a person types one, or where the
// link page has to be opened by hand. The iPhone and iPad keep their own.

/// Signing in on Apple TV: with a phone and the code first, a password as the fallback.
public enum TVSignInWords {
    public static let codeTitle = "Sign in with your phone"
    public static let stepTwo = "2. Sign in there, and check it shows this code"
    public static let newCode = "New code"
    public static let usePassword = "Use a password instead"
    public static let useCode = "Use a code instead"
    public static let anotherServer = "Another server"
    public static let qrCaption = "Point your phone’s camera here"
    public static let expiredTitle = "That code ran out"
    public static let expiredBody = "Codes last ten minutes. A new one takes a moment."
    public static let failedTitle = "Couldn’t get a code"
    public static let passwordTitle = "Sign in with a password"
    public static let nameLabel = "Name"
    public static let passwordLabel = "Password"
    public static let signIn = "Sign in"
    public static let iPhoneTitle = "Type on your iPhone"
    public static let iPhoneBody = "When a field is selected, an iPhone nearby offers its keyboard. Holding the remote’s microphone button lets you say it instead."
    public static let protectionOn = "This server’s sign-in protection is on, so this Apple TV signs in with the code."

    /// "1. Scan the code, or open 192.168.1.20:27587/link"; before the code
    /// has come, the link page without its address.
    public static func stepOne(link: URL?) -> String {
        "1. Scan the code, or open \(link.map(linkPageLabel) ?? "the server’s link page")"
    }

    /// Under the code: "Good for 9 more minutes. This Apple TV signs in by itself."
    public static func goodFor(expiresAt: Date, now: Date = .now) -> String {
        let minutes = max(Int((expiresAt.timeIntervalSince(now) / 60).rounded(.up)), 1)
        return "Good for \(minutes) more \(minutes == 1 ? "minute" : "minutes"). This Apple TV signs in by itself."
    }

    /// "Signed in as Robin", once the code is approved.
    public static func signedInAs(_ name: String) -> String { "Signed in as \(name)" }

    /// "Getting your library from Harbour House".
    public static func gettingLibrary(from server: String?) -> String {
        server.map { "Getting your library from \($0)" } ?? "Getting your library"
    }

    /// The code as it shows, three and three: "4K7 9QD".
    public static func shownCode(_ code: String) -> String { spacedCode(code) }
}

/// Choosing a server on Apple TV: one list, by name and where each one is,
/// with Enter an address as its last row.
public enum TVServerPickerWords {
    public static let title = chooseServerTitle
    public static let lead = "Your movies, shows and live TV come from a server you or someone you know runs."
    public static let noneTitle = "No server found on this network"
    public static let noneLead = "Check that the server is on, and that this Apple TV is on the same network as it."
    public static let deniedLead = "Local network is off for WatchMyMedia, so it can’t look for servers. Allow it in Settings › Privacy & Security › Local Network, or enter an address."
    public static let looking = "Looking on this network"
    public static let lookAgain = "Look again"
    public static let enterAddress = "Enter an address"
    public static let connect = "Connect"
    public static let inUse = "In use"
    /// "Not found on this network": a server used before that hasn't answered on this network while the list was up.
    public static let notFoundHere = "Not found on this network"

    /// Under Enter an address: what it is for, with an example of an address.
    public static func enterAddressLine(serversListed: Bool) -> String {
        serversListed
            ? "For a server this Apple TV can’t find by itself, such as 192.168.1.20"
            : "If you know it, such as 192.168.1.20 or https://home.example.net"
    }

    /// A server's line: where it is and who signed in there.
    /// "On this network · Robin signed in here before", "Not found on this network",
    /// "Too old for this app". No address and no version.
    public static func line(for row: ServerPickerModel.Row) -> String {
        if row.tooOld { return ServersWords.tooOld }
        let place = row.kind == .found ? onThisNetwork : notFoundHere
        guard let name = row.userName?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return place }
        return "\(place) · \(row.signedIn ? "\(name) is signed in here" : "\(name) signed in here before")"
    }
}

/// Servers, the account tab's page on Apple TV.
public enum TVServersWords {
    public static let serversGroup = "Servers"
    public static let yoursGroup = "Yours"
    public static let manageValue = "In Settings"

    /// "Signed in on Harbour House", under the person's name.
    public static func signedInOn(_ server: String?) -> String {
        server.map { "Signed in on \($0)" } ?? "Signed in"
    }

    /// The person's initial in the monogram: "R".
    public static func initial(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?"
    }

    /// A server's line in the list, when its state says nothing: where it is
    /// once seen on this network, else who is signed in there.
    public static func line(seenHere: Bool, userName: String?) -> String? {
        let name = userName?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        if seenHere { return name.map { "\(onThisNetwork) · \($0)" } ?? onThisNetwork }
        return name.map { "Signed in as \($0)" }
    }

    /// A failed switch, said on the server's row: "Couldn’t reach Den server. It
    /// may be off, or only reachable on its own network." Where the app still
    /// is goes, as the page says it at the top.
    public static func failureLine(_ failure: ServersModel.Failure, stillOn current: String?) -> String {
        var body = failure.body
        if let current {
            let still = ServersWords.stillOn(current)
            body = body.replacingOccurrences(of: still, with: "").trimmingCharacters(in: .whitespaces)
        }
        return [failure.title + ".", body].filter { !$0.isEmpty }.joined(separator: " ")
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
