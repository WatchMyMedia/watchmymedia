package media.watchmy.client.tv.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri

/**
 * Where the TV can't show YouTube's player in a WebView ([TrailerWebViews];
 * PLAN-TV-TRAILERS.md §4.4), it hands YouTube trailers to the YouTube app
 * (PLAN-TRAILERS.md §6.2): an `ACTION_VIEW` of the watch link, which the
 * YouTube app takes, as Jellyfin's Android TV app does. Google TV ships with
 * YouTube. The trailer screen's Open in YouTube uses it too.
 */
private fun watchIntent(url: String) = Intent(Intent.ACTION_VIEW, url.toUri())

/**
 * Whether an app here can open a YouTube watch link; without one (and
 * without a usable WebView) the Trailer button is hidden for YouTube
 * trailers, and the trailer screen has no Open in YouTube. Android 11 and
 * later show the app only through the `<queries>` entry in the app's
 * manifest.
 */
internal fun Context.canOpenYouTube(): Boolean {
    val intent = watchIntent("https://www.youtube.com/watch?v=WhWc3b3KhnY")
    val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return found != null
}

/**
 * Opens [url] in the YouTube app; false when nothing could open it after
 * all. The extras ask the YouTube app to play it full screen and to come
 * back here when it ends, instead of playing another video and leaving
 * people four Backs from the movie page. They are the retired YouTube
 * Android Player API's and undocumented, but TV0 saw both work on a Google
 * TV Streamer (2026-11-18): YouTube opened its StandalonePlayerActivity and
 * returned to the app by itself at the end. Other apps that take the link
 * ignore them.
 */
internal fun Context.openYouTube(url: String): Boolean = try {
    startActivity(
        watchIntent(url)
            .putExtra(EXTRA_FORCE_FULLSCREEN, true)
            .putExtra(EXTRA_FINISH_ON_ENDED, true),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** The YouTube app's extra for playing a watch link full screen (see [openYouTube]). */
internal const val EXTRA_FORCE_FULLSCREEN = "force_fullscreen"

/** The YouTube app's extra for coming back when the video ends (see [openYouTube]). */
internal const val EXTRA_FINISH_ON_ENDED = "finish_on_ended"
