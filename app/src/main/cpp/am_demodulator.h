#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include "frequency_mixer.h"
#include "iq_decimator.h"

class AmDemodulator {
public:
    static constexpr int inputSampleRate = DspProfiles::iqRate;
    static constexpr int channelBandwidthHz = DspProfiles::modes[1].bandwidthHz;
    void reset();
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Input: interleaved unsigned 8-bit IQ at 2.4 Msps. Output: mono PCM at 48 kHz.
    size_t process(const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity);

private:
    FrequencyMixer mixer_;
    IqDecimator decimator_;
    static constexpr int audioDecimation = DspProfiles::channelRate / DspProfiles::audioRate;
    static constexpr int tapsCount = 161;
    std::array<float, tapsCount> historyI_{};
    std::array<float, tapsCount> historyQ_{};
    int historyPosition_ = 0;
    int audioCount_ = 0;
    float carrier_ = 0.0f;
    int warmupSamples_ = 960;
};
