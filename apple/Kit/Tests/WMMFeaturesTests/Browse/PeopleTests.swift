import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// The cast as people who each open their titles in the library (API level 34).
@MainActor
@Suite struct PeopleTests {
    init() { FakeLatency.duration = .zero }

    @Test func theRowHasTheCastThenTheCrewEachOnce() {
        let cast = [Person(name: "Mara Lindqvist", role: "Io Vance", personID: 1), Person(name: "Tobias Renn", role: "Dov", personID: 2)]
        let crew = [Person(name: "Petra Lind", role: "Director", personID: 3), Person(name: "Petra Lind", role: "Writer", personID: 3),
                    Person(name: "Tobias Renn", role: "Producer", personID: 2)]
        let row = CastPerson.row(cast: cast, crew: crew)
        #expect(row.map(\.name) == ["Mara Lindqvist", "Tobias Renn", "Petra Lind"])
        #expect(row.map(\.role) == ["Io Vance", "Dov", "Director, Writer"], "someone in the cast keeps their part")
        #expect(row[2].accessibilityLabel == "Petra Lind, Director, Writer")
        #expect(row[0].accessibilityLabel == "Mara Lindqvist, as Io Vance")
        #expect(row[0].route == .person(id: 1, name: "Mara Lindqvist"))
    }

    @Test func peopleTheServerDoesntKnowMakeNoRow() {
        #expect(CastPerson.row(cast: [Person(name: "Ada Brook", role: "June")]).isEmpty, "a face that takes focus must open something")
    }

    @Test func aPersonsPageListsTheirTitlesNewestFirstWithTheirParts() async throws {
        let model = PersonModel(library: FakeLibraryRepository(), navigator: Navigator(), personID: FakePeople.inesVarga, name: "Ines Varga")
        #expect(model.heading == "Ines Varga")
        await model.retry()
        let titles = model.titles
        #expect(titles.count == 18, "two films and every show")
        let years = titles.compactMap(\.item.year)
        #expect(years == years.sorted(by: >))
        #expect(model.countLine == "18 titles in your library: 2 movies and 16 shows")
        let harbor = try #require(titles.first { $0.item.title == "Harbor Lights" })
        #expect(model.line(for: harbor) == "TV show · as Maren Holt")
        let film = try #require(titles.first { $0.item.id == 502 })
        #expect(model.line(for: film) == "2026 · as Io Vance")
    }

    @Test func choosingATitleOpensItsPage() async throws {
        let navigator = Navigator()
        let model = PersonModel(library: FakeLibraryRepository(), navigator: navigator, personID: FakePeople.hollisKade, name: "Hollis Kade")
        await model.retry()
        let first = try #require(model.titles.first)
        model.open(first)
        #expect(navigator.path(for: .home) == [.movie(id: first.item.id)])
    }

    @Test func aPersonsPageFromAnotherServerOpensItsTitlesThere() async throws {
        // A cast row on a page opened in place from Cabin server opens the person there,
        // so their titles are Cabin server's, and open in place too.
        let services = AppServices.fake(several: true, redesign: true)
        let navigator = Navigator()
        let cabin = FakeServers.cabin.id
        let model = PersonModel(library: services.on(cabin).library, navigator: navigator, personID: FakeCabin.odileMarsh, name: "Odile Marsh")
        await model.retry()
        #expect(model.titles.map(\.item.title).contains("The Harbor Choir"), "Cabin server's titles")
        let first = try #require(model.titles.first)
        model.open(first)
        #expect(navigator.path(for: .home) == [.onServer(cabin, .movie(id: first.item.id))])
        navigator.open(CastPerson(personID: FakeCabin.corinVale, name: "Corin Vale").route, on: cabin)
        #expect(navigator.path(for: .home).last == .onServer(cabin, .person(id: FakeCabin.corinVale, name: "Corin Vale")))
    }

    @Test func aFaceWithoutAPictureShowsTheirInitials() {
        #expect(personInitials("Ines Varga") == "IV")
        #expect(personInitials("Adaeze") == "A")
        #expect(personInitials("Mara de la Cruz") == "MD", "the first two words")
    }

    @Test func nobodyTheLibraryHasSaysSo() async {
        let model = PersonModel(library: FakeLibraryRepository(), navigator: Navigator(), personID: 1, name: "Nobody")
        await model.retry()
        #expect(model.state == .failed("Nobody by that name is in the library."))
    }

    @Test func theCountLineNamesWhatThereIs() {
        #expect(personCountLine(movies: 6, shows: 3) == "9 titles in your library: 6 movies and 3 shows")
        #expect(personCountLine(movies: 1, shows: 0) == "1 movie in your library")
        #expect(personCountLine(movies: 0, shows: 2) == "2 shows in your library")
    }
}
