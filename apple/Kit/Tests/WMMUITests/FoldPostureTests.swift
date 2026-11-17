import CoreGraphics
import Testing
@testable import WMMUI

/// PLAN-IPHONE-DUO.md §4.2: how a window sits on a folding device.
@Suite struct FoldPostureTests {
    private let upright = CGSize(width: 669, height: 951)
    private let wide = CGSize(width: 951, height: 669)

    @Test func noHingeOrAFlatHingeIsFlat() {
        for hinge in [HingeState.none, .closed, .fullyOpen] {
            #expect(FoldPosture.of(hinge: hinge, window: upright, regularWidth: true) == .flat)
            #expect(FoldPosture.of(hinge: hinge, window: wide, regularWidth: true) == .flat)
        }
    }

    @Test func halfOpenUprightIsTabletopAndHalfOpenWideIsABook() {
        #expect(FoldPosture.of(hinge: .partlyOpen, window: upright, regularWidth: true) == .tabletop)
        #expect(FoldPosture.of(hinge: .partlyOpen, window: wide, regularWidth: true) == .book)
    }

    @Test func halfOpenInACompactWindowIsFlat() {
        // The outer screen, or the inner one shared with another app: the fold isn't the window's middle.
        #expect(FoldPosture.of(hinge: .partlyOpen, window: CGSize(width: 466, height: 678), regularWidth: false) == .flat)
        #expect(FoldPosture.of(hinge: .partlyOpen, window: CGSize(width: 475, height: 669), regularWidth: false) == .flat)
    }

    @Test func aWindowNotYetMeasuredIsFlat() {
        #expect(FoldPosture.of(hinge: .partlyOpen, window: .zero, regularWidth: true) == .flat)
    }

    @Test func theFoldSplitsTheWindowInHalf() {
        let tabletop = FoldPosture.tabletop.halves(of: upright)
        #expect(tabletop.first == CGRect(x: 0, y: 0, width: 669, height: 475.5))
        #expect(tabletop.second == CGRect(x: 0, y: 475.5, width: 669, height: 475.5))
        let book = FoldPosture.book.halves(of: wide)
        #expect(book.first.width == 475.5 && book.second.minX == 475.5)
        let flat = FoldPosture.flat.halves(of: wide)
        #expect(flat.first == flat.second && flat.first.size == wide)
    }
}
