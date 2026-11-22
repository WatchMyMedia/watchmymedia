import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// The pieces of watchlists on iPhone and iPad: the Watchlist button on a
// title's page, the long-press menus' item, Save to watchlist and the
// message with Undo. What they say and do is the Kit's ``WatchlistStore``.

/// The Watchlist button: a bookmark, filled and gold while a watchlist
/// holds the title ("On Watchlist", "On Halloween", "On 2 watchlists"). With
/// one watchlist or none it toggles at once; with several it opens Save to
/// watchlist, a popover from the button on a regular width and a sheet on a
/// compact one. A toggle to VoiceOver.
struct WatchlistButton: View {
    let title: WatchlistTitle
    /// The watchlists the page said hold the title.
    let said: [Int]?
    var fullWidth = true
    @Environment(WatchlistStore.self) private var watchlists
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let holding = watchlists.holding(title.key, said: said)
        let words = watchlists.buttonWords(title.key, said: said)
        // Read here, in the body, so the popover opens when the store's picker changes.
        let picker = watchlists.picker.flatMap { $0.fromButton && $0.title.key == title.key ? $0 : nil }
        Button(words) { Task { await watchlists.press(title, said: said, fromButton: true) } }
            .buttonStyle(BellButtonStyle(on: !holding.isEmpty, fullWidth: fullWidth, symbol: "bookmark"))
            .accessibilityLabel(WatchlistWords.buttonLabel(words, title: title.title))
            .accessibilityAddTraits(holding.isEmpty ? .isToggle : [.isToggle, .isSelected])
            .accessibilityIdentifier("watchlist-button")
            .popover(item: Binding(get: { picker }, set: { if $0 == nil { picker?.close() } }), arrowEdge: .bottom) { picker in
                // A popover from the button where there is room; a compact width draws it as a sheet, sized as one.
                WatchlistPickerView(picker: picker, popover: sizeClass.isWide)
                    .presentationCompactAdaptation(.sheet)
            }
            .onChange(of: said, initial: true) { _, said in watchlists.pageRead(title.key, holding: said) }
    }
}

/// A card's long press: Add to Watchlist or Remove from Watchlist with one
/// watchlist (or none yet), Save to watchlist… with several. Nothing for a
/// title TMDB hasn't matched, nor below API level 33.
struct WatchlistMenuButton: View {
    let title: WatchlistTitle?
    /// The card's `watchlisted`.
    let said: Bool
    @Environment(WatchlistStore.self) private var watchlists

    init(_ item: BrowseItem) {
        title = item.watchlistTitle
        said = item.watchlisted
    }

    init(title: WatchlistTitle?, said: Bool) {
        self.title = title
        self.said = said
    }

    var body: some View {
        if let title, let item = watchlists.menuItem(title.key, said: said) {
            Button { Task { await watchlists.press(title, cardSaid: said) } } label: {
                Label(item.words, systemImage: item.systemImage)
            }
        }
    }
}

/// Save to watchlist: every watchlist with a check, each saved or removed
/// as it is pressed, then New watchlist… (or, at 25, why not). New watchlist
/// asks for a name; Create waits for one, and saves the title to it.
struct WatchlistPickerView: View {
    let picker: WatchlistPicker
    /// From the button on a regular width: its own size.
    var popover = false

    var body: some View {
        VStack(spacing: 0) {
            bar
            ScrollView {
                Group {
                    if picker.naming { naming } else { checks }
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
                .padding(.bottom, 20)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .frame(width: popover ? 360 : nil)
        .frame(minHeight: popover ? 300 : nil)
        .background(Palette.sheet.ignoresSafeArea())
        .presentationBackground(Palette.sheet)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("watchlist-picker")
    }

    /// Done, or Cancel and Create while naming, either side of the title and what it saves.
    private var bar: some View {
        HStack(alignment: .center) {
            Group {
                if picker.naming {
                    Button(WatchlistWords.cancel) { picker.cancelNaming() }
                        .keyboardShortcut(.cancelAction)
                } else {
                    // Holds the width only: a bare Color.clear would take the sheet's height.
                    Color.clear.frame(height: 0)
                }
            }
            .frame(width: 80, alignment: .leading)
            VStack(spacing: 1) {
                Text(picker.naming ? WatchlistWords.newWatchlist : WatchlistWords.pickerTitle)
                    .font(.headline)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(picker.title.title).font(.footnote).foregroundStyle(Palette.muted).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            Group {
                if picker.naming {
                    Button(WatchlistWords.create) { Task { await picker.create() } }
                        .fontWeight(.semibold)
                        .disabled(!picker.canCreate)
                        .accessibilityIdentifier("watchlist-create")
                } else {
                    Button(WatchlistWords.done) { picker.close() }
                        .fontWeight(.semibold)
                        .keyboardShortcut(.defaultAction)
                        .accessibilityIdentifier("watchlist-done")
                }
            }
            .frame(width: 80, alignment: .trailing)
        }
        .foregroundStyle(Palette.gold)
        .padding(.horizontal, 16)
        .padding(.top, popover ? 14 : 18)
        .padding(.bottom, 12)
    }

    private var checks: some View {
        RoundedGroup {
            ForEach(picker.rows) { row in
                Button { Task { await picker.toggle(row) } } label: {
                    HStack(spacing: 12) {
                        Image(systemName: row.checked ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22))
                            .foregroundStyle(row.checked ? Palette.gold : Palette.faint)
                            .accessibilityHidden(true)
                        Text(row.name).font(.body.weight(.medium)).foregroundStyle(Palette.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(row.count).font(.subheadline).foregroundStyle(Palette.muted)
                    }
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(row.checked ? [.isToggle, .isSelected] : .isToggle)
                .accessibilityIdentifier("watchlist-row-\(row.id)")
            }
            if let limit = picker.atLimit {
                Text(limit)
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Button { picker.startNaming() } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "plus")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Palette.gold)
                            .frame(width: 22)
                            .accessibilityHidden(true)
                        Text(WatchlistWords.newWatchlistRow).font(.body.weight(.medium)).foregroundStyle(Palette.gold)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityIdentifier("watchlist-new")
            }
        }
    }

    private var naming: some View {
        WatchlistNameField(name: Binding(get: { picker.name }, set: { picker.name = $0 }), problem: picker.problem) {
            Task { await picker.create() }
        }
    }
}

/// "Saved to Watchlist" with Undo, over the bottom of the page, while it
/// shows; and why a change went back, as a note. Drawn by the tab on screen
/// only, so it is said once.
struct WatchlistBannerView: View {
    @Environment(WatchlistStore.self) private var watchlists

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            if let banner = watchlists.banner {
                HStack(spacing: 10) {
                    Image(systemName: banner.saved ? "bookmark.fill" : "bookmark")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Palette.gold)
                        .accessibilityHidden(true)
                    Text(banner.text)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(WatchlistWords.undo) { Task { await watchlists.undoBanner() } }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.gold)
                        .frame(minWidth: 64, minHeight: 44)
                        .accessibilityIdentifier("watchlist-undo")
                }
                .padding(.leading, 14)
                .padding(.trailing, 4)
                .frame(maxWidth: 560, minHeight: 52)
                .background(Color(hex: 0x2C2C30).opacity(0.97), in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.14)))
                .shadow(color: .black.opacity(0.55), radius: 15, y: 10)
                .padding(.horizontal, 12)
                .padding(.bottom, 10)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("watchlist-banner")
                .task(id: banner.id) { AccessibilityNotification.Announcement(banner.text).post() }
            }
        }
        .frame(maxWidth: .infinity)
        .animation(.easeOut(duration: 0.25), value: watchlists.banner)
        .messageToast(Binding(get: { watchlists.message }, set: { watchlists.message = $0 }))
    }
}
