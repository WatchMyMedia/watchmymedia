import XCTest

extension XCUIApplication {
    /// Opens Settings from a tab's root: its tab where the tab bar has one,
    /// else (a server with Live TV, whose tab takes Settings' place) the
    /// account button, which opens Servers, and Settings there.
    @MainActor
    func openSettings(timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let tab = buttons["Settings"].firstMatch
        let account = buttons["account-button"].firstMatch
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            (tab.exists && tab.isHittable) || (account.exists && account.isHittable)
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: timeout), .completed, "a way to Settings", file: file, line: line)
        if tab.exists && tab.isHittable {
            tab.tap()
            return
        }
        account.tap()
        let row = buttons["servers-settings"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Servers offers Settings where it has no tab", file: file, line: line)
        row.tap()
    }
}
