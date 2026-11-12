package media.watchmy.client.mobile.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNavigationTest {
    @Test
    fun `older servers keep the established phone destinations`() {
        assertEquals(listOf(MobileTab.Home, MobileTab.TvShows, MobileTab.Movies, MobileTab.Search, MobileTab.Settings), MobileTab.shown(downloads = false, rail = false))
        assertEquals(listOf(MobileTab.Home, MobileTab.TvShows, MobileTab.Movies, MobileTab.Downloads, MobileTab.Settings), MobileTab.shown(downloads = true, rail = false))
    }

    @Test
    fun `live replaces settings without adding a sixth phone tab`() {
        for (downloads in listOf(false, true)) {
            val tabs = MobileTab.shown(downloads, rail = false, liveTV = true)
            assertEquals(5, tabs.size)
            assertFalse(MobileTab.Settings in tabs)
            assertEquals(MobileTab.LiveTV, tabs[3])
            assertEquals(if (downloads) MobileTab.Downloads else MobileTab.Search, tabs.last())
        }
    }

    @Test
    fun `tablet keeps search and downloads and uses the account picture for settings when live is available`() {
        val tabs = MobileTab.shown(downloads = true, rail = true, liveTV = true)
        assertEquals(6, tabs.size)
        assertTrue(MobileTab.Search in tabs)
        assertTrue(MobileTab.Downloads in tabs)
        assertTrue(MobileTab.LiveTV in tabs)
        assertFalse(MobileTab.Settings in tabs)
        assertTrue(MobileTab.Settings in MobileTab.shown(downloads = true, rail = true, liveTV = false))
    }
}
