import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

/// Where the player's chrome sits: inside the safe area left and right,
/// 16 pt from the top, and just above the home indicator, as the artboards
/// place it on an 852 × 393 screen; 24 pt in from every edge on iPad.
struct PlayerInsets: Equatable {
    var leading: CGFloat
    var trailing: CGFloat
    var top: CGFloat
    var bottom: CGFloat

    init(_ safe: EdgeInsets, metrics: PlayerMetrics = .phone) {
        leading = max(safe.leading, metrics.edge)
        trailing = max(safe.trailing, metrics.edge)
        top = safe.top + metrics.topGap
        bottom = max(safe.bottom + metrics.bottomGap, metrics.edge)
    }
}

/// The touch controls (PlayerControls.dc.html): Close, the title, AirPlay
/// and Picture in Picture along the top; back 10 s, play/pause and forward
/// 10 s in the middle; the scrubber, the times and "…" (the picker) along
/// the bottom. `metrics` are the iPhone's or the iPad's sizes; on iPad the
/// title has the stream line under it (PadPlayerControls.dc.html). While the
/// scrubber is dragged, the seek thumbnail sits over its thumb, inside the
/// controls' edges, and the middle buttons make way (PlayerScrub.dc.html,
/// Pad2PlayerScrub.dc.html).
struct PlayerControlsView: View {
    @Environment(CastService.self) private var cast
    let model: PlayerChromeModel
    let pictureInPicture: PictureInPicture
    let insets: PlayerInsets
    var metrics: PlayerMetrics = .phone
    var portrait = false
    var onEpisodes: (() -> Void)?
    /// Fit and fill, beside Close (PlayerFitFill.dc.html).
    var fitButton: VideoFitButton?

    var body: some View {
        let state = model.state
        ZStack {
            VStack(spacing: 0) {
                LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom).frame(height: metrics.sidePanelPicker ? 160 : 110)
                Spacer(minLength: 0)
                LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom).frame(height: metrics.sidePanelPicker ? 240 : 150)
            }
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar(state)
                Spacer(minLength: 0)
                bottomBar(state)
            }
            .padding(.leading, insets.leading)
            .padding(.trailing, insets.trailing)
            .padding(.top, insets.top)
            .padding(.bottom, insets.bottom)

            transport(state)
                .opacity(model.showsScrubTile ? 0 : 1)
                .allowsHitTesting(!model.showsScrubTile)
        }
        .overlayPreferenceValue(ScrubTrackKey.self) { track in
            if model.showsScrubTile, let track {
                GeometryReader { proxy in
                    ScrubTileOverlay(model: model, track: proxy[track], size: proxy.size, insets: insets, metrics: metrics)
                }
                .allowsHitTesting(false)
            }
        }
    }

    private func topBar(_ state: PlayerState) -> some View {
        ZStack(alignment: .top) {
            VStack(spacing: 6) {
                if let growing = state.growing { Label("Recording · ends \(LiveClock.device.time(growing.endsAt))", systemImage: "record.circle").font(.caption.bold()).foregroundStyle(Palette.liveText) }
                if !portrait {
                Text(playerTitleLine(title: state.title, subtitle: state.subtitle))
                    .font(metrics.title)
                    .foregroundStyle(PlayerStyle.light)
                    .shadow(color: .black.opacity(0.6), radius: 4, y: 1)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                // At Automatic, "Auto · 720p": beside the stream line on iPad, alone on iPhone.
                let chip = autoChip(state)
                if metrics.streamChip, let line = streamLine(state) {
                    HStack(spacing: 8) {
                        if let chip { AutoChip(text: chip, size: 12) }
                        Text(line)
                            .font(.system(size: 12))
                            .foregroundStyle(Palette.textSecondary)
                            .lineLimit(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 3)
                            .background(.black.opacity(0.45), in: Capsule())
                    }
                } else if let chip {
                    AutoChip(text: chip, size: 11)
                }
            }
            }
            .padding(.horizontal, 120)
            .padding(.top, metrics.streamChip ? 8 : 0)
            .frame(minHeight: metrics.button)
            HStack(spacing: metrics.streamChip ? 12 : 10) {
                RoundIconButton(systemImage: "xmark", label: "Close", size: metrics.button, iconSize: metrics.buttonIcon) { model.close() }
                if let fitButton { fitButton }
                Spacer(minLength: 0)
                if cast.available && !state.isOffline {
                    CastHandoverButton(model: model)
                        .frame(width: metrics.button, height: metrics.button)
                        .background(PlayerStyle.scrim, in: Circle())
                }
                AirPlayButton()
                    .frame(width: metrics.button, height: metrics.button)
                    .background(PlayerStyle.scrim, in: Circle())
                if pictureInPicture.isPossible {
                    RoundIconButton(systemImage: "pip.enter", label: "Picture in Picture", size: metrics.button, iconSize: metrics.buttonIcon) {
                        model.showControls()
                        pictureInPicture.toggle()
                    }
                }
            }
        }
    }

    private func transport(_ state: PlayerState) -> some View {
        let playing = state.status == .playing || state.status == .buffering || state.status == .loading
        return HStack(spacing: metrics.transportSpacing) {
            RoundIconButton(systemImage: "gobackward.10", label: "Back 10 seconds", size: metrics.seekButton, iconSize: metrics.seekIcon, weight: .medium, background: .clear) {
                model.seek(byMs: -DoubleTapSeek.stepMs)
            }
            ZStack {
                if model.isWaiting { SpinnerRing(size: metrics.playButton + 8, lineWidth: 3) }
                RoundIconButton(systemImage: playing ? "pause.fill" : "play.fill", label: playing ? "Pause" : "Play", size: metrics.playButton, iconSize: metrics.playIcon, background: .clear) {
                    model.togglePlayPause()
                }
                .accessibilityIdentifier("player-play-pause")
            }
            RoundIconButton(systemImage: "goforward.10", label: "Forward 10 seconds", size: metrics.seekButton, iconSize: metrics.seekIcon, weight: .medium, background: .clear) {
                model.seek(byMs: DoubleTapSeek.stepMs)
            }
        }
    }

    private func bottomBar(_ state: PlayerState) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: metrics.streamChip ? 16 : 14) {
                PlayerTime(model: model, remaining: false)
                    .font(metrics.time)
                    .frame(minWidth: 40, alignment: .leading)
                    .accessibilityHidden(true)
                PlayerScrubber(model: model)
                if state.growing != nil {
                    GoToNowButton(model: model)
                }
                PlayerTime(model: model, remaining: true)
                    .font(metrics.time)
                    .frame(minWidth: 48, alignment: .trailing)
                    .accessibilityHidden(true)
                if !metrics.sidePanelPicker {
                    RoundIconButton(systemImage: "ellipsis", label: model.pickerTitle, iconSize: metrics.buttonIcon + 2, background: .clear) {
                        model.openPicker(.subtitles)
                    }
                }
            }
            if metrics.sidePanelPicker && !portrait {
                HStack(spacing: 16) {
                    Spacer()
                    ForEach(model.pickerTabs, id: \.self) { tab in
                        Button(tab.title) { model.openPicker(tab, pausesPlayback: false) }
                            .buttonStyle(OutlineButtonStyle())
                    }
                    if let onEpisodes {
                        Button(action: onEpisodes) { Label("Episodes", systemImage: "list.bullet") }
                            .buttonStyle(OutlineButtonStyle())
                    }
                }
            }
        }
        .foregroundStyle(Palette.text)
    }

}

/// The scrubber: the track, how far it is buffered, the gold played part,
/// the intro and credits bands, and a thumb to drag. Dragging previews the
/// time over the thumb and seeks on release; a tap seeks at once. VoiceOver
/// adjusts it 10 s at a time.
struct PlayerScrubber: View {
    let model: PlayerChromeModel

    var body: some View {
        let clock = model.clock
        let durationMs = clock.durationMs
        let shown = model.shownPositionMs
        GeometryReader { geo in
            let width = geo.size.width
            let timeline = model.state.growing?.plannedDurationMs ?? durationMs
            let recorded = fraction(ofMs: durationMs, durationMs: timeline)
            let played = fraction(ofMs: shown, durationMs: timeline)
            let buffered = min(bufferedFraction(positionMs: clock.positionMs, bufferedMs: clock.bufferedMs, durationMs: timeline), recorded)
            let dragging = model.scrubMs != nil
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.12)).frame(height: 5)
                Capsule().fill(.white.opacity(0.3)).frame(width: width * recorded, height: 5)
                Capsule().fill(.white.opacity(0.4)).frame(width: width * buffered, height: 5)
                Capsule().fill(Palette.gold).frame(width: width * played, height: 5)
                ForEach(model.bands, id: \.self) { band in
                    BandMark(style: band.style)
                        .frame(width: max(width * (band.end - band.start), 4), height: 13)
                        .offset(x: width * band.start)
                }
                Circle()
                    .fill(Palette.text)
                    .shadow(color: .black.opacity(0.5), radius: 2, y: 1)
                    .frame(width: dragging ? 20 : 14, height: dragging ? 20 : 14)
                    .offset(x: width * played - (dragging ? 10 : 7))
                if dragging && !model.showsScrubTile {
                    Text(formatClock(ms: shown))
                        .font(PlayerStyle.time)
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Palette.ground.opacity(0.85), in: RoundedRectangle(cornerRadius: 6))
                        .fixedSize()
                        .position(x: min(max(width * played, 28), width - 28), y: -14)
                }
            }
            .frame(width: width, height: geo.size.height)
            .anchorPreference(key: ScrubTrackKey.self, value: .bounds) { $0 }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { model.scrub(x: $0.location.x, width: width) }
                    .onEnded { _ in model.endScrub() }
            )
            .animation(.easeOut(duration: 0.12), value: dragging)
            // A tick as the thumb is taken (PLAN-APPLE-REVIEW.md §6.3).
            .sensoryFeedback(.impact(weight: .light), trigger: dragging) { _, now in now }
        }
        .frame(height: 44)
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue(scrubberAccessibilityValue(positionMs: shown, durationMs: durationMs))
        .accessibilityHint(model.bands.map { $0.accessibilityLabel(durationMs: durationMs) }.joined(separator: ". "))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: model.seek(byMs: DoubleTapSeek.stepMs)
            case .decrement: model.seek(byMs: -DoubleTapSeek.stepMs)
            @unknown default: break
            }
        }
    }
}

/// The elapsed time, or what is left, beside the scrubber: views of their
/// own, since they change with every tick and the controls around them don't.
private struct PlayerTime: View {
    let model: PlayerChromeModel
    let remaining: Bool

    var body: some View {
        let shown = model.shownPositionMs
        Text(remaining ? remainingLabel(positionMs: shown, durationMs: model.clock.durationMs) : formatClock(ms: shown))
    }
}

/// "Go to now" on a recording that is still recording, once the play is more than 10 s behind it.
private struct GoToNowButton: View {
    let model: PlayerChromeModel

    var body: some View {
        let clock = model.clock
        if clock.durationMs - clock.positionMs > PlayerState.goToNowBehindMs {
            Button("Go to now") { model.seek(toMs: max(model.clock.durationMs - 3000, 0)) }.font(.caption.bold()).foregroundStyle(Palette.gold).frame(minHeight: 44)
        }
    }
}

/// The seek thumbnail and its time, centred on the thumb and kept inside the controls' edges.
private struct ScrubTileOverlay: View {
    let model: PlayerChromeModel
    let track: CGRect
    let size: CGSize
    let insets: PlayerInsets
    let metrics: PlayerMetrics

    var body: some View {
        let shown = model.shownPositionMs
        let width = metrics.tileWidth
        let height = (width * model.scrubTileAspect).rounded()
        let thumbX = track.minX + track.width * fraction(ofMs: shown, durationMs: model.clock.durationMs)
        let x = min(max(thumbX, insets.leading + width / 2), size.width - insets.trailing - width / 2)
        // Its bottom (the time) clears the dragged thumb.
        let tall = height + metrics.tileGap + 24
        SeekTile(image: model.scrubTile, time: formatClock(ms: shown), width: width, height: height, metrics: metrics)
            .fixedSize()
            .position(x: x, y: track.midY - 16 - tall / 2)
    }
}

/// Where the scrubber's track is, for the seek thumbnail over its thumb.
struct ScrubTrackKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

/// The seek thumbnail (PlayerScrub.dc.html): the tile with a light edge, and
/// the time under it. Dark until the tile is cut; decorative, since
/// VoiceOver reads the scrubber's time.
struct SeekTile: View {
    let image: CGImage?
    let time: String
    let width: CGFloat
    let height: CGFloat
    let metrics: PlayerMetrics

    var body: some View {
        VStack(spacing: metrics.tileGap) {
            ZStack {
                PlayerStyle.deepGround
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(.medium)
                        .aspectRatio(contentMode: .fill)
                }
            }
            .frame(width: width, height: height)
            .clipShape(RoundedRectangle(cornerRadius: metrics.tileRadius))
            .overlay(RoundedRectangle(cornerRadius: metrics.tileRadius).inset(by: -1).strokeBorder(Palette.text.opacity(0.92), lineWidth: 2))
            .shadow(color: .black.opacity(0.65), radius: 14, y: 10)
            Text(time)
                .font(metrics.time)
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 8)
                .frame(height: 24)
                .background(Palette.ground.opacity(0.85), in: RoundedRectangle(cornerRadius: 6))
        }
        .accessibilityHidden(true)
    }
}

/// A segment's band: gold for the intro and recap, light for the credits.
private struct BandMark: View {
    let style: BandStyle

    var body: some View {
        let tint: Color = style == .opening ? Palette.gold : .white
        RoundedRectangle(cornerRadius: 2)
            .fill(tint.opacity(style == .opening ? 0.2 : 0.1))
            .overlay(RoundedRectangle(cornerRadius: 2).strokeBorder(tint.opacity(style == .opening ? 0.6 : 0.35), lineWidth: 1))
    }
}

/// "Skip Intro": in auto-delay mode a burgundy fill runs across it before the skip happens by itself.
struct SkipButton: View {
    let prompt: SkipPrompt
    /// Where the fill is: it runs with the clock.
    let clock: PlayerClock
    var metrics: PlayerMetrics = .phone
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: metrics.skipHeight > 44 ? 10 : 8) {
                Text(prompt.label)
                Image(systemName: "chevron.right.2").font(.system(size: 15, weight: .bold))
            }
            .font(metrics.skipFont)
            .foregroundStyle(Palette.text)
            .padding(.horizontal, metrics.skipHeight > 44 ? 22 : 18)
            .frame(height: metrics.skipHeight)
            .background {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Palette.ground.opacity(0.78)
                        Palette.burgundy.frame(width: geo.size.width * (clock.skipProgress ?? 0))
                    }
                }
                .animation(.linear(duration: 0.25), value: clock.skipProgress)
            }
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(.white.opacity(0.35)))
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(prompt.label)
    }
}

/// The double-tap feedback: a soft arc on that side with the run's total ("+30 s").
struct SeekRipple: View {
    let burst: SeekBurst

    var body: some View {
        let back = burst.zone == .back
        GeometryReader { geo in
            ZStack {
                Ellipse()
                    .fill(.white.opacity(0.12))
                    .frame(width: geo.size.width * 1.6, height: geo.size.height * 1.2)
                    .offset(x: back ? -geo.size.width * 0.3 : geo.size.width * 0.3)
                VStack(spacing: 6) {
                    Image(systemName: back ? "gobackward" : "goforward").font(.system(size: 28, weight: .medium))
                    Text(burst.label).font(PlayerStyle.time)
                }
                .foregroundStyle(Palette.text)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
