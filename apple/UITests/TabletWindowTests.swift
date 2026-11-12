import XCTest
import UIKit

/// iPadOS window resizing exercises the same compact-width layouts as Split View.
final class TabletWindowTests: XCTestCase {
    @MainActor
    func testLibraryAtACompactWindowWidth() throws {
        guard UIDevice.current.userInterfaceIdiom == .pad else { throw XCTSkip("Requires an iPad.") }
        guard #available(iOS 26, *) else { throw XCTSkip("Requires iPadOS window resizing.") }
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-tab", "tvShows"]
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["Clear filters"].waitForExistence(timeout: 20))
        let originalFrame = app.windows.firstMatch.frame
        let corner = app.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -3, dy: -3))
        let compact = app.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.9))
        corner.press(forDuration: 0.5, thenDragTo: compact)
        let resized = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.windows.firstMatch.frame.width < 600
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [resized], timeout: 10), .completed)
        XCTAssertTrue(app.staticTexts["Harbor Lights"].firstMatch.exists)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Tablet compact library window"
        shot.lifetime = .keepAlways
        add(shot)
        let window = app.windows.firstMatch
        let restore = window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: originalFrame.maxX - window.frame.minX - 3,
            dy: originalFrame.maxY - window.frame.minY - 3
        ))
        window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -3, dy: -3))
            .press(forDuration: 0.5, thenDragTo: restore)
        let expanded = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.windows.firstMatch.frame.width >= 1000
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [expanded], timeout: 10), .completed)
    }
}
