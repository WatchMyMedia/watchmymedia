package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersTest {
    @Test fun `addresses read without the scheme`() {
        assertEquals("192.168.1.20:27587", displayAddress("http://192.168.1.20:27587"))
        assertEquals("192.168.1.20:27587", displayAddress("http://192.168.1.20:27587/"))
        assertEquals("home.watchmy.media", displayAddress("https://home.watchmy.media"))
        // A server behind a proxy's path keeps it (the TV used to drop it).
        assertEquals("example.net/watch", displayAddress("https://example.net/watch"))
        // The pairing link, without its code.
        assertEquals("192.168.1.20:27587/link", displayAddress("http://192.168.1.20:27587/link?code=KQ7F2W"))
        assertEquals("nas.local", displayAddress("nas.local"))
    }

    @Test fun `plain http to a hostname gets the https nudge`() {
        assertTrue(needsHttpsNudge("http://home.example.net"))
        assertTrue(needsHttpsNudge("HTTP://nas.example.org:27587/"))
        assertTrue(needsHttpsNudge(" http://media.example.net/watch "))
        assertFalse(needsHttpsNudge("https://home.example.net"))
        assertFalse(needsHttpsNudge("http://192.168.1.20:27587"))
        assertFalse(needsHttpsNudge("http://office-mini.local"))
        assertFalse(needsHttpsNudge("http://NAS.LOCAL"))
        assertFalse(needsHttpsNudge("http://localhost:27587"))
        assertFalse(needsHttpsNudge("nas.local"))
        assertFalse(needsHttpsNudge("nas"))
        // A bare name has no dot, so it is on this network (the TV used to nudge it).
        assertFalse(needsHttpsNudge("http://nas"))
        assertFalse(needsHttpsNudge("http://[fd7a:115c::1]:27587"))
    }

    @Test fun `hosts only this network reaches`() {
        for (host in listOf(
            "127.0.0.1", "127.8.9.10", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1",
            "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.20",
            "169.254.10.20", "fe80::1", "fe80::1%wlan0", "febf::1",
            "100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "fc00::1",
            "::ffff:192.168.1.20", "::FFFF:10.0.0.1",
            "nas.local", "NAS.LOCAL", "office-mini.local.", "nas",
        )) {
            assertTrue(host, isPrivateNetworkHost(host))
        }
        for (host in listOf(
            "8.8.8.8", "203.0.113.5", "172.15.0.1", "172.32.0.1", "192.169.0.1", "169.253.0.1",
            "100.63.255.255", "100.128.0.1", "11.0.0.1", "2001:db8::1", "fec0::1", "fe00::1", "::2", "::",
            "::ffff:8.8.8.8", "home.example.net", "local.example.net", "example.localhost.net",
            "256.1.1.1", "1.2.3", "12345", "", "not:an:address::x",
        )) {
            assertFalse(host, isPrivateNetworkHost(host))
        }
    }

    @Test fun `plain http beyond this network is not encrypted`() {
        assertTrue(isNotEncrypted("http://203.0.113.5:27587"))
        assertTrue(isNotEncrypted("HTTP://home.example.net"))
        assertTrue(isNotEncrypted("http://[2001:db8::1]:27587"))
        assertTrue(isNotEncrypted("http://user@203.0.113.5"))
        // Typed without a scheme, as the server picker reads it: a bare IP address is http.
        assertTrue(isNotEncrypted("203.0.113.5"))
        assertTrue(isNotEncrypted(" 203.0.113.5:8096/ "))
        assertTrue(isNotEncrypted("home.example.net:8096"))
        assertTrue(isNotEncrypted("2001:db8::1"))

        assertFalse(isNotEncrypted("https://203.0.113.5"))
        assertFalse(isNotEncrypted("https://home.example.net"))
        assertFalse("tried over https", isNotEncrypted("home.example.net"))
        assertFalse(isNotEncrypted("http://192.168.1.20:27587"))
        assertFalse(isNotEncrypted("http://100.101.102.103:27587"))
        assertFalse(isNotEncrypted("http://[fd7a:115c::1]:27587"))
        assertFalse(isNotEncrypted("http://nas.local:27587/watch"))
        assertFalse(isNotEncrypted("http://nas"))
        assertFalse(isNotEncrypted("192.168.1.20"))
        assertFalse(isNotEncrypted("nas.local"))
        assertFalse(isNotEncrypted(""))
        assertFalse(isNotEncrypted("ftp://203.0.113.5"))

        assertTrue(Server("a", "A", "http://203.0.113.5:27587", "0.4.0", 3).notEncrypted)
        assertFalse(Server("a", "A", "http://192.168.1.20:27587", "0.4.0", 3).notEncrypted)
        assertFalse(Server("a", "A", "https://home.example.net", "0.4.0", 3).notEncrypted)
    }

    @Test fun `a URL is on the server when it shares its origin and path`() {
        val base = "http://192.168.1.20:27587"
        assertTrue(isOnServer(base, "http://192.168.1.20:27587/api/files/1/stream?play=a1"))
        assertTrue(isOnServer(base, "HTTP://192.168.1.20:27587/api/x#frag"))
        assertTrue(isOnServer("https://home.example.net", "https://HOME.example.net:443/api/x"))
        assertTrue(isOnServer("http://home.example.net:80", "http://home.example.net/api/x"))
        assertTrue(isOnServer("https://example.net/watch", "https://example.net/watch/api/x"))
        assertTrue(isOnServer("http://[fd7a:115c::1]:27587", "http://[fd7a:115c::1]:27587/api/x"))
        assertTrue("only where the host is matters", isOnServer(base, "http://192.168.1.20:27587/api/a b?q=x\\y"))

        assertFalse("another port", isOnServer(base, "http://192.168.1.20:27588/api/x"))
        assertFalse("another scheme", isOnServer("https://home.example.net", "http://home.example.net/api/x"))
        assertFalse("another host", isOnServer(base, "http://192.168.1.21:27587/api/x"))
        assertFalse("outside the path", isOnServer("https://example.net/watch", "https://example.net/watcher/api/x"))
        assertFalse("outside the path", isOnServer("https://example.net/watch", "https://example.net/other"))
        assertFalse("a user name", isOnServer(base, "http://192.168.1.20:27587@evil.example/api/x"))
        assertFalse("a user name", isOnServer(base, "http://evil.example@192.168.1.20:27587/api/x"))
        assertFalse("a backslash", isOnServer(base, "http://evil.example\\@192.168.1.20:27587/api/x"))
        assertFalse("a space", isOnServer(base, "http://192.168.1.20:27587 /api/x"))
        assertFalse("relative", isOnServer(base, "/api/files/1/stream"))
        assertFalse("protocol-relative", isOnServer(base, "//192.168.1.20:27587/api/x"))
        assertFalse(isOnServer(base, "ftp://192.168.1.20:27587/api/x"))
        assertFalse(isOnServer("", "http://192.168.1.20:27587/api/x"))
    }

    @Test fun `the oldest server named in the footers`() {
        assertEquals("0.1.0", MIN_SERVER_VERSION)
    }
}
