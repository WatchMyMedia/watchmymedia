package media.watchmy.client.core.data

import media.watchmy.client.model.ApiException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    private fun c(s: String) = ServerAddress.candidates(s)

    @Test
    fun `bare addresses get http and the server's port`() {
        assertEquals(listOf("http://192.168.1.20:27587"), c("192.168.1.20"))
        assertEquals(listOf("http://nas.local:27587"), c(" nas.local/ "))
        assertEquals(listOf("http://nas:27587"), c("nas"))
        assertEquals(listOf("http://[fe80::1]:27587"), c("[fe80::1]"))
    }

    @Test
    fun `a typed port or scheme is kept`() {
        assertEquals(listOf("http://192.168.1.20:8080"), c("192.168.1.20:8080"))
        assertEquals(listOf("https://nas.local:8443"), c("https://nas.local:8443"))
        assertEquals(listOf("https://home.example.net"), c("https://home.example.net/"))
        assertEquals(listOf("https://home.example.net/wmm"), c("https://home.example.net/wmm?x=1"))
        // http without a port: the server's own port first, then 80.
        assertEquals(listOf("http://192.168.1.20:27587", "http://192.168.1.20"), c("http://192.168.1.20"))
    }

    @Test
    fun `a domain name never silently downgrades to http`() {
        assertEquals(listOf("https://home.example.net"), c("home.example.net"))
    }

    @Test(expected = ApiException::class)
    fun `blank is refused`() {
        c("  ")
    }

    @Test(expected = ApiException::class)
    fun `spaces inside are refused`() {
        c("my server")
    }

    @Test(expected = ApiException::class)
    fun `other schemes are refused`() {
        c("ftp://nas.local")
    }

    @Test
    fun `a probe follows a redirect only on the same host, and never down to http`() {
        fun r(base: String, location: String) = ServerAddress.redirectedBase(base, location.toHttpUrl())
        assertEquals("https://home.example.net", r("http://home.example.net", "https://home.example.net/api/server"))
        assertEquals("https://home.example.net:8443/wmm", r("http://home.example.net", "https://home.example.net:8443/wmm/api/server?x=1"))
        assertEquals("http://nas.local:27587/wmm", r("http://nas.local:27587", "http://nas.local:27587/wmm/api/server"))
        assertNull("down to http", r("https://home.example.net", "http://home.example.net/api/server"))
        assertNull("another host", r("http://home.example.net", "https://evil.example/api/server"))
        assertNull("a user name", r("http://home.example.net", "https://me@home.example.net/api/server"))
        assertNull("not the server's info", r("http://home.example.net", "https://home.example.net/login"))
        assertNull(ServerAddress.redirectedBase("http://home.example.net", null))
    }
}
