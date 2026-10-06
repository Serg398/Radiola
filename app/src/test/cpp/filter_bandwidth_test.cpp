#include "dsp_filters.h"
#include <cassert>
#include <complex>
#include <cstdio>

template<size_t N> double gain(const std::array<float, N>& taps, double hz, double rate) {
    std::complex<double> sum{};
    for (size_t n = 0; n < N; ++n)
        sum += double(taps[n]) * std::polar(1.0, -6.283185307179586*hz*n/rate);
    return 20*std::log10(std::abs(sum));
}
int main() {
    for (const auto& profile : DspProfiles::modes) {
        assert(profile.bandwidthHz == 2 * profile.filterCutoffHz);
        double edge;
        if (profile.id == 0) {
            edge = gain(DspProfiles::halfBand, profile.filterCutoffHz, DspProfiles::iqRate) +
                gain(DspProfiles::decimateFive, profile.filterCutoffHz, DspProfiles::iqRate / 2);
        } else if (profile.id == 1) {
            edge = gain(DspFilters::amChannel(), profile.filterCutoffHz, DspProfiles::channelRate);
        } else if (profile.id == 2) {
            edge = gain(DspFilters::nfmChannel(), profile.filterCutoffHz, DspProfiles::channelRate);
        } else if (profile.id == 5) {
            edge = gain(DspFilters::cwChannel(), profile.filterCutoffHz, DspProfiles::audioRate / 4);
        } else {
            edge = gain(DspFilters::ssbChannel(), profile.filterCutoffHz, DspProfiles::audioRate / 4);
            assert(std::abs(profile.offsetHz) == 1500);
        }
        std::printf("mode %d: displayed %d Hz, FIR edge %.3f dB\n", profile.id, profile.bandwidthHz, edge);
        assert(edge > -6.2 && edge < -5.8);
    }
    std::puts("PASS: displayed bandwidth uses the actual FIR -6 dB design edges");
}
