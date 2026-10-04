#include "audio_agc.h"
#include <cstdio>
#include <vector>

double meanLevel(int amplitude, bool enabled) {
    std::vector<int16_t> pcm(96000);
    for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<int16_t>(amplitude * std::sin(6.28318530718 * 1000 * i / 48000));
    AudioAgc agc;
    agc.process(pcm.data(), pcm.size(), enabled);
    double mean = 0;
    for (size_t i = 72000; i < pcm.size(); ++i) mean += std::abs(static_cast<int>(pcm[i]));
    return mean / 24000;
}

int main() {
    const double weak = meanLevel(500, true);
    const double strong = meanLevel(10000, true);
    const double manualWeak = meanLevel(500, false);
    const double manualStrong = meanLevel(10000, false);
    const bool passed = weak > 2000 && strong > 2000 && strong / weak < 2 && manualStrong / manualWeak > 19;
    std::printf("%s: auto_weak=%.1f auto_strong=%.1f manual_ratio=%.1f\n", passed ? "PASS" : "FAIL", weak, strong, manualStrong / manualWeak);
    return passed ? 0 : 1;
}
