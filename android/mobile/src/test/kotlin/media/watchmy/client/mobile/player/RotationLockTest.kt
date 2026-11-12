package media.watchmy.client.mobile.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationLockTest {
    @Test fun `phones keep the lock on every version`() {
        assertTrue(rotationLockWorks(sdk = 36, displaySmallestWidthDp = 411))
        assertTrue(rotationLockWorks(sdk = 37, displaySmallestWidthDp = 599))
    }

    @Test fun `tablets and unfolded foldables lose it from Android 16, which ignores the request`() {
        assertTrue(rotationLockWorks(sdk = 35, displaySmallestWidthDp = 800))
        assertFalse(rotationLockWorks(sdk = 36, displaySmallestWidthDp = 600))
        assertFalse(rotationLockWorks(sdk = 37, displaySmallestWidthDp = 673))
    }
}
