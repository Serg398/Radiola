#pragma once
#include <array>
#include <cmath>
#include "dsp_profiles.h"

namespace DspFilters {
template<size_t N> std::array<float, N> lowpass(float cutoffHz, float rate) {
    constexpr float pi = 3.14159265358979323846f;
    std::array<float, N> result{};
    const float cutoff = cutoffHz / rate;
    float sum = 0;
    for (size_t i = 0; i < N; ++i) {
        const int p = static_cast<int>(i) - static_cast<int>((N - 1) / 2);
        const float sinc = p == 0 ? 2*cutoff : std::sin(2*pi*cutoff*p)/(pi*p);
        result[i] = sinc * (0.54f - 0.46f * std::cos(2*pi*i/(N-1)));
        sum += result[i];
    }
    for (float& coefficient : result) coefficient /= sum;
    return result;
}
inline const auto& amChannel() {
    static const auto taps = lowpass<161>(DspProfiles::modes[1].filterCutoffHz, DspProfiles::channelRate);
    return taps;
}
inline const auto& nfmChannel() {
    static const auto taps = lowpass<161>(DspProfiles::modes[2].filterCutoffHz, DspProfiles::channelRate);
    return taps;
}
inline const auto& ssbInput() {
    static const auto taps = lowpass<161>(DspProfiles::modes[3].inputCutoffHz, DspProfiles::channelRate);
    return taps;
}
inline const auto& ssbChannel() {
    static const auto taps = lowpass<129>(DspProfiles::modes[3].filterCutoffHz, DspProfiles::audioRate / 4);
    return taps;
}
inline const auto& cwChannel() {
    static const auto taps = lowpass<129>(DspProfiles::modes[5].filterCutoffHz, DspProfiles::audioRate / 4);
    return taps;
}
}
