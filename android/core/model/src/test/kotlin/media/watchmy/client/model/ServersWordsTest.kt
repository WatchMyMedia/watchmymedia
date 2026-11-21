package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words Servers, Add a server and a search of every server share on the phone, the tablet and the TV. */
class ServersWordsTest {
    private fun saved(id: String, lastUsed: Long, userName: String? = "Sam", signedIn: Boolean = userName != null) =
        SavedServer(Server(id, id, "http://$id.local:27587", "1.4.5", 30), userName, signedIn, lastUsed, 1L.takeIf { signedIn })

    @Test fun `registered servers are those signed in to at some time, the one in use first, then by last use`() {
        val all = listOf(saved("a", 10), saved("b", 30), saved("never", 50, userName = null), saved("left", 20, signedIn = false), saved("c", 40))
        assertEquals("one chosen and never signed in to is not registered", listOf("b", "c", "left", "a"), all.registered("b").map { it.server.id })
        assertEquals("with none in use, by last use", listOf("c", "b", "left", "a"), all.registered(null).map { it.server.id })
    }

    @Test fun `Settings' row names the server in use and how many more`() {
        assertEquals("Living room and 2 more", serversSummary("Living room server", 3))
        assertEquals("Living room and 1 more", serversSummary("Living room server", 2))
        assertEquals("with one, its name as it is", "Living room server", serversSummary("Living room server", 1))
        assertEquals("a name that doesn't end in server is kept whole", "Office Mac mini and 2 more", serversSummary("Office Mac mini", 3))
        assertEquals("a server called Server keeps its name", "Server and 1 more", serversSummary("Server", 2))
    }

    @Test fun `a server is called by its name`() {
        assertEquals("Now on Cabin server", nowOn("Cabin server"))
        assertEquals("Couldn’t reach Cabin server", couldntReach("Cabin server"))
        assertEquals("It may be off, or only reachable on its own network. You’re still on Living room server.", couldntReachBody("Living room server"))
        assertEquals("It may be off, or only reachable on its own network.", couldntReachBody(null))
        assertEquals("Sam · home.example.net", serverLine("Sam", "https://home.example.net"))
        assertEquals("Sam · 192.0.2.31:27587 · v1.4.5", serverLineWithVersion("Sam", "http://192.0.2.31:27587", "1.4.5"))
        assertEquals("Sam on Living room server. Open Servers", accountButtonLabel("Sam", "Living room server"))
        assertEquals("On Cabin server. Open Servers", onServerLabel("Cabin server"))
        assertEquals("Switching stops casting to Living room TV.", switchingStopsCasting("Living room TV"))
    }

    @Test fun `downloads from a server are counted in its words`() {
        assertEquals("Watch its 3 downloads", watchItsDownloads(3))
        assertEquals("Watch its 1 download", watchItsDownloads(1))
        assertEquals("3 more from Cabin server", moreDownloadsFrom(3, "Cabin server"))
        assertEquals("None", downloadsFromIt(null))
        assertEquals("3 · 12.4 GB", downloadsFromIt(DownloadTotals(3, 13_310_000_000)))
    }

    @Test fun `removing a server says what goes from this device and what stays`() {
        assertEquals("Remove Cabin server from this phone?", removeServerTitle("Cabin server", "phone"))
        assertEquals("This phone is signed out of it, and its 3 downloads here are deleted.", removeServerDoes("phone", 3))
        assertEquals("This tablet is signed out of it, and its 1 download here is deleted.", removeServerDoes("tablet", 1))
        assertEquals("a TV holds none", "This TV is signed out of it.", removeServerDoes("TV", 0))
    }

    @Test fun `Add a server's steps and last words`() {
        assertEquals("Step 1 of 3 · Choose a server · next: Sign in", AddServerStep.Choose.line)
        assertEquals("Step 2 of 3 · Sign in · next: Added", AddServerStep.SignIn.line)
        assertEquals("Step 3 of 3 · Added", AddServerStep.Added.line)
        assertEquals("Already added: Living room server, Cabin server.", alreadyAdded(listOf("Living room server", "Cabin server")))
        assertNull(alreadyAdded(emptyList()))
        assertEquals("Den server is added", serverAdded("Den server"))
        assertEquals("You’re on it now, signed in as Sam.", serverAddedLine("Sam"))
        assertEquals("This phone is signed in to 3 servers. Search looks in all of them.", serverAddedChanged("phone", 3))
    }

    @Test fun `a search of every server says how many, who is still out and who didn't answer`() {
        assertEquals("Find a show, a movie or an episode on any of your 3 servers.", searchYourServersLine(3))
        assertEquals("Still asking Cabin server…", stillAsking(listOf("Cabin server")))
        assertEquals("Still asking Cabin server and Den server…", stillAsking(listOf("Cabin server", "Den server")))
        assertEquals("Den server didn’t answer, so its titles aren’t listed.", didntAnswer("Den server"))
        assertEquals("No matches on your 3 servers", noMatchesOnServers(3))
        assertEquals("Nothing is called “harbr” on any of them. Check the spelling, or try fewer words.", noMatchesOnServersLine("harbr"))
    }
}
