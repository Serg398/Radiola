#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "frequency_mixer.h"

// USB/LSB: 150..2850 Hz sideband. CW: +/-250 Hz, heard at 700 Hz.
// Complex filters select the sideband before product detection.
class SsbDemodulator {
public:
    void reset() {
        mixer_.reset();
        inputI_.fill(0); inputQ_.fill(0);
        channelI_.fill(0); channelQ_.fill(0);
        inputPosition_ = channelPosition_ = iqCount_ = audioCount_ = voiceCount_ = 0;
        sumI_ = sumQ_ = voiceI_ = voiceQ_ = previousAudio_ = dc_ = 0;
        phase_ = tonePhase_ = 0;
    }
    void setFrequencyOffset(int hz) { mixer_.setOffset(hz); }
    // Native mode IDs: USB=3, LSB=4, CW=5.
    size_t process(const uint8_t* iq, size_t bytes, int mode, int16_t* output, size_t capacity) {
        static const auto inputFilter = filter<161>(5000.0f, 240000.0f);
        static const auto ssbFilter = filter<129>(1350.0f, 12000.0f);
        static const auto cwFilter = filter<129>(250.0f, 12000.0f);
        constexpr float pi = 3.14159265358979323846f;
        const float shift = mode == 3 ? 1500.0f : mode == 4 ? -1500.0f : 0.0f;
        const auto& coefficients = mode == 5 ? cwFilter : ssbFilter;
        size_t produced = 0;
        for (size_t byte = 0; byte + 1 < bytes; byte += 2) {
            float mixedI, mixedQ;
            mixer_.mix(iq[byte] - 127.5f, iq[byte+1] - 127.5f, mixedI, mixedQ);
            sumI_ += mixedI; sumQ_ += mixedQ;
            if (++iqCount_ < 10) continue;
            inputI_[inputPosition_] = sumI_ / 10;
            inputQ_[inputPosition_] = sumQ_ / 10;
            inputPosition_ = (inputPosition_ + 1) % 161;
            sumI_ = sumQ_ = 0; iqCount_ = 0;
            if (++audioCount_ < 5) continue;
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
                tonePhase_ += 2*pi*700/12000;
                if (tonePhase_ >= 2*pi) tonePhase_ -= 2*pi;
            } else {
                audio = i*c - q*s;
            }
            phase_ += 2*pi*shift/12000;
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
    template <size_t N> static std::array<float, N> filter(float cutoffHz, float rate) {
        constexpr float pi = 3.14159265358979323846f;
        std::array<float, N> result{};
        const float cutoff = cutoffHz / rate;
        float sum = 0;
        for (size_t i = 0; i < N; ++i) {
            const int p = static_cast<int>(i) - (N - 1) / 2;
            const float sinc = p == 0 ? 2*cutoff : std::sin(2*pi*cutoff*p)/(pi*p);
            result[i] = sinc * (0.54f - 0.46f * std::cos(2*pi*i/(N-1)));
            sum += result[i];
        }
        for (auto& coefficient : result) coefficient /= sum;
        return result;
    }
    FrequencyMixer mixer_;
    std::array<float, 161> inputI_{}, inputQ_{};
    std::array<float, 129> channelI_{}, channelQ_{};
    int inputPosition_ = 0, channelPosition_ = 0, iqCount_ = 0, audioCount_ = 0, voiceCount_ = 0;
    float sumI_ = 0, sumQ_ = 0, voiceI_ = 0, voiceQ_ = 0;
    float phase_ = 0, tonePhase_ = 0, previousAudio_ = 0, dc_ = 0;
};
