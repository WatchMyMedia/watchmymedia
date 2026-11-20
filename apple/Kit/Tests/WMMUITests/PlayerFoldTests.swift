import CoreGraphics
import Testing
@testable import WMMUI

/// The players on a folding device.
@Suite struct PlayerWindowTests {
    @Test func onlyAWindowRegularBothWaysTurnsWithTheDevice() {
        // An iPad, or the Duo's inner screen held either way.
        #expect(PlayerWindow.of(regularWidth: true, regularHeight: true) == .wide)
        // An iPhone upright, the Duo's outer screen, or a narrow window beside another app.
        #expect(PlayerWindow.of(regularWidth: false, regularHeight: true) == .phone)
        // An iPhone or the outer screen turned.
        #expect(PlayerWindow.of(regularWidth: false, regularHeight: false) == .phone)
        // The largest iPhones turned: regular width, but still a phone's, or the
        // player would stop asking for landscape once it had turned them.
        #expect(PlayerWindow.of(regularWidth: true, regularHeight: false) == .phone)
    }
}

@Suite struct PlayerFoldTests {
    @Test func tabletopIsAutomaticForEveryPlayer() {
        var film = PlayerFold(hasBook: false)
        var live = PlayerFold(hasBook: true)
        #expect(film.layout == .flat && live.layout == .flat)
        film.follow(.tabletop)
        live.follow(.tabletop)
        #expect(film.layout == .tabletop && live.layout == .tabletop)
    }

    @Test func onlyLiveTVHasABook() {
        var film = PlayerFold(hasBook: false)
        var live = PlayerFold(hasBook: true)
        film.follow(.book)
        live.follow(.book)
        #expect(film.layout == .flat)
        #expect(live.layout == .book)
    }

    @Test func theWholeScreenLastsUntilTheHingeNextChanges() {
        var fold = PlayerFold(hasBook: true)
        fold.follow(.tabletop)
        fold.useWholeScreen()
        #expect(fold.layout == .flat)
        // The same posture again (the probe reports what it already said) keeps it.
        fold.follow(.tabletop)
        #expect(fold.layout == .flat)
        // Opened flat and half open again: the tabletop layout is back.
        fold.follow(.flat)
        #expect(fold.layout == .flat)
        fold.follow(.tabletop)
        #expect(fold.layout == .tabletop)
    }

    @Test func turningTheDeviceIsAHingeChangeToo() {
        var fold = PlayerFold(hasBook: true)
        fold.follow(.tabletop)
        fold.useWholeScreen()
        fold.follow(.book)
        #expect(fold.layout == .book)
    }

    @Test func theWholeScreenOnAFlatPlayerChangesNothingLater() {
        var fold = PlayerFold(hasBook: false)
        fold.useWholeScreen()
        fold.follow(.tabletop)
        #expect(fold.layout == .tabletop)
        // Held like a book the film player is flat already: nothing to leave.
        var book = PlayerFold(hasBook: false)
        book.follow(.book)
        book.useWholeScreen()
        book.follow(.tabletop)
        #expect(book.layout == .tabletop)
    }
}

@Suite struct SafeInsetsTests {
    private let size = CGSize(width: 669, height: 951)
    private let safe = SafeInsets(top: 40, leading: 10, bottom: 20, trailing: 12)

    @Test func aPieceKeepsOnlyTheEdgesItTouches() {
        let upper = CGRect(x: 0, y: 0, width: 669, height: 475.5)
        #expect(safe.within(upper, of: size) == SafeInsets(top: 40, leading: 10, bottom: 0, trailing: 12))
        let lower = CGRect(x: 0, y: 475.5, width: 669, height: 475.5)
        #expect(safe.within(lower, of: size) == SafeInsets(top: 0, leading: 10, bottom: 20, trailing: 12))
    }

    @Test func aPieceInsideTheSafeAreaHasNone() {
        #expect(safe.within(CGRect(x: 100, y: 100, width: 200, height: 200), of: size) == .zero)
    }
}

@Suite struct PlayerArrangementTests {
    private let wide = CGSize(width: 951, height: 669)
    private let upright = CGSize(width: 669, height: 951)
    private let outer = CGSize(width: 678, height: 466)

    @Test func aPhonesWindowIsAllPicture() {
        let a = PlayerArrangement.film(size: outer, safe: SafeInsets(leading: 62), wide: false, fold: .flat, panelOpen: true)
        #expect(a.kind == .full)
        #expect(a.picture == CGRect(origin: .zero, size: outer))
        #expect(a.panel == nil && a.below == nil)
    }

    @Test func theInnerScreenHeldWideIsAllPictureUntilAPanelOpens() {
        let closed = PlayerArrangement.film(size: wide, safe: .zero, wide: true, fold: .flat, panelOpen: false)
        #expect(closed.kind == .full && closed.picture.size == wide)
        // DuoOpenPlayer: the panel 320 wide, the picture keeps 16:9 in the 631 left.
        let open = PlayerArrangement.film(size: wide, safe: .zero, wide: true, fold: .flat, panelOpen: true)
        #expect(open.kind == .beside)
        #expect(open.picture == CGRect(x: 0, y: 0, width: 631, height: 669))
        #expect(open.panel == CGRect(x: 631, y: 0, width: 320, height: 669))
    }

    @Test func thePanelTakesTheSafeAreaOnItsSide() {
        let open = PlayerArrangement.film(size: wide, safe: SafeInsets(trailing: 84), wide: true, fold: .flat, panelOpen: true)
        #expect(open.panel == CGRect(x: 547, y: 0, width: 404, height: 669))
        #expect(open.picture.width == 547)
    }

    @Test func panelsGoBesideThePictureOnlyOnANarrowWideWindowHeldWide() {
        #expect(PlayerArrangement.panelsBeside(size: wide, wide: true))
        #expect(!PlayerArrangement.panelsBeside(size: upright, wide: true))
        #expect(!PlayerArrangement.panelsBeside(size: outer, wide: false))
        #expect(!PlayerArrangement.panelsBeside(size: CGSize(width: 1210, height: 834), wide: true))
    }

    @Test func onlyTheOuterScreenTurnedHasTheRowOfOptions() {
        #expect(PlayerArrangement.chipRow(size: outer, wide: false))
        // The largest iPhone turned, and the outer screen upright.
        #expect(!PlayerArrangement.chipRow(size: CGSize(width: 956, height: 440), wide: false))
        #expect(!PlayerArrangement.chipRow(size: CGSize(width: 466, height: 678), wide: false))
        // A narrow window beside another app, and a wide window (its own buttons).
        #expect(!PlayerArrangement.chipRow(size: CGSize(width: 507, height: 1032), wide: false))
        #expect(!PlayerArrangement.chipRow(size: wide, wide: true))
    }

    @Test func anIPadFullScreenLaysThePanelOverThePicture() {
        let pad = CGSize(width: 1210, height: 834)
        let a = PlayerArrangement.film(size: pad, safe: .zero, wide: true, fold: .flat, panelOpen: true)
        #expect(a.kind == .full && a.picture.size == pad)
    }

    @Test func uprightThePictureSitsOverTheDetails() {
        // DuoTallPlayer, and an iPad held upright.
        let a = PlayerArrangement.film(size: upright, safe: SafeInsets(top: 40), wide: true, fold: .flat, panelOpen: true)
        #expect(a.kind == .portrait)
        #expect(a.picture == CGRect(x: 0, y: 40, width: 669, height: 669.0 * 9 / 16))
        #expect(a.below == CGRect(x: 0, y: a.picture.maxY, width: 669, height: 951 - a.picture.maxY))
    }

    @Test func tabletopPutsThePictureAboveTheFold() {
        for wide in [true, false] {
            let a = PlayerArrangement.film(size: upright, safe: SafeInsets(top: 40), wide: wide, fold: .tabletop, panelOpen: true)
            #expect(a.kind == .tabletop)
            #expect(a.picture == CGRect(x: 0, y: 0, width: 669, height: 475.5))
            #expect(a.below == CGRect(x: 0, y: 475.5, width: 669, height: 475.5))
            #expect(a.panel == nil)
        }
    }

    @Test func liveTVFlatIsThePictureOverTheChannelsUpright() {
        let tall = PlayerArrangement.live(size: CGSize(width: 402, height: 874), safe: SafeInsets(top: 62), fold: .flat)
        #expect(tall.kind == .portrait && tall.picture.minY == 62)
        let turned = PlayerArrangement.live(size: CGSize(width: 874, height: 402), safe: .zero, fold: .flat)
        #expect(turned.kind == .full)
    }

    @Test func liveTVLikeABookKeepsEverythingOffTheFold() {
        let a = PlayerArrangement.live(size: wide, safe: .zero, fold: .book)
        #expect(a.kind == .book)
        // DuoBookLive: a 16:9 card at the top of the left page.
        #expect(a.picture == CGRect(x: 22, y: 20, width: 425.5, height: 239))
        #expect(a.panel == CGRect(x: 475.5, y: 0, width: 475.5, height: 669))
        let below = try! #require(a.below)
        #expect(below.minY == a.picture.maxY + 12 && below.maxY == 669 - 22)
        #expect(a.picture.maxX < 475.5 && below.maxX < 475.5)
    }

    @Test func liveTVOnATableSplitsAtTheFold() {
        let a = PlayerArrangement.live(size: upright, safe: .zero, fold: .tabletop)
        #expect(a.kind == .tabletop)
        #expect(a.picture.maxY == 475.5 && a.below?.minY == 475.5)
    }
}
