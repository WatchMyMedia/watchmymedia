package media.watchmy.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletLayoutTest {
    @Test fun railStartsAt600() {
        assertFalse(TabletLayout.isTablet(599))
        assertTrue(TabletLayout.isTablet(600))
    }
    @Test fun twoPanesStartAt1000() {
        assertFalse(TabletLayout.usesTwoPanes(999))
        assertTrue(TabletLayout.usesTwoPanes(1000))
    }
    @Test fun landscapePhonesKeepTheirPlayer() {
        assertFalse(TabletLayout.usesTabletPlayer(915, 412))
        assertFalse(TabletLayout.usesTabletPlayer(599, 800))
        assertTrue(TabletLayout.usesTabletPlayer(600, 600))
        assertTrue(TabletLayout.usesTabletPlayer(1280, 800))
    }
    @Test fun narrowWindowsKeepPhoneLayout() {
        assertFalse(TabletLayoutInfo(412).isTablet)
        assertFalse(TabletLayoutInfo(599).twoPane)
        assertTrue(TabletLayoutInfo(800).isTablet)
        assertFalse(TabletLayoutInfo(800).twoPane)
    }
}
