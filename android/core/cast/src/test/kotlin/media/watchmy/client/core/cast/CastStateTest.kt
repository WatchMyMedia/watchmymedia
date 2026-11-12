package media.watchmy.client.core.cast

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastServer
import media.watchmy.client.model.CastState
import media.watchmy.client.model.CastToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.google.android.gms.cast.framework.CastState as Sdk

/** What the phone's Cast button shows ([castState]) and whether the server casts ([serverCasts]). */
class CastStateTest {
    @Test
    fun `examples`() {
        assertEquals(CastState.Unavailable, state(sdkReady = false))
        assertEquals(CastState.Unavailable, state(serverCasts = false))
        assertEquals(CastState.NoDevices, state(sdkState = Sdk.NO_DEVICES_AVAILABLE))
        assertEquals(CastState.Available, state(sdkState = Sdk.NOT_CONNECTED))
        assertEquals(CastState.Connecting(null), state(sdkState = Sdk.CONNECTING))
        assertEquals("The session names the device before the SDK says connecting", CastState.Connecting("Den TV"), state(connecting = "Den TV"))
        assertEquals(CastState.Connected("Den TV"), state(attached = true, deviceName = "Den TV"))
        assertEquals("A nameless device", CastState.Connected("TV"), state(attached = true, deviceName = " "))
        assertEquals(CastState.Connected("TV"), state(attached = true, deviceName = null))
        assertEquals("The SDK connected, but the remote isn't attached yet", CastState.Available, state(sdkState = Sdk.CONNECTED))
    }

    @Test
    fun `every combination follows the rules in order`() {
        for (attached in BOOLS) for (name in listOf(null, "", "Den TV")) for (ready in BOOLS) for (casts in BOOLS)
            for (sdk in listOf(Sdk.NO_DEVICES_AVAILABLE, Sdk.NOT_CONNECTED, Sdk.CONNECTING, Sdk.CONNECTED)) for (connecting in listOf(null, "Den TV")) {
                val s = castState(attached, name, ready, casts, sdk, connecting)
                val case = "attached=$attached name=$name ready=$ready casts=$casts sdk=$sdk connecting=$connecting"
                when {
                    attached -> assertEquals(case, CastState.Connected(name?.takeIf { it.isNotBlank() } ?: "TV"), s)
                    !ready || !casts -> assertEquals(case, CastState.Unavailable, s)
                    sdk == Sdk.CONNECTING || connecting != null -> assertEquals(case, CastState.Connecting(connecting), s)
                    sdk == Sdk.NO_DEVICES_AVAILABLE -> assertEquals(case, CastState.NoDevices, s)
                    else -> assertEquals(case, CastState.Available, s)
                }
            }
    }

    @Test
    fun `only a server that says casting casts`() = runTest {
        assertTrue(gateway { CastServer("s", "http://s", casting = true) }.serverCasts())
        assertFalse(gateway { CastServer("s", "http://s", casting = false) }.serverCasts())
        assertFalse("No server in use", gateway { null }.serverCasts())
        assertFalse("Unreachable", gateway { throw ApiException(null, "Couldn't reach the server.", IOException()) }.serverCasts())
        assertFalse("Anything else", gateway { error("bad reply") }.serverCasts())
        try {
            gateway { throw CancellationException("left") }.serverCasts()
            fail("Cancellation goes through")
        } catch (_: CancellationException) {
        }
    }

    private fun state(
        attached: Boolean = false,
        deviceName: String? = null,
        sdkReady: Boolean = true,
        serverCasts: Boolean = true,
        sdkState: Int = Sdk.NOT_CONNECTED,
        connecting: String? = null,
    ) = castState(attached, deviceName, sdkReady, serverCasts, sdkState, connecting)

    private fun gateway(server: suspend () -> CastServer?) = object : CastGateway {
        override suspend fun castServer() = server()

        override suspend fun castToken(): CastToken = error("not used")
    }

    private companion object {
        val BOOLS = listOf(false, true)
    }
}
