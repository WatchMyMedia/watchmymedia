package media.watchmy.client.tv.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri

/**
 * The TV hands YouTube trailers to the YouTube app (PLAN-TRAILERS.md §6.2):
 * an `ACTION_VIEW` of the watch link, which the YouTube app takes, as
 * Jellyfin's Android TV app does. Google TV ships with YouTube.
 */
private fun watchIntent(url: String) = Intent(Intent.ACTION_VIEW, url.toUri())

/**
 * Whether an app here can open a YouTube watch link; without one, the
 * Trailer button is hidden for YouTube trailers. Android 11 and later show
 * the app only through the `<queries>` entry in the app's manifest.
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

/** Opens [url] in the YouTube app; false when nothing could open it after all. */
internal fun Context.openYouTube(url: String): Boolean = try {
    startActivity(watchIntent(url))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
