#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "frequency_mixer.h"

// 12.5 kHz narrow FM channel; 2.4 MSPS IQ to 48 kHz mono PCM.
class NfmDemodulator {
public:
    void reset() {
        mixer_.reset();
        historyI_.fill(0); historyQ_.fill(0);
        position_ = iqCount_ = audioCount_ = 0;
        sumI_ = sumQ_ = previousI_ = previousQ_ = 0;
        dc_ = lowpass1_ = lowpass2_ = lastPhase_ = 0;
        havePrevious_ = discardPhase_ = false;
    }
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    void retune() { discardPhase_ = true; }
    size_t process(const uint8_t* iq, size_t bytes, int16_t* output, size_t capacity) {
        static const auto coefficients = [] {
            std::array<float, taps> result{};
            constexpr float pi = 3.14159265358979323846f;
            constexpr float cutoff = 6000.0f / 240000.0f;
            float sum = 0;
            for (int i = 0; i < taps; ++i) {
                const int p = i - (taps - 1) / 2;
                const float sinc = p == 0 ? 2 * cutoff : std::sin(2 * pi * cutoff * p) / (pi * p);
                result[i] = sinc * (0.54f - 0.46f * std::cos(2 * pi * i / (taps - 1)));
                sum += result[i];
            }
            for (auto& coefficient : result) coefficient /= sum;
            return result;
        }();
        size_t produced = 0;
        for (size_t byte = 0; byte + 1 < bytes; byte += 2) {
            float mixedI, mixedQ;
            mixer_.mix(iq[byte] - 127.5f, iq[byte + 1] - 127.5f, mixedI, mixedQ);
            sumI_ += mixedI; sumQ_ += mixedQ;
            if (++iqCount_ < 10) continue;
            historyI_[position_] = sumI_ / 10;
            historyQ_[position_] = sumQ_ / 10;
            position_ = (position_ + 1) % taps;
            sumI_ = sumQ_ = 0; iqCount_ = 0;
            if (++audioCount_ < 5) continue;
            audioCount_ = 0;
            float filteredI = 0, filteredQ = 0;
            int p = position_;
            for (float coefficient : coefficients) {
                p = p == 0 ? taps - 1 : p - 1;
                filteredI += coefficient * historyI_[p];
                filteredQ += coefficient * historyQ_[p];
            }
            if (havePrevious_) {
                const float phase = discardPhase_ ? lastPhase_ : std::atan2(
                    previousI_ * filteredQ - previousQ_ * filteredI,
                    previousI_ * filteredI + previousQ_ * filteredQ);
                discardPhase_ = false; lastPhase_ = phase;
                dc_ += 0.001f * (phase - dc_);
                // Voice low-pass; no broadcast WFM de-emphasis.
                lowpass1_ += 0.325f * (phase - dc_ - lowpass1_);
                lowpass2_ += 0.325f * (lowpass1_ - lowpass2_);
                if (produced < capacity) output[produced++] = static_cast<int16_t>(
                    std::clamp(lowpass2_ * 15000.0f, -30000.0f, 30000.0f));
            }
            previousI_ = filteredI; previousQ_ = filteredQ; havePrevious_ = true;
        }
        return produced;
    }
private:
    static constexpr int taps = 161;
    FrequencyMixer mixer_;
    std::array<float, taps> historyI_{}, historyQ_{};
    int position_ = 0, iqCount_ = 0, audioCount_ = 0;
    float sumI_ = 0, sumQ_ = 0, previousI_ = 0, previousQ_ = 0;
    float dc_ = 0, lowpass1_ = 0, lowpass2_ = 0, lastPhase_ = 0;
    bool havePrevious_ = false, discardPhase_ = false;
};
