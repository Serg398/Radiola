#include "am_demodulator.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

constexpr double pi = 3.14159265358979323846;

double tone(const std::vector<int16_t>& pcm, double frequency) {
    double real = 0, imag = 0;
    for (size_t i = 9600; i < pcm.size(); ++i) {
        const double phase = 2 * pi * frequency * i / 48000;
        real += pcm[i] * std::cos(phase);
        imag += pcm[i] * std::sin(phase);
    }
    return std::hypot(real, imag) * 2 / (pcm.size() - 9600);
}

int main() {
    std::vector<uint8_t> iq(AmDemodulator::inputSampleRate * 2);
    for (size_t i = 0; i < iq.size() / 2; ++i) {
        const double t = static_cast<double>(i) / AmDemodulator::inputSampleRate;
        const double wanted = 25 * (1 + 0.5 * std::sin(2 * pi * 1000 * t));
        const double adjacent = 60 * (1 + 0.3 * std::sin(2 * pi * 2000 * t));
        const double real = wanted * std::cos(2 * pi * 1500 * t) + adjacent * std::cos(2 * pi * 20000 * t);
        const double imag = wanted * std::sin(2 * pi * 1500 * t) + adjacent * std::sin(2 * pi * 20000 * t);
        iq[2 * i] = static_cast<uint8_t>(std::clamp(std::round(real + 127.5), 0.0, 255.0));
        iq[2 * i + 1] = static_cast<uint8_t>(std::clamp(std::round(imag + 127.5), 0.0, 255.0));
    }
    AmDemodulator demodulator;
    demodulator.reset();
    std::vector<int16_t> pcm;
    int16_t block[2048];
    const auto start = std::chrono::steady_clock::now();
    for (size_t offset = 0; offset < iq.size(); offset += 32768) {
        const size_t count = demodulator.process(iq.data() + offset, std::min<size_t>(32768, iq.size() - offset), block, 2048);
        pcm.insert(pcm.end(), block, block + count);
    }
    const double elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    const double wanted = tone(pcm, 1000);
    const double adjacent = tone(pcm, 2000);
    const bool passed = pcm.size() == 48000 && wanted > 1000 && adjacent < wanted * 0.1 && elapsed < 1000;
    std::printf("%s: samples=%zu wanted=%.1f adjacent=%.1f processing_ms=%.1f\n", passed ? "PASS" : "FAIL", pcm.size(), wanted, adjacent, elapsed);
    return passed ? 0 : 1;
}
