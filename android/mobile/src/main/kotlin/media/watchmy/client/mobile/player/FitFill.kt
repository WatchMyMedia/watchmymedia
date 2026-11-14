package media.watchmy.client.mobile.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Fit and fill (the PlayerFitFill board, PLAN-ANDROID-REVIEW §6.3): a film
 * wider than the screen, or an episode narrower than a tall phone held
 * sideways, either fits whole with bars or fills the screen with its edges
 * cropped.
 */

/**
 * The share of a [videoWidth] × [videoHeight] picture that filling a
 * [width] × [height] screen crops off: 0 when they are the same shape, and
 * null while the video's size isn't known yet.
 */
internal fun fillCrop(videoWidth: Float?, videoHeight: Float?, width: Float, height: Float): Float? {
    if (videoWidth == null || videoHeight == null || !(videoWidth > 0f) || !(videoHeight > 0f) || !(width > 0f) || !(height > 0f)) return null
    val video = videoWidth / videoHeight
    val screen = width / height
    return 1f - min(video, screen) / max(video, screen)
}

/** Whether filling is worth offering: it crops at least a sliver (1%). */
internal fun offersFill(crop: Float?): Boolean = crop != null && crop >= 0.01f

/** What the player says for a moment after switching (the board's note). */
internal fun fitFillNote(fill: Boolean, crop: Float): String =
    if (fill) "Fill · ${(crop * 100).roundToInt()}% of the picture is cropped at the edges" else "Fit · the whole picture"
