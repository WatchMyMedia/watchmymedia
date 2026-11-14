package media.watchmy.client.buildlogic

/** The largest version code Play accepts. */
internal const val MAX_PLAY_VERSION_CODE = 2_100_000_000

/**
 * The version code for major.minor.patch[-pre], in an order that matches the
 * tags': major × 10⁷ + minor × 10⁵ + patch × 100 + a last pair that puts the
 * pre-releases before their release (alpha.N is N, beta.N 30 + N, rc.N
 * 60 + N, the release 99). So 1.2.3-rc.1 is 10200361 and 1.2.3 is 10200399.
 * Play takes codes up to [MAX_PLAY_VERSION_CODE], which leaves majors up to
 * 209. The app's build file calls it with the release tag (`-PappVersion`);
 * `VersionCodeTest` pins the order and the bounds.
 */
fun versionCodeOf(version: String): Int {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.?(\d+)?)?""").matchEntire(version)
        ?: error("appVersion $version is not major.minor.patch, optionally with -alpha.N, -beta.N or -rc.N")
    val (major, minor, patch, pre, n) = match.destructured
    val number = n.ifEmpty { "0" }.toInt()
    require(major.toInt() <= 209 && minor.toInt() <= 99 && patch.toInt() <= 999 && number <= 29) {
        "appVersion $version is out of range for a version code (major ≤ 209, minor ≤ 99, patch ≤ 999, pre-release ≤ 29)"
    }
    val last = when (pre) {
        "alpha" -> number
        "beta" -> 30 + number
        "rc" -> 60 + number
        else -> 99
    }
    return major.toInt() * 10_000_000 + minor.toInt() * 100_000 + patch.toInt() * 100 + last
}
