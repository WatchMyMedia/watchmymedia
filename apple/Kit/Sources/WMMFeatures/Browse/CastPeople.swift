import Foundation
import WMMModel

/// One person in a title's row of people (Apple TV's movie and show pages):
/// the cast first, in billing order, then the crew, each person once with
/// every job they did ("Director, Writer").
public struct CastPerson: Hashable, Sendable, Identifiable {
    public var personID: Int
    public var name: String
    /// The part ("Maren Holt"), or the crew's jobs ("Director, Writer").
    public var role: String?
    public var profilePath: String?

    public var id: Int { personID }

    /// Where choosing them goes: their movies and shows in the library.
    public var route: Route { .person(id: personID, name: name) }

    /// What VoiceOver says: "Mara Lindqvist, as Io Vance" or "Petra Lind, Director".
    public var accessibilityLabel: String {
        guard let role else { return name }
        return isCrew ? "\(name), \(role)" : "\(name), as \(role)"
    }

    /// From the crew rather than the cast.
    public var isCrew = false

    public init(personID: Int, name: String, role: String? = nil, profilePath: String? = nil, isCrew: Bool = false) {
        self.personID = personID
        self.name = name
        self.role = role
        self.profilePath = profilePath
        self.isCrew = isCrew
    }

    /// The row a title's page draws: the people the server knows by ID,
    /// the cast first, then the crew not already in it, each once. Empty
    /// when none has an ID (a server below API level 34, or a title whose
    /// credits it hasn't fetched again yet): the page then shows the cast
    /// as names that take no focus, as it did, since a face that takes focus
    /// must open something.
    public static func row(cast: [Person], crew: [Person] = []) -> [CastPerson] {
        var people: [CastPerson] = []
        var seen: [Int: Int] = [:]
        for person in cast {
            guard let id = person.personID, seen[id] == nil else { continue }
            seen[id] = people.count
            people.append(CastPerson(personID: id, name: person.name, role: person.role, profilePath: person.profilePath))
        }
        for person in crew {
            guard let id = person.personID else { continue }
            if let index = seen[id] {
                // Someone who acts and directs is in the row once, as the cast has them.
                guard people[index].isCrew, let job = person.role, !(people[index].role ?? "").contains(job) else { continue }
                people[index].role = [people[index].role, job].compactMap { $0 }.joined(separator: ", ")
                continue
            }
            seen[id] = people.count
            people.append(CastPerson(personID: id, name: person.name, role: person.role, profilePath: person.profilePath, isCrew: true))
        }
        return people
    }
}
