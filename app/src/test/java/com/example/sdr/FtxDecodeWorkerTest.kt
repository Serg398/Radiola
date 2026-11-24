package com.example.sdr

import com.example.sdr.decoders.ftx.FtxDecodeWorker
import com.example.sdr.decoders.ftx.FtxMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FtxDecodeWorkerTest {
    @Test fun decodeStartedOnOldFrequencyCannotPublishAfterRetune() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val worker = FtxDecodeWorker(decoder = executor) { _, _ ->
            entered.countDown()
            assertTrue(resume.await(3, TimeUnit.SECONDS))
            arrayOf("1000\tCQ K1ABC FN42")
        }
        try {
            worker.configure(FtxMode.FT8, 14_074_000L)
            val samples = ShortArray(FtxMode.FT8.windowSamples)
            worker.offer(samples, samples.size, 12000, 1)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.configure(FtxMode.FT8, 14_075_000L)
            resume.countDown()
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(worker.snapshot().decoding)
            assertTrue(worker.snapshot().lines.isEmpty())
        } finally { resume.countDown(); worker.close() }
    }

    @Test fun closeDuringDecodeDoesNotPublishOrAcceptMorePcm() {
        val entered = CountDownLatch(1)
        val worker = FtxDecodeWorker { _, _ ->
            entered.countDown()
            CountDownLatch(1).await(3, TimeUnit.SECONDS)
            emptyArray()
        }
        try {
            worker.configure(FtxMode.FT4, 7_047_500L)
            val samples = ShortArray(FtxMode.FT4.windowSamples)
            worker.offer(samples, samples.size, 12000, 1)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            worker.close()
            worker.offer(samples, samples.size, 12000, 1)
            worker.configure(FtxMode.FT8, 14_074_000L)
            assertFalse(worker.snapshot().decoding)
            assertTrue(worker.snapshot().lines.isEmpty())
        } finally { worker.close() }
    }
}
