import UIKit
import XCTest

/// Run on a fresh task-owned TV simulator install with
/// TEST_RUNNER_WMM_LIVE_SERVER=http://127.0.0.1:28281. The runner verifies the
/// synthetic server before sending credentials and approves the app's own code.
/// Native AVKit panels, retuning and Stop are required. Remote pause has its own
/// explicit opt-in diagnostic; it never turns a runtime failure into a skip.
final class LiveTVSyntheticTests: XCTestCase {
    @MainActor private var remote: XCUIRemote { XCUIRemote.shared }

    override func setUp() async throws { continueAfterFailure = false }

    @MainActor
    func testSyntheticTVPairingVideoSwitchBackAndReopen() async throws {
        try await runLiveFlow(checkRemotePause: false)
    }

    /// An explicit diagnostic, currently failing on the owned simulator for
    /// both live and file playback. Enabling it preserves a real failure.
    @MainActor
    func testSyntheticTVRemotePauseAndResume() async throws {
        try requireRemotePauseCheck()
        try await runLiveFlow(checkRemotePause: true)
    }

    @MainActor private func runLiveFlow(checkRemotePause: Bool) async throws {
        let base = try fixtureURL("WMM_LIVE_SERVER", example: "http://127.0.0.1:28281")
        let fixture = TVSyntheticFixture(base: base)
        try await fixture.verifyAndLogin()
        let app = XCUIApplication()
        addTeardownBlock { @MainActor in
            app.terminate()
            // Revoke only the sign-in identified from this pairing, and stop only
            // the exact live IDs observed for it. Other fixture viewers stay up.
            try await fixture.cleanup()
        }
        let channels = try await fixture.channels()
        XCTAssertGreaterThanOrEqual(channels.count, 2)
        let first = try XCTUnwrap(channels.first)
        let second = channels[1]
        app.launch()
        try await pair(app, fixture: fixture)
        try await fixture.identifyAppSignIn()
        let liveTab = app.buttons["Live TV"].firstMatch
        XCTAssertTrue(liveTab.waitForExistence(timeout: 20), "Pairing refreshes the API 11 Live TV capability without a foreground transition.")
        XCTAssertTrue(focus(liveTab, moves: [.up, .up, .up] + Array(repeating: .right, count: 5) + Array(repeating: .left, count: 5)))
        remote.press(.select)
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 15))
        playGuideChannel(first.id, app: app)
        XCTAssertTrue(livePlayer(app).waitForExistence(timeout: 15))
        var current = try await fixture.waitForPlaying(channelID: first.id)
        try await assertMovingVideo(app, name: "TV synthetic channel 1")

        if checkRemotePause {
            accessibilityTree(app, "TV native focus before Play Pause")
            remote.press(.playPause)
            try await fixture.waitForPause(current.id, paused: true)
            let pausedFrame = try videoPixels(app)
            try await Task.sleep(for: .seconds(2))
            XCTAssertLessThanOrEqual(changedPixels(pausedFrame, try videoPixels(app)), 20,
                                     "Pause must hold the video frame, not briefly report paused before AVKit resumes.")
            shot(app, "TV synthetic paused tuner")
            remote.press(.playPause)
            try await fixture.waitForPause(current.id, paused: false)
            try await assertMovingVideo(app, name: "TV synthetic resumed channel")
            backToGuide(app)
            try await fixture.waitUntilReleased(current.id)
            try await fixture.assertNoOwnedSessions()
            return
        }

        var nativeResults: [String] = []
        XCTAssertTrue(openNativePanel("Channels", app: app), "The native Channels panel must be reachable.")
        do {
            shot(app, "TV native AVKit Channels")
            let next = app.buttons["live-channel-option-\(second.id)"]
            XCTAssertTrue(focus(next, moves: [.down] + Array(repeating: .right, count: channels.count)))
            remote.press(.select)
            let oldID = current.id
            current = try await fixture.waitForPlaying(channelID: second.id, excluding: [oldID])
            try await fixture.waitUntilReleased(oldID)
            nativeResults.append("Native Channels: selected the second channel; old tuner session released.")
            closeNativePanels(app)
        }
        try await assertMovingVideo(app, name: "TV synthetic channel 2")

        // Retuning can focus AVKit's contextual Ch buttons, which XCUI omits.
        // Reopen through the guide to verify Quality from a fresh native cover.
        backToGuide(app)
        try await fixture.waitUntilReleased(current.id)
        playGuideChannel(second.id, app: app)
        current = try await fixture.waitForPlaying(channelID: second.id, excluding: [current.id])
        try await assertMovingVideo(app, name: "TV synthetic channel 2 before Quality")

        XCTAssertTrue(openNativePanel("Quality", app: app), "The native Quality panel must be reachable.")
        do {
            shot(app, "TV native AVKit Quality")
            let cap = app.buttons["live-quality-option-480"]
            XCTAssertTrue(focus(cap, moves: [.down, .right, .right, .right, .left, .left]))
            remote.press(.select)
            let oldID = current.id
            current = try await fixture.waitForPlaying(channelID: second.id, quality: "480", excluding: [oldID])
            try await fixture.waitUntilReleased(oldID)
            nativeResults.append("Native Quality: selected 480p; replacement session uses the 480 cap and releases its predecessor.")
            closeNativePanels(app)
            try await assertMovingVideo(app, name: "TV synthetic 480p")
        }

        backToGuide(app)
        try await fixture.waitUntilReleased(current.id)
        playGuideChannel(first.id, app: app)
        current = try await fixture.waitForPlaying(channelID: first.id, excluding: [current.id])
        try await assertMovingVideo(app, name: "TV synthetic channel before native Stop")

        XCTAssertTrue(selectNativeAction("Stop live TV", app: app), "Native Stop must be reachable in AVKit transport.")
        XCTAssertTrue(livePlayer(app).waitForNonExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["live-tv-title"].waitForExistence(timeout: 15))
        nativeResults.append("Native Stop: selected in AVKit transport and returned to the guide.")
        try await fixture.waitUntilReleased(current.id)
        shot(app, "TV synthetic guide after tuner release")
        playGuideChannel(first.id, app: app)
        let reopened = try await fixture.waitForPlaying(channelID: first.id, excluding: [current.id])
        XCTAssertNotEqual(reopened.id, current.id, "Reopening acquires a fresh tuner session.")
        try await assertMovingVideo(app, name: "TV synthetic reopened channel")
        backToGuide(app)
        try await fixture.waitUntilReleased(reopened.id)
        try await fixture.assertNoOwnedSessions()
        let report = XCTAttachment(string: nativeResults.joined(separator: "\n"))
        report.name = "Native AVKit reachability — actual synthetic playback"
        report.lifetime = .keepAlways
        add(report)
    }

    /// A separate fresh install keeps the file-server sign-in distinct from the
    /// live fixture. Static Bloom is the fixture's HEVC/DTS file, not real media.
    @MainActor
    func testSyntheticTVHEVCDTSFileBrowsePlayAndBack() async throws {
        try await runFileFlow(checkRemotePause: false)
    }

    @MainActor
    func testSyntheticTVFileRemotePauseAndResume() async throws {
        try requireRemotePauseCheck()
        try await runFileFlow(checkRemotePause: true)
    }

    @MainActor private func runFileFlow(checkRemotePause: Bool) async throws {
        let base = try fixtureURL("WMM_TV_FILE_SERVER", example: "http://127.0.0.1:28282")
        let fixture = TVSyntheticFixture(base: base, fileFixture: true)
        try await fixture.verifyAndLogin()
        let app = XCUIApplication()
        addTeardownBlock { @MainActor in app.terminate(); try await fixture.cleanup() }
        let file = try await fixture.regressionFile()
        app.launch()
        try await pair(app, fixture: fixture)
        try await fixture.identifyAppSignIn()
        XCTAssertTrue(app.buttons["Movies"].waitForExistence(timeout: 20))
        let returnControl: XCUIElement
        if let movieID = file.movieId {
            // Keep the real sign-in and open the title discovered from the API.
            app.terminate()
            app.launchArguments = ["-open", "watchmymedia://movies/\(movieID)"]
            app.launch()
            XCTAssertTrue(app.staticTexts["Static Bloom"].waitForExistence(timeout: 20))
            let play = app.buttons.matching(NSPredicate(format: "label == 'Play from start' OR label == 'Play' OR label BEGINSWITH 'Resume'")).firstMatch
            returnControl = play
            XCTAssertTrue(play.waitForExistence(timeout: 10))
            shot(app, "TV synthetic HEVC movie detail")
            XCTAssertTrue(focus(play, moves: [.down, .down, .up, .left, .left, .right]))
            remote.press(.select)
        } else {
            let movies = app.buttons["Movies"].firstMatch
            XCTAssertTrue(focus(movies, moves: [.up, .up, .up, .right, .right, .right, .left, .left, .left]))
            remote.press(.select)
            let card = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Static Bloom")).firstMatch
            returnControl = card
            XCTAssertTrue(card.waitForExistence(timeout: 20))
            shot(app, "TV synthetic HEVC movie grid")
            XCTAssertTrue(focus(card, moves: [.down, .down] + Array(repeating: .right, count: 7) + Array(repeating: .left, count: 7)))
            remote.press(.select)
        }
        let initial = try await fixture.waitForFilePlaying()
        let advanced = try await fixture.waitForFileProgress(initial.id, after: initial.positionMs + 2_000)
        XCTAssertEqual(advanced.stream.mode, "audio", "HEVC stays copied while unsupported DTS is transcoded.")
        XCTAssertEqual(advanced.stream.stream.videoDecision, "copy")
        XCTAssertEqual(advanced.stream.stream.audioDecision, "transcode")
        XCTAssertFalse(livePlayer(app).exists, "The ordinary file opens the file player through the unified presentation.")
        // This fixture uses static SMPTE bars, so advancing file position plus
        // rendered saturated pixels is the evidence; it has no moving picture.
        let pixels = try videoPixels(app)
        var saturated = 0
        for index in stride(from: 0, to: pixels.count, by: 4) {
            let red = Int(pixels[index])
            let green = Int(pixels[index + 1])
            let blue = Int(pixels[index + 2])
            if max(red, max(green, blue)) - min(red, min(green, blue)) > 100 { saturated += 1 }
        }
        XCTAssertGreaterThan(saturated, 500, "The HEVC SMPTE color bars reach the real TV video layer.")
        shot(app, "TV synthetic HEVC DTS playback")
        let evidence = XCTAttachment(string: "Static Bloom file 6 advanced from \(initial.positionMs) to \(advanced.positionMs) ms; mode=\(advanced.stream.mode), video=\(advanced.stream.stream.videoDecision ?? "unknown"), audio=\(advanced.stream.stream.audioDecision ?? "unknown").")
        evidence.name = "TV ordinary-file playback evidence"
        evidence.lifetime = .keepAlways
        add(evidence)
        if checkRemotePause {
            accessibilityTree(app, "TV ordinary-file focus before Play Pause")
            remote.press(.playPause)
            let paused = try await fixture.waitForFilePause(initial.id)
            let pausedPixels = try videoPixels(app)
            try await Task.sleep(for: .seconds(2))
            XCTAssertLessThanOrEqual(changedPixels(pausedPixels, try videoPixels(app)), 20)
            shot(app, "TV ordinary-file paused with remote")
            remote.press(.playPause)
            _ = try await fixture.waitForFileProgress(initial.id, after: paused.positionMs + 2_000)
        }
        // Native AVKit may first hide its controls; stop as soon as the title
        // page or grid card can receive focus, never an extra Back into Home.
        for _ in 0..<4 {
            remote.press(.menu)
            if hittable(returnControl, timeout: 3) { break }
        }
        try await fixture.waitUntilFileReleased(initial.id)
        XCTAssertTrue(returnControl.isHittable, "Back restores the movie page or its library card.")
        shot(app, "TV synthetic movie after file playback cleanup")
    }

    private func requireRemotePauseCheck() throws {
        guard ProcessInfo.processInfo.environment["WMM_TV_REMOTE_PAUSE"] == "1" else {
            throw XCTSkip("Set TEST_RUNNER_WMM_TV_REMOTE_PAUSE=1 to investigate native remote pause. It currently fails for live and the unchanged file player on this simulator; physical Siri Remote verification remains outstanding.")
        }
    }

    private func fixtureURL(_ key: String, example: String) throws -> URL {
        guard let value = ProcessInfo.processInfo.environment[key], !value.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_\(key)=\(example) on a fresh owned TV simulator install.")
        }
        let base = try XCTUnwrap(URL(string: value.contains("://") ? value : "http://\(value)"))
        guard base.scheme == "http", ["127.0.0.1", "localhost"].contains(base.host ?? ""),
              base.port != nil, base.user == nil, base.password == nil, base.query == nil,
              base.fragment == nil, ["", "/"].contains(base.path) else {
            throw NSError(domain: "TVSyntheticFixture", code: 1, userInfo: [NSLocalizedDescriptionKey: "Use an explicit disposable loopback HTTP port; no discovered or real servers."])
        }
        return base
    }

    @MainActor private func pair(_ app: XCUIApplication, fixture: TVSyntheticFixture) async throws {
        let address = app.textFields["Server address"]
        XCTAssertTrue(address.waitForExistence(timeout: 15), "Use a fresh owned install; never select a remembered or discovered server.")
        XCTAssertTrue(focus(address, moves: [.right, .up, .down, .up, .right]))
        remote.press(.select)
        for character in fixture.address { address.typeText(String(character)) }
        remote.press(.menu)
        XCTAssertEqual(address.value as? String, fixture.address, "Verify every typed character before connecting to the owned loopback server.")
        let connect = app.buttons["Connect"]
        XCTAssertTrue(focus(connect, moves: [.down, .down, .down]))
        remote.press(.select)
        let codeElement = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Code ")).firstMatch
        XCTAssertTrue(codeElement.waitForExistence(timeout: 20))
        let code = String(codeElement.label.dropFirst(5)).filter { $0.isLetter || $0.isNumber }
        XCTAssertEqual(code.count, 6)
        try await fixture.approve(code)
    }

    @MainActor private func playGuideChannel(_ id: String, app: XCUIApplication) {
        let channel = app.buttons["live-channel-\(id)"]
        XCTAssertTrue(channel.waitForExistence(timeout: 15))
        XCTAssertTrue(focus(channel, moves: [.down, .down] + Array(repeating: .left, count: 5) + Array(repeating: .right, count: 5)))
        remote.press(.select)
    }

    @MainActor private func focus(_ element: XCUIElement, moves: [XCUIRemote.Button]) -> Bool {
        if element.exists && element.hasFocus { return true }
        for move in moves {
            remote.press(move)
            if element.exists && element.hasFocus { return true }
        }
        return false
    }

    @MainActor private func hittable(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    @MainActor private func backToGuide(_ app: XCUIApplication) {
        let guide = app.staticTexts["live-tv-title"]
        let player = livePlayer(app)
        for _ in 0..<4 where player.exists {
            remote.press(.menu)
            _ = player.waitForNonExistence(timeout: 2)
        }
        XCTAssertFalse(player.exists, "The live cover must dismiss; a covered guide can remain in the accessibility tree.")
        XCTAssertTrue(guide.waitForExistence(timeout: 10), "Back closes native panels before releasing and leaving the player.")
    }

    @MainActor private func livePlayer(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["live-player-screen"]
    }

    @MainActor private func closeNativePanels(_ app: XCUIApplication) {
        for _ in 0..<2 {
            let channels = app.staticTexts["live-panel-channels"]
            let quality = app.staticTexts["live-panel-quality"]
            guard channels.exists || quality.exists else { break }
            remote.press(.menu)
            _ = channels.waitForNonExistence(timeout: 2)
            _ = quality.waitForNonExistence(timeout: 2)
        }
        XCTAssertTrue(livePlayer(app).exists, "Closing a native info panel keeps the live player open.")
    }

    @MainActor private func openNativePanel(_ name: String, app: XCUIApplication) -> Bool {
        let heading = app.staticTexts["live-panel-\(name.lowercased())"]
        if heading.exists { return true }
        let other = app.staticTexts["live-panel-\(name == "Channels" ? "quality" : "channels")"]
        // AVKit's native tab labels render but are omitted from its accessibility
        // tree. Down opens the info area; Up from its content returns to the tab
        // row, where Channels is left of Quality. Assert our actual panel heading.
        for _ in 0..<3 {
            remote.press(.down)
            if heading.waitForExistence(timeout: 1) { return true }
            if other.exists {
                remote.press(name == "Channels" ? .left : .right)
                if heading.waitForExistence(timeout: 1) { return true }
                remote.press(.up)
                remote.press(name == "Channels" ? .left : .right)
                remote.press(.down)
                if heading.waitForExistence(timeout: 2) { return true }
            }
        }
        let visible = other.exists ? (name == "Channels" ? "Quality" : "Channels") : "transport"
        shot(app, "TV native \(visible) while navigating to \(name)")
        accessibilityTree(app, "TV native \(name) accessibility")
        return false
    }

    @MainActor private func selectNativeAction(_ label: String, app: XCUIApplication) -> Bool {
        closeNativePanels(app)
        // AVKit can use a UIAction's title instead of its accessibility label.
        let labels = label == "Stop live TV" ? [label, "Stop"] : [label]
        let query = label == "Stop live TV" ? app.cells : app.descendants(matching: .any)
        let action = query.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
        let moves: [XCUIRemote.Button] = [.select, .up, .right, .right, .right, .right,
                                         .down, .up, .up, .left, .left, .left, .left, .right, .right]
        guard focus(action, moves: moves) else {
            shot(app, "TV native \(label) access attempt")
            accessibilityTree(app, "TV native \(label) accessibility")
            return false
        }
        shot(app, "TV native \(label) focused")
        remote.press(.select)
        return true
    }

    /// Samples the middle of AVKit's video, away from channel text, spinners and
    /// bottom transport. Two colorful, changing samples establish rendered video
    /// rather than just a ready server session or static player background.
    @MainActor private func assertMovingVideo(_ app: XCUIApplication, name: String) async throws {
        XCTAssertTrue(livePlayer(app).waitForExistence(timeout: 15))
        var previous: [UInt8]?
        for _ in 0..<20 {
            let pixels = try videoPixels(app)
            var colors: Set<Int> = []
            for index in stride(from: 0, to: pixels.count, by: 4) {
                let red = Int(pixels[index]) / 16
                let green = Int(pixels[index + 1]) / 16
                let blue = Int(pixels[index + 2]) / 16
                colors.insert(red * 256 + green * 16 + blue)
            }
            if colors.count > 16, let previous {
                let changed = changedPixels(previous, pixels)
                if changed > 40 {
                    shot(app, "\(name) moving video")
                    let evidence = XCTAttachment(string: "Center video crop: \(colors.count) quantized colors; \(changed)/2048 pixels changed between frames two seconds apart.")
                    evidence.name = "\(name) frame evidence"
                    evidence.lifetime = .keepAlways
                    add(evidence)
                    return
                }
            }
            if previous == nil && colors.count > 16 { shot(app, "\(name) first video frame") }
            previous = colors.count > 16 ? pixels : nil
            try await Task.sleep(for: .seconds(2))
        }
        shot(app, "\(name) missing motion")
        XCTFail("The synthetic MPEG-2/AC-3 source must render moving, colorful video through the tvOS live HLS pipeline.")
    }

    private func changedPixels(_ before: [UInt8], _ after: [UInt8]) -> Int {
        var changed = 0
        for index in stride(from: 0, to: before.count, by: 4) {
            let red = abs(Int(after[index]) - Int(before[index]))
            let green = abs(Int(after[index + 1]) - Int(before[index + 1]))
            let blue = abs(Int(after[index + 2]) - Int(before[index + 2]))
            if red + green + blue > 40 { changed += 1 }
        }
        return changed
    }

    @MainActor private func videoPixels(_ app: XCUIApplication) throws -> [UInt8] {
        let image = try XCTUnwrap(app.screenshot().image.cgImage)
        let rect = CGRect(x: CGFloat(image.width) * 0.3, y: CGFloat(image.height) * 0.48,
                          width: CGFloat(image.width) * 0.4, height: CGFloat(image.height) * 0.2)
        let crop = try XCTUnwrap(image.cropping(to: rect))
        var pixels = [UInt8](repeating: 0, count: 64 * 32 * 4)
        try pixels.withUnsafeMutableBytes { bytes in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: 64, height: 32, bitsPerComponent: 8,
                                                 bytesPerRow: 64 * 4, space: CGColorSpaceCreateDeviceRGB(),
                                                 bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(crop, in: CGRect(x: 0, y: 0, width: 64, height: 32))
        }
        return pixels
    }

    @MainActor private func accessibilityTree(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(string: app.debugDescription)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    @MainActor private func shot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

private actor TVSyntheticFixture {
    struct RegressionFile: Decodable, Sendable {
        struct Parsed: Decodable, Sendable { let title: String }
        let id: Int
        let movieId: Int?
        let parsed: Parsed
        let videoCodec: String
        let audioCodec: String
    }
    struct FilePlay: Decodable, Sendable {
        struct Device: Decodable, Sendable { let name: String; let platform: String? }
        struct Stream: Decodable, Sendable {
            struct Media: Decodable, Sendable { let videoDecision: String?; let audioDecision: String? }
            let mode: String
            let stream: Media
        }
        let id: String
        let fileId: Int
        let positionMs: Int
        let state: String
        let device: Device
        let stream: Stream
    }
    struct Channel: Decodable, Sendable { let id: String; let available: Bool }
    struct Session: Decodable, Sendable {
        let id: String
        let channel: Channel
        let state: String
        let clientName: String
        let quality: String
        let paused: Bool
        let bufferBytes: Int
    }
    private struct Items<T: Decodable>: Decodable { let items: [T] }
    private struct FileActivity: Decodable { let plays: [FilePlay] }
    private struct Server: Decodable { let name: String; let version: String; let apiLevel: Int }
    private struct Login: Decodable { let token: String }
    private struct Pairing: Decodable { let name: String; let platform: String }
    private struct SignIn: Decodable { let id: String; let name: String; let platform: String? }
    let address: String
    private let base: URL
    private let fileFixture: Bool
    private let session = URLSession(configuration: .ephemeral)
    private var token = ""
    private var clientName = ""
    private var priorSignIns: Set<String> = []
    private var baseline: Set<String> = []
    private var ownedIDs: Set<String> = []
    private var fileBaseline: Set<String> = []
    private var ownedFileIDs: Set<String> = []
    private var appSignInID: String?

    init(base: URL, fileFixture: Bool = false) {
        self.base = base.appendingPathComponent("api")
        address = base.absoluteString
        self.fileFixture = fileFixture
    }

    func verifyAndLogin() async throws {
        let server = try JSONDecoder().decode(Server.self, from: await request("server"))
        if fileFixture {
            guard server.name == "Apple Live File Regression" else {
                throw XCTSkip("The owned file server must be named Apple Live File Regression before any sign-in.")
            }
        } else {
            guard server.version == "live-tv-fixture", server.apiLevel >= 11 else {
                throw XCTSkip("The owned port must serve the synthetic live-tv-fixture build at API level 11 or newer.")
            }
        }
        let body = try JSONSerialization.data(withJSONObject: [
            "name": fileFixture ? "viewer" : "admin", "password": fileFixture ? "fixture-viewer-1" : "fixture-admin-1",
            "client": ["deviceId": UUID().uuidString, "name": "TV synthetic test observer", "platform": "tvos"]
        ])
        token = try JSONDecoder().decode(Login.self, from: await request("auth/login", method: "POST", body: body)).token
    }
    func channels() async throws -> [Channel] {
        try JSONDecoder().decode(Items<Channel>.self, from: await request("live-tv/channels")).items.filter(\.available)
    }
    func regressionFile() async throws -> RegressionFile {
        let file = try JSONDecoder().decode(RegressionFile.self, from: await request("files/6"))
        guard file.id == 6, file.parsed.title == "Static Bloom", ["hevc", "h265"].contains(file.videoCodec), file.audioCodec == "dts" else {
            throw failure("File 6 must be the fictional Static Bloom HEVC/DTS fixture.")
        }
        return file
    }
    func approve(_ code: String) async throws {
        let pairing = try JSONDecoder().decode(Pairing.self, from: await request("auth/pair/\(code)"))
        guard pairing.platform == "tvos", !pairing.name.isEmpty else { throw failure("The displayed pairing must belong to the TV app.") }
        clientName = pairing.name
        if fileFixture {
            let active = try await fileActivity()
            guard !active.contains(where: { $0.device.name == clientName }) else {
                throw XCTSkip("Another TV with the same client name is playing; leave it untouched and run on an idle owned fixture.")
            }
            fileBaseline = Set(active.map(\.id))
        } else {
            let active = try await activity()
            guard !active.contains(where: { $0.clientName == clientName }) else {
                throw XCTSkip("Another TV with the same client name is playing; leave it untouched and run on an idle owned fixture.")
            }
            baseline = Set(active.map(\.id))
        }
        priorSignIns = Set(try await signIns().map(\.id))
        _ = try await request("auth/pair/\(code)/approve", method: "POST")
    }
    func identifyAppSignIn() async throws {
        for _ in 0..<20 {
            let matches = try await signIns().filter { !priorSignIns.contains($0.id) && $0.platform == "tvos" && $0.name == clientName }
            guard matches.count <= 1 else { throw failure("Cannot uniquely identify this TV pairing; no sign-ins will be revoked.") }
            if let match = matches.first { appSignInID = match.id; return }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("The approved TV did not claim its sign-in.")
    }
    func waitForPlaying(channelID: String, quality: String? = nil, excluding: Set<String> = []) async throws -> Session {
        for _ in 0..<45 {
            let owned = try await ownedActivity()
            if let current = owned.first(where: { $0.channel.id == channelID && !excluding.contains($0.id) && $0.state == "playing" && $0.bufferBytes > 0 && (quality == nil || $0.quality == quality) }) {
                guard owned.count == 1 else { throw failure("The TV owns more than one live tuner session after tuning.") }
                return current
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("No new playing TV session with HLS media reached the requested channel and quality.")
    }
    func waitForPause(_ id: String, paused: Bool) async throws {
        var consecutive = 0
        for _ in 0..<25 {
            if try await ownedActivity().contains(where: { $0.id == id && $0.paused == paused }) {
                consecutive += 1
                if consecutive >= 4 { return }
            } else { consecutive = 0 }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("The TV's pause state did not remain stable in the real live-session heartbeat for three seconds.")
    }
    func waitUntilReleased(_ id: String) async throws {
        for _ in 0..<20 {
            if try await !activity().contains(where: { $0.id == id }) { return }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("The TV left its previous tuner active after Stop, Back or replacement.")
    }
    func assertNoOwnedSessions() async throws {
        guard try await ownedActivity().isEmpty else { throw failure("Closing the TV player left a live tuner active.") }
    }
    func waitForFilePlaying() async throws -> FilePlay {
        for _ in 0..<45 {
            if let play = try await ownedFileActivity().first(where: { $0.fileId == 6 && $0.state == "playing" }) { return play }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("The TV did not begin the ordinary HEVC/DTS file.")
    }
    func waitForFileProgress(_ id: String, after position: Int) async throws -> FilePlay {
        for _ in 0..<30 {
            if let play = try await ownedFileActivity().first(where: { $0.id == id && $0.positionMs > position }) { return play }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("The TV file's real playback position did not advance.")
    }
    func waitForFilePause(_ id: String) async throws -> FilePlay {
        var pausedPosition: Int?
        var consecutive = 0
        for _ in 0..<25 {
            if let play = try await ownedFileActivity().first(where: { $0.id == id && $0.state == "paused" }) {
                if let position = pausedPosition, abs(play.positionMs - position) <= 250 {
                    consecutive += 1
                    if consecutive >= 4 { return play }
                } else {
                    pausedPosition = play.positionMs
                    consecutive = 1
                }
            } else {
                pausedPosition = nil
                consecutive = 0
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("The ordinary TV file did not remain paused with a stable position for three seconds after the remote press.")
    }
    func fileIsReleased(_ id: String) async throws -> Bool {
        try await !fileActivity().contains(where: { $0.id == id })
    }
    func waitUntilFileReleased(_ id: String) async throws {
        for _ in 0..<20 {
            if try await fileIsReleased(id) { return }
            try await Task.sleep(for: .seconds(1))
        }
        throw failure("Back left this TV's file playback active.")
    }
    func cleanup() async throws {
        guard !token.isEmpty else { session.invalidateAndCancel(); return }
        var cleanupFailed = false
        // On an assertion failure, finish only the file plays already identified
        // as this TV's. Do not alter any baseline viewer or manufacture progress.
        if fileFixture {
            do {
                for play in try await fileActivity() where ownedFileIDs.contains(play.id) {
                    let body = try JSONSerialization.data(withJSONObject: ["playId": play.id, "positionMs": play.positionMs, "state": "closed"])
                    _ = try await request("files/\(play.fileId)/progress", method: "PUT", body: body)
                }
            } catch { cleanupFailed = true }
        }
        // Revocation covers a successful Start whose response was lost before
        // the observer learned its ID. This is only the exact paired TV sign-in.
        if let appSignInID {
            do { _ = try await request("auth/sessions/\(appSignInID)", method: "DELETE") } catch { cleanupFailed = true }
        }
        for id in ownedIDs {
            do { _ = try await request("live-tv/activity/\(id)", method: "DELETE") } catch { cleanupFailed = true }
        }
        do { _ = try await request("auth/logout", method: "POST") } catch { cleanupFailed = true }
        session.invalidateAndCancel()
        if cleanupFailed { throw failure("Could not finish cleanup of this test's exact fixture sign-in/session IDs.") }
    }
    private func ownedActivity() async throws -> [Session] {
        let owned = try await activity().filter { !baseline.contains($0.id) && $0.clientName == clientName }
        ownedIDs.formUnion(owned.map(\.id))
        return owned
    }
    private func activity() async throws -> [Session] {
        try JSONDecoder().decode(Items<Session>.self, from: await request("live-tv/activity")).items
    }
    private func ownedFileActivity() async throws -> [FilePlay] {
        let owned = try await fileActivity().filter { !fileBaseline.contains($0.id) && $0.device.name == clientName && $0.device.platform == "tvos" }
        ownedFileIDs.formUnion(owned.map(\.id))
        return owned
    }
    private func fileActivity() async throws -> [FilePlay] {
        try JSONDecoder().decode(FileActivity.self, from: await request("activity")).plays
    }
    private func signIns() async throws -> [SignIn] {
        try JSONDecoder().decode(Items<SignIn>.self, from: await request("auth/sessions")).items
    }
    private func request(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        var request = URLRequest(url: base.appendingPathComponent(path))
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw failure("Synthetic fixture API returned HTTP \(status).") }
        return data
    }
    private func failure(_ message: String) -> NSError {
        NSError(domain: "TVSyntheticFixture", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
