import XCTest

/// A person's page on iPhone and iPad, on the fakes at API level 34
/// (`-fakesRedesign`): a movie page's Cast and crew are buttons, the cast
/// first, then the crew; a face opens the person's page, with their name,
/// how many of their titles the library has, and those titles newest first
/// with the part they play; a poster opens its title, and Back comes back to
/// the person, then to the movie. On an older server the cast takes no taps.
/// iPad runs held wide.
final class PersonTests: XCTestCase {
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
    func testACastFaceOpensThePersonsTitlesAndBackComesBack() {
        let app = launch(["-fakesRedesign", "-open", "watchmymedia://movies/502"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Ninety Fathoms"].firstMatch.waitForExistence(timeout: 20))

        let ines = app.buttons["cast-person-9001"]
        scroll(app, to: ines)
        XCTAssertTrue(onScreen(app, ines), "Ines Varga is in the cast")
        XCTAssertEqual(ines.label, "Ines Varga, as Io Vance")
        XCTAssertTrue(app.staticTexts["Cast and crew"].exists, "the crew follows the cast")
        XCTAssertEqual(app.buttons["cast-person-9021"].label, "Petra Lind, Director, Writer", "a director who writes is there once")
        shot("Cast and crew on a movie page")

        ines.tap()
        let name = app.staticTexts["person-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15), "her page opens")
        XCTAssertEqual(name.label, "Ines Varga")
        let count = app.staticTexts["person-count"]
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        XCTAssertEqual(count.label, "18 titles in your library: 2 movies and 16 shows")
        let fathoms = app.buttons["person-title-movie-502"]
        XCTAssertTrue(fathoms.waitForExistence(timeout: 10))
        XCTAssertTrue(fathoms.label.contains("2026 · as Io Vance"), "a poster says the year and her part: \(fathoms.label)")
        shot("A person's page")
        let harbor = app.buttons["person-title-show-100"]
        scroll(app, to: harbor)
        XCTAssertTrue(harbor.label.contains("TV show · as Maren Holt"), "a show says so, and her part: \(harbor.label)")
        shot("Further down a person's page, the name in the bar")

        // A poster opens its title; Back comes back to her page, then to the movie.
        harbor.tap()
        let eyebrow = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "HARBOR LIGHTS · ")).firstMatch
        XCTAssertTrue(eyebrow.waitForExistence(timeout: 15), "Harbor Lights' page opens")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(name.waitForExistence(timeout: 10), "Back: her page")
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(ines.waitForExistence(timeout: 10), "Back again: the movie, at its cast")
    }

    @MainActor
    func testAnOlderServersCastTakesNoTaps() {
        let app = launch(["-open", "watchmymedia://movies/502"])
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["Ninety Fathoms"].firstMatch.waitForExistence(timeout: 20))
        let heading = app.staticTexts["Cast"]
        scroll(app, to: heading)
        XCTAssertTrue(heading.exists, "the cast is there")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "cast-person-")).firstMatch.exists,
                       "but a server that doesn't say who they are opens nobody")
    }

    // MARK: Helpers

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
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
