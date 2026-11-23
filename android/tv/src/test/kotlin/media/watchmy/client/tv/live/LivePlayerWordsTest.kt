package media.watchmy.client.tv.live

import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the live player says when a channel stops, and how far behind live it is. */
class LivePlayerWordsTest {
    private val channel = LiveChannel("c", "s", "4.1", "NFLD")

    @Test fun `the player's problems are words, never the tuner's make`() {
        val busy = liveProblem(LivePlayerState(status = PlayerStatus.Error, reason = "tuners_busy"), channel)
        assertEquals("The tuners are busy", busy.title)
        assertFalse(busy.body.contains("HDHomeRun"))
        assertEquals("Back to the guide", busy.leaveLabel)
        val signal = liveProblem(LivePlayerState(status = PlayerStatus.Error, reason = "no_signal"), channel)
        assertEquals("No signal on 4.1", signal.title)
        val taken = liveProblem(LivePlayerState(status = PlayerStatus.Ended, reason = "recording", takeoverRecording = PlayRequest(1L, title = "The Quiet Meridian")), channel, takeover = "The Quiet Meridian")
        assertEquals("Stopped for a recording", taken.title)
        assertEquals("The Quiet Meridian needed the tuner you were using.", taken.body)
        assertEquals("Watch it from the start", taken.actionLabel)
        val still = liveProblem(LivePlayerState(status = PlayerStatus.Playing, askStillWatching = true), channel)
        assertEquals("Still watching 4.1?", still.title)
        assertEquals(listOf("Keep watching", "Stop"), listOf(still.actionLabel, still.leaveLabel))
    }

    @Test fun `behind live reads in minutes and seconds`() {
        assertEquals("0:31 behind live", behindLiveLabel(31_400))
        assertEquals("12:05 behind live", behindLiveLabel(725_000))
        assertEquals("1:02:03 behind live", behindLiveLabel(3_723_000))
        assertEquals("Behind live", behindLiveLabel(null))
    }
}
