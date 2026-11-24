#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

class ChannelIqTap {
public:
    static constexpr size_t pairsPerBlock = 4096;
    struct Block {
        std::array<int16_t, pairsPerBlock * 2> samples{};
        int64_t frequencyHz = 0;
        uint64_t epoch = 0, index = 0, timestampNs = 0;
    };

    void enable(bool enabled) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto token = subscription_.load(std::memory_order_relaxed);
        if (static_cast<bool>(token & 1) == enabled) return;
        subscription_.store(token + 1, std::memory_order_release);
        queued_ = tail_ = 0;
    }

    // Called once per USB callback, then push() runs on that same producer thread.
    bool begin(uint64_t session, uint64_t tuning, int mode, uint64_t timestampNs) {
        const auto token = subscription_.load(std::memory_order_acquire);
        if (!(token & 1)) return false;
        if (token != token_ || session != session_ || tuning != tuning_ || mode != mode_) {
            token_ = token;
            session_ = session;
            tuning_ = tuning;
            mode_ = mode;
            ++pending_.epoch;
            pending_.index = 0;
            pending_.frequencyHz = static_cast<int64_t>(tuning >> 32) + static_cast<int32_t>(tuning);
            filled_ = 0;
        }
        callbackNs_ = timestampNs;
        return true;
    }

    void push(float i, float q) {
        if (filled_ == 0) pending_.timestampNs = callbackNs_;
        pending_.samples[2 * filled_] = sample16(i);
        pending_.samples[2 * filled_ + 1] = sample16(q);
        if (++filled_ != pairsPerBlock) return;
        publish();
        pending_.index += pairsPerBlock;
        filled_ = 0;
    }

    bool read(Block& output) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!queued_) return false;
        output = queue_[tail_];
        tail_ = (tail_ + 1) % queue_.size();
        --queued_;
        return true;
    }

private:
    static int16_t sample16(float value) {
        return static_cast<int16_t>(std::clamp(value * 256.0f, -32768.0f, 32767.0f));
    }

    void publish() {
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (!lock || subscription_.load(std::memory_order_relaxed) != token_) return;
        if (queued_ && queue_[tail_].epoch != pending_.epoch) queued_ = tail_ = 0;
        if (queued_ == queue_.size()) {
            tail_ = (tail_ + 1) % queue_.size();
            --queued_;
        }
        queue_[(tail_ + queued_) % queue_.size()] = pending_;
        ++queued_;
    }

    std::atomic<uint64_t> subscription_{0};
    std::mutex mutex_;
    std::array<Block, 12> queue_{};
    size_t queued_ = 0, tail_ = 0;
    Block pending_;
    size_t filled_ = 0;
    uint64_t token_ = 0, session_ = 0, tuning_ = 0, callbackNs_ = 0;
    int mode_ = -1;
};
