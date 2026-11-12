import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

/// The receiver's own state drives the remote; this view runs no playback rules.
struct CastRemoteScreen: View {
    @Environment(CastService.self) private var cast
    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var scrub: Double?
    @State private var handback = false

    private var state: PlayerState { cast.state }
    private var live: CastReceiverState.Live? { cast.receiver?.live }

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let wide = geo.size.width >= PadLayout.sideBySide
                let layout = wide ? AnyLayout(HStackLayout(alignment: .top, spacing: 40)) : AnyLayout(VStackLayout(spacing: 24))
                ScrollView {
                    layout {
                        heading.frame(maxWidth: wide ? 340 : .infinity)
                        controls.frame(maxWidth: wide ? 560 : .infinity)
                    }
                    .padding(24).frame(maxWidth: wide ? 1080 : 720).frame(maxWidth: .infinity)
                }
            }
            .background(Palette.ground).foregroundStyle(Palette.text)
            .navigationTitle("Cast remote").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .presentationBackground(Palette.ground)
        .task { cast.command(.input) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("cast.remote")
    }

    private var heading: some View {
        VStack(spacing: 16) {
            if cast.ownsMedia, let request = cast.request, let path = request.stillPath ?? request.backdropPath ?? request.posterPath {
                Artwork(url: services.library.imageURL(path, size: .w780), seed: state.title)
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .accessibilityHidden(true)
            } else {
                Image(systemName: live == nil ? "tv" : "antenna.radiowaves.left.and.right")
                    .font(.system(size: 64, weight: .light)).foregroundStyle(Palette.gold).padding(.top, 24)
            }
            Text("Playing on \(cast.deviceName)").font(.headline).foregroundStyle(Palette.muted)
            Text(state.title).font(.title2.bold()).multilineTextAlignment(.center)
            if let subtitle = state.subtitle { Text(subtitle).foregroundStyle(Palette.muted).multilineTextAlignment(.center) }
        }.frame(maxWidth: .infinity)
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 24) {
            if let message = cast.message, message != state.error {
                HStack(alignment: .top) {
                    Text(message).font(.footnote).foregroundStyle(Palette.warning)
                    Spacer()
                    Button("Dismiss") { cast.clearMessage() }.font(.footnote)
                }
            }
            if !cast.connected {
                Text("Connection lost. Reconnect to control the TV.").foregroundStyle(Palette.warning)
                CastButton()
            } else if let stop = state.stopMessage {
                Label("Playback stopped", systemImage: "stop.circle").font(.headline)
                Text(stop.isEmpty ? "An admin stopped this play." : stop)
            } else if let error = state.error {
                Label("Couldn’t play on TV", systemImage: "exclamationmark.triangle").font(.headline)
                Text(error).foregroundStyle(Palette.muted)
                if let request = cast.request, cast.ownsMedia {
                    Button("Try again") { Task { _ = await cast.play(request, quality: state.quality) } }
                        .buttonStyle(PrimaryButtonStyle())
                }
            } else {
                if cast.loading { ProgressView("Starting playback on TV").frame(maxWidth: .infinity) }
                else if let live {
                    liveControls(live)
                } else {
                    fileControls
                }
                HStack(spacing: 40) {
                    if live == nil {
                        Button { cast.command(.seek(max(0, state.positionMs - 10_000))) } label: { Image(systemName: "gobackward.10").frame(width: 48, height: 48) }.accessibilityLabel("Back 10 seconds on TV")
                    }
                    Button { cast.togglePlayPause() } label: { Image(systemName: state.status == .playing ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 60)).frame(width: 72, height: 72) }
                        .accessibilityLabel(state.status == .playing ? "Pause on TV" : "Play on TV")
                        .accessibilityIdentifier("cast.remote.playPause")
                    if live == nil {
                        Button { cast.command(.seek(min(state.durationMs, state.positionMs + 10_000))) } label: { Image(systemName: "goforward.10").frame(width: 48, height: 48) }.accessibilityLabel("Forward 10 seconds on TV")
                    }
                }.frame(maxWidth: .infinity).foregroundStyle(Palette.gold)
                if state.askStillWatching {
                    Text("Are you still watching?").font(.headline)
                    Button("Keep watching") { cast.command(.stillWatching(true)) }.buttonStyle(PrimaryButtonStyle())
                    Button("Stop watching") { cast.command(.stillWatching(false)) }
                }
                if live == nil { fileOptions }
                VStack(alignment: .leading, spacing: 8) {
                    Label("TV volume", systemImage: "speaker.wave.2")
                    Slider(value: Binding(get: { Double(cast.media?.volume ?? 1) }, set: { cast.setVolume(Float($0)) }), in: 0...1)
                        .accessibilityLabel("TV volume")
                }
            }
            if cast.ownsMedia, live == nil, cast.request != nil {
                Button("Continue on this \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")") { handback = true }
                    .buttonStyle(GhostButtonStyle())
                    .confirmationDialog("Move playback from the TV to this device?", isPresented: $handback, titleVisibility: .visible) {
                        Button("Continue on this device") { cast.continueOnDevice(); dismiss() }
                    }
            }
            if cast.connected {
                HStack {
                    Button("Disconnect") { cast.disconnect(); dismiss() }
                    Spacer()
                    Button("Stop casting", role: .destructive) { cast.stop(); dismiss() }
                }.frame(minHeight: 44)
                Text("Disconnect leaves playback on the TV. Stop casting ends it.").font(.footnote).foregroundStyle(Palette.muted)
            }
        }
    }

    private var fileControls: some View {
        VStack(spacing: 8) {
            Slider(value: Binding(get: { scrub ?? Double(state.positionMs) }, set: { scrub = $0 }), in: 0...Double(max(state.durationMs, 1))) { editing in
                if !editing, let scrub { cast.command(.seek(Int(scrub))); self.scrub = nil }
            }.disabled(state.durationMs <= 0).accessibilityLabel("Playback position on TV")
            HStack { Text(formatClock(ms: Int(scrub ?? Double(state.positionMs)))); Spacer(); Text(formatClock(ms: state.durationMs)) }
                .font(.caption.monospacedDigit()).foregroundStyle(Palette.muted)
            HStack {
                if let skip = state.skip { Button(skip.label) { cast.command(.skip) }.buttonStyle(GhostButtonStyle()) }
                if state.nextEpisode != nil { Button("Next episode") { cast.command(.playNext) }.buttonStyle(GhostButtonStyle()) }
                if state.upNext != nil { Button("Watch credits") { cast.command(.watchCredits) } }
            }
        }
    }

    private var fileOptions: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Audio").font(.caption).foregroundStyle(Palette.muted)
                Picker("Audio", selection: Binding(get: { state.selectedAudio ?? -1 }, set: { cast.command(.audio($0)) })) {
                    ForEach(state.audio) { Text($0.label).tag($0.index) }
                }.labelsHidden().disabled(state.audio.isEmpty)
            }
            Divider()
            VStack(alignment: .leading, spacing: 4) {
                Text("Subtitles").font(.caption).foregroundStyle(Palette.muted)
                Picker("Subtitles", selection: Binding(get: { state.selectedSubtitle ?? "" }, set: { cast.command(.subtitle($0.isEmpty ? nil : $0)) })) {
                    Text("Off").tag("")
                    ForEach(state.subtitles) { Text($0.label).tag($0.id) }
                }.labelsHidden()
            }
            if state.subtitles.contains(where: { $0.source == .online }) { Text(openSubtitlesCredit).font(.caption).foregroundStyle(Palette.muted) }
            Divider()
            VStack(alignment: .leading, spacing: 4) {
                Text("Quality").font(.caption).foregroundStyle(Palette.muted)
                Picker("Quality", selection: Binding(get: { state.quality }, set: { cast.command(.quality($0.rawValue)) })) {
                    ForEach(state.qualities, id: \.self) { Text($0.label).tag($0) }
                }.labelsHidden()
            }
            if state.quality == .auto, let step = state.autoStep { Text("Now \(step.label)").font(.caption).foregroundStyle(Palette.muted) }
        }.pickerStyle(.menu).padding(16).background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
    }

    private func liveControls(_ live: CastReceiverState.Live) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("\(live.number) · \(live.name)", systemImage: "dot.radiowaves.left.and.right").font(.headline)
            if let now = live.now { Text(now.title); if let episode = now.episodeTitle { Text(episode).font(.subheadline).foregroundStyle(Palette.muted) } }
            HStack {
                Button("Previous channel") { cast.command(.channelStep(-1)) }
                Spacer()
                Button("Go live") { cast.goLive() }
                Spacer()
                Button("Next channel") { cast.command(.channelStep(1)) }
            }.frame(minHeight: 44)
            if cast.ownsMedia, cast.supportsLive {
                Menu("Channels") {
                    ForEach(services.liveTV.channels.filter(\.available)) { channel in Button(channel.title) { cast.command(.channel(channel.id)) } }
                }.frame(minHeight: 44)
            }
            Picker("Live quality", selection: Binding(get: { live.quality }, set: { cast.command(.quality($0)) })) {
                if live.quality == "auto" || cast.supportsLiveAutomatic && services.liveTV.capabilities?.automatic == true {
                    Text("Automatic").tag(LiveQuality.auto.rawValue)
                }
                ForEach(LiveQuality.fixed, id: \.self) { Text($0.title).tag($0.rawValue) }
            }.pickerStyle(.menu)
            if live.quality == "auto" {
                let output = state.autoStep == .original ? "Original" : state.autoStep?.label ?? "Starting"
                Text("Now Auto · \(output)").font(.caption).foregroundStyle(Palette.muted)
            }
        }
    }
}
