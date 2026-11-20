import XCTest

/// Related titles at the end of movie and show pages on iPhone and iPad, on
/// the fakes at API level 30 (`-fakesRelated`): the collection, then More
/// like this, after everything else; a card opens its title's page over
/// this one and Back comes back to the row; a page with nothing related, or
/// on an older server, ends as it did. iPad runs held wide, where the movie
/// page's rows end its right-hand column.
final class RelatedTitlesTests: XCTestCase {
    override func setUp() async throws { continueAfterFailure = false }

    private var pad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    @MainActor
    private func launch(_ arguments: [String]) -> XCUIApplication {
        XCUIDevice.shared.orientation = pad ? .landscapeLeft : .portrait
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"] + arguments
        app.launch()
        return app
    }

    @MainActor
    func testAMoviePageEndsWithItsCollectionThenMoreLikeThis() {
        let app = launch(["-fakesRelated", "-open", "watchmymedia://movies/500"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Dead Reckoning Bay"].firstMatch.waitForExistence(timeout: 20))

        let collection = heading(app, "Carrow Coast Collection")
        let similar = heading(app, "More like this")
        let coldHarbour = card(app, "Cold Harbour Road")
        scroll(app, to: coldHarbour)
        XCTAssertTrue(onScreen(app, coldHarbour), "the collection's first film, in release order")
        XCTAssertTrue(card(app, "Low Tide at Carrow").exists)
        XCTAssertTrue(collection.exists, "the collection's row, named as TMDB names it")
        scroll(app, to: similar)
        XCTAssertTrue(similar.exists)
        XCTAssertLessThan(collection.frame.minY, similar.frame.minY, "the collection first, then More like this")
        XCTAssertFalse(app.buttons["row-see-all"].exists, "no See all")
        XCTAssertTrue(card(app, "Ninety Fathoms").label.contains("2026"), "a card reads its title and line: \(card(app, "Ninety Fathoms").label)")
        snap("The movie page ends with its collection and More like this")

        // A card opens its page over this one; Back comes back to the row.
        scroll(app, to: coldHarbour)
        coldHarbour.tap()
        XCTAssertTrue(waitUntil { !coldHarbour.exists && app.staticTexts["Cold Harbour Road"].firstMatch.exists }, "Cold Harbour Road's page opens")
        snap("A collection card opens its film's page")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(waitUntil { self.onScreen(app, coldHarbour) }, "Back comes back to the row")
        snap("Back: the movie page at its rows")
    }

    @MainActor
    func testAShowPageEndsWithMoreLikeThis() {
        let app = launch(["-fakesRelated", "-open", "watchmymedia://shows/100"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Harbor Lights"].firstMatch.waitForExistence(timeout: 20))
        let similar = heading(app, "More like this")
        scroll(app, to: similar)
        // Up to the row's first card, under its heading.
        for _ in 0..<3 where firstCard(app, under: similar).map({ !onScreen(app, $0) }) ?? true { nudge(app) }
        let first = try? XCTUnwrap(firstCard(app, under: similar), "More like this ends the page")
        guard let first else { return XCTFail("no card under More like this") }
        XCTAssertTrue(onScreen(app, first))
        XCTAssertFalse(heading(app, "Carrow Coast Collection").exists, "a show has no collection")
        let title = String(first.label.prefix { $0 != "," })
        XCTAssertFalse(title.isEmpty)
        XCTAssertNotEqual(title, "Harbor Lights", "never the page's own show")
        snap("The show page ends with More like this")

        first.tap()
        // The episode on top's eyebrow names its show: "THE QUIET MERIDIAN · S02 E04".
        let eyebrow = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "\(title.uppercased()) · ")).firstMatch
        XCTAssertTrue(eyebrow.waitForExistence(timeout: 10), "\(title)'s page opens")
        snap("A card opens its show's page")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(waitUntil { self.onScreen(app, self.card(app, title)) }, "Back comes back to the row")
    }

    @MainActor
    func testAPageWithNothingRelatedEndsAsItDid() {
        let app = launch(["-fakesRelated", "-open", "watchmymedia://movies/507"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Tin Sky"].firstMatch.waitForExistence(timeout: 20))
        for _ in 0..<6 { app.swipeUp() }
        XCTAssertFalse(app.descendants(matching: .any)["related-titles"].exists)
        XCTAssertFalse(heading(app, "More like this").exists)
    }

    @MainActor
    func testAnOlderServerShowsNoRows() {
        // The fakes' server is at API level 6 without -fakesRelated.
        let app = launch(["-open", "watchmymedia://movies/500"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Dead Reckoning Bay"].firstMatch.waitForExistence(timeout: 20))
        for _ in 0..<6 { app.swipeUp() }
        XCTAssertFalse(app.descendants(matching: .any)["related-titles"].exists)
        XCTAssertFalse(heading(app, "Carrow Coast Collection").exists)
    }

    // MARK: Helpers

    /// A row's heading, by its words.
    @MainActor
    private func heading(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", title)).firstMatch
    }

    /// A related title's card: its title, then its line ("Cold Harbour Road, 2022 · 1 h 52 min").
    @MainActor
    private func card(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(title),")).firstMatch
    }

    /// The first card under a row's heading.
    @MainActor
    private func firstCard(_ app: XCUIApplication, under heading: XCUIElement) -> XCUIElement? {
        guard heading.exists else { return nil }
        let top = heading.frame.maxY
        return app.buttons.matching(NSPredicate(format: "label CONTAINS %@", ", ")).allElementsBoundByIndex
            .filter { $0.frame.minY > top && $0.frame.minY < top + 80 }
            .min { $0.frame.minX < $1.frame.minX }
    }

    /// A short scroll up.
    @MainActor
    private func nudge(_ app: XCUIApplication) {
        let window = app.windows.firstMatch.frame
        let x = pad ? window.width * 0.7 : window.width / 2
        let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: window.height * 0.6))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -150)))
    }

    /// Scrolls the page (iPad's right-hand column, held wide) until `element` is on screen.
    @MainActor
    private func scroll(_ app: XCUIApplication, to element: XCUIElement) {
        for _ in 0..<10 where !onScreen(app, element) {
            let window = app.windows.firstMatch.frame
            let x = pad ? window.width * 0.7 : window.width / 2
            let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: window.height * 0.75))
            let end = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: window.height * 0.35))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
    }

    /// On screen, clear of the tab bar.
    @MainActor
    private func onScreen(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        guard element.exists else { return false }
        var visible = app.windows.firstMatch.frame
        let tabBar = app.tabBars.firstMatch
        if tabBar.exists, tabBar.frame.minY > visible.midY { visible.size.height = tabBar.frame.minY - visible.minY }
        return visible.contains(element.frame)
    }

    @MainActor
    private func waitUntil(timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in condition() }, object: nil)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor
    private func snap(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
