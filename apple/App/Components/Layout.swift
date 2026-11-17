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
    /// From this width a page puts its parts side by side, as in landscape
    /// (1194 pt on the 11-inch iPad, 867 pt beside the iPhone Duo's vertical
    /// bar); narrower, they stack, as in portrait (834 pt on the 11-inch iPad).
    static let sideBySide: CGFloat = 860
    /// From this width, the iPad held wide: room for a page's controls in one
    /// row. Side by side but narrower (the iPhone Duo's 867 pt), they take two.
    static let landscape: CGFloat = 1000
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

extension View {
    /// A row that scrolls sideways stops at the system's vertical bar (the
    /// iPhone Duo's tab bar down one edge, DuoOuterHome) instead of running on
    /// under its glass. Where there is no such bar (an iPhone in landscape, by
    /// its camera) the row still runs to the edge of the screen.
    func stopsAtVerticalBar() -> some View { modifier(VerticalBarClip()) }
}

private struct VerticalBarClip: ViewModifier {
    func body(content: Content) -> some View {
        // `toolbarVerticalEdge` is iOS 27.1's (Xcode 27.1 and its Swift 6.4.1).
        #if compiler(>=6.4.1)
        if #available(iOS 27.1, *) {
            content.modifier(VerticalBarEdgeClip())
        } else {
            content
        }
        #else
        content
        #endif
    }
}

#if compiler(>=6.4.1)
@available(iOS 27.1, *)
private struct VerticalBarEdgeClip: ViewModifier {
    @Environment(\.toolbarVerticalEdge) private var edge

    func body(content: Content) -> some View {
        // Always clipped, so the row keeps its identity (and its scroll) when
        // the bar comes and goes with a fold; without a bar the clip reaches
        // far past both edges. Up and down it never clips a card's shadow. A
        // clip, not a mask, which would draw every row offscreen.
        content.clipShape(RowClip(outset: edge == nil ? 4000 : 0))
    }
}

/// The row's own frame, `outset` past it at each side and 60 pt above and below.
private struct RowClip: Shape {
    var outset: CGFloat

    func path(in rect: CGRect) -> Path {
        Path(rect.insetBy(dx: -outset, dy: -60))
    }
}
#endif
