package media.watchmy.client.tv.detail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Debug builds only: switches how the remote drives YouTube's player on the
 * TV's trailer screen ([TrailerRemote], PLAN-TV-TRAILERS.md §4.3) without a
 * rebuild, for the check on a real TV (TV0). Only adb can send it (the
 * shell holds `DUMP`). From android/, with the package the build installed:
 *
 * ```
 * adb shell am broadcast -n media.watchmy.client/media.watchmy.client.tv.detail.TrailerRemoteSwitch --es remote api
 * ```
 *
 * `remote` is `api` or `keyboard` (the default); without it, the reply says
 * which one is set. The next trailer opened uses it, and it stays set until
 * the app's data is cleared.
 */
class TrailerRemoteSwitch : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val asked = intent.getStringExtra(EXTRA_REMOTE)
        val remote = TrailerRemoteSetting.parse(asked)
        // Storage off the main thread, which StrictMode watches in debug builds.
        val pending = goAsync()
        thread(name = "TrailerRemoteSwitch") {
            try {
                pending.resultData = when {
                    remote != null -> {
                        TrailerRemoteSetting.write(context, remote)
                        "trailer remote: ${remote.name.lowercase()}"
                    }
                    asked != null -> "remote is api or keyboard, not $asked"
                    else -> "trailer remote: ${TrailerRemoteSetting.read(context).name.lowercase()}"
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_REMOTE = "remote"
    }
}
