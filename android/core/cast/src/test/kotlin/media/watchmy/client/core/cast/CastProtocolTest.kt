package media.watchmy.client.core.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live parts of the protocol, as web/src/cast/protocol.ts writes them (PLAN-LIVE-TV.md §4.1). */
class CastProtocolTest {
    @Test fun `a channel's load has no file`() {
        val text = """{"v":1,"server":{"id":"s1","urls":["http://127.0.0.1:27587"]},"token":"c1.x","live":{"channelId":"c7","quality":"720"},"device":{"name":"Living Room TV"}}"""
        val load = CastProtocol.decodeLoad(text)!!
        assertNull(load.fileId)
        assertEquals(LiveLoad("c7", "720"), load.live)
        assertEquals("wmm:live:c7", CastProtocol.liveContentId("c7"))
        // And back again, with no fileId written.
        val again = CastProtocol.encodeLoad(load)
        assertTrue(again, !again.contains("fileId"))
        assertEquals(load, CastProtocol.decodeLoad(again))
    }

    @Test fun `a file's load still decodes as before`() {
        val load = CastProtocol.decodeLoad("""{"server":{"id":"s1","urls":[]},"token":"c1.x","fileId":42}""")!!
        assertEquals(42L, load.fileId)
        assertNull(load.live)
        assertNull(load.subtitleMode)
        assertTrue(!CastProtocol.encodeLoad(load).contains("subtitleMode"))
    }

    @Test fun `subtitle modes are additive and preserve explicit Off`() {
        for (mode in listOf("auto", "off", "future")) {
            val load = CastProtocol.decodeLoad("""{"server":{"id":"s1","urls":[]},"token":"c1.x","fileId":42,"subtitleMode":"$mode"}""")!!
            assertEquals(mode, load.subtitleMode)
            assertEquals(load, CastProtocol.decodeLoad(CastProtocol.encodeLoad(load)))
        }
    }

    @Test fun `a live state names the channel and what the receiver can do`() {
        val text = """{"type":"state","v":1,"features":["live"],"phase":"playing","title":"7.1 Meadow News","live":{"channelId":"c7","number":"7.1","name":"Meadow News","quality":"720","now":{"title":"The Sleuth Files","episodeTitle":"The Low Tide","start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z"},"next":null,"paused":true,"pauseLeftSeconds":210,"reason":null}}"""
        val state = CastProtocol.decodeState(text)!!
        assertEquals(listOf(CastProtocol.FEATURE_LIVE), state.features)
        assertNull(state.fileId)
        val live = state.live!!
        assertEquals("7.1", live.number)
        assertEquals("The Low Tide", live.now?.episodeTitle)
        assertNull(live.next)
        assertEquals(210, live.pauseLeftSeconds)
    }

    @Test fun `an older receiver's state has no features`() {
        val state = CastProtocol.decodeState("""{"type":"state","v":1,"phase":"idle"}""")!!
        assertNull(state.features)
        assertNull(state.live)
    }

    @Test fun `channel commands round-trip`() {
        assertEquals("""{"type":"channel","channelId":"c9"}""", CastCommand.Channel("c9").encode())
        assertEquals("""{"type":"channelStep","by":-1}""", CastCommand.ChannelStep(-1).encode())
        assertEquals(CastCommand.Channel("c9"), CastCommand.decode("""{"type":"channel","channelId":"c9"}"""))
        assertEquals(CastCommand.ChannelStep(1), CastCommand.decode("""{"type":"channelStep","by":1}"""))
        assertNull(CastCommand.decode("""{"type":"channelStep","by":3}"""))
    }
}
