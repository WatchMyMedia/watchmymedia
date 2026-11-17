import CoreGraphics

/// How the show page lays out the episode on top and its season
/// (PLAN-SHOW-PAGE.md §4.4, PLAN-IPHONE-DUO.md §3).
public enum ShowPageLayout: Sendable, Equatable {
    /// A compact width (iPhone, the iPhone Duo's outer screen): the episode
    /// over the season's tabs and its row of episodes (ShowPage).
    case stacked
    /// The iPad's: the still beside the episode's facts, the season's tabs
    /// and row under them (Pad2ShowPage, Pad2ShowPortrait).
    case pad
    /// The episode in a column beside the season's tabs and its episodes in
    /// a list (DuoOpenShow): the iPhone Duo's inner screen held wide.
    case panes
    /// Half open like a book: the episode on the leading page, the season on
    /// the other, nothing across the fold (DuoBookShow).
    case book

    /// From this content width the episode and its season fit side by side:
    /// the iPhone Duo held wide leaves 867 pt beside its vertical bar, and the
    /// 11-inch iPad in portrait (834 pt) still stacks. The app's
    /// `PadLayout.sideBySide`, where every page puts its parts side by side.
    public static let panesFrom: CGFloat = 860
    /// From this width the iPad's layout fits its first screen: the still at
    /// 480 pt beside the facts, the season's row under them.
    public static let padFrom: CGFloat = 1000
    /// The episode's side beside the season (DuoOpenShow): a 420 pt column,
    /// 24 pt in from the edge, and half the 24 pt between the two.
    public static let episodeColumn: CGFloat = 456

    /// The layout for a page `width` points wide (the safe area's), in a
    /// regular width or not, held as `posture` says.
    public static func of(regularWidth: Bool, width: CGFloat, posture: FoldPosture) -> ShowPageLayout {
        guard regularWidth else { return .stacked }
        if posture == .book { return .book }
        return (panesFrom..<padFrom).contains(width) ? .panes : .pad
    }

    /// Whether the episode and the season are side by side, each scrolling on its own.
    public var sideBySide: Bool { self == .panes || self == .book }

    /// The width of the episode's side, from the page's leading edge: the
    /// fold, for a book, or the episode's column. The page is `width` wide
    /// inside the safe area, which leaves `leadingInset` and `trailingInset`
    /// of the window (the vertical bar, on one side). Nil when the two
    /// aren't side by side.
    public func episodeSide(width: CGFloat, leadingInset: CGFloat, trailingInset: CGFloat) -> CGFloat? {
        switch self {
        case .stacked, .pad:
            return nil
        case .panes:
            return min(Self.episodeColumn, width)
        case .book:
            // The fold is the middle of the window, which takes in the insets.
            let window = CGSize(width: leadingInset + width + trailingInset, height: 1)
            let fold = FoldPosture.book.halves(of: window).first.maxX - leadingInset
            return min(max(fold, 0), width)
        }
    }
}
