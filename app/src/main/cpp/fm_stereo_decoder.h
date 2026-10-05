#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>

// ITU-R BS.450 pilot-tone multiplex at 240 kHz -> interleaved L/R at 48 kHz.
class FmStereoDecoder {
public:
    void reset() { *this = FmStereoDecoder(); }
    bool locked() const { return locked_; }
    bool process(float multiplex, bool enabled, int16_t& left, int16_t& right) {
        constexpr float pi = 3.14159265358979323846f;
        constexpr float nominal = 2.f * pi * 19000.f / 240000.f;
        const float sine = std::sin(phase_), cosine = std::cos(phase_);
        // Two low-pass stages isolate the pilot in a quadrature downconverter.
        constexpr float pilotRate = 0.00392f; // 150 Hz per stage.
        pilotI1_ += pilotRate * (multiplex * cosine - pilotI1_);
        pilotQ1_ += pilotRate * (multiplex * sine - pilotQ1_);
        pilotI_ += pilotRate * (pilotI1_ - pilotI_);
        pilotQ_ += pilotRate * (pilotQ1_ - pilotQ_);
        const float amplitude = std::hypot(pilotI_, pilotQ_);
        const float error = amplitude > 0.005f ? std::atan2(pilotI_, pilotQ_) : 0.f;
        frequency_ = std::clamp(frequency_ + 1.e-8f * error, -0.000393f, 0.000393f); // +/-15 Hz.
        phase_ += nominal + frequency_ + 0.0004f * error;
        if (phase_ >= 2.f * pi) phase_ -= 2.f * pi;
        const bool good = amplitude > 0.025f && std::abs(error) < 0.25f;
        if (good) {
            if (goodSamples_ < 24000) ++goodSamples_;
            if (goodSamples_ >= 24000) locked_ = true;
            badSamples_ = 0;
        } else {
            goodSamples_ = 0;
            if (++badSamples_ >= 4800) { locked_ = false; badSamples_ = 4800; }
        }
        // The subcarrier is sin(2*pilot phase), with positive crossings at pilot zeros.
        mono_[position_] = multiplex;
        difference_[position_] = multiplex * (4.f * sine * cosine);
        position_ = (position_ + 1) % taps;
        if (++decimation_ != 5) return false;
        decimation_ = 0;
        float sum = 0.f, difference = 0.f;
        int pos = position_;
        const auto& coefficients = filter();
        for (float coefficient : coefficients) {
            pos = pos == 0 ? taps - 1 : pos - 1;
            sum += coefficient * mono_[pos];
            difference += coefficient * difference_[pos];
        }
        const float target = enabled && locked_ ? 1.f : 0.f;
        blend_ += 0.001f * (target - blend_); // 21 ms; no abrupt channel changes.
        const float channels[2] = {sum + blend_ * difference, sum - blend_ * difference};
        int16_t* output[2] = {&left, &right};
        for (int c = 0; c < 2; ++c) {
            dc_[c] += 0.001f * (channels[c] - dc_[c]);
            deemphasis_[c] += 0.294f * (channels[c] - dc_[c] - deemphasis_[c]); // 50 us.
            *output[c] = static_cast<int16_t>(std::clamp(deemphasis_[c] * 13000.f, -32767.f, 32767.f));
        }
        return true;
    }
private:
    static constexpr int taps = 129;
    static const std::array<float, taps>& filter() {
        static const auto coefficients = [] {
            std::array<float, taps> values{};
            constexpr float pi = 3.14159265358979323846f;
            constexpr float cutoff = 15000.f / 240000.f;
            float total = 0.f;
            for (int i = 0; i < taps; ++i) {
                const int x = i - (taps - 1) / 2;
                const float sinc = x == 0 ? 2.f * cutoff : std::sin(2.f * pi * cutoff * x) / (pi * x);
                // Blackman window suppresses pilot and stereo aliases before decimation.
                values[i] = sinc * (0.42f - 0.5f * std::cos(2.f * pi * i / (taps - 1))
                    + 0.08f * std::cos(4.f * pi * i / (taps - 1)));
                total += values[i];
            }
            for (float& value : values) value /= total;
            return values;
        }();
        return coefficients;
    }
    float phase_ = 0.f, frequency_ = 0.f;
    float pilotI1_ = 0.f, pilotQ1_ = 0.f, pilotI_ = 0.f, pilotQ_ = 0.f;
    int goodSamples_ = 0, badSamples_ = 0;
    bool locked_ = false;
    std::array<float, taps> mono_{}, difference_{};
    int position_ = 0, decimation_ = 0;
    float blend_ = 0.f;
    std::array<float, 2> dc_{}, deemphasis_{};
};
