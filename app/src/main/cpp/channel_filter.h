#pragma once
#include <array>
#include <algorithm>
#include "dsp_filters.h"

// Only coefficient changes do filter design work. Processing allocates no memory.
class ChannelFilter {
public:
    void reset() { i_.fill(0); q_.fill(0); position_ = 0; }
    void configure(int bandwidth, int rate, int taps) {
        if (bandwidth == bandwidth_ && taps == taps_ && rate == rate_) return;
        bandwidth_ = bandwidth; rate_ = rate; taps_ = taps;
        const float cutoff = bandwidth * .5f;
        if (taps == 161) copy(DspFilters::lowpass<161>(cutoff, rate));
        else if (taps == 129) copy(DspFilters::lowpass<129>(cutoff, rate));
        else if (taps == 513) copy(DspFilters::lowpass<513>(cutoff, rate));
        else copy(DspFilters::lowpass<641>(cutoff, rate));
    }
    void push(float i, float q) {
        i_[position_] = i_[position_ + historySize] = i;
        q_[position_] = q_[position_ + historySize] = q;
        if (++position_ == historySize) position_ = 0;
    }
    void output(float& i, float& q) const {
        const float* hi = i_.data() + position_ + historySize - taps_;
        const float* hq = q_.data() + position_ + historySize - taps_;
        const int middle = taps_ / 2;
        i = coefficients_[middle] * hi[middle]; q = coefficients_[middle] * hq[middle];
        for (int k = 0; k < middle; ++k) {
            i += coefficients_[k] * (hi[k] + hi[taps_ - 1 - k]);
            q += coefficients_[k] * (hq[k] + hq[taps_ - 1 - k]);
        }
    }
private:
    template<size_t N> void copy(const std::array<float, N>& taps) {
        std::copy(taps.begin(), taps.end(), coefficients_.begin());
    }
    int bandwidth_ = 0, rate_ = 0, taps_ = 161, position_ = 0;
    static constexpr int historySize = 641;
    std::array<float, 641> coefficients_{};
    std::array<float, 1282> i_{}, q_{};
};
