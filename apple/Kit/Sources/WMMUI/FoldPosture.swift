import CoreGraphics

/// The hinge as the app reads it (UIKit's `UIHinge.Status`, iOS 27.1).
/// `none` is every device without one and every system before iOS 27.1.
public enum HingeState: Sendable, Equatable {
    case none, closed, partlyOpen, fullyOpen
}

/// How a window sits on a folding device (PLAN-IPHONE-DUO.md §4.2).
///
/// Nothing reports where the fold is. On the iPhone Duo it runs through the
/// middle of the inner screen, which reports a regular width held either way;
/// the outer screen, and a window sharing the inner screen with another app,
/// are compact. So a folded posture is only reported for a regular-width
/// window, which then fills the inner screen, and the fold is the middle of
/// the window.
public enum FoldPosture: Sendable, Equatable {
    /// Closed, fully open, or no hinge: lay out as usual.
    case flat
    /// Half open with the fold across: the upper half stands up, the lower half lies flat.
    case tabletop
    /// Half open with the fold down the middle, like a book.
    case book

    public static func of(hinge: HingeState, window size: CGSize, regularWidth: Bool) -> FoldPosture {
        guard hinge == .partlyOpen, regularWidth, size.width > 0, size.height > 0 else { return .flat }
        return size.height > size.width ? .tabletop : .book
    }

    public var isFolded: Bool { self != .flat }

    /// The two halves of a window of `size`, either side of the fold: the
    /// upper then the lower one (tabletop), or the left then the right one
    /// (book). A flat window has no fold, and gives itself twice.
    public func halves(of size: CGSize) -> (first: CGRect, second: CGRect) {
        switch self {
        case .flat:
            let whole = CGRect(origin: .zero, size: size)
            return (whole, whole)
        case .tabletop:
            let half = size.height / 2
            return (CGRect(x: 0, y: 0, width: size.width, height: half),
                    CGRect(x: 0, y: half, width: size.width, height: size.height - half))
        case .book:
            let half = size.width / 2
            return (CGRect(x: 0, y: 0, width: half, height: size.height),
                    CGRect(x: half, y: 0, width: size.width - half, height: size.height))
        }
    }
}
