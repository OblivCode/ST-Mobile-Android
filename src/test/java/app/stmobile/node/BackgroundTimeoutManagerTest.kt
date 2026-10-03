package app.stmobile.node

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

            val fired = latch.await(500, TimeUnit.MILLISECONDS)
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
            manager.schedule(100, TimeUnit.MILLISECONDS)
            assertTrue(manager.isScheduled)

            Thread.sleep(15)
            manager.cancel()
            assertFalse(manager.isScheduled)

            val fired = latch.await(200, TimeUnit.MILLISECONDS)
            assertFalse("Timeout callback must not be invoked after cancel", fired)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testZeroOrNegativeTimeoutDisabled() {
        var called = false
        val manager = BackgroundTimeoutManager {
            called = true
        }

        try {
            manager.schedule(0, TimeUnit.MILLISECONDS)
            assertFalse("Zero timeout should not be scheduled", manager.isScheduled)

            manager.schedule(-5, TimeUnit.MINUTES)
            assertFalse("Negative timeout should not be scheduled", manager.isScheduled)

            Thread.sleep(50)
            assertFalse("Callback should never be invoked when timeout <= 0", called)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testRescheduleOverridesPrevious() {
        val counter = AtomicInteger(0)
        val manager = BackgroundTimeoutManager {
            counter.incrementAndGet()
        }

        try {
            manager.schedule(200, TimeUnit.MILLISECONDS)
            // Immediately reschedule with shorter delay
            manager.schedule(40, TimeUnit.MILLISECONDS)

            Thread.sleep(150)
            assertEquals("Only the rescheduled task should execute", 1, counter.get())
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun testZeroDaemonLeaksAndImmediateQueuePurge() {
        val customExecutor = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "test-timeout-worker").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
        }

        val latch = CountDownLatch(1)
        val manager = BackgroundTimeoutManager(scheduler = customExecutor) {
            latch.countDown()
        }

        try {
            // Schedule long running timeout
            manager.schedule(60, TimeUnit.MINUTES)
            assertTrue(manager.isScheduled)
            assertEquals("Executor queue must hold exactly 1 pending task", 1, customExecutor.queue.size)

            // Cancel must purge immediately from queue due to removeOnCancelPolicy
            manager.cancel()
            assertFalse(manager.isScheduled)
            assertEquals("Executor queue must be immediately purged of cancelled task", 0, customExecutor.queue.size)
        } finally {
            manager.shutdown()
            assertTrue("Executor must be shut down", customExecutor.isShutdown)
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
}
