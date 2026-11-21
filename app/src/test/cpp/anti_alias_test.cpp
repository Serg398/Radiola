#include "iq_decimator.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>

static double gain(int hz) {
    IqDecimator filter;
    double energy = 0;
    int outputs = 0, measured = 0;
    for (int n = 0; n < 120000; ++n) {
        const double angle = 6.283185307179586 * hz * n / DspProfiles::iqRate;
        float i, q;
        if (filter.push(std::cos(angle), std::sin(angle), i, q)) {
            if (++outputs > 1000) { energy += double(i)*i + double(q)*q; ++measured; }
        }
    }
    assert(outputs == 120000 / (DspProfiles::iqRate / DspProfiles::channelRate));
    return 10 * std::log10(energy / measured);
}
int main() {
    double ripple = 0, worst = -200;
    int worstHz = 0;
    for (int hz = 0; hz <= DspProfiles::passbandHz; hz += 2500) {
        const double db = gain(hz);
        ripple = std::max(ripple, std::abs(db));
    }
    for (int hz = DspProfiles::stopbandHz; hz <= DspProfiles::iqRate / 2; hz += 2500) {
        const double db = gain(hz);
        if (db > worst) { worst = db; worstHz = hz; }
        assert(db <= -DspProfiles::minimumStopbandDb);
    }
    // Negative aliases, exact multiples and narrow-channel offsets.
    for (int center = 240000; center <= 1200000; center += 240000)
        for (int offset : {-6000, -2300, 0, 200, 2300, 6000})
            for (int sign : {-1, 1})
                assert(gain(sign * (center + offset)) <= -DspProfiles::minimumStopbandDb);
    assert(ripple <= DspProfiles::maximumRippleDb);
    // Compare the optimized kernel to a double-precision convolution of both FIRs.
    IqDecimator filter;
    for (int run = 0; run < 2; ++run) {
        filter.reset();
        for (int n = 0; n < 5000; ++n) {
            float i, q;
            if (!filter.push(n == 0 ? 1.f : 0.f, n == 0 ? .5f : 0.f, i, q)) continue;
            const int halfIndex = (n - 1) / 2;
            double reference = 0;
            for (size_t k = 0; k < DspProfiles::postDecimationFilter.size(); ++k) {
                const int index = 2 * (halfIndex - int(k)) + 1;
                if (index >= 0 && index < int(DspProfiles::halfBand.size()))
                    reference += double(DspProfiles::postDecimationFilter[k]) * DspProfiles::halfBand[index];
            }
            assert(std::abs(i - reference) < 1.e-7 && std::abs(q - reference*.5) < 1.e-7);
        }
    }
    std::printf("PASS anti-alias: ripple %.5f dB, worst stopband %.2f dB at %d Hz\n", ripple, worst, worstHz);
}
