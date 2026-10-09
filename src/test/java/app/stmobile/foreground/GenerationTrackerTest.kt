package app.stmobile.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GenerationTrackerTest {

    @Test
    fun testSingleSourceLifecycle() {
        var startCount = 0
        var endCount = 0
        val tracker = GenerationTracker(
            onFirstStarted = { startCount++ },
            onAllEnded = { endCount++ },
        )

        assertFalse(tracker.isGenerating)
        assertEquals(0, tracker.activeCount)

        assertTrue("First start should return true", tracker.onStarted("webview"))
        assertTrue(tracker.isGenerating)
        assertEquals(1, tracker.activeCount)
        assertEquals(1, startCount)
        assertEquals(0, endCount)

        assertTrue("Final end should return true", tracker.onEnded("webview"))
        assertFalse(tracker.isGenerating)
        assertEquals(0, tracker.activeCount)
        assertEquals(1, startCount)
        assertEquals(1, endCount)
    }

    @Test
    fun testOverlappingMultiSources() {
        val starts = AtomicInteger(0)
        val ends = AtomicInteger(0)
        val tracker = GenerationTracker(
            onFirstStarted = { starts.incrementAndGet() },
            onAllEnded = { ends.incrementAndGet() },
        )

        assertTrue(tracker.onStarted("webview"))
        assertEquals(1, starts.get())
        assertEquals(1, tracker.activeCount)

        // Second source ("ipc") starts while "webview" is active
        assertFalse("Second overlapping source start should return false", tracker.onStarted("ipc"))
        assertEquals(1, starts.get()) // No second start trigger
        assertEquals(2, tracker.activeCount)
        assertTrue(tracker.isGenerating)

        // "webview" ends (e.g. reload or client finished)
        assertFalse("First source ending while another active should return false", tracker.onEnded("webview"))
        assertEquals(0, ends.get()) // No end trigger yet
        assertEquals(1, tracker.activeCount)
        assertTrue(tracker.isGenerating)

        // "ipc" ends
        assertTrue("Last source ending should return true", tracker.onEnded("ipc"))
        assertEquals(1, ends.get())
        assertEquals(0, tracker.activeCount)
        assertFalse(tracker.isGenerating)
    }

    @Test
    fun testDuplicateEndedIdempotency() {
        var endCount = 0
        val tracker = GenerationTracker(
            onFirstStarted = {},
            onAllEnded = { endCount++ },
        )

        tracker.onStarted("webview")
        tracker.onEnded("webview")
        assertEquals(1, endCount)

        // Repeated onEnded calls (e.g. rapid page reloads or disconnect retries)
        assertFalse(tracker.onEnded("webview"))
        assertFalse(tracker.onEnded("webview"))
        assertFalse(tracker.onEnded("nonexistent"))
        assertEquals(1, endCount)
        assertEquals(0, tracker.activeCount)
        assertFalse(tracker.isGenerating)
    }

    @Test
    fun testDuplicateStartIdempotency() {
        var startCount = 0
        val tracker = GenerationTracker(
            onFirstStarted = { startCount++ },
            onAllEnded = {},
        )

        assertTrue(tracker.onStarted("webview"))
        assertFalse("Duplicate start with same source token should return false", tracker.onStarted("webview"))
        assertEquals(1, startCount)
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun testResetCleansUpActiveSources() {
        var endCount = 0
        val tracker = GenerationTracker(
            onFirstStarted = {},
            onAllEnded = { endCount++ },
        )

        tracker.onStarted("webview")
        tracker.onStarted("ipc")
        assertEquals(2, tracker.activeCount)
        assertTrue(tracker.isGenerating)

        assertTrue("Reset while generating should return true and trigger onAllEnded", tracker.reset())
        assertEquals(1, endCount)
        assertEquals(0, tracker.activeCount)
        assertFalse(tracker.isGenerating)

        assertFalse("Reset while idle should return false", tracker.reset())
        assertEquals(1, endCount)
    }

    @Test
    fun testConcurrentSourceTransitions() {
        val starts = AtomicInteger(0)
        val ends = AtomicInteger(0)
        val tracker = GenerationTracker(
            onFirstStarted = { starts.incrementAndGet() },
            onAllEnded = { ends.incrementAndGet() },
        )

        val threads = 8
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)

        for (i in 0 until threads) {
            val source = "source-$i"
            executor.execute {
                try {
                    for (j in 0 until iterationsPerThread) {
                        tracker.onStarted(source)
                        tracker.onEnded(source)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent iterations must complete", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("All active sources must be cleared after balanced start/end calls", 0, tracker.activeCount)
        assertFalse(tracker.isGenerating)
        assertEquals("Edge-triggered starts and ends must balance", starts.get(), ends.get())
    }
}
