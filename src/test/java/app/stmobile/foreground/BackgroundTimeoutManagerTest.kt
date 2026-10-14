package app.stmobile.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundTimeoutManagerTest {

    @Test
    fun testTimeoutTriggered() {
        val latch = CountDownLatch(1)
        val manager = BackgroundTimeoutManager {
            latch.countDown()
        }

        try {
            manager.schedule(50, TimeUnit.MILLISECONDS)
            assertTrue("Timeout task should be marked scheduled", manager.isScheduled)

            val fired = latch.await(1000, TimeUnit.MILLISECONDS)
            assertTrue("Timeout callback must be invoked upon expiration", fired)
            assertFalse("Timeout task should be cleared after firing", manager.isScheduled)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testTimeoutCancelled() {
        val latch = CountDownLatch(1)
        val manager = BackgroundTimeoutManager {
            latch.countDown()
        }

        try {
            manager.schedule(5, TimeUnit.MINUTES)
            assertTrue(manager.isScheduled)

            manager.cancel()
            assertFalse(manager.isScheduled)
            assertEquals(0L, manager.getRemainingMillis())

            val fired = latch.await(100, TimeUnit.MILLISECONDS)
            assertFalse("Timeout callback must not be invoked after cancel", fired)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testPauseAndResumeCountdown() {
        val latch = CountDownLatch(1)
        val manager = BackgroundTimeoutManager {
            latch.countDown()
        }

        try {
            manager.schedule(500, TimeUnit.MILLISECONDS)
            assertTrue(manager.isScheduled)
            assertFalse(manager.isPaused)

            manager.pause()
            assertTrue(manager.isScheduled)
            assertTrue(manager.isPaused)
            assertTrue("Remaining millis must be preserved", manager.getRemainingMillis() > 0)

            // While paused, callback must not fire
            val firedWhilePaused = latch.await(600, TimeUnit.MILLISECONDS)
            assertFalse("Timeout must not fire while paused", firedWhilePaused)

            // Resume should continue and fire with remaining time
            manager.resume()
            assertFalse(manager.isPaused)
            assertTrue(manager.isScheduled)

            val firedAfterResume = latch.await(2000, TimeUnit.MILLISECONDS)
            assertTrue("Timeout must fire after resume", firedAfterResume)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testZeroOrNegativeTimeoutDisabled() {
        val latch = CountDownLatch(1)
        val manager = BackgroundTimeoutManager {
            latch.countDown()
        }

        try {
            manager.schedule(0, TimeUnit.MILLISECONDS)
            assertFalse("Zero timeout should not be scheduled", manager.isScheduled)

            manager.schedule(-5, TimeUnit.MINUTES)
            assertFalse("Negative timeout should not be scheduled", manager.isScheduled)

            val fired = latch.await(50, TimeUnit.MILLISECONDS)
            assertFalse("Callback should never be invoked when timeout <= 0", fired)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testRescheduleOverridesPrevious() {
        val counter = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val manager = BackgroundTimeoutManager {
            counter.incrementAndGet()
            latch.countDown()
        }

        try {
            // First schedule with a long delay that will not fire during test
            manager.schedule(5, TimeUnit.MINUTES)
            // Immediately reschedule with shorter delay
            manager.schedule(50, TimeUnit.MILLISECONDS)

            val fired = latch.await(1000, TimeUnit.MILLISECONDS)
            assertTrue("Rescheduled task should execute", fired)
            assertEquals("Only the rescheduled task should execute", 1, counter.get())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testShutdownTerminatesSchedulerCleanly() {
        val customExecutor = ScheduledThreadPoolExecutor(1)
        val manager = BackgroundTimeoutManager(scheduler = customExecutor) {}

        manager.schedule(10, TimeUnit.MINUTES)
        assertTrue(manager.isScheduled)

        manager.shutdown()
        assertFalse(manager.isScheduled)
        assertTrue("Scheduler must be marked shutdown", customExecutor.isShutdown)
        val terminated = customExecutor.awaitTermination(200, TimeUnit.MILLISECONDS)
        assertTrue("Scheduler threads must terminate cleanly", terminated)
    }

    @Test
    fun testOnTickInvokedPeriodicallyAndStopsOnCancel() {
        val tickCount = AtomicInteger(0)
        val tickLatch = CountDownLatch(3)
        val manager = BackgroundTimeoutManager(
            tickIntervalMs = 20L,
            onTick = {
                tickCount.incrementAndGet()
                tickLatch.countDown()
            },
            onTimeout = {},
        )

        try {
            manager.schedule(5, TimeUnit.MINUTES)
            val ticksFired = tickLatch.await(500, TimeUnit.MILLISECONDS)
            assertTrue("onTick should fire periodically while scheduled", ticksFired)
            assertTrue("onTick should have fired at least 3 times", tickCount.get() >= 3)

            manager.cancel()
            val countAfterCancel = tickCount.get()
            Thread.sleep(80)
            assertEquals("onTick must not continue after cancel", countAfterCancel, tickCount.get())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testFormatRemainingDuration() {
        assertEquals("0s", ServiceNotificationHelper.formatRemainingDuration(0L))
        assertEquals("0s", ServiceNotificationHelper.formatRemainingDuration(-500L))
        assertEquals("1s", ServiceNotificationHelper.formatRemainingDuration(1L))
        assertEquals("1s", ServiceNotificationHelper.formatRemainingDuration(999L))
        assertEquals("1s", ServiceNotificationHelper.formatRemainingDuration(1000L))
        assertEquals("2s", ServiceNotificationHelper.formatRemainingDuration(1001L))
        assertEquals("59s", ServiceNotificationHelper.formatRemainingDuration(59000L))
        assertEquals("1m", ServiceNotificationHelper.formatRemainingDuration(60000L))
        assertEquals("1m 5s", ServiceNotificationHelper.formatRemainingDuration(64001L))
        assertEquals("9m 30s", ServiceNotificationHelper.formatRemainingDuration(569001L))
        assertEquals("10m", ServiceNotificationHelper.formatRemainingDuration(600000L))
    }
}
