import Foundation
import Observation

/// How the picture meets the screen on iPhone and iPad (PLAN-APPLE-REVIEW.md
/// §6.3, design/mockups/ios/PlayerFitFill.dc.html). Fit shows the whole
/// picture inside the safe area, with bars (`AVLayerVideoGravity.resizeAspect`);
/// Fill covers the screen, under the Dynamic Island too, and crops the edges
/// (`.resizeAspectFill`). Fill never stretches.
public enum VideoFit: String, CaseIterable, Hashable, Sendable {
    case fit, fill
}

/// Keeps the last choice on this device, for both players and every video
/// after it: the person who fills a film wants the next one filled too.
public struct VideoFitStore: @unchecked Sendable {
    // UserDefaults is thread-safe; older SDKs don't mark it Sendable.
    private let defaults: UserDefaults
    static let key = "player.videoFit"

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    /// The remembered choice; Fit until the person chooses.
    public var mode: VideoFit {
        defaults.string(forKey: Self.key).flatMap(VideoFit.init(rawValue:)) ?? .fit
    }

    public func save(_ mode: VideoFit) {
        defaults.set(mode.rawValue, forKey: Self.key)
    }
}

/// Fit and fill for the film and Live TV players on iPhone and iPad: the
/// button beside Close, the pinch, and the short note that says which and how
/// much is cropped. The choice is remembered (``VideoFitStore``).
@MainActor
@Observable
public final class VideoFitModel {
    /// How long the note stays after a switch.
    public static let noteMs = 1_800
    /// A pinch past these scales switches: out fills, in fits.
    public static let pinchOutScale = 1.15
    public static let pinchInScale = 0.87

    public private(set) var mode: VideoFit
    /// "Fill · 9% of the picture is cropped at the edges", while it shows.
    public private(set) var note: String?

    @ObservationIgnored private let store: VideoFitStore
    @ObservationIgnored private let noteMs: Int
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var noteTask: Task<Void, Never>?

    /// `noteMs` is how long the note stays (longer for UI tests on a busy machine).
    public init(store: VideoFitStore = VideoFitStore(), noteMs: Int = VideoFitModel.noteMs,
                sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.store = store
        self.noteMs = noteMs
        self.sleep = sleep
        mode = store.mode
    }

    public var filled: Bool { mode == .fill }

    /// The button says what it does: "Fill the screen" while fitted.
    public var buttonLabel: String { filled ? "Fit the whole picture" : "Fill the screen" }

    /// Arrows out while fitted, arrows in while filled, as the board draws them.
    public var buttonSymbol: String { filled ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right" }

    /// The button: switches and says so. `video` is the picture's size, when
    /// known; `screen` the area the picture is drawn in.
    public func toggle(video: CGSize?, screen: CGSize) {
        choose(filled ? .fit : .fill, video: video, screen: screen)
    }

    public func choose(_ mode: VideoFit, video: CGSize?, screen: CGSize) {
        self.mode = mode
        store.save(mode)
        show(Self.note(mode, croppedPercent: video.flatMap { Self.croppedPercent(video: $0, screen: screen) }), hides: true)
    }

    /// While a pinch goes on: once it passes the threshold towards the other
    /// mode, the note says what letting go will do.
    public func pinchChanged(scale: Double) {
        if scale >= Self.pinchOutScale, !filled {
            show("Pinch out: fill the screen", hides: false)
        } else if scale <= Self.pinchInScale, filled {
            show("Pinch in: fit the whole picture", hides: false)
        } else if noteTask == nil {
            note = nil
        }
    }

    /// The pinch let go: out past the threshold fills, in past it fits, and
    /// anything less leaves the picture as it was.
    public func pinchEnded(scale: Double, video: CGSize?, screen: CGSize) {
        if scale >= Self.pinchOutScale, !filled {
            choose(.fill, video: video, screen: screen)
        } else if scale <= Self.pinchInScale, filled {
            choose(.fit, video: video, screen: screen)
        } else if noteTask == nil {
            note = nil
        }
    }

    /// Forgets the note at once (the player leaves).
    public func dispose() {
        noteTask?.cancel()
        noteTask = nil
        note = nil
    }

    private func show(_ text: String, hides: Bool) {
        noteTask?.cancel()
        noteTask = nil
        note = text
        guard hides else { return }
        let sleep = sleep
        let noteMs = noteMs
        noteTask = Task { [weak self] in
            do { try await sleep(.milliseconds(noteMs)) } catch { return }
            guard let self, !Task.isCancelled else { return }
            self.note = nil
            self.noteTask = nil
        }
    }

    // MARK: - The rule

    /// How much of the picture Fill crops on `screen`, in whole percent: the
    /// narrower of the two shapes over the wider. nil when either size is empty.
    public nonisolated static func croppedPercent(video: CGSize, screen: CGSize) -> Int? {
        guard video.width > 0, video.height > 0, screen.width > 0, screen.height > 0 else { return nil }
        let v = video.width / video.height
        let s = screen.width / screen.height
        return Int(((1 - min(v, s) / max(v, s)) * 100).rounded())
    }

    /// "Fit · the whole picture", or "Fill · 18% of the picture is cropped at the edges".
    public nonisolated static func note(_ mode: VideoFit, croppedPercent: Int?) -> String {
        switch (mode, croppedPercent) {
        case (.fit, _): "Fit · the whole picture"
        case (.fill, nil): "Fill · the edges are cropped"
        case (.fill, 0?): "Fill · nothing is cropped on this screen"
        case let (.fill, percent?): "Fill · \(percent)% of the picture is cropped at the edges"
        }
    }
}
