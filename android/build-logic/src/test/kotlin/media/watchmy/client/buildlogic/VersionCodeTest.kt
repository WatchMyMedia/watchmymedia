package media.watchmy.client.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version codes rise with the tags, pre-releases before their release, and stay inside what Play takes. */
class VersionCodeTest {
    @Test fun `the documented examples`() {
        assertEquals(10200361, versionCodeOf("1.2.3-rc.1"))
        assertEquals(10200399, versionCodeOf("1.2.3"))
        assertEquals(10400099, versionCodeOf("1.4.0"))
    }

    @Test fun `tags in release order give rising codes`() {
        val tags = listOf(
            "0.9.9",
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.2", "1.0.0-alpha.29",
            "1.0.0-beta", "1.0.0-beta.1", "1.0.0-beta.29",
            "1.0.0-rc", "1.0.0-rc.1", "1.0.0-rc.2", "1.0.0-rc.29",
            "1.0.0",
            "1.0.1-alpha.1", "1.0.1", "1.0.999",
            "1.1.0-rc.1", "1.1.0", "1.99.999",
            "2.0.0-alpha.1", "2.0.0",
        )
        val codes = tags.map(::versionCodeOf)
        tags.zipWithNext().zip(codes.zipWithNext()).forEach { (versions, pair) ->
            assertTrue("${versions.first} (${pair.first}) should come before ${versions.second} (${pair.second})", pair.first < pair.second)
        }
    }

    @Test fun `a pre-release written with or without its dot is the same`() {
        assertEquals(versionCodeOf("1.2.3-rc.4"), versionCodeOf("1.2.3-rc4"))
        assertEquals(versionCodeOf("1.2.3-beta.0"), versionCodeOf("1.2.3-beta"))
    }

    @Test fun `the largest version stays inside Play's limit`() {
        val largest = versionCodeOf("209.99.999")
        assertEquals(2_099_999_999, largest)
        assertTrue(largest <= MAX_PLAY_VERSION_CODE)
        assertEquals(1, versionCodeOf("0.0.0-alpha.1"))
        assertEquals(0, versionCodeOf("0.0.0-alpha"))
    }

    @Test fun `out of range parts are refused`() {
        listOf("210.0.0", "1.100.0", "1.0.1000", "1.0.0-rc.30", "1.0.0-alpha.30").forEach { version ->
            assertThrows(version, IllegalArgumentException::class.java) { versionCodeOf(version) }
        }
    }

    @Test fun `anything but major minor patch is refused`() {
        listOf("1.2", "v1.2.3", "1.2.3-dev", "1.2.3-rc.1-extra", "1.2.3.4", "").forEach { version ->
            assertThrows(version, IllegalStateException::class.java) { versionCodeOf(version) }
        }
    }
}
