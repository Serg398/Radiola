#include "ssb_demodulator.h"
#include <vector>
#include <cstdio>

constexpr double pi = 3.14159265358979323846;
struct Result { double rms, purity; size_t count; };
Result tone(int mode, int carrierOffset, int audioHz) {
    SsbDemodulator demod;
    demod.reset(); demod.setFrequencyOffset(250000);
    std::vector<uint8_t> iq(32768);
    std::vector<int16_t> pcm;
    int16_t output[2048];
    for (size_t start = 0; start < 2400000; start += iq.size()/2) {
        const size_t samples = std::min<size_t>(iq.size()/2, 2400000-start);
        for (size_t i = 0; i < samples; ++i) {
            const double phase = 2*pi*(250000+carrierOffset)*(start+i)/2400000.0;
            iq[2*i] = static_cast<uint8_t>(std::round(127.5+40*std::cos(phase)));
            iq[2*i+1] = static_cast<uint8_t>(std::round(127.5+40*std::sin(phase)));
        }
        const auto count = demod.process(iq.data(), samples*2, mode, output, 2048);
        pcm.insert(pcm.end(), output, output+count);
    }
    double energy = 0, real = 0, imag = 0;
    for (size_t i = 9600; i < pcm.size(); ++i) {
        energy += double(pcm[i])*pcm[i];
        real += pcm[i]*std::cos(2*pi*audioHz*i/48000);
        imag += pcm[i]*std::sin(2*pi*audioHz*i/48000);
    }
    const double count = pcm.size()-9600;
    return {std::sqrt(energy/count), energy > 0 ? 2*(real*real+imag*imag)/(count*energy) : 0, pcm.size()};
}
int main() {
    bool passed = true;
    for (int mode = 3; mode <= 5; ++mode) {
        const int offset = mode == 3 ? 1000 : mode == 4 ? -1000 : 0;
        const auto wanted = tone(mode, offset, mode == 5 ? 700 : 1000);
        const auto rejected = tone(mode, mode == 5 ? 1500 : -offset, mode == 5 ? 2200 : 1000);
        const double rejection = 20*std::log10(wanted.rms/std::max(rejected.rms, 0.01));
        const bool pass = wanted.count == 48000 && wanted.rms > 2000 && wanted.purity > 0.98 && rejection > 35;
        std::printf("%s mode=%d samples=%zu rms=%.1f tone_purity=%.4f rejection_db=%.1f\n", pass ? "PASS" : "FAIL", mode, wanted.count, wanted.rms, wanted.purity, rejection);
        passed = passed && pass;
    }
    return passed ? 0 : 1;
}
