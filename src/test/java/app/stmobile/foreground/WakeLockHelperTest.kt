package app.stmobile.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockHelperTest {

    private class FakeWakeLockAcquirer : WakeLockAcquirer {
        var acquireCount = 0
        var releaseCount = 0
        var lastTimeoutMs = 0L
        override var isHeld: Boolean = false

        override fun acquire(timeoutMs: Long) {
            acquireCount++
            lastTimeoutMs = timeoutMs
            isHeld = true
        }

        override fun release() {
            if (isHeld) {
                releaseCount++
                isHeld = false
            }
        }
    }

    @Test
    fun testAcquireCallsAcquirerWithDefaultTimeout() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        assertFalse(helper.isHeld)
        helper.acquire()

        assertTrue(helper.isHeld)
        assertEquals(1, fake.acquireCount)
        assertEquals(WakeLockHelper.DEFAULT_TIMEOUT_MS, fake.lastTimeoutMs)
    }

    @Test
    fun testReleaseWhenHeldCallsAcquirer() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        helper.acquire()
        assertTrue(helper.isHeld)

        helper.release()
        assertFalse(helper.isHeld)
        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun testReleaseWhenNotHeldIsNoOp() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        helper.release()
        assertEquals(0, fake.releaseCount)
    }

    @Test
    fun testForceRelease() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        helper.acquire()
        helper.forceRelease()

        assertFalse(helper.isHeld)
        assertEquals(1, fake.releaseCount)
    }
}
