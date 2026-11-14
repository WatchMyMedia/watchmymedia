import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The Next Episode card (PlayerNext.dc.html): the still, the episode, and a
/// button that fills as the countdown runs ("Next episode in 7"), or "Play
/// now" when autoplay is off; "Watch credits" hides the card.
struct NextEpisodeCard: View {
    let prompt: UpNextPrompt
    /// The countdown, which runs with the clock.
    let clock: PlayerClock
    var width: CGFloat = 320
    let still: URL?
    let onPlay: () -> Void
    let onWatchCredits: () -> Void

    var body: some View {
        let next = prompt.next
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Artwork(url: still, seed: "\(next.showTitle) \(next.title)")
                    .accessibilityHidden(true)
                VStack {
                    HStack {
                        Badge("Next episode")
                        Spacer()
                    }
                    Spacer()
                    HStack {
                        Spacer()
                        if let runtime = next.runtimeMs { PlainBadge(text: formatDuration(ms: runtime)) }
                    }
                }
                .padding(10)
            }
            .frame(width: width, height: width * 150 / 320)
            .clipped()

            VStack(alignment: .leading, spacing: 3) {
                Text(next.cardTitle).font(.system(size: 16, weight: .bold)).foregroundStyle(Palette.text)
                Text(next.cardMeta).font(.system(size: 12)).foregroundStyle(Palette.muted)
                HStack(spacing: 8) {
                    CountdownButton(prompt: prompt, clock: clock, action: onPlay)
                    Button("Watch credits", action: onWatchCredits)
                        .buttonStyle(OutlineButtonStyle())
                }
                .padding(.top, 10)
            }
            .padding(EdgeInsets(top: 12, leading: 14, bottom: 14, trailing: 14))
        }
        .frame(width: width)
        .background(Palette.ground.opacity(0.94))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.16)))
        .shadow(color: .black.opacity(0.7), radius: 25, y: 20)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Next episode")
    }
}

/// "Next episode in 7", filling as the countdown runs, or "Play now": the
/// part of the card that follows the clock.
private struct CountdownButton: View {
    let prompt: UpNextPrompt
    let clock: PlayerClock
    let action: () -> Void

    var body: some View {
        var prompt = prompt
        prompt.countdownMs = prompt.countdownMs == nil ? nil : clock.upNextCountdownMs
        return Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "play.fill").font(.system(size: 14))
                Text(prompt.buttonLabel).monospacedDigit()
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Palette.text)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Color.white.opacity(0.14)
                        Palette.burgundy.frame(width: geo.size.width * prompt.countdownProgress)
                    }
                }
                .animation(.linear(duration: 0.25), value: prompt.countdownProgress)
            }
            .clipShape(RoundedRectangle(cornerRadius: 9))
            .contentShape(RoundedRectangle(cornerRadius: 9))
        }
        .buttonStyle(.plain)
    }
}
