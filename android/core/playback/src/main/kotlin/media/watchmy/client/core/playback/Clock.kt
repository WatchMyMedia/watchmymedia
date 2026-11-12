package media.watchmy.client.core.playback

import android.os.SystemClock

/** Time for the player: [elapsedMs] for timers (never jumps), [wallMs] for the server's expiry times and Watch Next. */
interface Clock {
    fun elapsedMs(): Long

    fun wallMs(): Long
}

object DeviceClock : Clock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun wallMs(): Long = System.currentTimeMillis()
}
