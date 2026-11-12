package media.watchmy.client.core.playback.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasuresTest {
    private var now = 0L
    private val stalls = StallCounter { now }
    private val meter = ThroughputMeter { now }

    @Test
    fun aSourcesFirstBufferingIsItsStartNotAStall() {
        stalls.load()
        stalls.buffering(aheadMs = 0)
        now += 3_000
        stalls.ready()
        assertEquals(Stalls(0, 0), stalls.stalls)
    }

    @Test
    fun bufferingAfterReadyIsAStallTimedUntilReady() {
        stalls.load()
        stalls.ready()
        now += 10_000
        stalls.buffering(aheadMs = 0)
        now += 1_500
        assertEquals("one under way counts", Stalls(1, 1_500), stalls.stalls)
        now += 500
        stalls.ready()
        now += 10_000
        stalls.buffering(aheadMs = 0)
        now += 1_000
        stalls.idle()
        now += 5_000
        assertEquals(Stalls(2, 3_000), stalls.stalls)

        stalls.load()
        assertEquals("a new decision counts afresh", Stalls(0, 0), stalls.stalls)
    }

    @Test
    fun bufferingWithDataInHandWaitsOnTheDecoderNotTheLink() {
        stalls.load()
        stalls.ready()
        now += 10_000
        stalls.buffering(aheadMs = 30_000)
        now += 400
        stalls.ready()
        assertEquals(Stalls(0, 0), stalls.stalls)
        stalls.buffering(aheadMs = StallCounter.DRY_MS - 1)
        assertEquals(1, stalls.stalls.count)
    }

    @Test
    fun aSeeksBufferingIsNotAStall() {
        stalls.load()
        stalls.ready()
        now += 10_000
        stalls.seek()
        stalls.buffering(aheadMs = 0)
        now += 4_000
        stalls.ready()
        assertEquals(Stalls(0, 0), stalls.stalls)

        // A seek that needed no buffering hides no later stall.
        stalls.seek()
        now += 20_000
        stalls.buffering(aheadMs = 0)
        assertEquals(1, stalls.stalls.count)

        // A seek out of a stall ends it.
        now += 2_000
        stalls.seek()
        now += 3_000
        assertEquals(Stalls(1, 2_000), stalls.stalls)
    }

    @Test
    fun throughputIsTheMetersRecentHlsEstimate() {
        meter.hls = true
        assertNull(meter.throughputBps)
        meter.sample(bytes = 0, bitrateEstimate = 1_000_000)
        assertNull("a guess from the network type", meter.throughputBps)
        meter.sample(bytes = 2_000_000, bitrateEstimate = 24_000_000)
        assertEquals(24_000_000L, meter.throughputBps)
        now += ThroughputMeter.FRESH_MS + 1
        assertNull("stale", meter.throughputBps)

        meter.sample(bytes = 2_000_000, bitrateEstimate = 5_000_000)
        meter.hls = false
        assertNull("direct play's rate is the stream's", meter.throughputBps)
    }

    @Test
    fun completedMediaTransferHasItsOwnFreshAgeAndResetsAtLoad() {
        meter.load(hls = true)
        meter.completedMedia(bytes = 1_000_000, durationMs = 1_000)
        assertEquals(TransferSample(8_000_000, 0), meter.transfer)
        now += 5_000
        assertEquals(TransferSample(8_000_000, 5_000), meter.transfer)
        now += ThroughputMeter.FRESH_MS
        assertNull(meter.transfer)
        meter.load(hls = false)
        meter.completedMedia(bytes = 1_000_000, durationMs = 1_000)
        assertNull("a progressive file is not transfer evidence", meter.transfer)
    }
}
