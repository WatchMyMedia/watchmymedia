package media.watchmy.client.mobile.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The player's icons from the phone mockups (24×24), beside the shared ones in WmmIcons. */
internal object PlayerIcons {
    private const val FRAME = "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"

    val PictureInPicture = icon(stroke = listOf(FRAME), fill = listOf("M13 11h5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z"))
    val Lock = icon(stroke = listOf("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 8 0v4"))
    val Audio = icon(stroke = listOf("M4 10v4h4l5 4V6L8 10z", "M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"), fill = listOf("M4 10v4h4l5 4V6L8 10z"))
    val Captions = icon(stroke = listOf(FRAME, "M10 10a2 2 0 1 0 0 4M17 10a2 2 0 1 0 0 4"))
    val Quality = icon(stroke = listOf(FRAME, "M8 9v6M8 12h3M11 9v6M14 9v6h2a3 3 0 0 0 0-6z"))
    val SkipNext = icon(stroke = listOf("M18 5v14"), fill = listOf("M6 5v14l9-7z"), width = 2.4f)
    val SkipSegment = icon(stroke = listOf("m5 5 8 7-8 7M13 5l8 7-8 7"), width = 2.4f)
    val Replay = icon(stroke = listOf("M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5"))
    val Forward = icon(stroke = listOf("M21 12a9 9 0 1 1-3-6.7", "M21 4v5h-5"))
    val Pause = icon(fill = listOf("M7 5h4v14H7zM13 5h4v14h-4z"))
    val Signal = icon(stroke = listOf("M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0"), fill = listOf("M12 18.5a1 1 0 1 1 0 2a1 1 0 1 1 0-2z"))

    private fun icon(stroke: List<String> = emptyList(), fill: List<String> = emptyList(), width: Float = 2f): ImageVector {
        val b = ImageVector.Builder("player", 24.dp, 24.dp, 24f, 24f)
        for (p in fill) b.addPath(PathParser().parsePathString(p).toNodes(), fill = SolidColor(Color.White))
        for (p in stroke) {
            b.addPath(
                PathParser().parsePathString(p).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
