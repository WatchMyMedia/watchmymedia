package media.watchmy.client.core.playback.player

import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.DevicePlayback

/** How [ExoMediaPlayer] is built, from this device and its playback settings; read when a player screen opens. */
data class PlayerOptions(
    /** Dolby and DTS may leave as they are (Audio passthrough Auto); false decodes them to PCM here. */
    val passthrough: Boolean = true,
    /**
     * The TV switches the display to the video's frame rate itself (Match
     * frame rate), so Media3 must leave the display alone: two switches
     * would fight.
     */
    val appMatchesFrameRate: Boolean = false,
    /**
     * Media already played that the player keeps (from the keyframe before),
     * so a jump back of a few seconds plays at once instead of asking the
     * server again, which restarts a transcode. 0 keeps none.
     */
    val backBufferMs: Int = 0,
) {
    companion object {
        /**
         * Kept back: [BACK_BUFFER_MS] on phones and tablets, none on TVs or
         * low-RAM devices. It adds no memory but counts in Media3's byte
         * target (about 140 MB) with what lies ahead, so on a TV's 4K remux
         * or high-bitrate direct play it would cost forward buffer (a 60 Mbps
         * stream fits about 19 s in all; 15 s back would leave 4 s ahead).
         */
        fun of(device: DevicePlayback, tv: Boolean, lowRam: Boolean = false) = PlayerOptions(
            passthrough = device.audioPassthrough != AudioPassthrough.Off,
            appMatchesFrameRate = tv && device.matchFrameRate,
            backBufferMs = if (tv || lowRam) 0 else BACK_BUFFER_MS,
        )

        const val BACK_BUFFER_MS = 15_000
    }
}
