import SafariServices
import SwiftUI

/// A web page in an in-app Safari: the server's sign-in page while pairing,
/// or its web page while it has no accounts.
struct InAppSafari: UIViewControllerRepresentable {
    let url: URL
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.preferredControlTintColor = UIColor(red: 1, green: 0.714, blue: 0.071, alpha: 1)
        controller.preferredBarTintColor = UIColor(red: 0.059, green: 0.059, blue: 0.063, alpha: 1)
        controller.dismissButtonStyle = .close
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {
        context.coordinator.onDone = onDone
    }

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    final class Coordinator: NSObject, SFSafariViewControllerDelegate {
        var onDone: () -> Void

        init(onDone: @escaping () -> Void) { self.onDone = onDone }

        func safariViewControllerDidFinish(_ controller: SFSafariViewController) { onDone() }
    }
}

extension View {
    /// Shows `url` in an in-app Safari while it is set.
    func inAppSafari(url: Binding<URL?>) -> some View {
        fullScreenCover(isPresented: Binding(get: { url.wrappedValue != nil }, set: { if !$0 { url.wrappedValue = nil } })) {
            if let page = url.wrappedValue {
                InAppSafari(url: page) { url.wrappedValue = nil }
                    .ignoresSafeArea()
            }
        }
    }
}
