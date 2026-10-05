#include "nfm_demodulator.h"
#include <vector>
#include <cstdio>

int main() {
    constexpr double pi = 3.14159265358979323846;
    std::vector<uint8_t> iq(4800000);
    for (size_t i = 0; i < iq.size() / 2; ++i) {
        const double t = i / 2400000.0;
        const double phase = 2 * pi * 250000 * t - 2.5 * std::cos(2 * pi * 1000 * t);
        iq[2*i] = static_cast<uint8_t>(std::round(127.5 + 60 * std::cos(phase)));
        iq[2*i+1] = static_cast<uint8_t>(std::round(127.5 + 60 * std::sin(phase)));
    }
    NfmDemodulator demod;
    for (int run = 0; run < 2; ++run) {
        demod.reset(); demod.setFrequencyOffset(250000);
        std::vector<int16_t> pcm;
        int16_t output[2048];
        for (size_t offset = 0; offset < iq.size(); offset += 32768) {
            const size_t count = demod.process(iq.data() + offset,
                std::min<size_t>(32768, iq.size() - offset), output, 2048);
            pcm.insert(pcm.end(), output, output + count);
        }
        double energy = 0, real = 0, imag = 0;
        for (size_t i = 9600; i < pcm.size(); ++i) {
            energy += double(pcm[i]) * pcm[i];
            real += pcm[i] * std::cos(2*pi*1000*i/48000);
            imag += pcm[i] * std::sin(2*pi*1000*i/48000);
        }
        const double count = pcm.size() - 9600;
        const double rms = std::sqrt(energy/count);
        const double purity = 2 * (real*real+imag*imag) / (count * energy);
        const bool pass = pcm.size() == 47999 && rms > 2000 && purity > 0.95;
        std::printf("%s NFM run=%d samples=%zu rms=%.1f tone_purity=%.4f\n", pass ? "PASS" : "FAIL", run, pcm.size(), rms, purity);
        if (!pass) return 1;
    }
    return 0;
}
