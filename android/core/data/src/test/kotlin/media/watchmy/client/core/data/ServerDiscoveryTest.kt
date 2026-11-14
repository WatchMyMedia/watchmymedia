package media.watchmy.client.core.data

import android.net.nsd.NsdServiceInfo
import app.cash.turbine.test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Servers announcing themselves, found, resolved and lost in any order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerDiscoveryTest {
    /** Finds and loses services when the test says, and resolves each when the test answers it. */
    private class FakeBrowser : NsdBrowser {
        var found: (NsdServiceInfo) -> Unit = {}
        var lost: (NsdServiceInfo) -> Unit = {}
        var stopped = false
        val answers = ConcurrentHashMap<String, CompletableDeferred<NsdServiceInfo?>>()

        override fun browse(type: String, found: (NsdServiceInfo) -> Unit, lost: (NsdServiceInfo) -> Unit): (() -> Unit) {
            this.found = found
            this.lost = lost
            return { stopped = true }
        }

        override suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = answers.getOrPut(info.serviceName) { CompletableDeferred() }.await()

        fun answer(name: String, address: String) {
            answers.getOrPut(name) { CompletableDeferred() }.complete(service(name).apply {
                hostAddresses = listOf(InetAddress.getByName(address))
                port = 27587
                setAttribute("id", "id-$name")
            })
        }
    }

    @Test
    fun `a server is listed once resolved and dropped when it says goodbye`() = runBlocking {
        val browser = FakeBrowser()
        NsdDiscovery(browser).servers().test {
            assertEquals(emptyList<Any>(), awaitItem())
            browser.found(service("Attic"))
            browser.answer("Attic", "192.168.1.20")
            assertEquals(listOf("http://192.168.1.20:27587"), awaitItem().map { it.baseUrl })
            browser.lost(service("Attic"))
            assertEquals(emptyList<Any>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(browser.stopped)
    }

    @Test
    fun `a server lost while it resolves doesn't come back`() = runBlocking {
        val browser = FakeBrowser()
        NsdDiscovery(browser).servers().test {
            assertEquals(emptyList<Any>(), awaitItem())
            browser.found(service("Attic"))
            delay(50)
            browser.lost(service("Attic"))
            browser.answer("Attic", "192.168.1.20")
            delay(200)
            expectNoEvents()
            // Found again, it is listed again.
            browser.answers.remove("Attic")
            browser.found(service("Attic"))
            browser.answer("Attic", "192.168.1.21")
            assertEquals(listOf("http://192.168.1.21:27587"), awaitItem().map { it.baseUrl })
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private fun service(name: String) = NsdServiceInfo().apply {
    serviceName = name
    serviceType = NsdDiscovery.SERVICE_TYPE
}
