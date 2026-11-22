import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Watchlists on Apple TV: the Watchlist button on a title's page, the
// long-press menus' item and Save to watchlist, the whole screen. Apple TV
// says nothing after a change: the button's words changing is enough. What
// they say and do is the Kit's ``WatchlistStore``.

/// The Watchlist button: "Watchlist", or gold "On Watchlist", "On
/// Halloween", "On 2 watchlists" with a filled bookmark. With one watchlist
/// or none Select saves or removes at once; with several it opens Save to
/// watchlist.
struct TVWatchlistButton: View {
    let title: WatchlistTitle
    /// The watchlists the page said hold the title.
    let said: [Int]?
    @Environment(WatchlistStore.self) private var watchlists

    var body: some View {
        let holding = watchlists.holding(title.key, said: said)
        let words = watchlists.buttonWords(title.key, said: said)
        // The icon inside the Text: a focused button lifts an Image of its own (apple/AGENTS.md).
        Button { Task { await watchlists.press(title, said: said, fromButton: true) } } label: {
            Text("\(Image(systemName: holding.isEmpty ? "bookmark" : "bookmark.fill"))  \(words)")
        }
        .buttonStyle(TVButtonStyle(kind: holding.isEmpty ? .secondary : .goldText, height: 62, fontSize: 22))
        .accessibilityLabel(WatchlistWords.buttonLabel(words, title: title.title))
        .accessibilityAddTraits(holding.isEmpty ? .isToggle : [.isToggle, .isSelected])
        .accessibilityIdentifier("watchlist-button")
        .onChange(of: said, initial: true) { _, said in watchlists.pageRead(title.key, holding: said) }
    }
}

/// A card's long press: Add to Watchlist or Remove from Watchlist with one
/// watchlist (or none yet), Save to watchlist… with several.
struct TVWatchlistMenuButton: View {
    let title: WatchlistTitle?
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

/// Save to watchlist on Apple TV: the whole screen, a check beside each
/// watchlist, saved or removed as it is selected, then New watchlist…,
/// which asks for a Name with the keyboard. There is no Done: Menu closes
/// the list, and from the Name goes back to it.
struct TVWatchlistPicker: View {
    let picker: WatchlistPicker
    @FocusState private var focus: Place?

    private enum Place: Hashable {
        case row(Int)
        case new
        case name
        case create
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                Text(WatchlistWords.pickerTitle)
                    .font(.system(size: 56, weight: .heavy))
                    .tracking(-1.1)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text([picker.title.title, picker.title.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 26))
                    .foregroundStyle(Palette.muted)
            }
            .padding(.bottom, 36)
            if picker.naming { naming } else { checks }
        }
        .padding(.horizontal, 160)
        .padding(.top, 80)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.ground.ignoresSafeArea())
        .onExitCommand {
            if picker.naming { picker.cancelNaming() } else { picker.close() }
        }
        .accessibilityIdentifier("watchlist-picker")
    }

    private var checks: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(WatchlistWords.tvPickerHint).font(.system(size: 20)).foregroundStyle(Palette.faint)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(picker.rows) { row in
                        Button { Task { await picker.toggle(row) } } label: {
                            HStack(spacing: 0) {
                                CheckBox(on: row.checked).padding(.leading, 18)
                                SettingsRow(title: row.name) { Text(row.count) }
                            }
                            .frame(minHeight: 72)
                        }
                        .buttonStyle(SettingsRowStyle())
                        .focused($focus, equals: .row(row.id))
                        // The count in the label: a toggle's value is whether it is on.
                        .accessibilityLabel("\(row.name), \(row.count)")
                        .accessibilityAddTraits(row.checked ? [.isToggle, .isSelected] : .isToggle)
                        .accessibilityIdentifier("watchlist-row-\(row.id)")
                    }
                    Rectangle().fill(.white.opacity(0.12)).frame(height: 1).padding(.vertical, 8).accessibilityHidden(true)
                    if let limit = picker.atLimit {
                        Text(limit).font(.system(size: 22)).foregroundStyle(Palette.muted).padding(.horizontal, 18)
                    } else {
                        Button { picker.startNaming() } label: {
                            SettingsRow(title: WatchlistWords.newWatchlistRow) { Text("\(Image(systemName: "bookmark"))") }
                                .frame(minHeight: 72)
                        }
                        .buttonStyle(SettingsRowStyle())
                        .focused($focus, equals: .new)
                        .accessibilityIdentifier("watchlist-new")
                    }
                }
                .frame(maxWidth: 900, alignment: .leading)
                .padding(.vertical, 20)
            }
            .focusSection()
        }
    }

    private var naming: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(WatchlistWords.newWatchlist).font(.system(size: 34, weight: .bold)).foregroundStyle(Palette.text)
            TextField(WatchlistWords.name, text: Binding(get: { picker.name }, set: { picker.name = $0 }))
                .font(.system(size: 26))
                .frame(maxWidth: 900)
                .focused($focus, equals: .name)
                .onSubmit { Task { await picker.create() } }
                .accessibilityIdentifier("watchlist-name")
            if let problem = picker.problem {
                Text(problem)
                    .font(.system(size: 22))
                    .foregroundStyle(Palette.warning)
                    .accessibilityIdentifier("watchlist-name-problem")
            }
            HStack(spacing: 18) {
                Button(WatchlistWords.cancel) { picker.cancelNaming() }
                    .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                Button(WatchlistWords.create) { Task { await picker.create() } }
                    .buttonStyle(TVButtonStyle(kind: .primary, height: 62, fontSize: 22))
                    .disabled(!picker.canCreate)
                    .focused($focus, equals: .create)
                    .accessibilityIdentifier("watchlist-create")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            Text(WatchlistWords.tvCreateHint(picker.title.title)).font(.system(size: 20)).foregroundStyle(Palette.faint)
        }
        .onAppear { focus = .name }
    }

    /// A gold box with a tick, or an empty one.
    private struct CheckBox: View {
        let on: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            RoundedRectangle(cornerRadius: 8)
                .fill(on ? Palette.gold : .clear)
                .strokeBorder(on ? Palette.gold : (focused ? Palette.ground : Palette.faint), lineWidth: 2)
                .frame(width: 34, height: 34)
                .overlay { if on { Image(systemName: "checkmark").font(.system(size: 20, weight: .bold)).foregroundStyle(Palette.ground) } }
                .accessibilityHidden(true)
        }
    }
}
