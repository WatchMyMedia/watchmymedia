import Testing
import WMMFakes
import WMMModel

/// The fakes say which device they run on, so an iPad with `-fakes` reads "This iPad".
@MainActor
@Suite struct FakeDeviceTests {
    @Test func anIPhoneByDefault() {
        let services = AppServices.fake()
        #expect(services.client.model == "iPhone 16")
        #expect(services.client.name == "Sam’s iPhone")
        #expect(services.auth.deviceName == "Sam’s iPhone")
    }

    @Test func anIPadNamesItselfEverywhere() async throws {
        FakeLatency.duration = .zero
        let services = AppServices.fake(client: .fakePad)
        #expect(services.client.model.hasPrefix("iPad"))
        #expect(services.client.name == "Sam’s iPad")
        #expect(services.auth.deviceName == "Sam’s iPad")
        let current = try #require(try await services.auth.sessions(all: false).first { $0.current })
        #expect(current.name == "Sam’s iPad")
        #expect(current.model == "iPad Pro 11″")
        #expect(current.product == "WatchMyMedia for iOS")
        #expect(current.platform == "ios")
    }
}
