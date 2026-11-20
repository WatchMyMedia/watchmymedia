import Foundation
import WMMModel

// Link a TV and Add a server as stepped tasks:
// each step's
// number and name and the next one's, each button's label and why it is
// off, every failure ending in what was or wasn't done, and the finished
// screen's What changed and What happens next.

/// Where a stepped task stands, as the bar over its sheet says it.
public struct FlowStep: Hashable, Sendable {
    /// 1-based.
    public var number: Int
    /// Every step's name in order.
    public var names: [String]
    /// Steps done, ticked on Apple TV's steps: those before this one, a
    /// skipped one included, and all of them once finished.
    public var done: Set<Int>

    public init(number: Int, names: [String], done: Set<Int>? = nil) {
        self.number = number
        self.names = names
        self.done = done ?? Set(1..<max(number, 1))
    }

    public var count: Int { names.count }
    public var name: String { names[number - 1] }
    public var next: String? { number < count ? names[number] : nil }
    public var isLast: Bool { number == count }

    /// "Step 1 of 3 · Enter the code · next: Check it’s yours"; the last step's name alone.
    public var line: String {
        guard let next else { return name }
        return "Step \(number) of \(count) · \(name) · next: \(next)"
    }

    /// What VoiceOver reads: "Step 2 of 3, Check it’s yours".
    public var accessibilityLabel: String { isLast ? name : "Step \(number) of \(count), \(name)" }

    /// Apple TV's footer: "Step 1 of 3 · next: Sign in".
    public var shortLine: String { next.map { "Step \(number) of \(count) · next: \($0)" } ?? name }
}

/// A task's last screen: what changed and what happens next.
public struct FinishedWords: Hashable, Sendable {
    public var title: String
    public var lede: String
    public var whatChanged: [String]
    public var whatHappensNext: [String]

    public static let whatChangedHeading = "What changed"
    public static let whatHappensNextHeading = "What happens next"
}

// MARK: - Link a TV

/// Why looking a code up failed, ending in what wasn't done.
func linkLookUpProblem(_ error: any Error, serverName: String?) -> SettingsNotice {
    switch (error as? APIError)?.status {
    case 404?:
        SettingsNotice(title: "No TV is waiting with that code",
                       body: "Nothing was linked. Check the code on the TV, or choose Get a new code there, then enter it here.")
    case 429?:
        linkTooManyCodes
    case nil, (500...)?:
        SettingsNotice(title: "Couldn’t reach \(serverName ?? "the server")", body: "Nothing was linked. Check your connection and try again.")
    default:
        SettingsNotice(title: "Couldn’t check that code", body: "Nothing was linked. \(error.displayMessage)")
    }
}

/// The server refused a code with too many wrong ones.
let linkTooManyCodes = SettingsNotice(title: "Too many wrong codes", body: "Nothing was linked. Try again in a minute.")

/// Why approving failed, ending in what wasn't done.
func linkApproveProblem(_ error: any Error, device: PendingDevice, serverName: String?) -> SettingsNotice {
    switch (error as? APIError)?.status {
    case 409?:
        SettingsNotice(title: "This TV is already signed in",
                       body: "Someone approved this code a moment ago, so nothing more was linked. It’s listed under Signed-in devices.")
    case 404?:
        SettingsNotice(title: "The code ran out before it was approved",
                       body: "Nothing was linked. On the TV, choose Get a new code, then enter it here.")
    case 429?:
        SettingsNotice(title: "Too many tries", body: "Nothing was linked, and the TV is still waiting. Try again in a minute.")
    case nil, (500...)?:
        SettingsNotice(title: "Couldn’t reach \(serverName ?? "the server")",
                       body: "Nothing was linked, and the TV is still waiting. Check your connection and try again.")
    default:
        SettingsNotice(title: "Couldn’t approve \(device.name)", body: "Nothing was linked. \(error.displayMessage)")
    }
}

extension LinkTVModel {
    public static let stepNames = ["Enter the code", "Check it’s yours", "Linked"]

    public var flowStep: FlowStep {
        switch step {
        case .enter: FlowStep(number: 1, names: Self.stepNames)
        case .found: FlowStep(number: 2, names: Self.stepNames)
        case .approved: FlowStep(number: 3, names: Self.stepNames, done: [1, 2, 3])
        }
    }

    /// The TV behind the code, once found.
    public var device: PendingDevice? {
        switch step {
        case .enter: nil
        case .found(let d), .approved(let d): d
        }
    }

    public var title: String {
        switch step {
        case .enter: "Enter the code on the TV"
        case .found: "Is this your TV?"
        case .approved(let d): "\(d.name) is signed in"
        }
    }

    public var lede: String {
        switch step {
        case .enter: "The TV shows a six-character code on its sign-in screen. Scanning the TV’s QR code opens this step with the code filled in."
        case .found: "Approve only a TV that is showing this code right now."
        case .approved: "It shows Home in a moment, signed in as \(userName)."
        }
    }

    public static let codeNote = "Codes last 10 minutes. A wrong code counts like a failed sign-in."
    public static let notMyTV = "Not my TV"
    public static let linkAnother = "Link another TV"
    /// Back from the second step, to the code.
    public static let backToCodeTitle = "Code"
    public static let thisTV = "This TV"

    /// The filled button: "Check this code", "Approve Living room TV", "Done".
    public var primaryTitle: String {
        switch step {
        case .enter: "Check this code"
        case .found(let d): "Approve \(d.name)"
        case .approved: SettingsWords.done
        }
    }

    /// Why the filled button is off; nil while it is on. After too many
    /// wrong codes it stays off for a minute, whatever is typed.
    public var primaryOffReason: String? {
        guard case .enter = step else { return nil }
        if tooManyCodes { return "Too many wrong codes. Nothing was linked. Try again in a minute." }
        if !complete { return "Check is off until all six characters are in." }
        return nil
    }

    /// The step has a Back: only Check it's yours, since the code is still
    /// waiting there; Linked has none, as the TV is signed in.
    public var hasBack: Bool { if case .found = step { true } else { false } }

    /// The code's boxes, spoken: "Code, four of six characters entered", then "Code K7M 4PQ".
    public var codeAccessibilityLabel: String {
        let numbers = ["no", "one", "two", "three", "four", "five", "six"]
        guard !complete else { return "Code \(spacedCode(code))" }
        return "Code, \(numbers[min(code.count, 6)]) of six characters entered"
    }

    /// The second step's rows: Name, App, Address, Asked.
    public func deviceRows(now: Date = .now) -> [(label: String, value: String)] {
        guard let d = device else { return [] }
        var rows: [(String, String)] = [("Name", d.name)]
        if let app = d.appLabel { rows.append(("App", app)) }
        if let ip = d.ip, !ip.isEmpty { rows.append(("Address", ip)) }
        if let asked = d.askedAt { rows.append(("Asked", formatAgo(asked, now: now))) }
        return rows
    }

    /// Under the TV's rows: "Approving signs this TV in as Sam. Anyone with its remote …".
    public var approveNote: String {
        "Approving signs this TV in as \(userName). Anyone with its remote can watch as you until you sign it out under Signed-in devices."
    }

    /// Linked's words, once the TV is signed in.
    public var finished: FinishedWords? {
        guard case .approved(let d) = step else { return nil }
        return FinishedWords(
            title: "\(d.name) is signed in",
            lede: "It shows Home in a moment, signed in as \(userName).",
            whatChanged: ["\(d.name) is signed in as \(userName).", "It is listed under Signed-in devices."],
            whatHappensNext: ["It stays signed in until you sign it out there, or change your password."]
        )
    }
}

// MARK: - Add a server

/// Why connecting to a server failed, as Add a server's first step says it.
public struct ConnectFailure: Hashable, Sendable {
    public enum Kind: Hashable, Sendable {
        /// Nothing answered at the address.
        case unreachable
        /// It answered, on this version, which this app can't use.
        case tooOld(version: String)
        /// It refused, or the address needs https: the reason as given.
        case other(String)
    }

    /// The row's ID, or ``ServerPickerModel/addressKey`` for the typed address.
    public var key: String
    /// What was being reached: a server's name, or the address as typed.
    public var label: String
    public var kind: Kind

    init(key: String, label: String, error: any Error) {
        self.key = key
        self.label = label
        if let old = error as? ServerTooOldError {
            kind = .tooOld(version: old.server.version)
        } else if let api = error as? APIError {
            let words = api.message
            let reasoned = words == localNetworkOffMessage || words.contains("https") || words.contains("certificate")
            kind = api.status == nil && !reasoned ? .unreachable : .other(words)
        } else {
            kind = .unreachable
        }
    }

    /// "Couldn’t reach 192.0.2.99" and what to check; `after` ends it ("You’re still on Living room server.").
    public func notice(after: String? = nil) -> SettingsNotice {
        let (title, body): (String, String) = switch kind {
        case .unreachable:
            ("Couldn’t reach \(label)", "Check the address, and that the server is running and on this network.")
        case .tooOld(let version):
            ("That server is too old for this app",
             "It is on \(versionLabel(version)), and this app needs server \(minimumServerVersion) or newer. Update that server, then try again.")
        case .other(let words):
            ("Couldn’t connect to \(label)", words)
        }
        return SettingsNotice(title: title, body: after.map { "\(body) \($0)" } ?? body)
    }
}

extension AddServerModel {
    public static let stepNames = ["Choose a server", "Sign in", "Added"]
    /// The task's name, over its steps.
    public static let title = "Add a server"

    public var flowStep: FlowStep {
        switch phase {
        case .picking, .connecting: FlowStep(number: 1, names: Self.stepNames)
        case .signingIn, .restoring: FlowStep(number: 2, names: Self.stepNames)
        case .finished: FlowStep(number: 3, names: Self.stepNames, done: [1, 2, 3])
        }
    }

    /// "You’re still on Living room server.", which ends every failure.
    public var stillOn: String { ServersWords.stillOn(originalServer.name) }

    /// The first step's title.
    public static let chooseTitle = "Which server?"
    public static let enterAddress = "Enter an address…"
    public static let enterAddressLine = "For a server that isn’t listed"
    public static let connectTitle = "Connect to this address"
    /// Back from Sign in, to the list.
    public static let backToServersTitle = "Servers"
    public static let useCodeTitle = "Use a code from a signed-in device instead"
    public static let usePasswordTitle = "Sign in with a password instead"

    /// "Use a code from a signed-in device instead": Sign in shows a pairing
    /// code to approve on a device signed in there; the screen runs the
    /// sign-in's ``SignInModel/pair()`` keyed on its ``SignInModel/pairingRun``.
    public func useCode() { signIn?.usePairingCode() }

    /// "Sign in with a password instead", back to the form, where the server allows passwords.
    public func usePassword() { signIn?.usePassword() }

    /// The link to the other way in: the code under the form, the form under the code; nil when there is none.
    public var otherWayTitle: String? {
        guard let signIn, !signIn.setupRequired else { return nil }
        if !signIn.codeSignIn { return Self.useCodeTitle }
        return signIn.passwordAllowed ? Self.usePasswordTitle : nil
    }

    /// Under the code: "On a phone or computer signed in to Den server, open
    /// Link a TV and enter this code. You’re still on Living room server until it’s approved."
    public var codeLede: String {
        "On a phone or computer signed in to \(signingInTo?.name ?? "that server"), open Link a TV and enter this code. You’re still on \(originalServer.name) until it’s approved."
    }
    /// Apple TV's path over the steps.
    public static let tvPath = "Settings › Server › Add a server"

    /// Apple TV's lede on the first step.
    public var chooseLede: String {
        "Servers on this network appear by themselves. You stay signed in on \(originalServer.name), and switching between them needs no password."
    }

    /// Why Connect to this address is off; nil while it is on.
    public var connectOffReason: String? {
        picker.address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Connect is off until there is an address." : nil
    }

    /// Why connecting failed, ending in "You’re still on Living room server."
    public var chooseProblem: SettingsNotice? { picker.failure?.notice(after: stillOn) }

    /// The server being signed in to, on the second step, or added.
    public var signingInTo: Server? {
        if outcome == .added { return addedServer }
        return phase == .signingIn || phase == .restoring ? signIn?.server : nil
    }

    /// "Sign in to Den server".
    public var signInTitle: String { "Sign in to \(signingInTo?.name ?? "the server")" }

    /// "192.0.2.52 · v1.3.2. Signing in here keeps you signed in on Living room server too."
    public var signInLede: String {
        let which = signingInTo.map { "\($0.displayAddress) · \($0.versionLabel). " } ?? ""
        return "\(which)Signing in here keeps you signed in on \(originalServer.name) too."
    }

    /// Why Sign in is off; nil while it is on.
    public var signInOffReason: String? {
        guard let signIn, !signIn.codeSignIn, !signIn.canSubmit, !signIn.busy else { return nil }
        return "Sign in is off until there is a name and a password."
    }

    /// A wrong name or password, said under the password: "Wrong name or password. You’re still on Living room server."
    public var passwordProblem: String? {
        signIn?.errorStatus == 401 ? "Wrong name or password. \(stillOn)" : nil
    }

    /// Any other failure to sign in, or to go back, above the button.
    public var signInProblem: SettingsNotice? {
        if let error { return SettingsNotice(title: "Couldn’t go back to \(originalServer.name)", body: error) }
        guard let signIn, let words = signIn.error, signIn.errorStatus != 401 else { return nil }
        if signIn.errorStatus == 429 { return SettingsNotice(title: "Too many tries", body: "Wait a minute, then try again. \(stillOn)") }
        return SettingsNotice(title: "Couldn’t sign in to \(signingInTo?.name ?? "the server")", body: "\(words) \(stillOn)")
    }

    /// Added's words: "Den server is added", that this device is on it now,
    /// how many servers it is signed in to and that search looks in all of
    /// them, and where switching is. `signedIn` is how many servers this
    /// device is signed in to now; `tv` words the way to Servers for the remote.
    public func finished(device: String, signedIn: Int, tv: Bool = false) -> FinishedWords? {
        guard outcome == .added, let server = addedServer else { return nil }
        let title = if skippedSignIn {
            "\(server.name) was added already"
        } else if signingInAgain {
            "You’re signed in to \(server.name) again"
        } else {
            "\(server.name) is added"
        }
        let count = max(signedIn, 1)
        let changed = count > 1
            ? "This \(device) is signed in to \(count) servers. Search looks in all of them."
            : "This \(device) is signed in to 1 server."
        return FinishedWords(
            title: title,
            lede: addedUser.map { "You’re on it now, signed in as \($0.name)." } ?? "You’re on it now.",
            whatChanged: [changed],
            whatHappensNext: [tv ? "Select your initial beside the tabs to switch. It doesn’t ask for a password again."
                                 : "Tap your initial on Home to switch. It doesn’t ask for a password again."]
        )
    }

    /// Apple TV's buttons on Added, and the line under them.
    public static let goHome = "Go to Home"
    public static let backToSettings = "Back to Settings"
    public static let tvFinishedHint = "There is no way back from here: the server is added. Menu goes to Home"
}
