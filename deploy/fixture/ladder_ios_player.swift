import AVFoundation
import UIKit

// A disposable, visible AVPlayer surface for the fictional A0/A1 ladders only.
// The runner installs it on a simulator that it creates and deletes.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        true
    }

    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Ladder fixture", sessionRole: session.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var timer: Timer?
    private var commandID = ""
    private var seekFinished = false
    private var seekElapsed = 0.0
    private let videoOutput = AVPlayerItemVideoOutput(pixelBufferAttributes: nil)
    private var sampledFrames = 0
    private var lastVideoTime = -1.0
    private let liveBuffered = CommandLine.arguments.contains("--live-buffered")
    private var liveStartRequested = false
    private var liveStartFinished = false

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        guard let url = URL(string: CommandLine.arguments.last ?? ""), url.scheme == "http" else { return }
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = liveBuffered ? 30 : 8
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = liveBuffered
        item.add(videoOutput)
        let player = AVPlayer(playerItem: item)
        let view = UIViewController()
        view.view.backgroundColor = .black
        let layer = AVPlayerLayer(player: player)
        layer.frame = windowScene.screen.bounds
        layer.videoGravity = .resizeAspect
        view.view.layer.addSublayer(layer)
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = view
        window.makeKeyAndVisible()
        self.window = window
        self.item = item
        self.player = player
        if !liveBuffered { player.play() }
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.sample() }
        sample()
    }

    private func sample() {
        guard let player, let item else { return }
        if liveBuffered, !liveStartRequested, item.status == .readyToPlay,
           let range = item.seekableTimeRanges.last?.timeRangeValue,
           CMTimeGetSeconds(range.duration) >= 36 {
            liveStartRequested = true
            let target = CMTimeSubtract(CMTimeRangeGetEnd(range), CMTime(seconds: 30, preferredTimescale: 600))
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
                self?.liveStartFinished = finished
                if finished { player.play() }
            }
        }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        if let data = try? Data(contentsOf: docs.appendingPathComponent("ladder-command.json")),
           let command = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let id = command["id"] as? String, id != commandID,
           let target = command["seekSeconds"] as? Double, target >= 0, target <= 240 {
            commandID = id
            seekFinished = false
            seekElapsed = 0
            let started = ProcessInfo.processInfo.systemUptime
            player.seek(to: CMTime(seconds: target, preferredTimescale: 600),
                        toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
                guard let self, self.commandID == id else { return }
                self.seekFinished = finished
                self.seekElapsed = ProcessInfo.processInfo.systemUptime - started
                player.play()
                self.sample()
            }
        }
        let videoTime = videoOutput.itemTime(forHostTime: CACurrentMediaTime())
        if videoOutput.hasNewPixelBuffer(forItemTime: videoTime),
           videoOutput.copyPixelBuffer(forItemTime: videoTime, itemTimeForDisplay: nil) != nil {
            sampledFrames += 1
            lastVideoTime = CMTimeGetSeconds(videoTime)
        }
        let events = item.accessLog()?.events ?? []
        let position = CMTimeGetSeconds(player.currentTime())
        let liveEnd = item.seekableTimeRanges.last.map { CMTimeGetSeconds(CMTimeRangeGetEnd($0.timeRangeValue)) } ?? position
        let ahead = item.loadedTimeRanges.map { $0.timeRangeValue }.filter {
            CMTimeRangeContainsTime($0, time: player.currentTime())
        }.map { CMTimeGetSeconds(CMTimeRangeGetEnd($0)) - position }.max() ?? 0
        let report: [String: Any] = [
            "status": item.status == .failed ? "failed" : item.status == .readyToPlay ? "ready" : "unknown",
            "positionSeconds": position.isFinite ? position : 0,
            "bufferedAheadSeconds": ahead.isFinite ? max(0, ahead) : 0,
            "liveDelaySeconds": liveEnd.isFinite && position.isFinite ? max(0, liveEnd - position) : 0,
            "liveStartFinished": liveStartFinished,
            "preferredBufferSeconds": item.preferredForwardBufferDuration,
            "indicatedBitrates": events.map { $0.indicatedBitrate },
            "stalls": events.reduce(0) { $0 + $1.numberOfStalls },
            "error": item.error.map { "\(($0 as NSError).domain) \(($0 as NSError).code)" } ?? "",
            "sampledVideoFrames": sampledFrames,
            "lastVideoSeconds": lastVideoTime.isFinite ? lastVideoTime : -1,
            "presentationWidth": item.presentationSize.width,
            "presentationHeight": item.presentationSize.height,
            "commandID": commandID, "seekFinished": seekFinished, "seekElapsedSeconds": seekElapsed
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: report) else { return }
        try? FileManager.default.createDirectory(at: docs, withIntermediateDirectories: true)
        try? data.write(to: docs.appendingPathComponent("ladder-report.json"), options: .atomic)
    }
}
