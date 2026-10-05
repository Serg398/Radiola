#include "squelch.h"
#include <array>
#include <cassert>
#include <cstdio>
#include <cstdlib>

int main() {
    Squelch gate;
    std::array<int16_t, 480> pcm;
    auto block = [&](bool enabled, float signal) {
        pcm.fill(12000);
        return gate.process(pcm.data(), pcm.size(), enabled, -65.f, signal);
    };
    gate.reset();
    assert(!block(true, -80.f));
    for (auto sample : pcm) assert(sample == 0);
    assert(block(true, -60.f));
    assert(pcm.front() > 0 && pcm.front() < 12000);
    assert(pcm.back() == 12000);
    for (size_t i = 1; i < pcm.size(); ++i) assert(std::abs(pcm[i] - pcm[i - 1]) <= 51);
    // Hysteresis keeps an already open gate from chattering.
    for (int i = 0; i < 30; ++i) assert(block(true, -67.f));
    // Weak signal closes after 120 ms; ramp finishes within this block.
    for (int i = 0; i < 11; ++i) assert(block(true, -80.f));
    assert(!block(true, -80.f));
    assert(pcm.front() > 0 && pcm.back() == 0);
    assert(!block(true, -80.f));
    for (auto sample : pcm) assert(sample == 0);
    assert(block(false, -180.f));
    assert(pcm.back() == 12000);
    assert(!block(true, -80.f));
    gate.reset();
    assert(!block(true, -80.f));
    // Exact threshold opens, including a negative waveform with the same ramp.
    assert(block(true, -65.f));
    pcm.fill(-12000);
    assert(gate.process(pcm.data(), pcm.size(), true, -65.f, -60.f));
    assert(pcm.back() == -12000);
    std::puts("PASS: threshold, hysteresis, hang time, ramps, bypass and reset");
}
