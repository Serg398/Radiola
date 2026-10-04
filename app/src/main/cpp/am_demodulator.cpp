#include "am_demodulator.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float pi = 3.14159265358979323846f;
constexpr int tapsCount = 161;
constexpr int channelRate = AmDemodulator::inputSampleRate / 10;

const std::array<float, tapsCount>& channelFilter() {
    static const auto coefficients = [] {
        std::array<float, tapsCount> result{};
        constexpr float cutoff = 5000.0f / channelRate;
        float sum = 0.0f;
        for (int i = 0; i < tapsCount; ++i) {
            const int position = i - (tapsCount - 1) / 2;
            const float sinc = position == 0
                ? 2.0f * cutoff
                : std::sin(2.0f * pi * cutoff * position) / (pi * position);
            const float hamming = 0.54f - 0.46f * std::cos(2.0f * pi * i / (tapsCount - 1));
            result[i] = sinc * hamming;
            sum += result[i];
        }
        for (float& coefficient : result) coefficient /= sum;
        return result;
    }();
    return coefficients;
}
}

void AmDemodulator::reset() {
    historyI_.fill(0.0f);
    historyQ_.fill(0.0f);
    historyPosition_ = 0;
    iqCount_ = 0;
    audioCount_ = 0;
    sumI_ = 0.0f;
    sumQ_ = 0.0f;
    carrier_ = 0.0f;
    warmupSamples_ = 960;
}

size_t AmDemodulator::process(
    const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity
) {
    const auto& coefficients = channelFilter();
    size_t produced = 0;
    for (size_t byte = 0; byte + 1 < byteCount; byte += 2) {
        sumI_ += static_cast<float>(iq[byte]) - 127.5f;
        sumQ_ += static_cast<float>(iq[byte + 1]) - 127.5f;
        if (++iqCount_ < iqDecimation) continue;

        historyI_[historyPosition_] = sumI_ / iqDecimation;
        historyQ_[historyPosition_] = sumQ_ / iqDecimation;
        historyPosition_ = (historyPosition_ + 1) % tapsCount;
        sumI_ = sumQ_ = 0.0f;
        iqCount_ = 0;
        if (++audioCount_ < audioDecimation) continue;
        audioCount_ = 0;

        float filteredI = 0.0f;
        float filteredQ = 0.0f;
        int position = historyPosition_;
        for (float coefficient : coefficients) {
            position = position == 0 ? tapsCount - 1 : position - 1;
            filteredI += coefficient * historyI_[position];
            filteredQ += coefficient * historyQ_[position];
        }
        const float envelope = std::sqrt(filteredI * filteredI + filteredQ * filteredQ);
        if (warmupSamples_ > 0) {
            --warmupSamples_;
            carrier_ = envelope;
            if (produced < capacity) output[produced++] = 0;
            continue;
        }
        carrier_ += 0.001f * (envelope - carrier_);
        const float audio = envelope - carrier_;
        if (produced < capacity) {
            output[produced++] = static_cast<int16_t>(
                std::clamp(audio * 256.0f, -30000.0f, 30000.0f)
            );
        }
    }
    return produced;
}
