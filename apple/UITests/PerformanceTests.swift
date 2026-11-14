import XCTest

/// Launch and scrolling on the fakes, five runs each (PLAN-APPLE-REVIEW.md
/// §4.3.1): `make apple-perf`. Skipped unless `WMM_PERF=1` (the make target
/// sets it), so `make apple-e2e` doesn't run them. The fakes are debug-only,
/// so these measure a Debug build: compare numbers with each other, before
/// and after a change on the same Mac, not with a release on a device.
final class PerformanceTests: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["WMM_PERF"] == "1", "performance tests run with make apple-perf")
        continueAfterFailure = false
    }

    private var options: XCTMeasureOptions {
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        return options
    }

    /// A signed-in launch, to the app's first frame and until it takes input.
    @MainActor
    func testSignedInLaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"]
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: options) {
            app.launch()
        }
    }

    /// Home's rows, flung up and back. The Home row of cards is the scroll.
    @MainActor
    func testScrollingHome() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Home"].waitForExistence(timeout: 30), "Home shows on the fakes")
        XCTAssertTrue(element(app, containing: "Recently added").waitForExistence(timeout: 30), "Home has its rows")
        fling(app)
    }

    /// The Movies grid of a large library (about 1,200 titles,
    /// `-fakesLargeLibrary`), flung up and back: pages load as it scrolls.
    @MainActor
    func testScrollingMovies() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes", "-fakesLargeLibrary", "-tab", "movies"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Movies"].waitForExistence(timeout: 30), "Movies shows on the fakes")
        fling(app)
    }

    /// Measures a fast swipe up and its deceleration, then puts the scroll back.
    @MainActor
    private func fling(_ app: XCUIApplication) {
        var metrics: [any XCTMetric] = [XCTOSSignpostMetric.scrollingAndDecelerationMetric]
        if #available(iOS 26.0, *) { metrics.append(XCTHitchMetric(application: app)) }
        let options = self.options
        options.invocationOptions = [.manuallyStop]
        let window = app.windows.firstMatch
        measure(metrics: metrics, options: options) {
            window.swipeUp(velocity: .fast)
            stopMeasuring()
            window.swipeDown(velocity: .fast)
            window.swipeDown(velocity: .fast)
        }
    }

    @MainActor
    private func element(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }
}
