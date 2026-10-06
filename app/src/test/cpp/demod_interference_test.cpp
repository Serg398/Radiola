#include "am_demodulator.h"
#include "nfm_demodulator.h"
#include "ssb_demodulator.h"
#include "wfm_demodulator.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

static std::vector<uint8_t> signal(int mode, bool blocker) {
    constexpr int samples = 1200000;
    constexpr double pi = 3.141592653589793;
    std::vector<uint8_t> iq(samples * 2);
    for (int n = 0; n < samples; ++n) {
        const double t = double(n) / 2400000;
        double phase = 0, amplitude = 8, bp = 2*pi*244000*t, ba = blocker ? 80 : 0;
        if (mode == 0 || mode == 2) {
            const double deviation = mode == 0 ? 30000 : 2500;
            phase = -(deviation / 1000) * std::cos(2*pi*1000*t);
            bp -= (deviation / 2300) * std::cos(2*pi*2300*t);
        } else if (mode == 1) {
            amplitude *= 1 + .5 * std::sin(2*pi*1000*t);
            ba *= 1 + .3 * std::sin(2*pi*2300*t);
        } else {
            phase = mode == 5 ? 0 : 2*pi*(mode == 3 ? 1000 : -1000)*t;
            bp = 2*pi*(mode == 5 ? 240200 : mode == 3 ? 242300 : -242300)*t;
        }
        const double i = amplitude*std::cos(phase) + ba*std::cos(bp);
        const double q = amplitude*std::sin(phase) + ba*std::sin(bp);
        iq[2*n] = uint8_t(std::lround(127.5 + i));
        iq[2*n+1] = uint8_t(std::lround(127.5 + q));
    }
    return iq;
}
static std::vector<int16_t> receive(int mode, bool blocker) {
    auto iq = signal(mode, blocker);
    AmDemodulator am; NfmDemodulator fm; WfmDemodulator wfm; SsbDemodulator ssb;
    am.reset(); fm.reset(); wfm.reset(); ssb.reset();
    std::vector<int16_t> pcm;
    int16_t output[2048];
    for (size_t offset = 0; offset < iq.size(); offset += 32768) {
        const auto bytes = std::min<size_t>(32768, iq.size() - offset);
        const auto data = iq.data() + offset;
        size_t count;
        switch (mode) {
            case 0: count = wfm.process(data, bytes, output, 2048); break;
            case 1: count = am.process(data, bytes, output, 2048); break;
            case 2: count = fm.process(data, bytes, output, 2048); break;
            default: count = ssb.process(data, bytes, mode, output, 2048); break;
        }
        pcm.insert(pcm.end(), output, output + count);
    }
    return pcm;
}
int main() {
    const char* names[] = {"WFM", "AM", "FM", "USB", "LSB", "CW"};
    for (int mode = 0; mode < 6; ++mode) {
        const auto clean = receive(mode, false), blocked = receive(mode, true);
        assert(clean.size() == blocked.size());
        double desired = 0, error = 0;
        const size_t start = mode == 0 ? 19200 : 9600;
        for (size_t n = start; n < clean.size(); ++n) {
            desired += double(clean[n])*clean[n];
            const double difference = double(blocked[n]) - clean[n];
            error += difference*difference;
        }
        const double ratio = 10*std::log10(desired / error);
        std::printf("%s strong blocker: desired/error %.2f dB\n", names[mode], ratio);
#ifndef DSP_BASELINE
        assert(ratio >= 20);
#endif
    }
}
