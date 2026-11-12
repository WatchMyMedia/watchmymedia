package media.watchmy.client.tv.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The player artboards' icons that WmmIcons does not have (24×24; strokes as in the mockups). */
internal object PlayerIcons {
    val Back = icon { stroke(2.4f, "m15 6-6 6 6 6") }
    val SkipAhead = icon { stroke(2.4f, "m5 5 8 7-8 7M13 5l8 7-8 7") }

    /** The seek thumbnail's step chip (PlayerScrub). */
    val SeekStep = icon { stroke(2.6f, "m5 6 6 6-6 6M13 6l6 6-6 6") }
    val SkipNext = icon {
        fill("M6 5v14l9-7z")
        stroke(2.4f, "M18 5v14")
    }
    val Audio = icon {
        fill("M4 10v4h4l5 4V6L8 10z")
        stroke(2f, "M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11")
    }
    val Subtitles = icon { stroke(2f, FRAME, "M10 10a2 2 0 1 0 0 4M17 10a2 2 0 1 0 0 4") }
    val Quality = icon { stroke(2f, FRAME, "M8 9v6M8 12h3M11 9v6M14 9v6h2a3 3 0 0 0 0-6z") }
    val Check = icon { stroke(3f, "m5 12 5 5 9-10") }
    val Signal = icon {
        stroke(2f, "M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0")
        fill("M12 18.5a1 1 0 1 1 0 2a1 1 0 1 1 0-2z")
    }

    private const val FRAME = "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"

    private class Builder(val b: ImageVector.Builder) {
        fun stroke(width: Float, vararg paths: String) = paths.forEach {
            b.addPath(
                PathParser().parsePathString(it).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun fill(path: String) {
            b.addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
        }
    }

    private fun icon(build: Builder.() -> Unit): ImageVector =
        Builder(ImageVector.Builder("player", 24.dp, 24.dp, 24f, 24f)).apply(build).b.build()
}
