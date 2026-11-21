#include "channel_filter.h"
#include "am_demodulator.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

static double response(int width, int rate, int taps, int hz) {
    ChannelFilter filter; filter.configure(width, rate, taps);
    double power = 0;
    for (int n = 0; n < rate / 5; ++n) {
        const double angle = 6.283185307179586 * hz * n / rate;
        filter.push(std::cos(angle), std::sin(angle));
        float i, q; filter.output(i, q);
        if (n > rate / 10) power += double(i)*i + double(q)*q;
    }
    return 10*std::log10(power / (rate / 10 - 1));
}
static double am(int width, int tone) {
    std::vector<uint8_t> iq(DspProfiles::iqRate);
    for (size_t n = 0; n < iq.size()/2; ++n) {
        const double amplitude = 40 * (1 + .3 * std::sin(6.283185307179586*tone*n/DspProfiles::iqRate));
        iq[2*n] = uint8_t(std::lround(127.5 + amplitude)); iq[2*n+1] = 128;
    }
    AmDemodulator demod; demod.reset(); demod.setBandwidth(width);
    std::vector<int16_t> pcm(24000);
    assert(demod.process(iq.data(), iq.size(), pcm.data(), pcm.size()) == pcm.size());
    double energy = 0;
    for (size_t n = 9600; n < pcm.size(); ++n) energy += double(pcm[n])*pcm[n];
    return std::sqrt(energy / (pcm.size()-9600));
}
int main() {
    assert(response(3000, 240000, 641, 1000) > -.5);
    assert(response(3000, 240000, 641, 4000) < -50);
    assert(response(150000, 240000, 161, 60000) > -.2);
    assert(response(150000, 240000, 161, 90000) < -50);
    assert(response(100, 12000, 513, 10) > -.2);
    assert(response(100, 12000, 513, 250) < -45);
    const double narrow = am(3000, 4000), wide = am(12000, 4000), wanted = am(3000, 1000);
    std::printf("AM: 4kHz audio narrow %.2f wide %.2f, 1kHz wanted %.2f\n", narrow, wide, wanted);
    assert(wide > 1000 && narrow < wide / 30 && wanted > 1000);
    // Width changes retain IQ history, including transitions between different tap counts.
    ChannelFilter filter; filter.configure(12000, 240000, 161);
    for (int n = 0; n < 1000; ++n) filter.push(1, .5);
    for (int width : {3000, 12000, 2000, 12000}) {
        filter.configure(width, 240000, width == 12000 ? 161 : 641);
        float i, q; filter.output(i, q);
        assert(std::abs(i-1) < .001 && std::abs(q-.5) < .001);
    }
    std::puts("PASS: selectable RF bandwidth, narrow CW, WFM filtering and continuous filter history");
}
