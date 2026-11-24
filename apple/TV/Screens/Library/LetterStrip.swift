import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What has focus on a library grid with its letter index: a poster, or a
/// letter of the index.
enum GridFocus: Hashable {
    case item(Int)
    case letter(String)
    /// A poster from another server (one card), by ``BrowseItem/cardID``: its id is that server's.
    case other(String)
}

/// The letter index at the grid's right edge, inside the safe area: # and A
/// to Z, the letters with no title dimmed and passed over. The letter of the
/// focused poster is gold; with focus on the index, the focused letter grows
/// and turns white and a bubble beside it says how many titles it holds.
/// The rules are `LetterIndex`'s; the screen moves focus (`LibraryScreen`).
struct LetterStrip: View {
    let index: LetterIndex
    let noun: String
    /// The focused poster's letter, gold while the grid has focus.
    let current: String?
    /// Whether focus can come here: only from the grid, so a row's end doesn't reach it.
    let active: Bool
    var focus: FocusState<GridFocus?>.Binding
    let select: (LetterIndex.Entry) -> Void
    let back: () -> Void

    static let letterHeight: CGFloat = 28
    /// From the top of the screen: under the grid's controls once the tab bar has scrolled away.
    static let top: CGFloat = 190
    private static let padding: CGFloat = 10
    /// The strip's width: a 40 pt letter with 6 pt either side.
    private static let width: CGFloat = 52
    private static let bubbleHeight: CGFloat = 110

    private var focusedLetter: String? {
        if case .letter(let letter) = focus.wrappedValue { return letter }
        return nil
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if let letter = focusedLetter, let entry = index.entry(letter),
               let place = index.entries.firstIndex(where: { $0.letter == letter }) {
                bubble(entry)
                    .padding(.top, Self.padding + CGFloat(place) * Self.letterHeight + Self.letterHeight / 2 - Self.bubbleHeight / 2)
                    .padding(.trailing, Self.width + 16)
                    .allowsHitTesting(false)
            }
            strip
        }
        .padding(.top, Self.top)
        // The letters end at the safe area's edge, 80 pt in.
        .padding(.trailing, TVLayout.gutter - 6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .ignoresSafeArea()
    }

    private var strip: some View {
        VStack(spacing: 0) {
            ForEach(index.entries) { entry in
                Button { select(entry) } label: { Text(entry.letter) }
                    .buttonStyle(LetterStyle(empty: entry.isEmpty, current: entry.letter == current))
                    .disabled(entry.isEmpty || !active)
                    .focused(focus, equals: .letter(entry.letter))
                    .accessibilityLabel(index.accessibilityLabel(entry, noun: noun))
                    .accessibilityAddTraits(entry.letter == current ? .isSelected : [])
                    .accessibilityIdentifier("letter-\(entry.letter)")
            }
        }
        .padding(.vertical, Self.padding)
        .padding(.horizontal, 6)
        .background {
            if focusedLetter != nil {
                RoundedRectangle(cornerRadius: 26)
                    .fill(.white.opacity(0.10))
                    .shadow(color: .black.opacity(0.5), radius: 25, y: 20)
            }
        }
        .onExitCommand(perform: back)
        // Left lands on the letter's first title. The focus engine usually moves there itself;
        // where it finds nothing level with the letter (a gap between rows), this does.
        .onMoveCommand { direction in
            guard direction == .left, let letter = focusedLetter, let entry = index.entry(letter) else { return }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(150))
                if focusedLetter == letter { select(entry) }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Jump to a letter")
        .accessibilityIdentifier("letter-index")
    }

    /// The letter in gold and how many titles it holds: "B · 42 movies".
    private func bubble(_ entry: LetterIndex.Entry) -> some View {
        HStack(spacing: 22) {
            Text(entry.letter)
                .font(.tv(.title2))
                .foregroundStyle(Palette.gold)
                .frame(minWidth: 56)
            Text(index.countLine(entry, noun: noun))
                .font(.tv(.body, .semibold))
                .foregroundStyle(Palette.text)
                .fixedSize()
        }
        .padding(.leading, 26)
        .padding(.trailing, 34)
        .frame(height: Self.bubbleHeight)
        .background(Color(red: 28 / 255, green: 28 / 255, blue: 31 / 255).opacity(0.96), in: RoundedRectangle(cornerRadius: 24))
        .shadow(color: .black.opacity(0.7), radius: 35, y: 30)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("letter-bubble")
    }
}

/// A letter of the index: light grey, gold for the focused poster's letter,
/// dim when it has no titles; focused, it grows and turns white.
private struct LetterStyle: ButtonStyle {
    let empty: Bool
    let current: Bool

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, empty: empty, current: current)
    }

    private struct Styled: View {
        let configuration: Configuration
        let empty: Bool
        let current: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.tv(.caption2, .bold))
                .foregroundStyle(focused ? Palette.ground : empty ? Color(hex: 0x6F6F75) : current ? Palette.gold : Palette.textSecondary)
                .frame(width: 40, height: LetterStrip.letterHeight)
                .background(focused ? Palette.text : .clear, in: RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: 15, y: 10)
                .scaleEffect(focused ? 1.2 : 1)
                .zIndex(focused ? 1 : 0)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}
