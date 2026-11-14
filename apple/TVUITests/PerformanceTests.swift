import XCTest

/// A signed-in launch of the Apple TV app on the fakes, five runs
/// (PLAN-APPLE-REVIEW.md §4.3.1): `make apple-perf`. Skipped unless
/// `WMM_PERF=1` (the make target sets it), so `make apple-tv-test-sim` and CI
/// don't run it. The fakes are debug-only, so this measures a Debug build:
/// compare numbers with each other on the same Mac, not with a release.
final class PerformanceTests: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["WMM_PERF"] == "1", "performance tests run with make apple-perf")
        continueAfterFailure = false
    }

    @MainActor
    func testSignedInLaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-fakes"]
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        // XCTApplicationLaunchMetric reports nothing on the tvOS simulator (Xcode 27.2),
        // so the clock times launch() too, which returns once the app is idle.
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true), XCTClockMetric()], options: options) {
            app.launch()
        }
    }
}
