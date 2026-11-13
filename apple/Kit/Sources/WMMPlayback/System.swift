import AVFoundation
import Foundation
#if canImport(MediaPlayer)
import MediaPlayer
#endif
#if canImport(UIKit)
import UIKit
#endif

/// What the lock screen, Control Center and the app switcher show.
public struct NowPlayingInfo: Hashable, Sendable {
    public var title: String
    public var subtitle: String?
    public var durationMs: Int
    public var positionMs: Int
    public var playing: Bool
    /// The picture beside the title; nil until it has loaded, or when there is none.
    public var artwork: NowPlayingArtwork?
    /// A live stream (Live TV): no length or position, and the system shows it as live.
    public var live: Bool

    public init(title: String, subtitle: String?, durationMs: Int, positionMs: Int, playing: Bool, artwork: NowPlayingArtwork? = nil, live: Bool = false) {
        self.title = title
        self.subtitle = subtitle
        self.durationMs = durationMs
        self.positionMs = positionMs
        self.playing = playing
        self.artwork = artwork
        self.live = live
    }
}

/// An image's bytes for Now Playing, told apart by `id` (the image's size and
/// path), so comparing two never compares the bytes.
public struct NowPlayingArtwork: Hashable, Sendable {
    public var id: String
    public var data: Data

    public init(id: String, data: Data) {
        self.id = id
        self.data = data
    }

    public static func == (a: Self, b: Self) -> Bool { a.id == b.id }

    public func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// What the system asks of the player: remote commands (lock screen,
/// headphones, an Apple TV remote over AirPlay) and audio session events.
public struct SystemCommands: Sendable {
    public var play: @MainActor @Sendable () -> Void
    public var pause: @MainActor @Sendable () -> Void
    public var togglePlayPause: @MainActor @Sendable () -> Void
    public var seekBy: @MainActor @Sendable (_ deltaMs: Int) -> Void
    public var seekTo: @MainActor @Sendable (_ positionMs: Int) -> Void
    /// An audio interruption (a call) began, or ended, saying whether to resume.
    public var interruption: @MainActor @Sendable (_ began: Bool, _ shouldResume: Bool) -> Void
    /// The audio route went away (headphones unplugged).
    public var routeLost: @MainActor @Sendable () -> Void
    /// The app came back to the foreground.
    public var foreground: @MainActor @Sendable () -> Void
    /// The play can move by seeks: the skip and scrub commands are offered.
    /// Live TV's can't; it pauses within its buffer and goes back to live.
    public var seeks: Bool

    public init(
        play: @escaping @MainActor @Sendable () -> Void,
        pause: @escaping @MainActor @Sendable () -> Void,
        togglePlayPause: @escaping @MainActor @Sendable () -> Void,
        seekBy: @escaping @MainActor @Sendable (Int) -> Void,
        seekTo: @escaping @MainActor @Sendable (Int) -> Void,
        interruption: @escaping @MainActor @Sendable (Bool, Bool) -> Void,
        routeLost: @escaping @MainActor @Sendable () -> Void,
        foreground: @escaping @MainActor @Sendable () -> Void,
        seeks: Bool = true
    ) {
        self.play = play
        self.pause = pause
        self.togglePlayPause = togglePlayPause
        self.seekBy = seekBy
        self.seekTo = seekTo
        self.interruption = interruption
        self.routeLost = routeLost
        self.foreground = foreground
        self.seeks = seeks
    }
}

/// The system around the player: Now Playing, remote commands, the audio
/// session and the app's lifecycle (the Android MediaSessionHost and
/// AppVisibility). One for the app; ``AppleSystem`` on devices, a fake in tests.
@MainActor
public protocol PlaybackSystem: AnyObject {
    /// Takes Now Playing, the remote commands and the audio session for one
    /// controller, until the session closes or another opens.
    func open(_ commands: SystemCommands) -> any SystemSession

    /// Asks the OS for time to send the last reports as the app goes to the
    /// background; call the returned closure when they are out.
    func holdForDelivery() -> @MainActor () -> Void
}

@MainActor
public protocol SystemSession: AnyObject {
    func update(_ info: NowPlayingInfo)

    func close()
}

/// ``PlaybackSystem`` on Apple devices: `MPNowPlayingInfoCenter`,
/// `MPRemoteCommandCenter`, `AVAudioSession` (playback, movie mode;
/// interruptions and lost routes pause) and the app's foreground return.
@MainActor
public final class AppleSystem: PlaybackSystem {
    /// One for the process: the file and Live TV players each have an
    /// AppleSystem, and a session one opens closes the other's.
    private static weak var current: AppleSystemSession?

    public init() {}

    public func open(_ commands: SystemCommands) -> any SystemSession {
        Self.current?.close()
        let session = AppleSystemSession(commands: commands)
        Self.current = session
        return session
    }

    public func holdForDelivery() -> @MainActor () -> Void {
        #if os(iOS) || os(tvOS)
        var id = UIBackgroundTaskIdentifier.invalid
        let end: @MainActor () -> Void = {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
        id = UIApplication.shared.beginBackgroundTask(withName: "Playback report") {
            MainActor.assumeIsolated { end() }
        }
        return end
        #else
        return {}
        #endif
    }
}

@MainActor
final class AppleSystemSession: SystemSession {
    private let commands: SystemCommands
    private var observers: [NSObjectProtocol] = []
    private var targets: [(AnyObject, Any)] = []
    private var last: NowPlayingInfo?
    private var lastAt = ContinuousClock.now
    private var closed = false
    #if canImport(MediaPlayer) && canImport(UIKit)
    /// The artwork last decoded, so the image is decoded once, not at every update.
    private var artwork: (id: String, item: MPMediaItemArtwork)?
    #endif

    init(commands: SystemCommands) {
        self.commands = commands
        activateAudio()
        addRemoteCommands()
    }

    func update(_ info: NowPlayingInfo) {
        guard !closed, needsUpdate(info) else { return }
        last = info
        lastAt = .now
        #if canImport(MediaPlayer)
        var values: [String: Any] = [
            MPMediaItemPropertyTitle: info.title,
            MPNowPlayingInfoPropertyPlaybackRate: info.playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if info.live {
            values[MPNowPlayingInfoPropertyIsLiveStream] = true
        } else {
            values[MPMediaItemPropertyPlaybackDuration] = Double(info.durationMs) / 1000
            values[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(info.positionMs) / 1000
        }
        if let subtitle = info.subtitle { values[MPMediaItemPropertyArtist] = subtitle }
        #if canImport(UIKit)
        if let item = artworkItem(info.artwork) { values[MPMediaItemPropertyArtwork] = item }
        #endif
        MPNowPlayingInfoCenter.default().nowPlayingInfo = values
        #endif
    }

    #if canImport(MediaPlayer) && canImport(UIKit)
    private func artworkItem(_ artwork: NowPlayingArtwork?) -> MPMediaItemArtwork? {
        guard let artwork else {
            self.artwork = nil
            return nil
        }
        if let cached = self.artwork, cached.id == artwork.id { return cached.item }
        guard let image = UIImage(data: artwork.data) else { return nil }
        let item = Self.artworkItem(image)
        self.artwork = (artwork.id, item)
        return item
    }

    /// Made off the main actor: the system asks for the image on a queue of its own.
    private nonisolated static func artworkItem(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
    #endif

    /// The system runs the clock from the rate, so only a change, or a position off by more than a second and a half, is news.
    private func needsUpdate(_ info: NowPlayingInfo) -> Bool {
        guard let last else { return true }
        if (last.title, last.subtitle, last.durationMs, last.playing, last.artwork?.id, last.live) != (info.title, info.subtitle, info.durationMs, info.playing, info.artwork?.id, info.live) { return true }
        if info.live { return false }
        let expected = last.positionMs + (last.playing ? Int((ContinuousClock.now - lastAt) / .milliseconds(1)) : 0)
        return abs(info.positionMs - expected) > 1_500
    }

    func close() {
        guard !closed else { return }
        closed = true
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers = []
        #if canImport(MediaPlayer)
        for (command, target) in targets { (command as? MPRemoteCommand)?.removeTarget(target) }
        targets = []
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        #endif
        PlaybackAudioSession.deactivate()
    }

    private func activateAudio() {
        #if os(iOS) || os(tvOS)
        PlaybackAudioSession.activate()
        let session = AVAudioSession.sharedInstance()
        let commands = commands
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            guard let type else { return }
            MainActor.assumeIsolated { commands.interruption(type == .began, options.contains(.shouldResume)) }
        })
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { commands.routeLost() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { commands.foreground() }
        })
        #endif
    }

    private func addRemoteCommands() {
        #if canImport(MediaPlayer)
        let center = MPRemoteCommandCenter.shared()
        let commands = commands
        func on(_ command: MPRemoteCommand, _ run: @escaping @MainActor @Sendable (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus) {
            command.isEnabled = true
            let target = command.addTarget { event in
                // Delivered on the main thread; should one ever come from elsewhere, act on it there.
                if Thread.isMainThread { return MainActor.assumeIsolated { run(event) } }
                nonisolated(unsafe) let event = event
                DispatchQueue.main.async { MainActor.assumeIsolated { _ = run(event) } }
                return .success
            }
            targets.append((command, target))
        }
        on(center.playCommand) { _ in commands.play(); return .success }
        on(center.pauseCommand) { _ in commands.pause(); return .success }
        on(center.togglePlayPauseCommand) { _ in commands.togglePlayPause(); return .success }
        guard commands.seeks else {
            // A file play before this one may have left them on.
            for command in [center.skipBackwardCommand, center.skipForwardCommand, center.changePlaybackPositionCommand] as [MPRemoteCommand] {
                command.isEnabled = false
            }
            return
        }
        center.skipBackwardCommand.preferredIntervals = [10]
        center.skipForwardCommand.preferredIntervals = [10]
        on(center.skipBackwardCommand) { _ in commands.seekBy(-10_000); return .success }
        on(center.skipForwardCommand) { _ in commands.seekBy(10_000); return .success }
        on(center.changePlaybackPositionCommand) { event in
            guard let change = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            commands.seekTo(Int(change.positionTime * 1000))
            return .success
        }
        #endif
    }
}
