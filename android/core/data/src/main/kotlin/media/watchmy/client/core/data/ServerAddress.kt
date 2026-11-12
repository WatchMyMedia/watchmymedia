package media.watchmy.client.core.data

import media.watchmy.client.model.ApiException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns what someone typed into the base URLs to try, in order. */
internal object ServerAddress {
    const val DEFAULT_PORT = 27587
    private const val PROBE_PATH = "/api/server"

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val PORT = Regex(""":\d{1,5}$""")

    /**
     * "192.168.1.20" and "nas.local" mean the server's own port over http.
     * A port or scheme typed is kept. A domain name without either is tried
     * over https (a reverse proxy); HTTP requires an explicit choice.
     */
    fun candidates(input: String): List<String> {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) throw invalid()
        val schemeGiven = text.contains("://")
        val url = (if (schemeGiven) text else "http://$text").toHttpUrlOrNull() ?: throw invalid()
        if (url.scheme != "http" && url.scheme != "https") throw invalid()
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw invalid()
        val authority = text.substringAfter("://").substringBefore('/')
        val portGiven = PORT.containsMatchIn(authority)
        return when {
            portGiven -> listOf(url)
            schemeGiven && url.scheme == "https" -> listOf(url)
            schemeGiven -> listOf(url.withPort(DEFAULT_PORT), url)
            isDomain(url.host) -> listOf(url.newBuilder().scheme("https").port(443).build())
            else -> listOf(url.withPort(DEFAULT_PORT))
        }.map(::normalise).distinct()
    }

    /** The base URL as servers are remembered: no query, no trailing slash. */
    fun normalise(url: HttpUrl): String = url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')

    /**
     * Where a probe of [base] answered with a redirect to [location] looks
     * next: the same host, over the same scheme or upgraded to https (never
     * down to http), at the base the location's /api/server names. Null for
     * any other redirect, which the probe doesn't follow, so an address
     * typed as http://home.example.net is remembered as the https one its
     * proxy points to.
     */
    fun redirectedBase(base: String, location: HttpUrl?): String? {
        val from = base.toHttpUrlOrNull() ?: return null
        val to = location ?: return null
        if (to.host != from.host || to.username.isNotEmpty() || to.password.isNotEmpty()) return null
        if (to.scheme != from.scheme && !(from.scheme == "http" && to.scheme == "https")) return null
        val path = to.encodedPath
        if (!path.endsWith(PROBE_PATH)) return null
        return normalise(to.newBuilder().encodedPath(path.removeSuffix(PROBE_PATH).ifEmpty { "/" }).build())
    }

    fun sameEndpoint(a: String, b: String): Boolean {
        val left = a.toHttpUrlOrNull() ?: return false
        val right = b.toHttpUrlOrNull() ?: return false
        return normalise(left) == normalise(right)
    }

    private fun HttpUrl.withPort(port: Int) = newBuilder().port(port).build()

    /** A name with dots that is not an address or an mDNS name: probably reached through a proxy. */
    private fun isDomain(host: String) = '.' in host && !host.endsWith(".local") && !IPV4.matches(host) && ':' !in host

    private fun invalid() = ApiException(null, "That doesn't look like a server address. Try one like 192.168.1.20 or nas.local.")
}
