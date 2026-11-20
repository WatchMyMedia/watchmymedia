import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Change password goes to PUT /api/auth/password.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct ChangePasswordRepositoryTests {
    let h = Harness()
    var auth: any AuthRepository { h.services.auth }

    @Test func itSendsBothPasswordsSignedAndAWrongOneDoesntSignOut() async throws {
        try await h.signIn()
        h.router.on("PUT /api/auth/password", .noContent)
        try await auth.changePassword(current: "fixture-viewer-1", new: "a new password")
        let request = try h.router.last("PUT /api/auth/password")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        let body = try h.body(request)
        #expect(body["current"] as? String == "fixture-viewer-1")
        #expect(body["new"] as? String == "a new password")

        h.router.on("PUT /api/auth/password", .problem(401, "current password is wrong"))
        await #expect(throws: APIError(status: 401, message: "Current password is wrong.")) {
            try await auth.changePassword(current: "nope", new: "a new password")
        }
        #expect(auth.user != nil, "a wrong current password is not a lost sign-in")

        h.router.on("PUT /api/auth/password", .problem(429, "too many failed sign-ins; try again in 15 minutes"))
        await #expect(throws: APIError(status: 429, message: "Too many failed sign-ins; try again in 15 minutes.")) {
            try await auth.changePassword(current: "nope", new: "a new password")
        }
    }
}
