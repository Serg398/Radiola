#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>

// Owned by the IQ producer. Never stops the PCM stream or resets demodulators.
class Squelch {
public:
    void reset() { open_ = false; hold_ = 0; gain_ = 0.f; }
    bool process(int16_t* pcm, size_t count, bool enabled, float threshold, float signal, size_t channels = 1) {
        const size_t frames = count / channels;
        if (!enabled) { open_ = true; hold_ = 0; }
        else if (signal >= threshold) { open_ = true; hold_ = 5760; }
        else if (open_ && signal >= threshold - 3.f) { hold_ = 5760; }
        else if (hold_ > frames) { hold_ -= frames; }
        else { hold_ = 0; open_ = false; }
        const float target = open_ ? 1.f : 0.f;
        for (size_t i = 0; i + channels <= count; i += channels) {
            gain_ += std::clamp(target - gain_, -1.f / 240.f, 1.f / 240.f);
            for (size_t c = 0; c < channels; ++c) pcm[i + c] = static_cast<int16_t>(pcm[i + c] * gain_);
        }
        return open_;
    }
private:
    bool open_ = false;
    size_t hold_ = 0;
    float gain_ = 0.f;
};
