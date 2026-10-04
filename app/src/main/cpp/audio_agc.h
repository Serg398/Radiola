#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

// Audio level control shared by every demodulation mode; owned by the IQ thread.
class AudioAgc {
public:
    void reset() { level_ = 4000.0f; gain_ = 1.0f; }

    void process(int16_t* pcm, size_t count, bool enabled) {
        for (size_t i = 0; i < count; ++i) {
            const float amplitude = std::abs(static_cast<float>(pcm[i]));
            const float rate = amplitude > level_ ? 0.002f : 0.00007f;
            level_ += rate * (amplitude - level_);
            const float target = enabled ? std::clamp(4000.0f / std::max(level_, 500.0f), 0.15f, 8.0f) : 1.0f;
            gain_ += 0.002f * (target - gain_);
            pcm[i] = static_cast<int16_t>(std::clamp(pcm[i] * gain_, -30000.0f, 30000.0f));
        }
    }

private:
    float level_ = 4000.0f;
    float gain_ = 1.0f;
};
