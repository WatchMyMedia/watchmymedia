import Darwin
import Foundation

/// Local socket checks never connect to whichever app may occupy the port.
enum ServerStartup {
    static func validPort(_ value: Int) -> Int? {
        (1...65535).contains(value) ? value : nil
    }

    static func portIsAvailable(_ port: Int) -> Bool {
        guard validPort(port) != nil else { return false }
        // Check both loopback families that localhost may resolve to.
        // Reusable wildcard binds on macOS can coexist with an existing
        // loopback listener, so probing the exact loopback address matters.
        let ipv4 = socket(AF_INET, SOCK_STREAM, 0)
        guard ipv4 >= 0 else { return false }
        defer { close(ipv4) }
        var reuseAddress: Int32 = 1
        guard setsockopt(ipv4, SOL_SOCKET, SO_REUSEADDR, &reuseAddress, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            return false
        }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = UInt16(port).bigEndian
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(ipv4, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(ipv4, 1) == 0 else { return false }

        let ipv6 = socket(AF_INET6, SOCK_STREAM, 0)
        guard ipv6 >= 0 else { return errno == EAFNOSUPPORT }
        defer { close(ipv6) }
        guard setsockopt(ipv6, SOL_SOCKET, SO_REUSEADDR, &reuseAddress, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            return false
        }
        var onlyIPv6: Int32 = 1
        guard setsockopt(ipv6, IPPROTO_IPV6, IPV6_V6ONLY, &onlyIPv6, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            return false
        }
        var address6 = sockaddr_in6()
        address6.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        address6.sin6_family = sa_family_t(AF_INET6)
        address6.sin6_addr = in6addr_loopback
        address6.sin6_port = UInt16(port).bigEndian
        return withUnsafePointer(to: &address6) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(ipv6, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) == 0 && listen(ipv6, 1) == 0
            }
        }
    }

    static func unusedPort() throws -> Int {
        // Let the OS choose candidates, then verify both families. The
        // child still handles a race after closing this probe normally.
        for _ in 0..<16 {
            let descriptor = socket(AF_INET, SOCK_STREAM, 0)
            guard descriptor >= 0 else { throw POSIXError(.EIO) }
            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            address.sin_family = sa_family_t(AF_INET)
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
            close(descriptor)
            guard bound == 0, got == 0 else { throw POSIXError(.EIO) }
            let port = Int(UInt16(bigEndian: address.sin_port))
            if portIsAvailable(port) { return port }
        }
        throw POSIXError(.EADDRINUSE)
    }

    static func addressInUse(_ log: String) -> Bool {
        log.lowercased().contains("bind: address already in use")
    }
}
