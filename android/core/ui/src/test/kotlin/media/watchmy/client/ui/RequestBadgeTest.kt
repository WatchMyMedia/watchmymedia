package media.watchmy.client.ui

import media.watchmy.client.model.RequestBackend
import media.watchmy.client.model.RequestOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBadgeTest {
    @Test fun `members can reach earlier requests after request permission is removed`() {
        val restricted = RequestOptions(backend = RequestBackend.Seerr, movies = false, shows = false, manage = false)
        assertFalse(restricted.canRequest)
        assertTrue(restricted.badge().requests)
        assertNull(restricted.badge().inbox)
        assertFalse(RequestOptions.None.badge().requests)
    }

    @Test fun `Seerr-only Inbox access stays separate from ARR management`() {
        val admin = RequestOptions(backend = RequestBackend.Seerr, manage = false, canOpenInbox = true, inbox = 2)
        assertEquals(2, admin.badge().inbox)
        assertNull(admin.copy(canOpenInbox = false).badge().inbox)
    }
}
