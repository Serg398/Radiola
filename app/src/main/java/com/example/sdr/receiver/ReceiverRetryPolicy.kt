package com.example.sdr.receiver

/** Bounded reconnect delay, shared without sharing protocol-specific recovery decisions. */
internal object ReceiverRetryPolicy {
    fun delayMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)
}
