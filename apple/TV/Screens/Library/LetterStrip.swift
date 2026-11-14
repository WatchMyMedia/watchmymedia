import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What has focus on a library grid with its letter index: a poster, or a
/// letter of the index.
enum GridFocus: Hashable {
    case item(Int)
    case letter(String)
}

/// The letter index at the grid's right edge (TvLibraryAZ.dc.html): # and A
/// to Z, the letters with no title dimmed and passed over. The letter of the
/// focused poster is gold; with focus on the index, the focused letter lifts
/// and a bubble beside it says how many titles it holds. The rules are
/// `LetterIndex`'s; the screen moves focus (`LibraryScreen`).
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

    static let letterHeight: CGFloat = 27
    static let top: CGFloat = 250
    private static let padding: CGFloat = 10

    private var focusedLetter: String? {
        if case .letter(let letter) = focus.wrappedValue { return letter }
        return nil
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if let letter = focusedLetter, let entry = index.entry(letter),
               let place = index.entries.firstIndex(where: { $0.letter == letter }) {
                bubble(entry)
                    .padding(.top, Self.padding + CGFloat(place) * Self.letterHeight + Self.letterHeight / 2 - 50)
                    .padding(.trailing, 120 - 44)
                    .allowsHitTesting(false)
            }
            strip
        }
        .padding(.top, Self.top)
        .padding(.trailing, 44)
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
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Jump to a letter")
        .accessibilityIdentifier("letter-index")
    }

    private func bubble(_ entry: LetterIndex.Entry) -> some View {
        HStack(spacing: 22) {
            Text(entry.letter)
                .font(.system(size: 64, weight: .heavy))
                .foregroundStyle(Palette.gold)
                .frame(width: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(index.countLine(entry, noun: noun))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Palette.text)
                Text("Select or swipe left to go there")
                    .font(.system(size: 17))
                    .foregroundStyle(Palette.muted)
            }
            .fixedSize()
        }
        .padding(.leading, 22)
        .padding(.trailing, 30)
        .padding(.vertical, 18)
        .frame(height: 100)
        .background(Color(red: 28 / 255, green: 28 / 255, blue: 31 / 255).opacity(0.96), in: RoundedRectangle(cornerRadius: 24))
        .shadow(color: .black.opacity(0.7), radius: 35, y: 30)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("letter-bubble")
    }
}

/// A letter of the index (the board's `.lt`): grey, gold for the focused
/// poster's letter, dim when it has no titles; focused, white and lifted
/// with the gold rim.
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
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(focused ? Palette.ground : empty ? Color(hex: 0x4A4A4F) : current ? Palette.gold : Color(hex: 0xBDBDBD))
                .frame(width: 40, height: LetterStrip.letterHeight)
                .background(focused ? Palette.text : .clear, in: RoundedRectangle(cornerRadius: 8))
                .overlay {
                    if focused { RoundedRectangle(cornerRadius: 8).stroke(Palette.gold, lineWidth: 3) }
                }
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: 15, y: 10)
                .scaleEffect(focused ? 1.25 : 1)
                .zIndex(focused ? 1 : 0)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}
