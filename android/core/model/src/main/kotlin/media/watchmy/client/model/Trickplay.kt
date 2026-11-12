package media.watchmy.client.model

import android.graphics.Bitmap

/** The API level whose decisions carry seek thumbnails ([PlaybackDecision.trickplay]; PLAN-TRICKPLAY.md). */
const val TRICKPLAY_API_LEVEL = 9

/**
 * A file's seek thumbnails (PLAN-TRICKPLAY.md §2): JPEG sheets of [columns] ×
 * [rows] tiles, each [width] × [height] px, one tile every [intervalMs]; [count]
 * tiles in all over [sheets] sheets, the last partly filled. [url] is a
 * sheet's absolute URL with `{sheet}` for its number from 0. It names the
 * thumbnails' version, so a sheet is cached for good.
 */
data class Trickplay(
    val intervalMs: Long,
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val sheets: Int,
    val url: String,
) {
    /**
     * The tile to show at [positionMs]: tile i shows the last keyframe at or
     * before i × [intervalMs], so the one at or before the position, and the
     * last tile past the end.
     */
    fun tileAt(positionMs: Long): TrickplayTile = tile((positionMs.coerceAtLeast(0) / intervalMs).coerceAtMost(count - 1L).toInt())

    /** Where tile [index] is: its sheet, and its cell's top left in that sheet. */
    fun tile(index: Int): TrickplayTile {
        val i = index.coerceIn(0, count - 1)
        val perSheet = columns * rows
        val cell = i % perSheet
        return TrickplayTile(i, i / perSheet, (cell % columns) * width, (cell / columns) * height, width, height)
    }

    fun sheetUrl(sheet: Int): String = url.replace(SHEET, sheet.toString())

    /** What a sheet is cached by: its URL without the query (a token), which names the file, the version and the sheet. */
    fun sheetKey(sheet: Int): String = sheetUrl(sheet).substringBefore('?')

    companion object {
        const val SHEET = "{sheet}"

        /** More than any manifest has (a sheet is 3,200 px wide), and small enough that a tile's place stays in an Int. */
        private const val MAX_SIZE = 1_000_000L

        /** The manifest as the server sent it, or null when it can't be drawn from (a size of 0, no `{sheet}` in the URL). */
        fun of(intervalMs: Long, width: Long, height: Long, columns: Long, rows: Long, count: Long, sheets: Long, url: String): Trickplay? {
            val sizes = listOf(width, height, columns, rows, count, sheets, width * columns, height * rows, columns * rows)
            if (intervalMs <= 0 || sizes.any { it !in 1L..MAX_SIZE } || SHEET !in url) return null
            if (columns * rows * sheets < count) return null
            return Trickplay(intervalMs, width.toInt(), height.toInt(), columns.toInt(), rows.toInt(), count.toInt(), sheets.toInt(), url)
        }
    }
}

/** One seek thumbnail: tile [index], at ([x], [y]) in sheet [sheet], [width] × [height] px. */
data class TrickplayTile(val index: Int, val sheet: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The pictures for seek thumbnails: a tile cut from its [Trickplay] sheet.
 * The app's fetches sheets through the image loader, which signs in to the
 * current server and nowhere else; the fakes draw theirs.
 */
interface SeekThumbnails {
    /**
     * [tile]'s picture, or null when its sheet can't be had. A sheet that
     * failed is not asked for again until the next [prefetch], so a scrub
     * doesn't retry it at every tile.
     */
    suspend fun load(trickplay: Trickplay, tile: TrickplayTile): Bitmap?

    /**
     * A scrub may start at [tile]: fetch its sheet and the sheets either
     * side, and give sheets that failed another go.
     */
    suspend fun prefetch(trickplay: Trickplay, tile: TrickplayTile)

    /** No thumbnails: the scrubber shows the time alone. */
    object None : SeekThumbnails {
        override suspend fun load(trickplay: Trickplay, tile: TrickplayTile): Bitmap? = null

        override suspend fun prefetch(trickplay: Trickplay, tile: TrickplayTile) = Unit
    }
}
