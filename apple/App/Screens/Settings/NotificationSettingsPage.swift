import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Notifications (PLAN-NEW-EPISODES.md §7.2; the new-episodes
/// boards' `settings`), in the You group: "On this iPhone" first, with this
/// device's New episodes switch, iOS's state and the limits; then the shows
/// the person follows, from the server, newest first, each opening its page
/// and with Stop notifying. On an iPad's wide page the two stand side by side.
/// The switch is kept on the device, says Saved for a few seconds, and while
/// iOS hasn't asked, turning it on asks. iOS's states are read when the page
/// appears and when the app comes forward.
struct NotificationSettingsPage: View {
    let model: SettingsModel
    @State private var deviceSwitch: NewEpisodeSwitch?
    /// The show a row opened. Pushed here rather than through the navigator,
    /// so it opens in the iPad's detail column as on iPhone.
    @State private var openedShow: Int?
    @Environment(FollowsStore.self) private var follows
    @Environment(NotificationRouter.self) private var router
    @Environment(NewEpisodeNotifier.self) private var newEpisodes
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(AppServices.self) private var services
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    private var device: String { model.facts.family }

    var body: some View {
        SettingsScroll(lede: NewEpisodeWords.lede) {
            SettingsColumns {
                deviceSection
            } right: {
                followedSection
            }
        }
        .navigationTitle(NewEpisodeWords.title)
        .navigationDestination(item: $openedShow) { ShowScreen(id: $0) }
        .task { if deviceSwitch == nil { deviceSwitch = NewEpisodeSwitch(memory: newEpisodes.memory, device: device) } }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            newEpisodes.refreshBackgroundStatus()
            await router.refreshStatus()
        }
        .task { await follows.refresh() }
    }

    // MARK: On this iPhone

    private var section: NewEpisodeSection {
        NewEpisodeSection(permission: router.permission, backgroundRefresh: newEpisodes.backgroundRefresh, notifyHere: newEpisodes.memory.notifyHere)
    }

    private var deviceSection: some View {
        let section = section
        let save = deviceSwitch?.save ?? .rest
        return VStack(alignment: .leading, spacing: 6) {
            GroupHeading(NewEpisodeWords.onThisDevice(device)).padding(.horizontal, 16)
            if section.notice == .refused {
                NoticeBox(systemImage: "bell.slash", message: NewEpisodeWords.systemRefused,
                          action: (NewEpisodeWords.openSettings, { openAppNotificationSettings(openURL) }))
                    .padding(.bottom, 2)
                    .accessibilityIdentifier("notifications-refused")
            }
            RoundedGroup {
                SwitchRow(title: NewEpisodeWords.switchTitle, line: save.rowLine(rest: NewEpisodeWords.switchLine),
                          isOn: section.switchOn, disabled: !section.switchEnabled) { on in switchTurned(on) }
                    .opacity(section.switchEnabled ? 1 : 0.45)
                    .accessibilityIdentifier("notifications-new-episodes")
            }
            if section.notice == .backgroundRefreshOff {
                NoticeBox(systemImage: "arrow.clockwise", message: NewEpisodeWords.backgroundRefreshOff,
                          action: (NewEpisodeWords.openSettings, { openAppSettings(openURL) }))
                    .padding(.top, 2)
                    .accessibilityIdentifier("notifications-background-refresh")
            }
            if section.showsLimits {
                SectionNote(NewEpisodeWords.limits(device: device))
                    .padding(.horizontal, 16)
                    .padding(.top, 2)
            }
        }
    }

    private func switchTurned(_ on: Bool) {
        guard let deviceSwitch else { return }
        let permission = router.permission
        Task {
            // On, and iOS lets them through: the check starts its cursor from now.
            if await deviceSwitch.set(on, permission: permission, ask: { await router.requestAuthorization() }) {
                await newEpisodes.check()
            }
        }
    }

    // MARK: Shows you follow

    private var followedSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading(NewEpisodeWords.followedTitle).padding(.horizontal, 16)
            if !follows.loaded, follows.error != nil {
                NoticeBox(systemImage: "exclamationmark.triangle", title: NewEpisodeWords.listFailedTitle, message: NewEpisodeWords.listFailedLine,
                          action: (NewEpisodeWords.tryAgain, { Task { await follows.refresh() } }))
                    .accessibilityIdentifier("notifications-list-failed")
            } else if !follows.loaded {
                RoundedGroup {
                    placeholderRow(titleWidth: 140, lineWidth: 96)
                    placeholderRow(titleWidth: 112, lineWidth: 80)
                }
                .accessibilityHidden(true)
            } else if follows.follows.isEmpty {
                RoundedGroup {
                    Text(NewEpisodeWords.noneFollowed)
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityIdentifier("notifications-none")
            } else {
                RoundedGroup {
                    ForEach(follows.follows) { follow in
                        FollowedShowRow(follow: follow, line: follows.line(for: follow),
                                        posterURL: services.library.imageURL(follow.posterPath, size: .w185)) {
                            if let id = follow.showID { openedShow = id }
                        } stop: {
                            Task { await follows.remove(follow) }
                        }
                    }
                }
            }
            // Only where Playback shows Live TV reminders; Playback is a link to it.
            if extras.available {
                NavigationLink { PlaybackSettingsPage(model: model) } label: {
                    Text("Live TV reminders are in \(Text(NewEpisodeWords.liveTVPointerLink).foregroundStyle(Palette.gold)).")
                        .font(.footnote)
                        .foregroundStyle(Palette.faint)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.top, 2)
                .accessibilityLabel(NewEpisodeWords.liveTVPointer)
                .accessibilityIdentifier("notifications-live-tv")
            }
        }
    }

    private func placeholderRow(titleWidth: CGFloat, lineWidth: CGFloat) -> some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 5).fill(Color(hex: 0x26262A)).frame(width: 34, height: 51)
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0x2A2A2E)).frame(width: titleWidth, height: 14)
                RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0x232326)).frame(width: lineWidth, height: 11)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

/// One show followed: its poster (none, and a dashed box, once it has left
/// the library), its title and line, and Stop notifying, a gold text button
/// and a swipe to the left too. The row opens the show's page while it is in
/// the library.
private struct FollowedShowRow: View {
    let follow: FollowedShow
    let line: (text: String, warning: Bool)
    let posterURL: URL?
    let open: () -> Void
    let stop: () -> Void

    var body: some View {
        // A swipe to the left shows Stop notifying beside the row, as a list's swipe action does.
        ScrollView(.horizontal) {
            HStack(spacing: 0) {
                content.containerRelativeFrame(.horizontal)
                Button(role: .destructive, action: stop) {
                    Text(NewEpisodeWords.stopNotifying)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 12)
                        .frame(width: 112)
                        .frame(maxHeight: .infinity)
                        .background(Color(red: 1, green: 0.27, blue: 0.23))
                }
                .buttonStyle(.plain)
                .accessibilityHidden(true)
            }
            .fixedSize(horizontal: false, vertical: true)
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .accessibilityIdentifier("followed-\(follow.tmdbID)")
    }

    private var content: some View {
        HStack(spacing: 12) {
            Group {
                if follow.inLibrary {
                    Artwork(url: posterURL, seed: follow.title, maxPixel: 120)
                } else {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(Color(hex: 0x151517))
                        .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [3, 3])).foregroundStyle(Palette.outline))
                }
            }
            .frame(width: 34, height: 51)
            .clipShape(RoundedRectangle(cornerRadius: 5))
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(follow.title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(line.text)
                    .font(.footnote)
                    .foregroundStyle(line.warning ? Palette.warning : Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(follow.inLibrary ? .isButton : [])
            Button(NewEpisodeWords.stopNotifying, action: stop)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Palette.gold)
                .buttonStyle(.borderless)
                .frame(minHeight: 44)
                .accessibilityLabel(NewEpisodeWords.stopNotifyingLabel(follow.title))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Palette.surfaceRaised)
        .contentShape(Rectangle())
        .onTapGesture { if follow.inLibrary { open() } }
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: Text(NewEpisodeWords.stopNotifying), stop)
    }
}
