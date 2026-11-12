package media.watchmy.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.model.SeekThumbnails
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.TrickplayTile
import java.util.concurrent.ConcurrentHashMap

/**
 * Seek thumbnails through the app's image loader (PLAN-TRICKPLAY.md §7.1).
 * A sheet is fetched once into the loader's disk cache, by its
 * [Trickplay.sheetKey], over the image client, which signs in to the current
 * server and nowhere else. A tile is cut from it with [BitmapRegionDecoder]:
 * a 320 × 180 bitmap of about 230 KB rather than the whole 3,200 × 1,800
 * sheet's 23 MB. Tiles stay in the loader's memory cache by sheet and cell.
 */
class CoilSeekThumbnails(
    private val context: Context,
    private val loader: () -> ImageLoader = { SingletonImageLoader.get(context) },
) : SeekThumbnails {
    /** Sheets that failed since the last [prefetch], by key: not asked for again at every tile of a scrub. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** One fetch of a sheet at a time, so a scrub's tile and a prefetch of its sheet don't both download it. */
    private val fetching = ConcurrentHashMap<String, Mutex>()

    override suspend fun load(trickplay: Trickplay, tile: TrickplayTile): Bitmap? {
        val key = trickplay.sheetKey(tile.sheet)
        if (key in failed) return null
        val request = ImageRequest.Builder(context)
            .data(trickplay.sheetUrl(tile.sheet))
            .diskCacheKey(key)
            .memoryCacheKey("$key#${tile.x},${tile.y}")
            .size(Size.ORIGINAL)
            .decoderFactory { result, _, _ -> TileDecoder(result.source, tile) }
            .build()
        val loader = loader()
        val result = fetching.getOrPut(key) { Mutex() }.withLock { loader.execute(request) }
        val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
        if (bitmap == null) {
            failed += key
            // The loader keeps a 404 on disk for good, and the sheet may yet be made under this URL.
            loader.diskCache?.remove(key)
        }
        return bitmap
    }

    override suspend fun prefetch(trickplay: Trickplay, tile: TrickplayTile) {
        failed.clear()
        val perSheet = trickplay.columns * trickplay.rows
        coroutineScope {
            for (sheet in listOf(tile.sheet, tile.sheet - 1, tile.sheet + 1)) {
                if (sheet !in 0 until trickplay.sheets) continue
                // In each, the tile a scrub from here comes to first.
                val index = when {
                    sheet < tile.sheet -> (sheet + 1) * perSheet - 1
                    sheet > tile.sheet -> sheet * perSheet
                    else -> tile.index
                }
                launch { load(trickplay, trickplay.tile(index)) }
            }
        }
    }
}

/** Decodes only [tile]'s cell of a sheet. */
private class TileDecoder(private val source: ImageSource, private val tile: TrickplayTile) : Decoder {
    override suspend fun decode(): DecodeResult = source.use {
        val decoder = source.fileOrNull()?.let { regionDecoder(it.toString()) } ?: regionDecoder(source.source().readByteArray())
        try {
            val cell = Rect(tile.x, tile.y, tile.x + tile.width, tile.y + tile.height)
            check(cell.intersect(0, 0, decoder.width, decoder.height)) { "Tile ${tile.index} is outside its sheet." }
            val bitmap = checkNotNull(decoder.decodeRegion(cell, null)) { "Couldn't decode tile ${tile.index}." }
            DecodeResult(bitmap.asImage(), isSampled = false)
        } finally {
            decoder.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun regionDecoder(path: String): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(path) else checkNotNull(BitmapRegionDecoder.newInstance(path, false))

    @Suppress("DEPRECATION")
    private fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(bytes, 0, bytes.size) else checkNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false))
}
