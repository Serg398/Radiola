#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "frequency_mixer.h"
#include "iq_decimator.h"
#include "dsp_filters.h"
#include "channel_filter.h"

// USB/LSB: 150..2850 Hz sideband. CW: +/-250 Hz, heard at 700 Hz.
// Complex filters select the sideband before product detection.
class SsbDemodulator {
public:
    void reset() {
        mixer_.reset();
        bandwidth_ = 0; filterMode_ = -1;
        inputI_.fill(0); inputQ_.fill(0);
        channel_.reset();
        inputPosition_ = audioCount_ = voiceCount_ = 0;
        decimator_.reset();
        voiceI_ = voiceQ_ = previousAudio_ = dc_ = 0;
        phase_ = tonePhase_ = 0;
    }
    void setBandwidth(int mode, int hz) {
        bandwidth_ = hz;
        channel_.configure(hz, voiceRate, mode == 5 && hz < 500 ? 513 : 129);
    }
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Native mode IDs: USB=3, LSB=4, CW=5.
    size_t process(const uint8_t* iq, size_t bytes, int mode, int16_t* output, size_t capacity) {
        const auto& inputFilter = DspFilters::ssbInput();
        if (bandwidth_ == 0 || mode != filterMode_) {
            setBandwidth(mode, bandwidth_ == 0 ? DspProfiles::modes[mode].bandwidthHz : bandwidth_);
            filterMode_ = mode;
        }
        constexpr float pi = 3.14159265358979323846f;
        const float shift = mode == 5 ? 0.f : (mode == 3 ? 1.f : -1.f) * (std::abs(DspProfiles::modes[mode].offsetHz) - DspProfiles::modes[mode].bandwidthHz * .5f + bandwidth_ * .5f);
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
            channel_.push(i*c + q*s, q*c - i*s);
            channel_.output(i, q);
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
    ChannelFilter channel_;
    int bandwidth_ = 0, filterMode_ = -1;
    int inputPosition_ = 0, audioCount_ = 0, voiceCount_ = 0;
    float voiceI_ = 0, voiceQ_ = 0;
    float phase_ = 0, tonePhase_ = 0, previousAudio_ = 0, dc_ = 0;
};
