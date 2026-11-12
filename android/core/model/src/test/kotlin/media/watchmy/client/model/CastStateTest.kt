package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CastStateTest {
    @Test
    fun castButton() {
        // Hidden only where this app cannot cast; no devices found yet still shows it.
        assertEquals(false, CastState.Unavailable.showsButton)
        assertEquals(true, CastState.NoDevices.showsButton)
        assertEquals(true, CastState.Available.showsButton)
        assertEquals(true, CastState.Connecting(null).showsButton)
        assertEquals(true, CastState.Connected("Living room TV").showsButton)
    }
}
