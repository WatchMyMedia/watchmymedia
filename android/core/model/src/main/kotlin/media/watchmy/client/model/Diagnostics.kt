package media.watchmy.client.model

/**
 * What the app can tell about its own health on this device, for a "Copy
 * diagnostics" row in Settings.
 * Nothing leaves the device unless the person copies it.
 */
interface Diagnostics {
    /**
     * The last few times the app's process ended, newest first, as Android
     * recorded them; empty before Android 11, which keeps no such record, and
     * on the fakes.
     */
    suspend fun pastExits(): List<ProcessExit>

    data object None : Diagnostics {
        override suspend fun pastExits(): List<ProcessExit> = emptyList()
    }
}

/**
 * One end of the app's process: when, why in Android's words ([reason], such
 * as "crash", "anr" or "low memory"), the system's own [description] when it
 * gave one, how much in use the app was ([importance], such as "foreground"),
 * and whether the system kept a trace of it ([hasTrace]: an ANR's stacks, or
 * a native crash's tombstone).
 */
data class ProcessExit(
    val atMs: Long,
    val reason: String,
    val description: String?,
    val importance: String,
    val hasTrace: Boolean,
)
