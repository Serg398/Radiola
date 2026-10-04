#include "wfm_demodulator.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float pi = 3.14159265358979323846f;
constexpr int tapsCount = 81;

const std::array<float, tapsCount>& audioFilter() {
    static const auto coefficients = [] {
        std::array<float, tapsCount> result{};
        constexpr float cutoff = 15000.0f / WfmDemodulator::channelBandwidthHz;
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

void WfmDemodulator::reset() {
    sumI_ = 0.0f;
    sumQ_ = 0.0f;
    iqCount_ = 0;
    previousI_ = 0.0f;
    previousQ_ = 0.0f;
    havePrevious_ = false;
    discriminatorHistory_.fill(0.0f);
    historyPosition_ = 0;
    audioCount_ = 0;
    dcEstimate_ = 0.0f;
    deemphasis_ = 0.0f;
}

size_t WfmDemodulator::process(
    const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity
) {
    size_t produced = 0;
    const auto& coefficients = audioFilter();
    for (size_t byte = 0; byte + 1 < byteCount; byte += 2) {
        sumI_ += static_cast<float>(iq[byte]) - 127.5f;
        sumQ_ += static_cast<float>(iq[byte + 1]) - 127.5f;
        if (++iqCount_ < iqDecimation) continue;

        const float currentI = sumI_ / iqDecimation;
        const float currentQ = sumQ_ / iqDecimation;
        sumI_ = 0.0f;
        sumQ_ = 0.0f;
        iqCount_ = 0;

        if (havePrevious_) {
            const float cross = previousI_ * currentQ - previousQ_ * currentI;
            const float dot = previousI_ * currentI + previousQ_ * currentQ;
            const float phase = std::atan2(cross, dot);
            discriminatorHistory_[historyPosition_] = phase;
            historyPosition_ = (historyPosition_ + 1) % tapsCount;
            if (++audioCount_ == audioDecimation) {
                audioCount_ = 0;
                float filtered = 0.0f;
                int position = historyPosition_;
                for (float coefficient : coefficients) {
                    position = position == 0 ? tapsCount - 1 : position - 1;
                    filtered += coefficient * discriminatorHistory_[position];
                }
                // Remove carrier offset, then apply 50 us broadcast FM de-emphasis.
                dcEstimate_ += 0.001f * (filtered - dcEstimate_);
                const float audio = filtered - dcEstimate_;
                deemphasis_ += 0.294f * (audio - deemphasis_);
                if (produced < capacity) {
                    output[produced++] = static_cast<int16_t>(
                        std::clamp(deemphasis_ * 13000.0f, -32767.0f, 32767.0f)
                    );
                }
            }
        }
        previousI_ = currentI;
        previousQ_ = currentQ;
        havePrevious_ = true;
    }
    return produced;
}
