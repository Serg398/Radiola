#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>

inline uint64_t visualNowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
struct VisualTiming {
    std::atomic<uint64_t> total{0}, count{0}, maximum{0};
    void reset() { total = 0; count = 0; maximum = 0; }
    void record(uint64_t ns) {
        total.fetch_add(ns, std::memory_order_relaxed); count.fetch_add(1, std::memory_order_relaxed);
        auto old = maximum.load(std::memory_order_relaxed);
        while (ns > old && !maximum.compare_exchange_weak(old, ns, std::memory_order_relaxed)) {}
    }
};
