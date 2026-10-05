#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdlib>

struct TuningPlan {
    // At 2.4 MSPS, leave 200 kHz to the capture edge for the WFM passband.
    static constexpr int digitalRangeHz = 1000000;
    int64_t centerHz;
    int offsetHz;
    bool hardwareRetune;

    static TuningPlan forFrequency(int64_t frequencyHz, int64_t currentCenterHz) {
        const auto offset = frequencyHz - currentCenterHz;
        if (currentCenterHz != 0 && std::abs(offset) <= digitalRangeHz) {
            return {currentCenterHz, static_cast<int>(offset), false};
        }
        // On crossing a band edge, move the channel to the opposite usable edge.
        // This leaves the captured band ahead of the tuning direction.
        const int shiftHz = currentCenterHz == 0 ? 250000
            : (offset > 0 ? digitalRangeHz : -digitalRangeHz);
        const int64_t center = std::clamp<int64_t>(frequencyHz + shiftHz, 500000, 1766000000);
        return {center, static_cast<int>(frequencyHz - center), true};
    }
};
