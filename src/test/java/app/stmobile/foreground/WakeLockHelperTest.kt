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

    @Test
    fun testMultiTokenIndependentRelease() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        helper.acquireToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT, 600_000L)
        assertTrue(helper.isHeld)
        assertTrue(helper.hasToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT))
        assertEquals(1, fake.acquireCount)

        // Acquire second token for inference
        helper.acquireToken(WakeLockHelper.TOKEN_INFERENCE, 900_000L)
        assertTrue(helper.isHeld)
        assertTrue(helper.hasToken(WakeLockHelper.TOKEN_INFERENCE))
        assertEquals(2, fake.acquireCount)

        // Release inference token: idle_timeout is still active, so fake lock must remain held!
        helper.releaseToken(WakeLockHelper.TOKEN_INFERENCE)
        assertTrue(helper.isHeld)
        assertFalse(helper.hasToken(WakeLockHelper.TOKEN_INFERENCE))
        assertTrue(helper.hasToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT))
        assertEquals(0, fake.releaseCount)

        // Release idle_timeout: all tokens gone, so fake lock must be released!
        helper.releaseToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT)
        assertFalse(helper.isHeld)
        assertFalse(helper.hasToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT))
        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun testForceReleaseClearsAllTokens() {
        val fake = FakeWakeLockAcquirer()
        val helper = WakeLockHelper(fake)

        helper.acquireToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT, 600_000L)
        helper.acquireToken(WakeLockHelper.TOKEN_VECTOR, 300_000L)
        assertTrue(helper.isHeld)

        helper.forceRelease()
        assertFalse(helper.isHeld)
        assertFalse(helper.hasToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT))
        assertFalse(helper.hasToken(WakeLockHelper.TOKEN_VECTOR))
        assertEquals(1, fake.releaseCount)
    }
}
