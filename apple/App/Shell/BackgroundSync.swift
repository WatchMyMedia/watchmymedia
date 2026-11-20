import BackgroundTasks
import Foundation
import WMMModel

/// Sends what was watched offline (watched marks and positions, which also
/// make Continue watching right on the person's other devices) in a
/// background refresh, so it reaches the server before the app is next
/// opened. It is ``DownloadRepository/sync()``,
/// the same sync the app runs at launch and on each return. Then it checks
/// for new episodes of the shows the person follows and notifies:
/// the same check the app runs when it opens,
/// which does nothing unless the person follows a show, this device's switch
/// is on and iOS allows the notifications. Nothing new is scheduled for it.
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
    private static var newEpisodes: NewEpisodeNotifier?

    private static var permitted: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String])?.contains(identifier) == true
    }

    /// At launch, before it finishes.
    static func register(_ services: AppServices, newEpisodes: NewEpisodeNotifier) {
        self.services = services
        self.newEpisodes = newEpisodes
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
        let completion = BackgroundCompletion(task)
        let work = Task { @MainActor in
            await services.downloads.sync()
            if !Task.isCancelled { await newEpisodes?.check() }
            completion.finish(success: !Task.isCancelled)
        }
        // Time's up: the work is cancelled (the new-episode check stops at
        // once, keeping its cursor), and the task ends now rather than when a
        // server that doesn't answer gives up, so iOS keeps granting refreshes.
        task.expirationHandler = { @Sendable in
            work.cancel()
            completion.finish(success: false)
        }
    }
}

/// Ends a background task once, from its work or from its expiry, whichever
/// comes first; the expiry handler may run on any queue.
final class BackgroundCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    private let task: BGTask

    init(_ task: BGTask) { self.task = task }

    func finish(success: Bool) {
        lock.lock()
        defer { lock.unlock() }
        guard !done else { return }
        done = true
        task.setTaskCompleted(success: success)
    }
}
