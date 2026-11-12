import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Parts the request screens share: the Show missing switch, and the bottom
// sheet (or, on iPad, form sheet) the request and report forms sit in.

/// "Show missing" and its switch, gold when on.
struct ShowMissingSwitch: View {
    let on: Bool
    let set: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { on }, set: set)) {
            Text("Show missing").scaledFont(15, weight: .medium).foregroundStyle(Palette.text)
        }
        .toggleStyle(.switch)
        .tint(Palette.gold)
        .fixedSize()
    }
}

/// The phone's bottom sheet, as tall as its content and draggable to full
/// height; on iPad (`centred`) a form sheet 560 pt wide in the middle of the
/// screen, as the episode sheet is.
struct FormSheet<Content: View>: View {
    var centred = false
    @ViewBuilder var content: () -> Content
    @State private var height: CGFloat = 480

    var body: some View {
        if centred {
            // As tall as its content when it opens (a form sheet keeps that
            // height), scrolling only when that is taller than the screen allows.
            ViewThatFits(in: .vertical) {
                padded.fixedSize(horizontal: false, vertical: true)
                ScrollView { padded }.scrollBounceBehavior(.basedOnSize)
            }
            // Shorter content (the confirmation) sits at the top.
            .frame(maxHeight: .infinity, alignment: .top)
            .frame(idealWidth: 560)
            .presentationSizing(.fitted)
            .presentationBackground(Palette.sheet)
            .presentationCornerRadius(20)
        } else {
            ScrollView {
                padded.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
            }
            .scrollBounceBehavior(.basedOnSize)
            .presentationDetents([.height(height), .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Palette.sheet)
            .presentationCornerRadius(20)
        }
    }

    private var padded: some View {
        VStack(alignment: .leading, spacing: 14) { content() }
            .padding(.horizontal, 16)
            .padding(.top, centred ? 18 : 22)
            .padding(.bottom, centred ? 18 : 12)
    }
}

/// The round close button at a sheet's top right.
struct SheetCloseButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Palette.muted)
                .frame(width: 30, height: 30)
                .background(Palette.outline, in: Circle())
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .padding(.top, -7)
        .padding(.trailing, -7)
        .accessibilityLabel("Close")
    }
}

/// The gold-ticked confirmation after a request or report.
struct SentCard: View {
    let text: String
    /// "Your requests" in the text links to the list.
    var onYourRequests: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Palette.ground)
                .frame(width: 32, height: 32)
                .background(Palette.gold, in: Circle())
                .accessibilityHidden(true)
            message
                .scaledFont(16, weight: .semibold)
                .foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(Palette.outline.opacity(0.75), in: RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder private var message: some View {
        if let onYourRequests, let range = text.range(of: "Your requests") {
            Button(action: onYourRequests) {
                Text("\(String(text[..<range.lowerBound]))\(Text("Your requests").foregroundStyle(Palette.gold))\(String(text[range.upperBound...]))")
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens Your requests")
        } else {
            Text(text)
        }
    }
}

/// The grey note with an info icon: who approves.
struct InfoNote: View {
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "info.circle").font(.system(size: 14)).accessibilityHidden(true)
            Text(text).scaledFont(13).fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Palette.muted)
    }
}
