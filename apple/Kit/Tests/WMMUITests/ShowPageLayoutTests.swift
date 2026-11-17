import CoreGraphics
import Testing
@testable import WMMUI

/// PLAN-IPHONE-DUO.md §3: the show page on the iPhone, the iPad and the iPhone Duo.
@Suite struct ShowPageLayoutTests {
    @Test func aCompactWidthStacksWhateverItsSizeOrPosture() {
        for width: CGFloat in [393, 382, 466, 951] {
            for posture in [FoldPosture.flat, .book, .tabletop] {
                #expect(ShowPageLayout.of(regularWidth: false, width: width, posture: posture) == .stacked)
            }
        }
    }

    @Test func theIPadKeepsItsLayoutInBothOrientations() {
        // The 11-inch iPad in portrait and landscape, the 13-inch in both, the iPad mini held wide.
        for width: CGFloat in [834, 1194, 1032, 1376, 1133, 744] {
            #expect(ShowPageLayout.of(regularWidth: true, width: width, posture: .flat) == .pad)
        }
    }

    @Test func theDuoHeldWideGetsTwoPanesFrom860() {
        #expect(ShowPageLayout.of(regularWidth: true, width: 867, posture: .flat) == .panes)
        #expect(ShowPageLayout.of(regularWidth: true, width: 860, posture: .flat) == .panes)
        #expect(ShowPageLayout.of(regularWidth: true, width: 859, posture: .flat) == .pad)
        #expect(ShowPageLayout.of(regularWidth: true, width: 999, posture: .flat) == .panes)
        #expect(ShowPageLayout.of(regularWidth: true, width: 1000, posture: .flat) == .pad)
        // Upright, the inner screen is the iPad's portrait.
        #expect(ShowPageLayout.of(regularWidth: true, width: 669, posture: .flat) == .pad)
    }

    @Test func halfOpenLikeABookPutsAPageEachSide() {
        #expect(ShowPageLayout.of(regularWidth: true, width: 867, posture: .book) == .book)
        // Stood on a table, the show page keeps its upright layout.
        #expect(ShowPageLayout.of(regularWidth: true, width: 669, posture: .tabletop) == .pad)
    }

    @Test func onlyPanesAndABookSitSideBySide() {
        #expect(ShowPageLayout.panes.sideBySide && ShowPageLayout.book.sideBySide)
        #expect(!ShowPageLayout.pad.sideBySide && !ShowPageLayout.stacked.sideBySide)
    }

    @Test func theEpisodesColumnIs420BesideTheSeason() {
        // 24 pt, the 420 pt column, half the gap; the season takes the rest.
        #expect(ShowPageLayout.panes.episodeSide(width: 867, leadingInset: 0, trailingInset: 84) == 456)
        #expect(ShowPageLayout.panes.episodeSide(width: 999, leadingInset: 84, trailingInset: 0) == 456)
        #expect(ShowPageLayout.pad.episodeSide(width: 1194, leadingInset: 0, trailingInset: 0) == nil)
        #expect(ShowPageLayout.stacked.episodeSide(width: 382, leadingInset: 0, trailingInset: 84) == nil)
    }

    @Test func aBooksEpisodePageEndsAtTheFoldWhicheverSideTheBarIs() {
        // 951 wide: the fold at 475.5, the vertical bar's 84 pt on the right or on the left.
        #expect(ShowPageLayout.book.episodeSide(width: 867, leadingInset: 0, trailingInset: 84) == 475.5)
        #expect(ShowPageLayout.book.episodeSide(width: 867, leadingInset: 84, trailingInset: 0) == 391.5)
        // Never past the page's own edges.
        #expect(ShowPageLayout.book.episodeSide(width: 300, leadingInset: 600, trailingInset: 0) == 0)
    }
}
