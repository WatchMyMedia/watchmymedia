import CoreGraphics

/// The window a player shows in, as its orientation rule sees it
/// (PLAN-IPHONE-DUO.md §4.3).
///
/// Only a window regular both ways is `wide`: an iPad's, or the iPhone Duo's
/// inner screen held either way (951 × 669, 669 × 951). The largest iPhones
/// are regular width in landscape but compact in height, so they stay a
/// phone's while the player has turned them; the Duo's outer screen, and a
/// narrow window beside another app, are compact.
public enum PlayerWindow: Sendable, Equatable {
    /// The film player turns it to landscape while it shows, and Live TV lets
    /// it turn any way but upside down; the rest of the app keeps it upright.
    case phone
    /// Turns with the device: the players ask for no orientation.
    case wide

    public static func of(regularWidth: Bool, regularHeight: Bool) -> PlayerWindow {
        regularWidth && regularHeight ? .wide : .phone
    }
}

/// Which folded layout a player draws, and *Use the whole screen*
/// (PLAN-IPHONE-DUO.md §3, decisions 4, 5 and 8).
///
/// Half open with the fold across, the film player and Live TV take the
/// tabletop layout by themselves. Held like a book, Live TV puts a page each
/// side of the fold; the film player stays the flat player. *Use the whole
/// screen* goes back to the flat player until the hinge next changes the
/// posture (opened flat, closed, or turned), when the posture's layout
/// applies again.
public struct PlayerFold: Sendable, Equatable {
    public enum Layout: Sendable, Equatable {
        case flat, tabletop, book
    }

    /// The posture the hinge reports now.
    public private(set) var posture: FoldPosture = .flat
    /// Whether this player has a book layout (Live TV) or stays flat held like a book (the film player).
    public let hasBook: Bool
    /// The folded posture *Use the whole screen* left, while it lasts.
    private var leftPosture: FoldPosture?

    public init(hasBook: Bool) {
        self.hasBook = hasBook
    }

    /// The hinge reported `posture`.
    public mutating func follow(_ posture: FoldPosture) {
        guard posture != self.posture else { return }
        self.posture = posture
        leftPosture = nil
    }

    /// The flat player, until the hinge next changes the posture.
    public mutating func useWholeScreen() {
        guard layout != .flat else { return }
        leftPosture = posture
    }

    public var layout: Layout {
        guard leftPosture != posture else { return .flat }
        switch posture {
        case .flat: return .flat
        case .tabletop: return .tabletop
        case .book: return hasBook ? .book : .flat
        }
    }
}

/// Insets from a window's edges: the safe area, or the part of it inside one
/// piece of a layout.
public struct SafeInsets: Sendable, Equatable {
    public var top: CGFloat
    public var leading: CGFloat
    public var bottom: CGFloat
    public var trailing: CGFloat

    public init(top: CGFloat = 0, leading: CGFloat = 0, bottom: CGFloat = 0, trailing: CGFloat = 0) {
        self.top = top
        self.leading = leading
        self.bottom = bottom
        self.trailing = trailing
    }

    public static let zero = SafeInsets()

    /// The part of these insets, from a window of `size`, that falls inside
    /// `rect`, measured from `rect`'s own edges: a piece away from an edge
    /// has none on that side.
    public func within(_ rect: CGRect, of size: CGSize) -> SafeInsets {
        SafeInsets(top: max(top - rect.minY, 0),
                   leading: max(leading - rect.minX, 0),
                   bottom: max(rect.maxY - (size.height - bottom), 0),
                   trailing: max(rect.maxX - (size.width - trailing), 0))
    }
}

/// Where a player's pieces go in its window (PLAN-IPHONE-DUO.md §4.4): the
/// picture (the video with whatever is drawn over it), a panel beside it,
/// and what sits under it or on the other page. The picture is always the
/// same view moved and resized, never another one, so a fold or a panel
/// re-lays the controls and never rebuilds the video.
public struct PlayerArrangement: Sendable, Equatable {
    public enum Kind: Sendable, Equatable {
        /// The picture fills the window (an iPhone or the Duo's outer screen
        /// turned, an iPad or the inner screen held wide).
        case full
        /// Taller than wide on a wide window, or Live TV upright on a phone:
        /// the picture at the top, the details under it.
        case portrait
        /// The panel beside the picture, which keeps 16:9 in what is left (`DuoOpenPlayer`).
        case beside
        /// Half open with the fold across: the picture on the upright half,
        /// the controls on the half lying flat (`DuoTabletopPlayer`, `DuoTabletopLive`).
        case tabletop
        /// Half open like a book: the picture and its controls on the left
        /// page, the list on the right (`DuoBookLive`).
        case book
    }

    public var kind: Kind
    /// The picture's area.
    public var picture: CGRect
    /// The panel beside the picture (`beside`), or the right page (`book`).
    public var panel: CGRect?
    /// Under the picture: the details (`portrait`), the controls on the flat
    /// half (`tabletop`), or the controls on the left page (`book`).
    public var below: CGRect?

    /// The panel beside the picture.
    public static let panelWidth: CGFloat = 320
    /// Below this width a panel goes beside the picture, where laid over it
    /// it would hide a third of it or more (the Duo's inner screen is 951
    /// wide); an iPad full screen, 1133 wide or more, lays it over the
    /// picture as before.
    public static let besideBelowWidth: CGFloat = 1000

    /// Whether the film player's panels open beside the picture in a flat
    /// window of `size`: a wide window held wide and narrower than
    /// ``besideBelowWidth`` (the Duo's inner screen). Elsewhere the options
    /// open over the picture, as on an iPad, or in the sheet, as on an iPhone.
    public static func panelsBeside(size: CGSize, wide: Bool) -> Bool {
        wide && size.width >= size.height && size.width < besideBelowWidth
    }

    /// Whether a phone's window shows Audio, Subtitles, Quality and Episodes
    /// as a row under the scrubber (`DuoOuterPlayer`) rather than "…" beside
    /// it: held wide with room for the row, as the iPhone Duo's outer screen
    /// turned is (678 × 466). An iPhone turned is 440 pt tall at most, and a
    /// narrow window beside another app is taller than wide.
    public static func chipRow(size: CGSize, wide: Bool) -> Bool {
        !wide && size.width > size.height && size.height >= 460
    }

    /// The film player's arrangement. `wide` is a ``PlayerWindow/wide``
    /// window (the iPad's sizes); `panelOpen` is a panel chosen beside the
    /// picture.
    public static func film(size: CGSize, safe: SafeInsets, wide: Bool, fold: PlayerFold.Layout, panelOpen: Bool) -> PlayerArrangement {
        let whole = CGRect(origin: .zero, size: size)
        if fold == .tabletop {
            let halves = FoldPosture.tabletop.halves(of: size)
            return PlayerArrangement(kind: .tabletop, picture: halves.first, below: halves.second)
        }
        guard wide else { return PlayerArrangement(kind: .full, picture: whole) }
        if size.height > size.width { return portrait(size: size, safe: safe) }
        if panelOpen, panelsBeside(size: size, wide: wide) {
            let panel = panelWidth + safe.trailing
            return PlayerArrangement(kind: .beside,
                                     picture: CGRect(x: 0, y: 0, width: max(size.width - panel, 0), height: size.height),
                                     panel: CGRect(x: max(size.width - panel, 0), y: 0, width: min(panel, size.width), height: size.height))
        }
        return PlayerArrangement(kind: .full, picture: whole)
    }

    /// Live TV's arrangement: folded, a half each side of the fold;
    /// flat, the picture over the channels when the window is taller than
    /// wide, and the whole window otherwise.
    public static func live(size: CGSize, safe: SafeInsets, fold: PlayerFold.Layout) -> PlayerArrangement {
        switch fold {
        case .tabletop:
            let halves = FoldPosture.tabletop.halves(of: size)
            return PlayerArrangement(kind: .tabletop, picture: halves.first, below: halves.second)
        case .book:
            let halves = FoldPosture.book.halves(of: size)
            let page = halves.first
            // As the board: a card at the top of the left page, 22 pt in
            // (more where the safe area is), 28 pt short of the fold.
            let x = page.minX + max(22, safe.leading)
            let top = max(20, safe.top)
            let width = max(page.maxX - 28 - x, 0)
            let height = min((width * 9 / 16).rounded(), page.height / 2)
            let picture = CGRect(x: x, y: top, width: width, height: height)
            let belowTop = picture.maxY + 12
            let below = CGRect(x: x, y: belowTop, width: width, height: max(page.maxY - max(22, safe.bottom) - belowTop, 0))
            return PlayerArrangement(kind: .book, picture: picture, panel: halves.second, below: below)
        case .flat:
            guard size.height > size.width else { return PlayerArrangement(kind: .full, picture: CGRect(origin: .zero, size: size)) }
            return portrait(size: size, safe: safe)
        }
    }

    /// The picture at 16:9 under the top of the safe area (the camera), the details under it.
    private static func portrait(size: CGSize, safe: SafeInsets) -> PlayerArrangement {
        let height = min(size.width * 9 / 16, size.height / 2)
        let picture = CGRect(x: 0, y: safe.top, width: size.width, height: height)
        return PlayerArrangement(kind: .portrait, picture: picture,
                                 below: CGRect(x: 0, y: picture.maxY, width: size.width, height: max(size.height - picture.maxY, 0)))
    }
}
