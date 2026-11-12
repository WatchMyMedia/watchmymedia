package media.watchmy.client.fake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import media.watchmy.client.model.SeekThumbnails
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.TrickplayTile
import media.watchmy.client.model.formatClock

/**
 * Seek thumbnails drawn in code, for the harnesses, previews and
 * `-Pfakes=true`: each tile a dark colour of its own in a lighter frame, with
 * its time, so a scrub shows the picture changing without a server.
 * [loads] counts what was asked for, for tests.
 */
class FakeSeekThumbnails : SeekThumbnails {
    var loads = 0
        private set
    var prefetches = 0
        private set

    override suspend fun load(trickplay: Trickplay, tile: TrickplayTile): Bitmap {
        loads++
        val bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (tile.index * 37f) % 360f
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.32f)))
        val frame = tile.height / 30f
        val edge = Paint().apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.7f))
            style = Paint.Style.STROKE
            strokeWidth = frame
        }
        canvas.drawRect(frame / 2, frame / 2, tile.width - frame / 2, tile.height - frame / 2, edge)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = tile.height / 4f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(formatClock(tile.index * trickplay.intervalMs), tile.width / 2f, tile.height / 2f + text.textSize / 3, text)
        return bitmap
    }

    override suspend fun prefetch(trickplay: Trickplay, tile: TrickplayTile) {
        prefetches++
    }
}
