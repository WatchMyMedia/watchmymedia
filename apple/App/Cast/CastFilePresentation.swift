import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

/// When connected, a fresh Play goes to the TV without creating a local play.
/// A failed LOAD offers an explicit local fallback instead of claiming success.
struct CastFilePresentation: View {
    let request: PlayRequest
    @Environment(CastService.self) private var cast
    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var local = false
    @State private var ready = false
    @State private var attempted = false
    @State private var starting = false

    var body: some View {
        Group {
            if local { PlayerScreen(request: request) }
            else if ready { CastRemoteScreen() }
            else {
                VStack(spacing: 20) {
                    Image(systemName: "tv").font(.system(size: 64)).foregroundStyle(Palette.gold)
                    Text("Play on \(cast.deviceName)").font(.title2.bold())
                    Text(request.title).font(.headline)
                    if starting { ProgressView("Connecting to TV") }
                    else {
                        if let message = cast.message { Text(message).foregroundStyle(Palette.muted).multilineTextAlignment(.center) }
                        Button("Try again") { Task { await start() } }.buttonStyle(PrimaryButtonStyle())
                        Button("Play on this device") { local = true }.buttonStyle(GhostButtonStyle())
                        Button("Back") { dismiss() }
                    }
                }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.ground).foregroundStyle(Palette.text)
            }
        }
        .task {
            guard !attempted else { return }; attempted = true
            let held = services.downloads.downloads.contains { $0.fileID == request.fileID && $0.downloaded }
            if cast.connected && !held && request.recordingID == nil { await start() } else { local = true }
        }
    }
    private func start() async {
        guard !starting else { return }; starting = true
        ready = await cast.play(request, quality: services.preferences.quality)
        starting = false
    }
}

struct CastHandoverButton: View {
    let model: PlayerChromeModel
    @Environment(CastService.self) private var cast
    @Environment(Navigator.self) private var navigator
    @Environment(\.scenePhase) private var scenePhase
    @State private var choseTV = false
    @State private var transferring = false

    var body: some View {
        Group {
            if model.request.recordingID != nil { EmptyView() }
            else if cast.connected {
                Button { Task { await transfer() } } label: {
                    Image(systemName: "tv").frame(width: 44, height: 44)
                }.accessibilityLabel("Move playback to \(cast.deviceName)")
            } else {
                CastButton(tapped: { choseTV = true })
            }
        }
        .disabled(transferring)
        .overlay { if transferring { ProgressView() } }
        .onChange(of: cast.connected) { _, connected in
            if connected && choseTV { choseTV = false; Task { await transfer() } }
        }
        .onChange(of: scenePhase) { _, phase in if phase != .active { choseTV = false } }
        .onDisappear { choseTV = false }
        .alert("Couldn’t move playback", isPresented: Binding(get: { cast.message != nil && !transferring && choseTV }, set: { if !$0 { cast.clearMessage(); choseTV = false } })) {
            Button("Keep playing here") { cast.clearMessage(); choseTV = false }
        } message: { Text(cast.message ?? "Try again.") }
    }
    private func transfer() async {
        guard !transferring else { return }; transferring = true
        let moved = await CastHandover.move(model.controller, request: model.request, to: cast)
        transferring = false
        guard moved, !Task.isCancelled else { choseTV = true; return }
        // Only a matching receiver state after SDK LOAD acknowledgement ends local progress.
        model.dispose()
        navigator.closePlayer()
    }
}
