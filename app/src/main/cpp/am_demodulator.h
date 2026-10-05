#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include "frequency_mixer.h"

class AmDemodulator {
public:
    static constexpr int inputSampleRate = 2400000;
    static constexpr int channelBandwidthHz = 12000;
    void reset();
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Input: interleaved unsigned 8-bit IQ at 2.4 Msps. Output: mono PCM at 48 kHz.
    size_t process(const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity);

private:
    FrequencyMixer mixer_;
    static constexpr int iqDecimation = 10;
    static constexpr int audioDecimation = 5;
    static constexpr int tapsCount = 161;
    std::array<float, tapsCount> historyI_{};
    std::array<float, tapsCount> historyQ_{};
    int historyPosition_ = 0;
    int iqCount_ = 0;
    int audioCount_ = 0;
    float sumI_ = 0.0f;
    float sumQ_ = 0.0f;
    float carrier_ = 0.0f;
    int warmupSamples_ = 960;
};
