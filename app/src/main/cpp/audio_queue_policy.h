#pragma once
#include <cstdint>

// The consumer owns tail. Discard an overdue queue instead of replaying stale audio.
// Unsigned subtraction also handles the 32-bit ring sequence wrap.
inline bool audioQueueOverdue(uint32_t head, uint32_t tail) {
    constexpr uint32_t maximumQueuedSamples = 48000 * 2 * 120 / 1000;
    return head - tail > maximumQueuedSamples;
}
