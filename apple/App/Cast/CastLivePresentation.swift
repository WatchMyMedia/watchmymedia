import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

struct CastLivePresentation: View {
    let request: LivePlayRequest
    @Environment(CastService.self) private var cast
    @Environment(\.dismiss) private var dismiss
    @State private var local = false
    @State private var ready = false
    @State private var attempted = false
    @State private var starting = false
    var body: some View {
        Group {
            if local { LivePlayerScreen(request: request) }
            else if ready { CastRemoteScreen() }
            else {
                VStack(spacing: 20) {
                    Text("Watch \(request.channel.title)").font(.title2.bold())
                    if starting { ProgressView("Opening channel on TV") }
                    else {
                        Text(cast.message ?? "The TV has not confirmed Live TV support yet.").foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                        Button("Try TV again") { Task { await start() } }.buttonStyle(PrimaryButtonStyle())
                        Button("Watch on this device") { local = true }.buttonStyle(GhostButtonStyle())
                        Button("Back") { dismiss() }
                    }
                }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.ground).foregroundStyle(Palette.text)
            }
        }.task {
            guard !attempted else { return }; attempted = true
            if cast.connected { await start() } else { local = true }
        }
    }
    private func start() async { guard !starting else { return }; starting = true; ready = await cast.playLive(request); starting = false }
}

struct CastLiveHandoverButton: View {
    let model: LivePlayerModel
    @Environment(CastService.self) private var cast
    @Environment(Navigator.self) private var navigator
    @State private var choseTV = false
    @State private var transferring = false
    @State private var failed = false
    var body: some View {
        Group {
            if cast.connected {
                Button { Task { await transfer() } } label: { Image(systemName: "tv").frame(width: 44, height: 44) }
                    .accessibilityLabel("Move live TV to \(cast.deviceName)")
                    .disabled(!cast.supportsLive || transferring)
            } else { CastButton(tapped: { choseTV = true }) }
        }
        .onChange(of: cast.receiver?.phase) { _, phase in
            if cast.connected && choseTV && phase != nil { choseTV = false; Task { await transfer() } }
        }
        .alert("Couldn’t move live TV", isPresented: $failed) { Button("Keep watching here") { cast.clearMessage() } } message: { Text(cast.message ?? "Try again.") }
    }
    private func transfer() async {
        guard !transferring, let channel = model.state.channel else { return }; transferring = true
        let moved = await cast.playLive(LivePlayRequest(channel: channel, quality: model.state.quality))
        transferring = false
        guard moved, !Task.isCancelled else { failed = true; return }
        model.dispose(); navigator.closePlayer()
    }
}
