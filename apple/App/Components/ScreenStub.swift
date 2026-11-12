import SwiftUI
import WMMUI

/// A placeholder for a screen not built yet.
struct ScreenStub: View {
    let title: String
    var detail = "Not built yet."

    var body: some View {
        EmptyState(systemImage: "hammer", title: title, message: detail)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .groundBackground()
            .navigationTitle(title)
    }
}
