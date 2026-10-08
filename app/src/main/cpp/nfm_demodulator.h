#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "frequency_mixer.h"
#include "iq_decimator.h"
#include "dsp_filters.h"
#include "channel_filter.h"

// 12.5 kHz narrow FM channel; 2.4 MSPS IQ to 48 kHz mono PCM.
class NfmDemodulator {
public:
    void reset() {
        mixer_.reset();
        setBandwidth(DspProfiles::modes[2].bandwidthHz);
        channel_.reset();
        audioCount_ = 0;
        decimator_.reset();
        previousI_ = previousQ_ = 0;
        dc_ = lowpass1_ = lowpass2_ = lastPhase_ = 0;
        havePrevious_ = discardPhase_ = false;
    }
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    void setBandwidth(int hz) { channel_.configure(hz, DspProfiles::channelRate, hz == DspProfiles::modes[2].bandwidthHz ? 161 : 641); }
    void retune() { discardPhase_ = true; }
    size_t process(const uint8_t* iq, size_t bytes, int16_t* output, size_t capacity) {
        size_t produced = 0;
        for (size_t byte = 0; byte + 1 < bytes; byte += 2) {
            float mixedI, mixedQ;
            mixer_.mix(iq[byte] - 127.5f, iq[byte + 1] - 127.5f, mixedI, mixedQ);
            float channelI, channelQ;
            if (!decimator_.push(mixedI, mixedQ, channelI, channelQ)) continue;
            channel_.push(channelI, channelQ);
            if (++audioCount_ < DspProfiles::channelRate / DspProfiles::audioRate) continue;
            audioCount_ = 0;
            float filteredI = 0, filteredQ = 0;
            channel_.output(filteredI, filteredQ);
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
    ChannelFilter channel_;
    int audioCount_ = 0;
    float previousI_ = 0, previousQ_ = 0;
    float dc_ = 0, lowpass1_ = 0, lowpass2_ = 0, lastPhase_ = 0;
    bool havePrevious_ = false, discardPhase_ = false;
};
