#include "wfm_demodulator.h"
#include "audio_agc.h"
#include "squelch.h"
#include <vector>
#include <array>
#include <cmath>
#include <cassert>
#include <cstdio>
#include <chrono>

constexpr double pi = 3.14159265358979323846;
struct Result { double ll, lr, rl, rr; bool locked; double monoDifference; double seconds; };
Result receive(bool stereo, bool pilot, double pilotHz = 19000.) {
    WfmDemodulator demod;
    demod.reset();
    demod.setFrequencyOffset(250000);
    demod.setStereo(stereo);
    std::vector<int16_t> output;
    output.reserve(192000);
    std::array<uint8_t, 32768> iq;
    std::array<int16_t, 4096> pcm;
    double phase = 0.;
    double processingSeconds = 0.;
    constexpr int total = 2400000 * 2;
    for (int base = 0; base < total;) {
        const int count = std::min(16384, total - base);
        for (int i = 0; i < count; ++i) {
            const double t = (base + i) / 2400000.;
            const double left = .35 * std::sin(2 * pi * 1000 * t);
            const double right = .28 * std::sin(2 * pi * 2300 * t);
            const double p = 2 * pi * pilotHz * t + .7;
            const double mpx = .5 * (left + right) + (pilot ? .5 * (left - right) * std::sin(2*p) + .09 * std::sin(p) : 0.);
            phase += 2 * pi * (250000 + 75000 * mpx) / 2400000.;
            phase = std::remainder(phase, 2*pi);
            iq[2*i] = static_cast<uint8_t>(std::lround(127.5 + 100 * std::cos(phase)));
            iq[2*i+1] = static_cast<uint8_t>(std::lround(127.5 + 100 * std::sin(phase)));
        }
        const auto started = std::chrono::steady_clock::now();
        const size_t produced = demod.process(iq.data(), count * 2, pcm.data(), pcm.size());
        processingSeconds += std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count();
        assert(produced % 2 == 0);
        output.insert(output.end(), pcm.begin(), pcm.begin() + produced);
        base += count;
    }
    assert(output.size() >= 191990 && output.size() <= 192000);
    auto amplitude = [&](int channel, double hz) {
        double re = 0, im = 0;
        for (size_t f = 48000; f < output.size()/2; ++f) {
            const double angle = 2*pi*hz*f/48000.;
            re += output[2*f+channel]*std::cos(angle);
            im += output[2*f+channel]*std::sin(angle);
        }
        return std::hypot(re, im) * 2 / (output.size()/2 - 48000);
    };
    double difference = 0;
    for (size_t f = 48000; f < output.size()/2; ++f) difference += std::abs(output[2*f] - output[2*f+1]);
    return {amplitude(0,1000), amplitude(0,2300), amplitude(1,1000), amplitude(1,2300),
        demod.stereoLocked(), difference, processingSeconds};
}
int main() {
    for (double pilotHz : {19000., 19002., 18998.}) {
        const auto r = receive(true, true, pilotHz);
        std::printf("pilot %.0f: lock=%d separation L %.1f dB R %.1f dB, processing %.3fs / 2s IQ\n",
            pilotHz, r.locked, 20*std::log10(r.ll/r.rl), 20*std::log10(r.rr/r.lr), r.seconds);
        assert(r.locked && r.ll > 1000 && r.rr > 1000);
        assert(r.ll > r.rl * 10 && r.rr > r.lr * 10);
    }
    const auto mono = receive(false, true);
    assert(mono.locked && mono.monoDifference == 0);
    const auto absent = receive(true, false);
    assert(!absent.locked && absent.monoDifference == 0);
    FmStereoDecoder decoder;
    int16_t left, right;
    for (int i=0; i<120000; ++i) decoder.process(.15f * std::sin(2*pi*19000*i/240000.), true, left, right);
    assert(decoder.locked());
    for (int i=0; i<48000; ++i) decoder.process(0.f, true, left, right);
    assert(!decoder.locked() && left == right);
    // Linked dynamics apply identical gains to L/R; hang time still counts frames.
    AudioAgc agc;
    Squelch gate;
    std::array<int16_t, 960> pcm;
    pcm.fill(8000);
    agc.process(pcm.data(), pcm.size(), true, 2);
    gate.process(pcm.data(), pcm.size(), false, -65, -80, 2);
    for (size_t i=0; i<pcm.size(); i+=2) assert(pcm[i] == pcm[i+1]);
    std::puts("PASS: stereo separation, pilot drift, forced mono, no-pilot fallback and linked audio");
}
