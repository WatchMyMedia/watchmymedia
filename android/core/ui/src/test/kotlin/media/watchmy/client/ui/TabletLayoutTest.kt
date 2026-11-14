package media.watchmy.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletLayoutTest {
    @Test fun railStartsAt600Wide() {
        assertFalse(TabletLayout.isTablet(599, 800))
        assertTrue(TabletLayout.isTablet(600, 800))
    }

    @Test fun tabletsNeed480Tall() {
        // A Pixel 8 held sideways, and a large phone held sideways: the phone layouts.
        assertFalse(TabletLayout.isTablet(915, 412))
        assertFalse(TabletLayout.isTablet(990, 479))
        assertTrue(TabletLayout.isTablet(960, 480))
        // A tablet either way up.
        assertTrue(TabletLayout.isTablet(1280, 800))
        assertTrue(TabletLayout.isTablet(800, 1280))
    }

    @Test fun twoPanesStartAt1000OnATablet() {
        assertFalse(TabletLayout.usesTwoPanes(999, 800))
        assertTrue(TabletLayout.usesTwoPanes(1000, 800))
        // Wide but short (a foldable's outer screen sideways, a split window) is not a tablet at all.
        assertFalse(TabletLayout.usesTwoPanes(1100, 420))
    }

    @Test fun landscapePhonesKeepTheirPlayer() {
        assertFalse(TabletLayout.usesTabletPlayer(915, 412))
        assertFalse(TabletLayout.usesTabletPlayer(599, 800))
        assertTrue(TabletLayout.usesTabletPlayer(600, 600))
        assertTrue(TabletLayout.usesTabletPlayer(1280, 800))
    }

    @Test fun narrowOrShortWindowsKeepPhoneLayout() {
        assertFalse(TabletLayoutInfo(412, 915).isTablet)
        assertFalse(TabletLayoutInfo(915, 412).isTablet)
        assertFalse(TabletLayoutInfo(599, 800).twoPane)
        assertTrue(TabletLayoutInfo(800, 1280).isTablet)
        assertFalse(TabletLayoutInfo(800, 1280).twoPane)
        assertTrue(TabletLayoutInfo(1280, 800).twoPane)
    }
}
