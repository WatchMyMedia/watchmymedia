import Foundation
import WMMModel

/// The people in the sample library's credits, each with a TMDB person ID,
/// so a cast row opens their titles (``FakeLibraryRepository/person(id:)``).
/// Every name is fictional. Ines Varga is in every show and in two films,
/// so her page has movies and shows; the rest are in one or the other.
public enum FakePeople {
    public static let inesVarga = 9_001
    public static let tomasReyes = 9_002
    public static let adaezeBell = 9_003
    public static let hollisKade = 9_011
    public static let miraOkonjo = 9_012
    public static let petraLind = 9_021

    /// A show's billed cast.
    public static func showCast(showID: Int) -> [Person] {
        [
            Person(name: "Ines Varga", role: showID == 100 ? "Maren Holt" : "Dr Lund", personID: inesVarga),
            Person(name: "Tomas Reyes", role: "Harbourmaster", personID: tomasReyes),
            Person(name: "Adaeze Bell", role: "Wren", personID: adaezeBell),
        ]
    }

    /// A movie's billed cast: Ines Varga plays in two of them.
    public static func movieCast(movieID: Int) -> [Person] {
        var cast = [
            Person(name: "Hollis Kade", role: "Captain Ruth Abernathy", personID: hollisKade),
            Person(name: "Mira Okonjo", role: "Dov", personID: miraOkonjo),
        ]
        if movieID == 502 || movieID == 504 { cast.append(Person(name: "Ines Varga", role: movieID == 502 ? "Io Vance" : "the pilot", personID: inesVarga)) }
        return cast
    }

    /// A movie's director and writer, with their jobs.
    public static func movieCrew(movieID: Int) -> [Person] {
        [Person(name: "Petra Lind", role: "Director", personID: petraLind), Person(name: "Petra Lind", role: "Writer", personID: petraLind)]
    }

    /// The person's titles in the sample library, newest first, with the
    /// part they play; nil for someone the library doesn't have.
    public static func person(id: Int) -> PersonDetail? {
        var name: String?
        var movies: [PersonTitle] = []
        var shows: [PersonTitle] = []
        for movie in FakeData.movies {
            let credits = movieCast(movieID: movie.id) + movieCrew(movieID: movie.id)
            guard let credit = credits.first(where: { $0.personID == id }) else { continue }
            name = credit.name
            let parts = movieCast(movieID: movie.id).filter { $0.personID == id }.compactMap(\.role)
            movies.append(PersonTitle(item: movie, character: parts.isEmpty ? nil : parts.joined(separator: " / ")))
        }
        for show in FakeData.shows {
            guard let credit = showCast(showID: show.id).first(where: { $0.personID == id }) else { continue }
            name = credit.name
            shows.append(PersonTitle(item: show, character: credit.role))
        }
        guard let name else { return nil }
        let newest: (PersonTitle, PersonTitle) -> Bool = { ($0.item.year ?? 0) > ($1.item.year ?? 0) }
        return PersonDetail(personID: id, name: name, movies: movies.sorted(by: newest), shows: shows.sorted(by: newest))
    }
}
