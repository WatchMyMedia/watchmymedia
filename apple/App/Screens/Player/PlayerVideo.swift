import AVFoundation
import AVKit
import SwiftUI
import UIKit
import WMMUI

/// The video, fitted on black; the backdrop, dimmed, until there is a
/// picture (and always on the fakes, which have no video), or while Picture
/// in Picture has it.
struct PlayerVideo: View {
    let player: AVPlayer?
    let backdrop: URL?
    let seed: String
    let pictureInPicture: PictureInPicture
    @State private var ready = false

    var body: some View {
        ZStack {
            Color.black
            if let player {
                VideoSurface(player: player, pictureInPicture: pictureInPicture) { ready = $0 }
            }
            if player == nil || !ready || pictureInPicture.isActive {
                Artwork(url: backdrop, seed: seed, maxPixel: 1600)
                    .overlay(Color.black.opacity(0.3))
                    .transition(.opacity)
            }
            if pictureInPicture.isActive {
                Label("Playing in Picture in Picture", systemImage: "pip")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(PlayerStyle.light)
            }
        }
        .animation(.easeOut(duration: 0.25), value: ready)
        .accessibilityHidden(!pictureInPicture.isActive)
    }
}

/// An `AVPlayerLayer` bound to the controller's player, aspect fit. Reports
/// when the first frame is ready, and hands the layer to Picture in Picture.
private struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    let pictureInPicture: PictureInPicture
    let onReady: (Bool) -> Void

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        view.onReady = onReady
        if view.playerLayer.player !== player { view.playerLayer.player = player }
        pictureInPicture.attach(view.playerLayer)
    }
}

final class PlayerLayerView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var onReady: ((Bool) -> Void)?
    private var readyObservation: NSKeyValueObservation?

    override init(frame: CGRect) {
        super.init(frame: frame)
        readyObservation = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
            let ready = layer.isReadyForDisplay
            Task { @MainActor in self?.onReady?(ready) }
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }
}

/// Picture in Picture on the player's layer: whether it can start now
/// (the button shows only then), whether it is on, and starting it by
/// itself when the viewer leaves the app while the video plays. The
/// controller keeps running meanwhile, with its heartbeats.
@MainActor
@Observable
final class PictureInPicture: NSObject {
    private(set) var isPossible = false
    private(set) var isActive = false
    @ObservationIgnored private var controller: AVPictureInPictureController?
    @ObservationIgnored private var possibleObservation: NSKeyValueObservation?
    @ObservationIgnored private weak var layer: AVPlayerLayer?

    func attach(_ layer: AVPlayerLayer) {
        guard self.layer !== layer, AVPictureInPictureController.isPictureInPictureSupported() else { return }
        self.layer = layer
        // Picture in Picture needs the playback audio session; the controller normally sets it.
        let session = AVAudioSession.sharedInstance()
        if session.category != .playback { try? session.setCategory(.playback, mode: .moviePlayback) }
        guard let controller = AVPictureInPictureController(playerLayer: layer) else { return }
        controller.delegate = self
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        self.controller = controller
        possibleObservation = controller.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] controller, _ in
            let possible = controller.isPictureInPicturePossible
            Task { @MainActor in self?.isPossible = possible }
        }
    }

    /// Picture in Picture will start by itself if the viewer leaves the app now.
    var mayStartAutomatically: Bool { isPossible && controller?.canStartPictureInPictureAutomaticallyFromInline == true }

    func toggle() {
        guard let controller else { return }
        if controller.isPictureInPictureActive { controller.stopPictureInPicture() } else { controller.startPictureInPicture() }
    }
}

extension PictureInPicture: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { isActive = true }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: any Error) {
        MainActor.assumeIsolated { isActive = false }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { isActive = false }
    }

    /// The player screen stays up underneath, so there is nothing to restore.
    nonisolated func pictureInPictureController(
        _ controller: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(true)
    }
}

/// The system AirPlay button, in the player's round style.
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = true
        view.tintColor = UIColor(Palette.text)
        view.activeTintColor = UIColor(Palette.gold)
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
