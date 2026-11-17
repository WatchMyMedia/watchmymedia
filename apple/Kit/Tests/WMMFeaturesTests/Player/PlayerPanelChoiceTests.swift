import Testing
@testable import WMMFeatures

/// PLAN-IPHONE-DUO.md §4.4: the panel beside the picture is the tab under the fold, and back.
@Suite struct PlayerPanelChoiceTests {
    @Test func nothingChosenShowsUpNextUnderTheFold() {
        let choice = PlayerPanelChoice()
        #expect(choice.panel == nil)
        #expect(choice.tab(episodes: true, offline: false) == .upNext)
        // A film has no season: its audio and subtitles.
        #expect(choice.tab(episodes: false, offline: false) == .tracks)
    }

    @Test func thePanelBesideThePictureIsTheTabUnderTheFold() {
        #expect(PlayerPanelChoice(panel: .episodes).tab(episodes: true, offline: false) == .upNext)
        #expect(PlayerPanelChoice(panel: .options(.audio)).tab(episodes: true, offline: false) == .tracks)
        #expect(PlayerPanelChoice(panel: .options(.subtitles)).tab(episodes: true, offline: false) == .tracks)
        #expect(PlayerPanelChoice(panel: .options(.quality)).tab(episodes: true, offline: false) == .quality)
    }

    @Test func theTabChosenUnderTheFoldIsThePanelWhenItOpensFlat() {
        var choice = PlayerPanelChoice()
        choice.choose(.quality)
        #expect(choice.panel == .options(.quality))
        choice.choose(.tracks)
        #expect(choice.panel == .options(.audio))
        choice.choose(.upNext)
        #expect(choice.panel == .episodes)
    }

    @Test func aDownloadHasNoQualityTab() {
        #expect(PlayerPanelChoice.tabs(episodes: true, offline: true) == [.upNext, .tracks])
        #expect(PlayerPanelChoice.tabs(episodes: false, offline: false) == [.tracks, .quality])
        #expect(PlayerPanelChoice(panel: .options(.quality)).tab(episodes: true, offline: true) == .tracks)
    }

    @Test func eachButtonOpensAndClosesItsPanel() {
        var choice = PlayerPanelChoice()
        choice.toggle(.options(.audio))
        #expect(choice.isOpen(.options(.subtitles)))
        #expect(!choice.isOpen(.episodes))
        choice.toggle(.episodes)
        #expect(choice.panel == .episodes)
        choice.toggle(.episodes)
        #expect(choice.panel == nil)
        choice.open(.options(.quality))
        choice.close()
        #expect(choice.panel == nil)
    }

    @Test func theTitleOverTheTabletopControls() {
        let episode = tabletopTitle(title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark")
        #expect(episode.kicker == "Harbor Lights · S03 E07")
        #expect(episode.name == "The Long Dark")
        let film = tabletopTitle(title: "The Paper Kite", subtitle: nil)
        #expect(film.kicker == nil && film.name == "The Paper Kite")
        let other = tabletopTitle(title: "Harbor Lights", subtitle: "Special")
        #expect(other.kicker == "Harbor Lights" && other.name == "Special")
    }
}
