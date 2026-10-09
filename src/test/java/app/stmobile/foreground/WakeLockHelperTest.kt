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
        var shouldThrowOnRelease = false
        override var isHeld: Boolean = false

        override fun acquire(timeoutMs: Long) {
            acquireCount++
            lastTimeoutMs = timeoutMs
            isHeld = true
        }

        override fun release() {
            if (shouldThrowOnRelease) {
                throw RuntimeException("WakeLock under-locked")
            }
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

    @Test
    fun testReleaseWhenWakeLockExpiredIsNoOp() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        helper.acquire()
        assertTrue(helper.isHeld)

        // Simulate OS-level expiration of the wakelock (isHeld turns false without explicit release)
        fake.isHeld = false
        assertFalse(helper.isHeld)

        // Calling release after expiration must not call acquirer.release(), avoiding under-lock crash
        helper.release()
        assertEquals("Should not call release when wakelock has already expired", 0, fake.releaseCount)
    }

    @Test
    fun testForceReleaseSuppressesExceptionWhenReleaseThrows() {
        val fake = FakeWakeLockAcquirer()
        fake.shouldThrowOnRelease = true
        val helper = WakeLockHelper(fake)

        helper.acquire()
        // forceRelease should safely absorb the exception without propagating it
        helper.forceRelease()
    }
}
