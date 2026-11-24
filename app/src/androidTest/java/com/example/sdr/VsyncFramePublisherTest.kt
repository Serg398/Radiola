package com.example.sdr

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.receiver.VsyncFramePublisher
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VsyncFramePublisherTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun singleSlowFrameDoesNotWaitForAnotherNetworkPacket() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val delivered = CountDownLatch(1)
            var result = 0
            val publisher = VsyncFramePublisher<Int> { value -> result = value; delivered.countDown() }
            try {
                publisher.offer(42)
                assertTrue("A single frame was left waiting for a second packet", delivered.await(500, TimeUnit.MILLISECONDS))
                assertEquals(42, result)
            } finally { instrumentation.runOnMainSync { publisher.clear() } }
        }
    }

    @Test fun networkBurstKeepsOrderAndPublishesOnSeparateDisplayFrames() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val delivered = CountDownLatch(3)
            val rows = ConcurrentLinkedQueue<Pair<Int, Long>>()
            val publisher = VsyncFramePublisher<Int> { value ->
                rows.add(value to System.nanoTime())
                delivered.countDown()
            }
            try {
                instrumentation.runOnMainSync { repeat(3) { publisher.offer(it) } }
                assertTrue(delivered.await(1, TimeUnit.SECONDS))
                assertEquals(listOf(0, 1, 2), rows.map { it.first })
                rows.toList().zipWithNext().forEach { (first, second) ->
                    assertTrue("Burst frames were published too close together", second.second - first.second >= 20_000_000L)
                }
            } finally { instrumentation.runOnMainSync { publisher.clear() } }
        }
    }

    @Test fun clearCancelsQueuedWakeAndOnlyTheNewSessionIsPublished() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val delivered = CountDownLatch(1)
            val rows = ConcurrentLinkedQueue<Int>()
            val publisher = VsyncFramePublisher<Int> { value -> rows.add(value); delivered.countDown() }
            try {
                instrumentation.runOnMainSync {
                    publisher.offer(1)
                    publisher.clear()
                    publisher.offer(2)
                }
                assertTrue(delivered.await(1, TimeUnit.SECONDS))
                SystemClock.sleep(100)
                assertEquals(listOf(2), rows.toList())
            } finally { instrumentation.runOnMainSync { publisher.clear() } }
        }
    }
}
