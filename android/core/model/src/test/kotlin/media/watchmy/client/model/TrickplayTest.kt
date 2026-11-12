package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The tile locator (PLAN-TRICKPLAY.md §2) and the manifest's checks. */
class TrickplayTest {
    /** The plan's example: a 2-hour film, 724 tiles of 320 × 180, 10 × 10 a sheet. */
    private val film = Trickplay(10_000, 320, 180, 10, 10, 724, 8, "http://nas:27587/api/files/42/trickplay/9c1e07a2/{sheet}.jpg")

    @Test fun `a position shows the tile at or before it`() {
        assertEquals(TrickplayTile(0, 0, 0, 0, 320, 180), film.tileAt(0))
        assertEquals(TrickplayTile(0, 0, 0, 0, 320, 180), film.tileAt(9_999))
        assertEquals(TrickplayTile(1, 0, 320, 0, 320, 180), film.tileAt(10_000))
        // 13:59 is tile 83: sheet 0, row 8, column 3.
        assertEquals(TrickplayTile(83, 0, 960, 1_440, 320, 180), film.tileAt(839_000))
    }

    @Test fun `tiles run across the columns, then down the rows, then on to the next sheet`() {
        assertEquals(TrickplayTile(9, 0, 2_880, 0, 320, 180), film.tile(9))
        assertEquals(TrickplayTile(10, 0, 0, 180, 320, 180), film.tile(10))
        assertEquals(TrickplayTile(99, 0, 2_880, 1_620, 320, 180), film.tile(99))
        assertEquals(TrickplayTile(100, 1, 0, 0, 320, 180), film.tile(100))
        assertEquals(TrickplayTile(723, 7, 960, 360, 320, 180), film.tile(723))
    }

    @Test fun `past the last tile and before the start, the ends`() {
        assertEquals(723, film.tileAt(7_240_000).index)
        assertEquals(723, film.tileAt(Long.MAX_VALUE).index)
        assertEquals(0, film.tileAt(-5_000).index)
        assertEquals(723, film.tile(10_000).index)
        assertEquals(0, film.tile(-1).index)
    }

    @Test fun `the tile's height is the manifest's, for a scope film or a 4∶3 one`() {
        val scope = film.copy(height = 134)
        assertEquals(TrickplayTile(11, 0, 320, 134, 320, 134), scope.tile(11))
        val square = film.copy(width = 240, columns = 8, rows = 12)
        assertEquals(TrickplayTile(9, 0, 240, 180, 240, 180), square.tile(9))
        assertEquals(1, square.tile(96).sheet)
    }

    @Test fun `a sheet's URL and cache key name its number, and the key leaves out a token`() {
        assertEquals("http://nas:27587/api/files/42/trickplay/9c1e07a2/7.jpg", film.sheetUrl(7))
        val cast = film.copy(url = film.url + "?access_token=cast-1")
        assertEquals("http://nas:27587/api/files/42/trickplay/9c1e07a2/0.jpg?access_token=cast-1", cast.sheetUrl(0))
        assertEquals("http://nas:27587/api/files/42/trickplay/9c1e07a2/0.jpg", cast.sheetKey(0))
    }

    @Test fun `a manifest that can't be drawn from is none`() {
        fun of(interval: Long = 10_000, width: Long = 320, height: Long = 180, columns: Long = 10, rows: Long = 10, count: Long = 724, sheets: Long = 8, url: String = film.url) =
            Trickplay.of(interval, width, height, columns, rows, count, sheets, url)
        assertEquals(film, of())
        assertNull(of(interval = 0))
        assertNull(of(width = 0))
        assertNull(of(height = -1))
        assertNull(of(count = 0))
        assertNull("more tiles than the sheets hold", of(sheets = 7))
        assertNull(of(url = "/api/files/42/trickplay/9c1e07a2/0.jpg"))
        assertNull(of(width = 5_000_000))
        assertNotNull("the last sheet partly filled", of(count = 701))
    }
}
