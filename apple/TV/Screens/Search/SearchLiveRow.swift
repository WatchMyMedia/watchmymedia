import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Search's On live TV row, after the library's rows: what is on now and
/// later whose title or episode matches. Select on a programme on now
/// watches its channel; on a later one it opens the guide on it, where it
/// can be recorded. A long press offers Remind me (or Remove reminder) and
/// Watch, as the guide's menu does.
struct TVSearchLiveRow: View {
    let model: LiveSearchModel
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator

    var body: some View {
        switch model.phase {
        case .idle, .failed:
            EmptyView()
        case .loading:
            VStack(alignment: .leading, spacing: 28) {
                RowHeading(title: liveOnLiveTVTitle)
                TVSpinner()
            }
            .padding(.horizontal, TVLayout.gutter)
        case .empty(let query):
            VStack(alignment: .leading, spacing: 16) {
                RowHeading(title: liveOnLiveTVTitle)
                // Words, not a box: nothing here takes focus.
                Text("\(liveSearchNothing(query, listedThrough: model.listedThrough)) \(liveSearchLooksAt)")
                    .font(.tv(.body))
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: 1100, alignment: .leading)
                    .accessibilityIdentifier("live-search-none")
            }
            .padding(.horizontal, TVLayout.gutter)
        case .results(let hits):
            TimelineView(.periodic(from: .now, by: 30)) { context in
                ShelfRow(title: liveOnLiveTVTitle, detail: liveSearchCountDetail(hits.count), items: hits) { hit in
                    card(hit, now: context.date)
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("live-search-results")
        }
    }

    private func card(_ hit: LiveSearchHit, now: Date) -> some View {
        let reminded = extras.isReminded(hit.programme)
        let select = hit.searchSelect(now: now)
        return Button { choose(hit, now: now) } label: {
            SearchLiveCard(hit: hit, now: now, reminded: reminded)
        }
        .buttonStyle(PlainCardStyle())
        .playPauseCard(.select) { choose(hit, now: now) }
        .contextMenu {
            let actions = LiveProgrammeActions(hit.programme, on: hit.channel, now: now, extras: extras)
            switch actions.reminder {
            case .set?:
                Button { Task { await extras.removeReminder(for: hit.programme) } } label: { Label(liveRemoveReminder, systemImage: "bell.slash") }
            case .offered?:
                Button { Task { await extras.remind(hit.programme, on: hit.channel) } } label: { Label(liveRemindMe, systemImage: "bell") }
            case .unavailable?, nil:
                EmptyView()
            }
            Button { navigator.playLive(.init(channel: hit.channel)) } label: {
                Label(liveWatchLabel(hit.channel, programme: hit.programme, now: now), systemImage: "play.fill")
            }
            .disabled(!actions.watch)
        }
        .accessibilityLabel(liveSearchAccessibilityLabel(hit, reminded: reminded, now: now))
        .accessibilityHint(select == .watch ? "Watches \(hit.channel.number)" : liveShowsInGuideHint)
        .accessibilityIdentifier("live-search-\(hit.channel.number)-\(hit.programme.title)")
    }

    /// Select: watches what is on now; a later programme opens the guide on it.
    private func choose(_ hit: LiveSearchHit, now: Date) {
        switch hit.searchSelect(now: now) {
        case .watch:
            navigator.playLive(.init(channel: hit.channel))
        case .showInGuide:
            extras.reveal = .init(channelID: hit.channel.id, programmeID: hit.programme.id, start: hit.programme.start)
            navigator.setPath([], for: .liveTV)
            navigator.select(.liveTV)
        }
    }
}

/// A card button without the system's focus effect, which would enlarge the
/// channel's logo inside it (apple/AGENTS.md); the card draws its own lift.
private struct PlainCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

/// A programme found: its picture with the channel on it, how far in it is
/// while it is on (its start time when it is later, and a bell when a
/// reminder is set), then its title and when.
private struct SearchLiveCard: View {
    let hit: LiveSearchHit
    let now: Date
    let reminded: Bool
    @Environment(\.isFocused) private var focused

    private let width = TVCardWidth.wideLarge

    var body: some View {
        let p = hit.programme, onNow = p.isOn(at: now)
        VStack(alignment: .leading, spacing: 0) {
            LivePictureView(programme: p, channel: hit.channel, wide: true)
                .frame(width: width, height: (width * 9 / 16).rounded())
                .overlay { TVBadge("\(hit.channel.number) \(hit.channel.name)").pinned(.topLeading) }
                .overlay {
                    if !onNow { TVBadge(LiveClock.device.time(p.start)).pinned(.topTrailing) }
                }
                .overlay {
                    if reminded && !onNow {
                        Text("\(Image(systemName: "bell.fill"))")
                            .font(.tv(.caption2, .bold))
                            .foregroundStyle(Palette.ground)
                            .frame(width: 40, height: 40)
                            .background(Palette.gold, in: Circle())
                            .pinned(.bottomTrailing)
                    }
                }
                .overlay {
                    if onNow { TVProgressBar(progress: p.progress(at: now)).pinned(.bottom, padding: 14) }
                }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusLift(focused)
            VStack(alignment: .leading, spacing: 2) {
                Text(p.title).font(.tv(.caption1, .semibold)).foregroundStyle(focused ? .white : Color.tvTitle)
                Text(liveSearchWhen(hit, now: now)).font(.tv(.caption2)).foregroundStyle(Palette.muted)
            }
            .lineLimit(1)
            .padding(.top, 16)
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: width, alignment: .leading)
    }
}
