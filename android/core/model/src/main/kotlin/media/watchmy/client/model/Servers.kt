package media.watchmy.client.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** The API level this app needs; servers below it are refused (GET /api/server). */
const val REQUIRED_API_LEVEL = 1

/** The oldest server release this app works with, as the footers say ("Needs server 0.1.0 or newer"); what it checks is [REQUIRED_API_LEVEL]. */
const val MIN_SERVER_VERSION = "0.1.0"

/** A WatchMyMedia server as GET /api/server describes it. */
data class Server(
    /** Stable random ID; servers are remembered by it, not by address. */
    val id: String,
    val name: String,
    /** Normalised base URL without a trailing slash, such as http://192.168.1.20:27587. */
    val baseUrl: String,
    val version: String,
    val apiLevel: Int,
    val setupRequired: Boolean = false,
    /**
     * The server wants a browser check (Cloudflare Turnstile) before a
     * password sign-in, which apps cannot show: sign in with a code instead.
     */
    val turnstile: Boolean = false,
) {
    val tooOld: Boolean get() = apiLevel < REQUIRED_API_LEVEL

    /** Plain http to a host beyond this network ([isNotEncrypted]): what is sent to it can be read on the way. */
    val notEncrypted: Boolean get() = isNotEncrypted(baseUrl)
}

/**
 * A server version as people read it, such as "v0.4.1". Servers stamp their
 * version from the git tag, "v" included, so only add one when it is missing.
 */
fun versionLabel(version: String): String = if (version.startsWith("v")) version else "v$version"

/**
 * An address as people read it, without the scheme, query or trailing
 * slash: "192.168.1.20:27587" from a base URL, "192.168.1.20:27587/link"
 * from the pairing link.
 */
fun displayAddress(url: String): String = url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** A host only this network reaches: a .local name, localhost, an IPv4 or an IPv6 address. */
private fun isLocalNetworkHost(host: String): Boolean =
    host.endsWith(".local") || host == "localhost" || IPV4.matches(host) || host.contains(':')

/**
 * Plain http to a host name (not an IP address or a .local name): the
 * server picker says to use https, as the password would cross the
 * internet unencrypted.
 */
fun needsHttpsNudge(address: String): Boolean {
    val a = address.trim().lowercase(Locale.ROOT)
    if (!a.startsWith("http://")) return false
    val name = a.removePrefix("http://").substringBefore('/').substringBefore(':')
    return name.contains('.') && !isLocalNetworkHost(name)
}

/**
 * Whether [address] (a server's base URL, or an address as someone typed it)
 * is plain http to a host beyond this network, so passwords, tokens and
 * video cross the internet unencrypted: "Not encrypted". https is
 * encrypted; http to a [private network host][isPrivateNetworkHost] stays at
 * home. Without a scheme it is read as the server picker would try it: a
 * domain name without a port over https, anything else (a bare IP address
 * such as 203.0.113.5) over http.
 */
fun isNotEncrypted(address: String): Boolean {
    val a = address.trim().lowercase(Locale.ROOT)
    val scheme = if ("://" in a) a.substringBefore("://") else null
    if (scheme != null && scheme != "http") return false
    val authority = a.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val (host, port) = splitHostPort(authority) ?: return false
    if (host.isEmpty()) return false
    if (scheme == null && port == null && '.' in host && ':' !in host && ipv4(host) == null && !host.trimEnd('.').endsWith(".local")) return false
    return !isPrivateNetworkHost(host)
}

/**
 * A host that only this network (or this device) reaches: loopback
 * (127.0.0.0/8, ::1, localhost), a private IPv4 address (10/8, 172.16/12,
 * 192.168/16), link-local (169.254/16, fe80::/10), carrier-grade NAT
 * (100.64/10, which Tailscale uses), an IPv6 unique local address
 * (fc00::/7, Tailscale's too), an IPv4 address in its IPv6 form
 * (::ffff:192.168.1.20) by its IPv4 address, a `.local` (mDNS) name, or a
 * bare name without dots, which the router's DNS answers. Anything else may
 * be across the internet. Reads the text only: it never looks a name up.
 */
fun isPrivateNetworkHost(host: String): Boolean {
    val h = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
    if (h.isEmpty()) return false
    ipv4(h)?.let { return privateIpv4(it) }
    if (':' in h) return ipv6(h)?.let(::privateIpv6) ?: false
    if (h.all { it in '0'..'9' || it == '.' }) return false
    return h == "localhost" || h.endsWith(".local") || '.' !in h
}

/**
 * Whether [url] is on the server at [baseUrl]: the same scheme, host and
 * port, and under its path. Sign-ins and media tokens go only to such URLs.
 * A URL with anything unusual where its host is (a user name, a backslash,
 * a space or a control character) is not on it, since URL parsers disagree
 * about where such a host ends. Reads the text only: it never looks a name up.
 */
fun isOnServer(baseUrl: String, url: String): Boolean {
    val base = origin(baseUrl) ?: return false
    val target = origin(url) ?: return false
    return base.scheme == target.scheme && base.host == target.host && base.port == target.port &&
        target.path.startsWith(base.path.trimEnd('/') + "/")
}

private class Origin(val scheme: String, val host: String, val port: Int, val path: String)

private fun origin(url: String): Origin? {
    if ("://" !in url) return null
    val scheme = url.substringBefore("://").lowercase(Locale.ROOT)
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> return null
    }
    val rest = url.substringAfter("://")
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, end).lowercase(Locale.ROOT)
    if (authority.any { it <= ' ' || it == '@' || it == '\\' || it == '\u007f' }) return null
    val (host, port) = splitHostPort(authority) ?: return null
    if (host.isEmpty()) return null
    val path = rest.substring(end).substringBefore('?').substringBefore('#').ifEmpty { "/" }
    return Origin(scheme, host, port ?: defaultPort, path)
}

/** "host:port", "[v6]:port" or a bare IPv6 address → host (without brackets) and port; null when it can't be read. */
private fun splitHostPort(authority: String): Pair<String, Int?>? {
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end < 0) return null
        val rest = authority.substring(end + 1)
        val port = if (rest.isEmpty()) null else rest.removePrefix(":").toIntOrNull() ?: return null
        return authority.substring(1, end) to port
    }
    // More than one colon without brackets: an IPv6 address on its own.
    if (authority.count { it == ':' } > 1) return authority to null
    val port = if (':' in authority) authority.substringAfter(':').toIntOrNull() ?: return null else null
    return authority.substringBefore(':') to port
}

/** The four parts of a dotted IPv4 address, each 0–255; null for anything else. */
private fun ipv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    return IntArray(4) { i ->
        val p = parts[i]
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
        p.toInt().takeIf { it <= 255 } ?: return null
    }
}

private fun privateIpv4(a: IntArray): Boolean = a[0] == 127 || a[0] == 10 ||
    (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168) ||
    (a[0] == 169 && a[1] == 254) || (a[0] == 100 && a[1] in 64..127)

/** The eight 16-bit groups of an IPv6 address (a zone such as %wlan0 and a dotted IPv4 tail allowed); null for anything else. */
private fun ipv6(host: String): IntArray? {
    var text = host.substringBefore('%')
    var tail: IntArray? = null
    if ('.' in text) {
        tail = ipv4(text.substringAfterLast(':')) ?: return null
        text = text.substringBeforeLast(':') + ":0:0"
    }
    fun groups(s: String): List<Int>? = if (s.isEmpty()) {
        emptyList()
    } else {
        s.split(':').map { g -> if (g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' }) g.toInt(16) else return null }
    }
    val halves = text.split("::")
    val words = when (halves.size) {
        1 -> groups(text)?.takeIf { it.size == 8 } ?: return null
        2 -> {
            val head = groups(halves[0]) ?: return null
            val rest = groups(halves[1]) ?: return null
            if (head.size + rest.size > 7) return null
            head + List(8 - head.size - rest.size) { 0 } + rest
        }
        else -> return null
    }.toIntArray()
    tail?.let {
        words[6] = it[0] shl 8 or it[1]
        words[7] = it[2] shl 8 or it[3]
    }
    return words
}

private fun privateIpv6(w: IntArray): Boolean {
    val zeros = (0..4).all { w[it] == 0 }
    return when {
        // ::1
        zeros && w[5] == 0 && w[6] == 0 && w[7] == 1 -> true
        // ::ffff:a.b.c.d
        zeros && w[5] == 0xffff -> privateIpv4(intArrayOf(w[6] shr 8, w[6] and 0xff, w[7] shr 8, w[7] and 0xff))
        // fc00::/7 and fe80::/10
        else -> (w[0] and 0xfe00) == 0xfc00 || (w[0] and 0xffc0) == 0xfe80
    }
}

/** A server found on the local network over mDNS (_watchmymedia._tcp). */
data class DiscoveredServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val version: String,
    val apiLevel: Int,
)

/** A server this device has used, with the account signed in on it, if any. */
data class SavedServer(
    val server: Server,
    val userName: String?,
    val signedIn: Boolean,
    val lastUsedAtMs: Long,
)

/**
 * Servers: found on the network, remembered, and the one in use. Methods
 * throw [ApiException] (or [ServerTooOldException]) on failure.
 */
interface ServerRepository {
    /** Servers announcing themselves on this network, updated as they come and go. */
    val discovered: Flow<List<DiscoveredServer>>

    /** Servers used before, most recent first. */
    val saved: Flow<List<SavedServer>>

    /** The server in use, or null before one is chosen. */
    val current: StateFlow<SavedServer?>

    /**
     * Looks an address up (GET /api/server) without choosing it. Accepts
     * "192.168.1.20", "nas.local:27587", "https://home.example.net" and fills in
     * the scheme and default port.
     */
    suspend fun probe(address: String): Server

    /** Makes [server] the one in use. */
    suspend fun select(server: Server)

    /** Forgets a remembered server and its sign-in. */
    suspend fun forget(serverId: String)
}
