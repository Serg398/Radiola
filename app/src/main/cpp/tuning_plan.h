#pragma once
#include <algorithm>
#include <cstdint>

struct TuningPlan {
    int64_t centerHz;
    int offsetHz;
    bool hardwareRetune;

    static TuningPlan forFrequency(int64_t frequencyHz, int64_t currentCenterHz) {
        // The complete capture follows the selected frequency.
        const int64_t center = std::clamp<int64_t>(frequencyHz, 500000, 1766000000);
        return {center, 0, currentCenterHz != center};
    }
};
