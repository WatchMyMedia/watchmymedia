import SwiftUI
import WMMFeatures
import WMMUI

/// A pairing code as six boxes with a gap after the third ("K7M 4PQ"), as the
/// pairing card and Link a TV draw it.
struct CodeBoxes: View {
    enum Size { case regular, large }

    let code: String
    var size: Size = .regular
    var dim = false
    /// The box the next character goes in, outlined in gold while typing.
    var focus: Int?
    var boxBackground: Color = Palette.ground

    @ScaledMetric(relativeTo: .title) private var scale: CGFloat = 1

    var body: some View {
        let characters = Array(code)
        HStack(spacing: 6) {
            ForEach(0..<pairingCodeLength, id: \.self) { index in
                if index == 3 { Color.clear.frame(width: size == .large ? 8 : 10, height: 1) }
                let focused = focus == index
                Text(index < characters.count ? String(characters[index]) : " ")
                    .font(.system(size: (size == .large ? 28 : 26) * min(scale, 1.4), weight: .bold).monospacedDigit())
                    .foregroundStyle(dim ? Palette.muted : Palette.text)
                    .frame(width: (size == .large ? 46 : 40) * min(scale, 1.4), height: (size == .large ? 56 : 52) * min(scale, 1.4))
                    .background(boxBackground, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(focused ? Palette.gold : Palette.outlineStrong, lineWidth: focused ? 2 : 1))
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement()
        .accessibilityLabel(pairingCodeAccessibilityLabel(code))
    }
}

/// Typing a code into the boxes: an invisible text field under them takes the
/// keyboard, so pasting and the loose forms ("abc-234") work.
struct CodeField: View {
    let code: String
    let onChange: @MainActor (String) -> Void
    let onSubmit: @MainActor () -> Void
    @FocusState.Binding var focused: Bool

    var body: some View {
        CodeBoxes(code: code, size: .large, focus: focused && code.count < pairingCodeLength ? code.count : nil, boxBackground: Palette.surfaceRaised)
            .accessibilityHidden(true)
            .background {
                // An explicit closure avoids Swift 6.3's compiler crash when
                // adapting the stored main-actor callback to Binding's setter.
                TextField("", text: Binding(get: { code }, set: { onChange($0) }), prompt: Text(verbatim: ""))
                    .focused($focused)
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.continue)
                    .onSubmit(onSubmit)
                    .foregroundStyle(.clear)
                    .tint(.clear)
                    .accessibilityLabel("Code")
                    .accessibilityValue(code.isEmpty ? "empty" : "\(spokenCode(code)), \(code.count) of \(pairingCodeLength) characters")
            }
            .contentShape(Rectangle())
            .onTapGesture { focused = true }
    }
}
