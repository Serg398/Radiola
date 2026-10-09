#pragma once
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <cstdint>

/** One owned pending frame; overload replaces the pending frame, never queues old work. */
template<class T> class LatestFrameMailbox {
public:
    void reset() { std::lock_guard<std::mutex> lock(mutex_); stopped_ = false; pending_ = false; replaced_ = 0; }
    template<class Writer> void submit(Writer writer) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (stopped_) return;
        if (pending_) replaced_.fetch_add(1, std::memory_order_relaxed);
        writer(frame_); pending_ = true; ready_.notify_one();
    }
    bool take(T& result) {
        std::unique_lock<std::mutex> lock(mutex_);
        ready_.wait(lock, [&] { return stopped_ || pending_; });
        if (stopped_) return false;
        result = frame_; pending_ = false; return true;
    }
    void stop() { std::lock_guard<std::mutex> lock(mutex_); stopped_ = true; pending_ = false; ready_.notify_all(); }
    uint64_t replaced() const { return replaced_.load(std::memory_order_relaxed); }
private:
    T frame_{};
    std::mutex mutex_;
    std::condition_variable ready_;
    bool pending_ = false, stopped_ = true;
    std::atomic<uint64_t> replaced_{0};
};
