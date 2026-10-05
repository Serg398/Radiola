#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdlib>

struct TuningPlan {
    static constexpr int digitalRangeHz = 800000;
    int64_t centerHz;
    int offsetHz;
    bool hardwareRetune;

    static TuningPlan forFrequency(int64_t frequencyHz, int64_t currentCenterHz) {
        const auto offset = frequencyHz - currentCenterHz;
        if (currentCenterHz != 0 && std::abs(offset) <= digitalRangeHz) {
            return {currentCenterHz, static_cast<int>(offset), false};
        }
        // Put the selected channel away from the receiver's DC spike.
        const int64_t center = std::clamp<int64_t>(frequencyHz + 250000, 500000, 1766000000);
        return {center, static_cast<int>(frequencyHz - center), true};
    }
};
