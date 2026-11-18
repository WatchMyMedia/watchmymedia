package media.watchmy.client.tv.detail

import android.content.Context
import android.webkit.WebView
import androidx.annotation.VisibleForTesting

/**
 * Whether this TV can show YouTube's player in a WebView
 * (PLAN-TV-TRAILERS.md §4.4). Some Android TVs have no WebView, or one that
 * fails to start; there a YouTube trailer goes to the YouTube app, as it
 * did before trailers played in the app.
 */
internal object TrailerWebViews {
    /** Making a WebView threw in this process: trailers go to the YouTube app from then on. */
    @Volatile private var broken = false

    /** A WebView was made in this process, so another one will be too. */
    @Volatile private var made = false

    /** How a WebView is made. Tests make one that fails, or one that records what reaches it. */
    @VisibleForTesting
    var factory: (Context) -> WebView = ::WebView

    /**
     * A WebView provider is installed, and making a WebView hasn't failed in
     * this process. Cheap enough for the movie page to ask as it draws.
     */
    fun installed(): Boolean = !broken && try {
        WebView.getCurrentWebViewPackage() != null
    } catch (_: RuntimeException) {
        false
    }

    /** A new WebView, or null when making one failed; [installed] then says no for the rest of the process. */
    fun create(context: Context): WebView? = try {
        factory(context).also { made = true }
    } catch (_: RuntimeException) {
        // A missing or updating provider throws AndroidRuntimeException (MissingWebViewPackageException).
        broken = true
        null
    } catch (_: LinkageError) {
        // A provider whose native library won't load.
        broken = true
        null
    }

    /**
     * Whether Trailer can open the trailer screen: [installed], and a WebView
     * can be made. The first time, one is made and thrown away, so a TV
     * whose WebView fails hands the trailer to the YouTube app instead of
     * opening a screen that can't play it.
     */
    fun usable(context: Context): Boolean = installed() && (made || create(context)?.also(WebView::destroy) != null)

    @VisibleForTesting
    fun reset() {
        broken = false
        made = false
        factory = ::WebView
    }
}
