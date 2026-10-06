#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "frequency_mixer.h"
#include "iq_decimator.h"
#include "dsp_filters.h"

// USB/LSB: 150..2850 Hz sideband. CW: +/-250 Hz, heard at 700 Hz.
// Complex filters select the sideband before product detection.
class SsbDemodulator {
public:
    void reset() {
        mixer_.reset();
        inputI_.fill(0); inputQ_.fill(0);
        channelI_.fill(0); channelQ_.fill(0);
        inputPosition_ = channelPosition_ = audioCount_ = voiceCount_ = 0;
        decimator_.reset();
        voiceI_ = voiceQ_ = previousAudio_ = dc_ = 0;
        phase_ = tonePhase_ = 0;
    }
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Native mode IDs: USB=3, LSB=4, CW=5.
    size_t process(const uint8_t* iq, size_t bytes, int mode, int16_t* output, size_t capacity) {
        const auto& inputFilter = DspFilters::ssbInput();
        const auto& ssbFilter = DspFilters::ssbChannel();
        const auto& cwFilter = DspFilters::cwChannel();
        constexpr float pi = 3.14159265358979323846f;
        const float shift = DspProfiles::modes[mode].offsetHz;
        const auto& coefficients = mode == 5 ? cwFilter : ssbFilter;
        size_t produced = 0;
        for (size_t byte = 0; byte + 1 < bytes; byte += 2) {
            float mixedI, mixedQ;
            mixer_.mix(iq[byte] - 127.5f, iq[byte+1] - 127.5f, mixedI, mixedQ);
            float channelI, channelQ;
            if (!decimator_.push(mixedI, mixedQ, channelI, channelQ)) continue;
            inputI_[inputPosition_] = channelI;
            inputQ_[inputPosition_] = channelQ;
            inputPosition_ = (inputPosition_ + 1) % 161;
            if (++audioCount_ < DspProfiles::channelRate / DspProfiles::audioRate) continue;
            audioCount_ = 0;
            float i = 0, q = 0;
            int position = inputPosition_;
            for (float coefficient : inputFilter) {
                position = position == 0 ? 160 : position - 1;
                i += coefficient * inputI_[position];
                q += coefficient * inputQ_[position];
            }
            voiceI_ += i; voiceQ_ += q;
            if (++voiceCount_ < 4) continue;
            voiceCount_ = 0;
            i = voiceI_ / 4; q = voiceQ_ / 4;
            voiceI_ = voiceQ_ = 0;
            const float c = std::cos(phase_), s = std::sin(phase_);
            channelI_[channelPosition_] = i*c + q*s;
            channelQ_[channelPosition_] = q*c - i*s;
            channelPosition_ = (channelPosition_ + 1) % 129;
            i = q = 0; position = channelPosition_;
            for (float coefficient : coefficients) {
                position = position == 0 ? 128 : position - 1;
                i += coefficient * channelI_[position];
                q += coefficient * channelQ_[position];
            }
            float audio;
            if (mode == 5) {
                audio = i * std::cos(tonePhase_) - q * std::sin(tonePhase_);
                tonePhase_ += 2*pi*700/voiceRate;
                if (tonePhase_ >= 2*pi) tonePhase_ -= 2*pi;
            } else {
                audio = i*c - q*s;
            }
            phase_ += 2*pi*shift/voiceRate;
            if (phase_ >= 2*pi) phase_ -= 2*pi;
            if (phase_ < 0) phase_ += 2*pi;
            dc_ += 0.001f * (audio - dc_);
            audio = (audio - dc_) * 256.0f;
            // Linear interpolation to the common 48 kHz playback rate.
            for (int sample = 1; sample <= 4; ++sample) {
                const float value = previousAudio_ + (audio - previousAudio_) * (sample / 4.0f);
                if (produced < capacity) output[produced++] = static_cast<int16_t>(std::clamp(value, -30000.0f, 30000.0f));
            }
            previousAudio_ = audio;
        }
        return produced;
    }
private:
    static constexpr int voiceRate = DspProfiles::audioRate / 4;
    FrequencyMixer mixer_;
    IqDecimator decimator_;
    std::array<float, 161> inputI_{}, inputQ_{};
    std::array<float, 129> channelI_{}, channelQ_{};
    int inputPosition_ = 0, channelPosition_ = 0, audioCount_ = 0, voiceCount_ = 0;
    float voiceI_ = 0, voiceQ_ = 0;
    float phase_ = 0, tonePhase_ = 0, previousAudio_ = 0, dc_ = 0;
};
