#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include "frequency_mixer.h"

class WfmDemodulator {
public:
    static constexpr int inputSampleRate = 2400000;
    static constexpr int channelBandwidthHz = 240000;
    void reset();
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Keep the audio filters running; ignore only the first cross-tuning phase difference.
    void retune() { discardPhaseOnce_ = true; }
    // Input: interleaved unsigned 8-bit IQ at 2.4 Msps. Output: mono PCM at 48 kHz.
    size_t process(const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity);

private:
    FrequencyMixer mixer_;
    static constexpr int iqDecimation = inputSampleRate / channelBandwidthHz;
    static constexpr int audioDecimation = 5;
    static constexpr int tapsCount = 81;
    float sumI_ = 0.0f;
    float sumQ_ = 0.0f;
    int iqCount_ = 0;
    float previousI_ = 0.0f;
    float previousQ_ = 0.0f;
    bool havePrevious_ = false;
    bool discardPhaseOnce_ = false;
    float lastPhase_ = 0.0f;
    std::array<float, tapsCount> discriminatorHistory_{};
    int historyPosition_ = 0;
    int audioCount_ = 0;
    float dcEstimate_ = 0.0f;
    float deemphasis_ = 0.0f;
};
