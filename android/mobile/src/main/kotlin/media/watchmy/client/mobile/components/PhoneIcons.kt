package media.watchmy.client.mobile.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons the phone screens use beyond [media.watchmy.client.ui.components.WmmIcons], in the same stroke style. */
object PhoneIcons {
    val LiveTV = stroke("M5 8h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "m8 3 4 5 4-5")
    val Fullscreen = stroke("M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5")
    val ChannelDown = stroke("m6 9 6 6 6-6")
    val ChannelUp = stroke("m6 15 6-6 6 6")
    val EyeOff = stroke("M3 3l18 18", "M10.6 6.1A10 10 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-3.2 3.9", "M6.6 6.6C3.8 8.4 2 12 2 12s3.5 6 10 6a9.7 9.7 0 0 0 5.4-1.6", "M9.9 9.9a3 3 0 0 0 4.2 4.2")
    val External = stroke("M14 4h6v6M20 4l-9 9", "M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5")
    val Refresh = stroke("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")

    // Downloads (PLAN-DOWNLOADS.md), as the download boards draw them.
    /** The Downloads tab and the empty list: an arrow into a tray. */
    val Downloads = stroke("M12 3v12M7 10l5 5 5-5M4 17v3h16v-3")
    val Wifi = stroke("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01")
    /** Removed from the server: a ring with a bar. */
    val RemovedRing = stroke(RING, "M8 12h8")
    val Pause = stroke("M8 5v14M16 5v14")
    /** The sheet's tracks line: a screen with subtitle lines. */
    val Tracks = stroke("M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M6 12h4M14 12h4M6 15h8")
    /** The sheet's free space line: a drive. */
    val Storage = stroke("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 15h18M7 18h.01")
    /** You're offline: a cloud struck through. */
    val CloudOff = stroke("M3 3l18 18", "M8.5 6.3A6 6 0 0 1 17.7 10H18a4 4 0 0 1 2.4 7.2M17 18H7a5 5 0 0 1-1.6-9.7")

    /** The Cast glyph: a screen with waves from its corner. */
    val Cast = stroke("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a9 9 0 0 1 8 8", "M2 16a5 5 0 0 1 4 4", "M2 20h.01")

    /** Three filled dots, upright. */
    val MoreVert: ImageVector = ImageVector.Builder("more", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            PathParser().parsePathString("M12 3a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM12 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM12 17a2 2 0 1 1 0 4a2 2 0 1 1 0-4z").toNodes(),
            fill = SolidColor(Color.White),
        )
        .build()

    private const val RING = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0"

    private fun stroke(vararg paths: String): ImageVector {
        val b = ImageVector.Builder("wmm", 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                PathParser().parsePathString(p).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
