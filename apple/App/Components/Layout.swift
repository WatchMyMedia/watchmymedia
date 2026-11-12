import SwiftUI

/// The measures of the iPad artboards (design/mockups/ipad-revised/), for a regular
/// width: an iPad full screen, or in a Split View wider than half. A compact
/// width (every iPhone, Slide Over, a 50/50 Split View) keeps the iPhone
/// screens (PLAN-IOS.md §11.1).
enum PadLayout {
    /// Space at the sides of an iPad screen; the iPhone's is 16.
    static let margin: CGFloat = 24
    /// Posters retain a readable minimum as the library pane changes width.
    static let posterMinimum: CGFloat = 170
    /// From this width Show and Movie put their parts side by side, as in
    /// landscape (1194 pt on the 11-inch iPad); narrower, they stack, as in portrait.
    static let sideBySide: CGFloat = 1000
    static let detailColumn: CGFloat = 440
    static let singleColumn: CGFloat = 720
    static let homeMaximum: CGFloat = 1440
}

extension Optional where Wrapped == UserInterfaceSizeClass {
    /// A regular width: the iPad layouts.
    var isWide: Bool { self == .regular }
}

extension View {
    /// The tab bar hides under a pushed page on iPhone; on iPad it stays at the top, as the artboards draw it.
    func tabBarHiddenOnPhone(_ sizeClass: UserInterfaceSizeClass?) -> some View {
        toolbar(sizeClass.isWide ? .automatic : .hidden, for: .tabBar)
    }
}
