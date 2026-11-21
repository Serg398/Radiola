#include "am_demodulator.h"
#include "nfm_demodulator.h"
#include "ssb_demodulator.h"
#include "wfm_demodulator.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <vector>

int main() {
    std::vector<uint8_t> iq(2 * DspProfiles::iqRate);
    for (size_t n = 0; n < iq.size()/2; ++n) {
        const double phase = -15 * std::cos(6.283185307179586*1000*n/DspProfiles::iqRate);
        iq[2*n] = uint8_t(127.5 + 60*std::cos(phase));
        iq[2*n+1] = uint8_t(127.5 + 60*std::sin(phase));
    }
    const char* names[] = {"WFM", "AM", "FM", "USB", "LSB", "CW", "WFM stereo"};
    for (int mode = 0; mode < 7; ++mode) {
        double timings[3];
        for (double& timing : timings) {
            AmDemodulator am; NfmDemodulator fm; WfmDemodulator wfm; SsbDemodulator ssb;
            am.reset(); fm.reset(); wfm.reset(); ssb.reset(); wfm.setStereo(mode == 6);
            int16_t output[2048];
            const auto start = std::clock();
            size_t produced = 0;
            for (size_t offset = 0; offset < iq.size(); offset += 32768) {
                const auto bytes = std::min<size_t>(32768, iq.size() - offset);
                const auto data = iq.data() + offset;
                switch (mode) {
                    case 0: case 6: produced += wfm.process(data, bytes, output, 2048); break;
                    case 1: produced += am.process(data, bytes, output, 2048); break;
                    case 2: produced += fm.process(data, bytes, output, 2048); break;
                    default: produced += ssb.process(data, bytes, mode, output, 2048); break;
                }
            }
            timing = double(std::clock()-start)/CLOCKS_PER_SEC;
            assert(produced >= 47999);
        }
        std::sort(timings, timings+3);
        std::printf("%s CPU %.2f ms / 1s IQ (median of 3)\n", names[mode], timings[1]*1000);
#ifndef DSP_BASELINE
        assert(timings[1] < .5); // At least 2x real-time DSP headroom on the tested target.
#endif
    }
}
