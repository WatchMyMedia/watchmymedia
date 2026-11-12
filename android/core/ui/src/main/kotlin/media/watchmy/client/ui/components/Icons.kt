package media.watchmy.client.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The mockups' stroke icons (24×24, 2 px stroke, round caps), as image
 * vectors that take the content colour. Add more here as screens need them.
 */
object WmmIcons {
    val Search = stroke("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0", "m20 20-3.5-3.5")
    val Home = stroke("M3 11 12 3l9 8", "M5 10v10h14V10")
    val Tv = stroke("M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 21h8")
    val Movie = stroke("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4")
    val Settings = stroke("M15.5 12a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0", "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1")
    val Server = stroke("M4.5 4h15A1.5 1.5 0 0 1 21 5.5v3A1.5 1.5 0 0 1 19.5 10h-15A1.5 1.5 0 0 1 3 8.5v-3A1.5 1.5 0 0 1 4.5 4z", "M4.5 14h15a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5v-3A1.5 1.5 0 0 1 4.5 14z", "M7 7h.01M7 17h.01")
    val Globe = stroke("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18")
    val Check = stroke("m5 12 5 5 9-10")
    val Back = stroke("M19 12H5M11 18l-6-6 6-6")
    val ChevronRight = stroke("m9 6 6 6-6 6")
    val ChevronDown = stroke("m6 9 6 6 6-6")
    val Eye = stroke("M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0")
    val Sort = stroke("M3 6h18M6 12h12M10 18h4")
    val Close = stroke("M6 6l12 12M18 6 6 18")
    val Subtitles = stroke("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M7 13h4M13 13h4M7 16h8")
    val SkipForward = stroke("M5 5l9 7-9 7z", "M19 5v14")
    val Replay10 = stroke("M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5")
    val Forward10 = stroke("M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val Download = stroke("M12 4v11", "m7 10 5 5 5-5", "M5 20h14")

    // Requests and missing episodes (the "Requests and missing episodes" boards).
    val Record = stroke(RING, "M7 12a5 5 0 1 0 10 0a5 5 0 1 0-10 0")
    val Clock = stroke(RING, "M12 7v5l3 2")
    val Alert = stroke("M12 3 2 20h20L12 3z", "M12 10v4M12 17h.01")
    val Person = stroke("M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0", "M4 21a8 8 0 0 1 16 0")
    val CheckCircle = stroke(RING, "M8 12l3 3 5-6")
    val XCircle = stroke(RING, "M9 9l6 6M15 9l-6 6")
    val SearchCircle = stroke("M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M20 20l-3.5-3.5")
    val EyeOff = stroke("M3 3l18 18", "M10.6 6.1A10 10 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-3.3 3.9", "M6.6 6.6C3.9 8.4 2 12 2 12s3.5 6 10 6a9.7 9.7 0 0 0 5.4-1.6", "M9.9 9.9a3 3 0 0 0 4.2 4.2")
    val Calendar = stroke("M4 6h16v14H4z", "M4 10h16M8 3v4M16 3v4")
    val PlusCircle = stroke(RING, "M12 8v8M8 12h8")
    val Plus = stroke("M12 5v14M5 12h14")
    val Tray = stroke("M3 13h5l2 3h4l2-3h5", "M5.5 5h13L21 13v6H3v-6z")
    val Info = stroke(RING, "M12 11v5M12 8h.01")
    val Screen = stroke("M3 5h18v12H3z", "M8 21h8M12 17v4")
    val Speaker = stroke("M4 9h4l5-4v14l-5-4H4z", "M16 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12")
    val Captions = stroke("M3 5h18v14H3z", "M7 11h4M13 11h4M7 15h7")
    val Question = stroke(RING, "M12 8v5M12 16h.01")
    val Paperclip = stroke("M21 11.5 12.5 20a5 5 0 0 1-7-7l9-9a3.3 3.3 0 0 1 4.7 4.7l-9 9a1.7 1.7 0 0 1-2.4-2.4l8.3-8.3")

    // The episode page (PLAN-EPISODE-PAGE.md): its More menu and Mark unwatched.
    val ChevronLeft = stroke("m15 18-6-6 6-6")
    val Flag = stroke("M5 21V4h11l-1.5 4L16 12H5")
    val Replace = stroke("M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5")
    val Trash = stroke("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val Undo = stroke("M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")

    // Review fixes (2026-11-01): Not encrypted, on a server reached over plain http.
    val LockOpen = stroke("M6 11h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 7.5-2")
    val File = stroke("M14 3H6v18h12V7zM14 3v4h4")
    val Ring = stroke(RING)

    // Casting Live TV (PLAN-LIVE-TV.md §4.1): the Cast glyph on the TV's cast screens, as the phone draws it.
    val Cast = stroke("M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M2 12a9 9 0 0 1 8 8", "M2 16a5 5 0 0 1 4 4", "M2 20h.01")

    /** Three filled dots in a row: TV's More. */
    val MoreHoriz: ImageVector = ImageVector.Builder("more-horiz", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M3 12a2 2 0 1 1 4 0a2 2 0 1 1-4 0zM10 12a2 2 0 1 1 4 0a2 2 0 1 1-4 0zM17 12a2 2 0 1 1 4 0a2 2 0 1 1-4 0z").toNodes(), fill = SolidColor(Color.White))
        .build()

    /** Filled play triangle. */
    val Play: ImageVector = ImageVector.Builder("play", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M8 5v14l11-7z").toNodes(), fill = SolidColor(Color.White))
        .build()

    /** Filled pause bars. */
    val Pause: ImageVector = ImageVector.Builder("pause", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M7 5h3v14H7zM14 5h3v14h-3z").toNodes(), fill = SolidColor(Color.White))
        .build()

    // Trailers (PLAN-TRAILERS.md): Open in YouTube's arrow out of a box, and the TV's filled clapperboard.
    val External = stroke("M14 4h6v6M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")

    /** The TV's Trailer button (Android TV MovieTrailer.dc.html). */
    val Clapper: ImageVector = ImageVector.Builder("clapper", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z").toNodes(), fill = SolidColor(Color.White))
        .build()

    // Live TV favourites and reminders (the LiveGuideExtras, LiveSearch and LiveReminders boards).
    val Star = stroke(STAR)
    val Bell = stroke("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val BellOff = stroke("M8.7 3A6 6 0 0 1 18 8a21.3 21.3 0 0 0 .6 5", "M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7", "M10.3 21a1.94 1.94 0 0 0 3.4 0", "M2 2l20 20")
    val Moved = stroke("M12 3a9 9 0 1 0 9 9", "M12 7v5l3 2", "M16 3h5v5")

    /** The filled star of a favourite. */
    val StarFilled: ImageVector = ImageVector.Builder("star-filled", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(STAR).toNodes(), fill = SolidColor(Color.White))
        .build()

    /** The filled bell of a reminder that is set. */
    val BellFilled: ImageVector = ImageVector.Builder("bell-filled", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9z").toNodes(), fill = SolidColor(Color.White))
        .addPath(PathParser().parsePathString("M10.3 21a1.94 1.94 0 0 0 3.4 0").toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        .build()

    // Rows (PLAN-ROWS.md): the editor, Add a row and the list page.
    val ListBullets = stroke("M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01")
    val Sliders = stroke("M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12", "M18 6a2 2 0 1 1-4 0a2 2 0 1 1 4 0", "M12 12a2 2 0 1 1-4 0a2 2 0 1 1 4 0", "M20 18a2 2 0 1 1-4 0a2 2 0 1 1 4 0")
    val Rows = stroke("M4 5h16v4H4z", "M4 11h16v4H4z", "M4 17h10")
    val Filter = stroke("M4 5h16l-6 7v6l-4 2v-8z")
    val ArrowUp = stroke("M12 19V5M6 11l6-6 6 6")
    val ArrowDown = stroke("M12 5v14M6 13l6 6 6-6")
    val NotFound = stroke("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0", "m20 20-3.5-3.5M9 9.2a2 2 0 1 1 2.6 1.9c-.4.2-.6.5-.6.9v.4M11 14.5h.01")
    val Warning = stroke("M12 3 2 20h20L12 3z", "M12 10v4M12 17h.01")

    /** Six dots: a row's drag handle. */
    val DragHandle: ImageVector = ImageVector.Builder("drag-handle", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            PathParser().parsePathString(
                "M9 4.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2zM15 4.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2zM9 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2zM15 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2zM9 16.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2zM15 16.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z",
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
        .build()

    private const val STAR = "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
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
