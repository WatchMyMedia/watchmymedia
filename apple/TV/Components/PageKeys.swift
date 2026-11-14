import SwiftUI
import UIKit

extension View {
    /// Page Up (-1) and Page Down (+1), on remotes that have them (a TV's own
    /// remote through HDMI-CEC, some universal remotes; the Siri Remote has
    /// none). SwiftUI has no handler for these presses, so a tap recognizer
    /// on the window takes them while `enabled`: keep it to while this view's
    /// content has focus, so other screens and the player get their own.
    func onPageKeys(enabled: Bool, perform action: @escaping (Int) -> Void) -> some View {
        background(PageKeysReceiver(enabled: enabled, action: action).allowsHitTesting(false).accessibilityHidden(true))
    }
}

private struct PageKeysReceiver: UIViewRepresentable {
    let enabled: Bool
    let action: (Int) -> Void

    func makeUIView(context: Context) -> Receiver {
        let view = Receiver()
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: Receiver, context: Context) {
        view.action = action
        view.enabled = enabled
    }

    static func dismantleUIView(_ view: Receiver, coordinator: ()) {
        view.detach()
    }

    final class Receiver: UIView {
        var action: ((Int) -> Void)?
        var enabled = false {
            didSet { recognizers.forEach { $0.isEnabled = enabled } }
        }
        private var recognizers: [UITapGestureRecognizer] = []

        override func didMoveToWindow() {
            super.didMoveToWindow()
            detach()
            guard let window else { return }
            for (type, selector) in [(UIPress.PressType.pageUp, #selector(pageUp)), (.pageDown, #selector(pageDown))] {
                let recognizer = UITapGestureRecognizer(target: self, action: selector)
                recognizer.allowedPressTypes = [NSNumber(value: type.rawValue)]
                recognizer.allowedTouchTypes = []
                recognizer.isEnabled = enabled
                window.addGestureRecognizer(recognizer)
                recognizers.append(recognizer)
            }
        }

        func detach() {
            recognizers.forEach { $0.view?.removeGestureRecognizer($0) }
            recognizers = []
        }

        @objc private func pageUp() { if enabled { action?(-1) } }
        @objc private func pageDown() { if enabled { action?(1) } }
    }
}
