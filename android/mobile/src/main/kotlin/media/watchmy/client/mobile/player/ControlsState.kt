package media.watchmy.client.mobile.player

import media.watchmy.client.model.PlayerStatus

/**
 * Whether the touch controls show, and when they were last touched. They
 * hide [HIDE_DELAY_MS] after the last touch while the video plays, and stay
 * while it is paused, loading or ended, while the viewer drags the scrubber
 * and while the picker is open.
 */
internal data class ControlsState(val visible: Boolean = true, val lastInputMs: Long = 0) {
    fun toggled(nowMs: Long) = ControlsState(!visible, nowMs)

    fun shown(nowMs: Long) = ControlsState(true, nowMs)

    fun touched(nowMs: Long) = copy(lastInputMs = nowMs)

    fun hidden() = copy(visible = false)

    /**
     * When the controls hide by themselves: [HIDE_DELAY_MS] after the later
     * of the last touch and [allowedSinceMs] (when hiding became allowed, so
     * a long load does not hide them the moment playback starts). Null
     * while they stay.
     */
    fun hideAtMs(autoHide: Boolean, allowedSinceMs: Long): Long? =
        if (visible && autoHide) maxOf(lastInputMs, allowedSinceMs) + HIDE_DELAY_MS else null

    companion object {
        const val HIDE_DELAY_MS = 3_000L

        fun autoHides(status: PlayerStatus, scrubbing: Boolean, pickerOpen: Boolean): Boolean =
            (status == PlayerStatus.Playing || status == PlayerStatus.Buffering) && !scrubbing && !pickerOpen
    }
}
