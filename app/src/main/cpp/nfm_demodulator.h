#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "frequency_mixer.h"
#include "iq_decimator.h"
#include "dsp_filters.h"

// 12.5 kHz narrow FM channel; 2.4 MSPS IQ to 48 kHz mono PCM.
class NfmDemodulator {
public:
    void reset() {
        mixer_.reset();
        historyI_.fill(0); historyQ_.fill(0);
        position_ = audioCount_ = 0;
        decimator_.reset();
        previousI_ = previousQ_ = 0;
        dc_ = lowpass1_ = lowpass2_ = lastPhase_ = 0;
        havePrevious_ = discardPhase_ = false;
    }
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    void retune() { discardPhase_ = true; }
    size_t process(const uint8_t* iq, size_t bytes, int16_t* output, size_t capacity) {
        const auto& coefficients = DspFilters::nfmChannel();
        size_t produced = 0;
        for (size_t byte = 0; byte + 1 < bytes; byte += 2) {
            float mixedI, mixedQ;
            mixer_.mix(iq[byte] - 127.5f, iq[byte + 1] - 127.5f, mixedI, mixedQ);
            float channelI, channelQ;
            if (!decimator_.push(mixedI, mixedQ, channelI, channelQ)) continue;
            historyI_[position_] = channelI;
            historyQ_[position_] = channelQ;
            position_ = (position_ + 1) % taps;
            if (++audioCount_ < DspProfiles::channelRate / DspProfiles::audioRate) continue;
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
    IqDecimator decimator_;
    std::array<float, taps> historyI_{}, historyQ_{};
    int position_ = 0, audioCount_ = 0;
    float previousI_ = 0, previousQ_ = 0;
    float dc_ = 0, lowpass1_ = 0, lowpass2_ = 0, lastPhase_ = 0;
    bool havePrevious_ = false, discardPhase_ = false;
};
