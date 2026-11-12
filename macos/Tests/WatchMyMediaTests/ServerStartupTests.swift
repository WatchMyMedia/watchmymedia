import Darwin
import Foundation
import XCTest
@testable import WatchMyMedia

final class ServerStartupTests: XCTestCase {
    func testValidPorts() {
        XCTAssertNil(ServerStartup.validPort(0))
        XCTAssertNil(ServerStartup.validPort(-1))
        XCTAssertNil(ServerStartup.validPort(65536))
        XCTAssertEqual(ServerStartup.validPort(1), 1)
        XCTAssertEqual(ServerStartup.validPort(65535), 65535)
    }

    func testBoundPortIsNotAvailableAndNewCandidateIsDifferent() throws {
        let held = try BoundPort()
        defer { held.close() }
        XCTAssertFalse(ServerStartup.portIsAvailable(held.port))
        let alternative = try ServerStartup.unusedPort()
        XCTAssertNotEqual(alternative, held.port)
        XCTAssertTrue(ServerStartup.portIsAvailable(alternative))
    }

    func testLegacyAndIncompleteSetupResponses() throws {
        let old = try JSONDecoder().decode(LocalStatus.self, from: Data(
            #"{"version":"old","setupRequired":true,"sessions":[]}"#.utf8))
        XCTAssertTrue(old.needsSetup)
        let withAccount = try JSONDecoder().decode(LocalStatus.self, from: Data(
            #"{"version":"new","setupRequired":false,"setupIncomplete":true,"sessions":[],"plays":[]}"#.utf8))
        XCTAssertTrue(withAccount.needsSetup)
        let complete = try JSONDecoder().decode(LocalStatus.self, from: Data(
            #"{"version":"new","setupRequired":false,"setupIncomplete":false,"sessions":[]}"#.utf8))
        XCTAssertFalse(complete.needsSetup)
    }

    func testOnlyActualBindFailureCountsAsPortCollision() {
        XCTAssertTrue(ServerStartup.addressInUse("error: listen tcp :27876: bind: address already in use\n"))
        XCTAssertFalse(ServerStartup.addressInUse("could not open database"))
        XCTAssertFalse(ServerStartup.addressInUse("other address already in use text"))
    }
}

final class BoundPort {
    private let descriptor: Int32
    let port: Int

    init() throws {
        let descriptor = socket(AF_INET, SOCK_STREAM, 0)
        guard descriptor >= 0 else { throw POSIXError(.EIO) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let got = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(descriptor, $0, &length)
            }
        }
        guard bound == 0, got == 0, listen(descriptor, 1) == 0 else {
            Darwin.close(descriptor)
            throw POSIXError(.EIO)
        }
        port = Int(UInt16(bigEndian: address.sin_port))
        self.descriptor = descriptor
    }

    func close() { Darwin.close(descriptor) }
}
