import XCTest

/// Boost dialogue, Reduce loud sounds, Subtitle size and subtitle timing on
/// the fakes at API level 35 (`-fakesDialogue`), with the remote: the info
/// panel's Sound pills and timing row (Left is Earlier, Right is Later,
/// focus staying on the row), and Settings' Sound menus and the size's
/// screen, where Left and Right show each size and Select keeps one.
/// Each step keeps a screenshot in the result bundle.
final class DialogueTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesDialogue"] + arguments
        app.launch()
        return app
    }

    @MainActor
    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// What has focus, of any kind: the timing row is a view of its own, not a button.
    @MainActor
    private func focus(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }

    /// Presses `button` until `element` has focus, waiting for focus to move after each press.
    @MainActor
    private func move(_ button: XCUIRemote.Button, to element: XCUIElement, in app: XCUIApplication, presses: Int = 10) -> Bool {
        for _ in 0..<presses {
            if element.lookedAt?.hasFocus == true { return true }
            let before = focus(app).lookedAt?.frame
            remote.press(button)
            _ = eventually(timeout: 2) { focus(app).lookedAt.map { $0.frame != before } ?? false }
        }
        return element.waitForFocus(timeout: 2)
    }

    @MainActor
    func testTheTimingRowTakesLeftAndRightAndTheSoundPillsSave() {
        let app = launch(["-open", "watchmymedia://play/9101", "-playerSubtitle", "s4", "-playerPanel", "tracks"])
        defer { app.terminate() }
        let panel = app.descendants(matching: .any)["player-options-tracks"]
        XCTAssertTrue(panel.waitUntilExists(timeout: 20), "the tab shows")
        XCTAssertTrue(app.buttons["player-sound-dialogueBoost-off"].exists, "Sound is under the audio tracks")
        let timing = app.descendants(matching: .any)["player-timing"]
        XCTAssertTrue(timing.exists, "Subtitle timing is under the subtitles while one is on")
        XCTAssertEqual(timing.value as? String, "In step")
        shot("tracks")

        // Into the subtitles' list, then down to the timing row.
        remote.press(.right)
        XCTAssertTrue(move(.down, to: timing, in: app), "Down reaches the timing row")
        remote.press(.right)
        XCTAssertTrue(timing.wait(until: NSPredicate(format: "value == '0.1 s later'"), timeout: 5), String(describing: timing.value))
        for _ in 0..<3 { remote.press(.right) }
        XCTAssertTrue(timing.wait(until: NSPredicate(format: "value == '0.4 s later'"), timeout: 5), String(describing: timing.value))
        XCTAssertTrue(timing.hasFocus, "Right moves the timing, not focus")
        shot("timing later")
        for _ in 0..<5 { remote.press(.left) }
        XCTAssertTrue(timing.wait(until: NSPredicate(format: "value == '0.1 s earlier'"), timeout: 5), String(describing: timing.value))
        XCTAssertTrue(timing.hasFocus, "Left moves the timing, not focus")
        // Down to Reset and Save for everyone (Sam is an admin).
        let reset = app.buttons["player-timing-reset"]
        XCTAssertTrue(reset.waitUntilExists(timeout: 5))
        XCTAssertTrue(app.buttons["player-timing-everyone"].exists)
        // Down lands on whichever is nearest, Save for everyone under the row's middle.
        remote.press(.down)
        XCTAssertTrue(app.waitForFocusedButton(timeout: 5) { $0 == "Save for everyone" || $0 == "Reset" })
        shot("timing buttons")
        if !reset.hasFocus { remote.press(.left) }
        XCTAssertTrue(reset.waitForFocus(timeout: 5))
        remote.press(.select)
        XCTAssertTrue(timing.wait(until: NSPredicate(format: "value == 'In step'"), timeout: 5))
        XCTAssertTrue(reset.hasFocus, "pressed, Reset keeps focus, dimmed, rather than focus jumping to the top")
        XCTAssertFalse(reset.isEnabled)
        shot("timing reset pressed")

        // Over to the sound pills, from the subtitle tracks above the row (Left on the row is Earlier):
        // Medium saves, the pill spins, then settles.
        let medium = app.buttons["player-sound-dialogueBoost-medium"]
        remote.press(.up)
        XCTAssertTrue(timing.waitForFocus(timeout: 5))
        XCTAssertTrue(reset.waitUntilGone(timeout: 5), "and goes once focus moves on")
        remote.press(.up)
        XCTAssertTrue(app.buttons["player-subtitles-find-more"].waitForFocus(timeout: 5))
        remote.press(.left)
        XCTAssertTrue(app.waitForFocusedButton(timeout: 5) { $0.hasPrefix("Boost dialogue") || $0.hasPrefix("Reduce loud sounds") || $0.hasPrefix("English") || $0.hasPrefix("Spanish") })
        // Down or up to Boost dialogue's pills, then along them to Medium.
        for _ in 0..<4 where !(app.focusedButton.lookedAt?.label.hasPrefix("Boost dialogue") ?? false) && !(app.focusedButton.lookedAt?.label.hasPrefix("Reduce") ?? false) {
            app.press(.down, thenWaitForFocusToMove: 2)
        }
        for _ in 0..<4 where app.focusedButton.lookedAt?.label.hasPrefix("Boost dialogue") != true {
            app.press(.up, thenWaitForFocusToMove: 2)
        }
        XCTAssertTrue(app.waitForFocusedButton(timeout: 3) { $0.hasPrefix("Boost dialogue") })
        let toMedium: XCUIRemote.Button = app.focusedButton.label.hasSuffix("High") ? .left : .right
        XCTAssertTrue(move(toMedium, to: medium, in: app, presses: 2))
        remote.press(.select)
        let line = app.staticTexts["player-sound-dialogueBoost-line"]
        XCTAssertTrue(line.wait(until: NSPredicate(format: "label == 'Changing the sound…'"), timeout: 3), line.label)
        shot("sound busy")
        XCTAssertTrue(line.wait(until: NSPredicate(format: "label == 'Makes voices clearer over music and effects.'"), timeout: 10), line.label)
        XCTAssertTrue(medium.isSelected)
        shot("sound medium")
    }

    @MainActor
    func testSoundIsTwoMenusAndTheSizeOpensItsPreview() {
        let app = launch(["-tab", "settings", "-settingsGroup", "languages"])
        defer { app.terminate() }
        let group = app.buttons["settings-group-languages"]
        XCTAssertTrue(group.waitUntilExists(timeout: 20))
        XCTAssertTrue(group.waitForFocus(timeout: 10))
        let boost = app.descendants(matching: .any)["settings-row-boost"]
        XCTAssertTrue(boost.exists, "Sound's rows follow the languages'")
        XCTAssertTrue(app.staticTexts["Apple TV’s own Enhance Dialogue and Reduce Loud Sounds, in Settings › Video and Audio, also work here."].exists)
        shot("settings")
        remote.press(.right)
        for _ in 0..<8 where !(boost.lookedAt.map { row in focus(app).lookedAt.map { row.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) } ?? false } ?? false) {
            remote.press(.down)
            _ = eventually(timeout: 1) { false }
        }
        remote.press(.select)
        let medium = app.otherElements.matching(NSPredicate(format: "label BEGINSWITH %@", "Medium")).firstMatch
        XCTAssertTrue(medium.waitUntilExists(timeout: 5), "Select opens Off, Medium and High")
        remote.press(.down)
        _ = eventually(timeout: 1) { false }
        shot("boost menu")
        remote.press(.select)
        XCTAssertTrue(boost.wait(until: NSPredicate(format: "value CONTAINS %@", "Saved. Voices are boosted at Medium"), timeout: 10), String(describing: boost.value))
        shot("boost saved")

        let size = app.buttons["settings-row-size"]
        for _ in 0..<4 where size.lookedAt?.hasFocus != true {
            remote.press(.down)
            _ = eventually(timeout: 1) { size.lookedAt?.hasFocus == true }
        }
        XCTAssertTrue(size.waitForFocus(timeout: 5))
        remote.press(.select)
        let normal = app.buttons["subtitle-size-normal"]
        XCTAssertTrue(normal.waitUntilExists(timeout: 10), "Subtitle size takes the screen")
        XCTAssertTrue(normal.waitForFocus(timeout: 5), "on the size saved")
        shot("size normal")
        remote.press(.right)
        remote.press(.right)
        XCTAssertTrue(app.buttons["subtitle-size-largest"].waitForFocus(timeout: 5))
        shot("size largest shown")
        remote.press(.select)
        let line = app.staticTexts["subtitle-size-line"]
        XCTAssertTrue(line.wait(until: NSPredicate(format: "label CONTAINS %@", "Saved. Subtitles are now Largest"), timeout: 10), line.label)
        shot("size largest saved")
        remote.press(.menu)
        XCTAssertTrue(normal.waitUntilGone(timeout: 5), "Menu goes back")
        XCTAssertTrue(size.wait(until: NSPredicate(format: "label CONTAINS %@", "Largest"), timeout: 5), size.label)
    }
}
