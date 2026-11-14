import XCTest

// Waits that look at once, then every quarter second.
//
// XCTest's own waits look late: `waitForExistence` and an
// `XCTNSPredicateExpectation` waited on with `XCTWaiter` first look about a
// second after they start, even when what they wait for is already there,
// and the expectation then looks only every 6 s on a busy tvOS 27.2
// simulator (apple/AGENTS.md). A test that waited after each remote press
// spent 1.3 s a press (the A–Z index test: 22 presses down the letters took
// 31 s of its 66). These ask the same question, with the same timeout, as
// soon as they start and as often as a look at the screen allows.

/// Waits until `condition` holds, asking at once and then every quarter
/// second until `timeout`; false if it never did.
@MainActor
func eventually(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
    let deadline = Date.now.addingTimeInterval(timeout)
    while true {
        if condition() { return true }
        if Date.now >= deadline { return false }
        RunLoop.current.run(until: min(deadline, Date.now.addingTimeInterval(0.25)))
    }
}

extension XCUIElement {
    /// Waits until the element exists and `predicate` holds for it, as an
    /// `XCTNSPredicateExpectation` on the element would. Each look reads one
    /// snapshot, so an element that goes between looks fails the look
    /// rather than the test.
    @MainActor
    func wait(until predicate: NSPredicate, timeout: TimeInterval) -> Bool {
        eventually(timeout: timeout) {
            guard let snapshot = lookedAt else { return false }
            return predicate.evaluate(with: snapshot)
        }
    }

    /// The element's snapshot, or nil when it isn't there. `snapshot()`
    /// alone retries for 2 s before it gives up on a missing element, so
    /// this asks `exists` first.
    @MainActor
    var lookedAt: (any XCUIElementSnapshot)? {
        exists ? try? snapshot() : nil
    }

    /// `waitForExistence(timeout:)`, looking at once, then half a second
    /// more once the element is there. A screen still coming up drops a
    /// press made at once (Right on the diagnostics code, Select on the
    /// trailer's code): XCTest's late first look gave it about a second,
    /// this gives it half of one. Wait for focus where a press needs it.
    @MainActor
    func waitUntilExists(timeout: TimeInterval) -> Bool {
        settled(eventually(timeout: timeout) { exists })
    }

    /// `waitForNonExistence(timeout:)`, looking at once, with the same half
    /// second for what is under the element to settle once it has gone.
    @MainActor
    func waitUntilGone(timeout: TimeInterval) -> Bool {
        settled(eventually(timeout: timeout) { !exists })
    }

    @MainActor
    private func settled(_ done: Bool) -> Bool {
        if done { RunLoop.current.run(until: Date.now.addingTimeInterval(0.5)) }
        return done
    }

    /// Waits until the element has focus.
    @MainActor
    func waitForFocus(timeout: TimeInterval = 10) -> Bool {
        wait(until: NSPredicate(format: "hasFocus == true"), timeout: timeout)
    }

    /// Waits until the element's label contains `text`.
    @MainActor
    func waitForLabel(containing text: String, timeout: TimeInterval = 10) -> Bool {
        wait(until: NSPredicate(format: "label CONTAINS %@", text), timeout: timeout)
    }
}

extension XCUIApplication {
    /// The button with focus, found again at each look.
    @MainActor
    var focusedButton: XCUIElement {
        buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }

    /// Waits until a button has focus and its label passes `test`, read from
    /// one snapshot.
    @MainActor
    func waitForFocusedButton(timeout: TimeInterval = 10, where test: (String) -> Bool) -> Bool {
        eventually(timeout: timeout) {
            guard let snapshot = focusedButton.lookedAt else { return false }
            return test(snapshot.label)
        }
    }

    /// Presses `button` on the remote and waits, up to `timeout`, for focus
    /// to leave the button that had it, so a loop that presses until focus
    /// reaches something neither presses a slow move twice nor waits out a
    /// timeout on each step it passes through.
    @MainActor
    func press(_ button: XCUIRemote.Button, thenWaitForFocusToMove timeout: TimeInterval = 3) {
        let from = focusedButton.lookedAt?.label
        XCUIRemote.shared.press(button)
        _ = waitForFocusedButton(timeout: timeout) { $0 != from }
    }
}
