package media.watchmy.client

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where MainActivity sends the intents it is started or brought back with.
 * A cast to this TV (Cast Connect's LOAD) goes to [castConnect], which takes
 * it when it is one and plays it; anything else with a link
 * (watchmymedia://play/…, a reminder's Watch, the TV launcher's Watch Next)
 * goes to the UI as [deepLink]. A launcher's own intent has no link and
 * changes nothing. [deepLinkCount] goes up with every link, so the same link
 * opened twice is handled twice.
 */
class IntentRouter(private val castConnect: (Intent) -> Boolean) {
    var deepLink by mutableStateOf<String?>(null)
        private set

    var deepLinkCount by mutableLongStateOf(0L)
        private set

    fun receive(intent: Intent?) {
        if (intent == null || castConnect(intent)) return
        deepLink = intent.dataString ?: return
        deepLinkCount++
    }
}
