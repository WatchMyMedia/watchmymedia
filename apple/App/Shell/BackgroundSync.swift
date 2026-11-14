import BackgroundTasks
import Foundation
import WMMModel

/// Sends what was watched offline (watched marks and positions, which also
/// make Continue watching right on the person's other devices) in a
/// background refresh, so it reaches the server before the app is next
/// opened (PLAN-APPLE-REVIEW.md §6.3). It is ``DownloadRepository/sync()``,
/// the same sync the app runs at launch and on each return.
///
/// The system allows only identifiers the Info.plist lists, and registering
/// another stops the app, so this does nothing until `project.yml` gives the
/// app `BGTaskSchedulerPermittedIdentifiers: [media.watchmy.client.sync]`
/// and `UIBackgroundModes: [fetch]`.
@MainActor
enum BackgroundSync {
    static let identifier = "media.watchmy.client.sync"
    /// The soonest the system is asked to run it after the app leaves.
    static let interval: TimeInterval = 60 * 60
    private static var services: AppServices?

    private static var permitted: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String])?.contains(identifier) == true
    }

    /// At launch, before it finishes.
    static func register(_ services: AppServices) {
        self.services = services
        guard permitted else { return }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            MainActor.assumeIsolated { run(task) }
        }
    }

    /// As the app goes to the background.
    static func schedule() {
        guard permitted else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func run(_ task: BGTask) {
        schedule()
        guard let services else { return task.setTaskCompleted(success: false) }
        let work = Task { @MainActor in
            await services.downloads.sync()
            task.setTaskCompleted(success: !Task.isCancelled)
        }
        task.expirationHandler = { work.cancel() }
    }
}
