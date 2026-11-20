import SwiftUI
import UIKit
import WMMUI

extension EnvironmentValues {
    /// How the window sits on a folding device. Set
    /// once, at the root, by ``SwiftUICore/View/readsFoldPosture()``. Anything
    /// but `.flat` means the fold runs through the middle of the window: across
    /// it (`.tabletop`) or down it (`.book`).
    @Entry var foldPosture: FoldPosture = .flat
}

extension View {
    /// Reads the hinge and the window's size at the root and hands the screens
    /// below `\.foldPosture`.
    func readsFoldPosture() -> some View { modifier(FoldPostureReader()) }
}

private struct FoldPostureReader: ViewModifier {
    @State private var posture = FoldPosture.flat

    func body(content: Content) -> some View {
        content
            .environment(\.foldPosture, posture)
            .background {
                FoldProbe { posture = $0 }
                    .ignoresSafeArea()
                    .accessibilityHidden(true)
            }
    }
}

/// A view as large as the window that follows the hinge, the window's size
/// and its size class, and reports the posture they make.
private struct FoldProbe: UIViewRepresentable {
    let report: (FoldPosture) -> Void

    func makeUIView(context: Context) -> FoldProbeView {
        let view = FoldProbeView()
        view.report = report
        return view
    }

    func updateUIView(_ view: FoldProbeView, context: Context) {
        view.report = report
    }
}

private final class FoldProbeView: UIView {
    var report: (FoldPosture) -> Void = { _ in }
    private var posture = FoldPosture.flat
    private var hinge = HingeState.none {
        didSet { if hinge != oldValue { apply() } }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        // UIKit's `UIHingeInteraction` (iOS 27.1) reports the state it starts in
        // as well as each change, unlike SwiftUI's `onHingeChange`. Built only
        // with an SDK that has it (Swift 6.4.1 is Xcode 27.1's); with an older
        // one, or on an older system, the hinge stays `.none` and every window
        // is flat.
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            addInteraction(UIHingeInteraction { [weak self] _, update in
                let state = HingeState(update.hinge?.status)
                MainActor.assumeIsolated { self?.hinge = state }
            })
        }
        #endif
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        apply()
    }

    // A fold or a rotation resizes the window, and with it this view.
    override func layoutSubviews() {
        super.layoutSubviews()
        apply()
    }

    private func apply() {
        guard let window else { return }
        let found = FoldPosture.of(hinge: hinge, window: window.bounds.size,
                                   regularWidth: window.traitCollection.horizontalSizeClass == .regular)
        guard found != posture else { return }
        posture = found
        // Not during UIKit's layout pass: SwiftUI's state changes on the next turn.
        Task { @MainActor [weak self] in self?.report(found) }
    }
}

#if compiler(>=6.4.1)
@available(iOS 27.1, *)
private extension HingeState {
    init(_ status: UIHinge.Status?) {
        switch status {
        case .closed?: self = .closed
        case .partiallyOpen?: self = .partlyOpen
        case .fullyOpen?: self = .fullyOpen
        default: self = .none
        }
    }
}
#endif
