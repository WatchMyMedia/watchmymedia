import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Apple TV's sign-in, server picker and Servers call a server by its name
/// and say where it is in words, with no address, version or device line.
@MainActor
@Suite struct TVAccountWordsTests {
    init() { FakeLatency.duration = .zero }

    @Test func theCodeSaysWhatToDoAndHowLongItLasts() {
        let now = Date(timeIntervalSinceReferenceDate: 800_000_000)
        #expect(TVSignInWords.stepOne(link: URL(string: "http://192.168.1.20:27587/link?code=K7M4PQ")!) == "1. Scan the code, or open 192.168.1.20:27587/link")
        #expect(TVSignInWords.stepOne(link: nil) == "1. Scan the code, or open the server’s link page")
        #expect(TVSignInWords.goodFor(expiresAt: now.addingTimeInterval(8 * 60 + 5), now: now) == "Good for 9 more minutes. This Apple TV signs in by itself.")
        #expect(TVSignInWords.goodFor(expiresAt: now.addingTimeInterval(20), now: now) == "Good for 1 more minute. This Apple TV signs in by itself.")
        #expect(TVSignInWords.shownCode("4K79QD") == "4K7 9QD")
        #expect(TVSignInWords.signedInAs("Robin") == "Signed in as Robin")
        #expect(TVSignInWords.gettingLibrary(from: "Harbour House") == "Getting your library from Harbour House")
    }

    @Test func thePickerNamesWhereEachServerIsAndNoAddress() throws {
        let servers = FakeServerRepository()
        let model = ServerPickerModel(servers: servers)
        model.startDiscovery()
        let living = try #require(model.found.first { $0.name == "Living room server" })
        #expect(TVServerPickerWords.line(for: living) == "On this network · Sam is signed in here")
        let office = try #require(model.found.first { $0.name == "Office Mac mini" })
        #expect(TVServerPickerWords.line(for: office) == "On this network")
        let remote = try #require(model.remembered.first { $0.name == "home.watchmy.media" })
        #expect(TVServerPickerWords.line(for: remote) == "Not found on this network · Sam signed in here before")
        let old = try #require(model.remembered.first { $0.name == "Old NAS" })
        #expect(TVServerPickerWords.line(for: old) == "Too old for this app")
        for row in model.found + model.remembered {
            let line = TVServerPickerWords.line(for: row)
            #expect(!line.contains(":27587") && !line.contains("v0."), "no address or version: \(line)")
        }
        var before = office
        before.userName = "Robin"
        #expect(TVServerPickerWords.line(for: before) == "On this network · Robin signed in here before")
    }

    @Test func serversSaysWhoAndWhereAndAFailureOnItsRow() {
        #expect(TVServersWords.signedInOn("Harbour House") == "Signed in on Harbour House")
        #expect(TVServersWords.initial(" robin") == "R")
        #expect(TVServersWords.line(seenHere: true, userName: "Sam") == "On this network · Sam")
        #expect(TVServersWords.line(seenHere: true, userName: nil) == "On this network")
        #expect(TVServersWords.line(seenHere: false, userName: "Sam") == "Signed in as Sam")
        #expect(TVServersWords.line(seenHere: false, userName: " ") == nil)
        let failure = ServersModel.Failure(
            serverID: "srv-den", title: ServersWords.couldNotReach("Den server"),
            body: [ServersWords.mayBeOff, ServersWords.stillOn("Living room server")].joined(separator: " "), downloads: 0
        )
        #expect(TVServersWords.failureLine(failure, stillOn: "Living room server")
                == "Couldn’t reach Den server. It may be off, or only reachable on its own network.")
    }
}
