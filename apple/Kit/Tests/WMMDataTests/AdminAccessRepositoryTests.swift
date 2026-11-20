import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Server settings' state comes from GET /api/auth/status.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct AdminAccessRepositoryTests {
    let h = Harness()
    var auth: any AuthRepository { h.services.auth }

    private func status(_ fields: String, admin: Bool = true) -> Reply {
        .json(#"{"setupRequired":false,\#(fields),"user":{"id":2,"name":"viewer","isAdmin":\#(admin)}}"#)
    }

    @Test func anAdminAtHomeAwayOrKeptOutAndAMember() async throws {
        try await h.signIn()
        h.router.on("GET /api/auth/status", status(#""adminAllowed":true,"onAdminNetwork":true"#))
        #expect(await auth.adminAccess() == .home)
        #expect(try h.router.last("GET /api/auth/status").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")

        h.router.on("GET /api/auth/status", status(#""adminAllowed":true,"onAdminNetwork":false"#))
        #expect(await auth.adminAccess() == .away)

        h.router.on("GET /api/auth/status", status(#""adminAllowed":false,"onAdminNetwork":false,"adminLimit":"network""#))
        #expect(await auth.adminAccess() == .keptOut(.network))

        h.router.on("GET /api/auth/status", status(#""adminAllowed":false,"onAdminNetwork":false,"adminLimit":"https""#))
        #expect(await auth.adminAccess() == .keptOut(.https))

        // A server from before onAdminNetwork: an allowed admin is at home.
        h.router.on("GET /api/auth/status", status(#""adminAllowed":true"#))
        #expect(await auth.adminAccess() == .home)

        h.router.on("GET /api/auth/status", status(#""adminAllowed":true"#, admin: false))
        #expect(await auth.adminAccess() == .member)
    }

    @Test func nobodySignedInOrNoAnswerSaysNothing() async throws {
        #expect(await auth.adminAccess() == nil)
        try await h.signIn()
        h.router.on("GET /api/auth/status", .problem(503, "busy"))
        #expect(await auth.adminAccess() == nil)
        #expect(auth.user != nil, "a refusal here signs nobody out")
    }
}
