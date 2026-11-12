import SwiftUI
import WMMModel

/// Shows `content` once loaded, a spinner while loading, and "Couldn't load
/// this" with Try again when it failed (android/mobile/.../States.kt).
public struct LoadableView<Value, Content: View>: View {
    let state: Loadable<Value>
    let retry: () async -> Void
    let content: (Value) -> Content

    public init(_ state: Loadable<Value>, retry: @escaping () async -> Void, @ViewBuilder content: @escaping (Value) -> Content) {
        self.state = state
        self.retry = retry
        self.content = content
    }

    public var body: some View {
        switch state {
        case .loading:
            ProgressView()
                .tint(Palette.muted)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded(let value):
            content(value)
        case .failed(let message):
            ErrorState(message: message, retry: retry)
        }
    }
}

/// "Couldn't load this", the reason, and Try again.
public struct ErrorState: View {
    let title: String
    let message: String
    let retry: (() async -> Void)?

    public init(title: String = "Couldn't load this", message: String, retry: (() async -> Void)? = nil) {
        self.title = title
        self.message = message
        self.retry = retry
    }

    public var body: some View {
        VStack(spacing: 10) {
            Text(title).font(.title3.weight(.bold)).foregroundStyle(Palette.text)
            Text(message).font(.subheadline).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            if let retry {
                Button("Try again") { Task { await retry() } }
                    .buttonStyle(PrimaryButtonStyle(height: 44, fullWidth: false))
                    .padding(.top, 6)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A quiet empty state: an icon, a title and a line.
public struct EmptyState: View {
    let systemImage: String
    let title: String
    let message: String

    public init(systemImage: String, title: String, message: String) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
    }

    public var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(Palette.faint)
                .frame(width: 64, height: 64)
                .background(Palette.surfaceRaised, in: Circle())
            Text(title).font(.title3.weight(.bold)).foregroundStyle(Palette.text)
            Text(message).font(.subheadline).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

/// A row heading: "Recently added" with a muted "TV Shows" after it.
public struct SectionHeading: View {
    let title: String
    let detail: String?

    public init(_ title: String, detail: String? = nil) {
        self.title = title
        self.detail = detail
    }

    public var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(title).font(.title3.weight(.semibold)).foregroundStyle(Palette.text)
            if let detail { Text(detail).font(.footnote.weight(.medium)).foregroundStyle(Palette.muted) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}
