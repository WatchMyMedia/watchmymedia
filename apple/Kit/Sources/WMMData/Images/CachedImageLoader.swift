import Foundation
import WMMModel

/// Loads artwork through a `URLSession` with a disk cache (the server marks
/// images immutable for a year). A picture's request carries the bearer
/// token of the server it is on, whichever server is in use (search lists
/// other servers' titles), and no token goes to any other host.
final class CachedImageLoader: ImageLoader {
    /// The sign-in for the server `url` is on, if any, read on the main actor
    /// for each image (``LocalStore/signedIn(for:)``).
    typealias SignIn = @MainActor @Sendable (_ url: URL) -> SignedInSession?
    /// A 401 with this sign-in's token: it was revoked.
    typealias Rejected = @Sendable (SignedInSession) async -> Void

    private let session: URLSession
    private let signIn: SignIn
    private let rejected: Rejected

    /// - Parameter rejected: what a revoked token does; by default what any
    ///   other request's would (``ServerAPI/tokenRefused()``).
    init(session: URLSession, signIn: @escaping SignIn, rejected: @escaping Rejected = { await $0.api.tokenRefused() }) {
        self.session = session
        self.signIn = signIn
        self.rejected = rejected
    }

    /// A session whose cache keeps about 200 MB of artwork on disk.
    static func makeSession() -> URLSession {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("Artwork", isDirectory: true)
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 200 * 1024 * 1024, directory: directory)
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 30
        config.httpMaximumConnectionsPerHost = 6
        return URLSession(configuration: config)
    }

    func data(for url: URL) async throws -> Data {
        let signedIn = await signIn(url)
        let request = signedIn?.api.authorizedRequest(url) ?? URLRequest(url: url)
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            if let signedIn, ServerAPI.revokes(status: http.statusCode, signed: RequestSigner.signed(request)) {
                await rejected(signedIn)
            }
            throw URLError(.badServerResponse)
        }
        return data
    }
}
