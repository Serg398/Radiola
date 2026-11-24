#include "channel_iq_tap.h"
#include "am_demodulator.h"
#include <array>
#include <cassert>
#include <cmath>

int main() {
    constexpr auto center = 100000000ULL;
    constexpr auto tuning = (center << 32) | 10000;
    constexpr auto inputPairs = ChannelIqTap::pairsPerBlock * DspProfiles::iqRate / DspProfiles::channelRate;
    std::array<uint8_t, inputPairs * 2> iq{};
    for (size_t n = 0; n < inputPairs; ++n) {
        const double phase = n * (6.283185307179586 * 12000.0 / DspProfiles::iqRate);
        iq[2 * n] = static_cast<uint8_t>(127.5 + 60 * std::cos(phase));
        iq[2 * n + 1] = static_cast<uint8_t>(127.5 + 60 * std::sin(phase));
    }

    ChannelIqTap tap;
    ChannelIqTap::Block block;
    assert(!tap.begin(1, tuning, 1, 100));
    assert(!tap.read(block));
    tap.enable(true);
    assert(tap.begin(1, tuning, 1, 100));

    AmDemodulator reference, observed;
    reference.reset();
    observed.reset();
    reference.setFrequencyOffset(10000);
    observed.setFrequencyOffset(10000);
    std::array<int16_t, 4096> expected{}, actual{};
    const auto count = reference.process(iq.data(), iq.size(), expected.data(), expected.size());
    assert(observed.process(iq.data(), iq.size(), actual.data(), actual.size(), &tap) == count);
    assert(expected == actual);
    assert(tap.read(block));
    assert(block.index == 0 && block.frequencyHz == 100010000 && block.timestampNs == 100);
    float iCorrelation = 0, qCorrelation = 0;
    for (size_t n = 128; n < ChannelIqTap::pairsPerBlock; ++n) {
        const double phase = n * (6.283185307179586 * 2000.0 / DspProfiles::channelRate);
        const auto i = block.samples[2 * n], q = block.samples[2 * n + 1];
        iCorrelation += i * std::cos(phase) + q * std::sin(phase);
        qCorrelation += q * std::cos(phase) - i * std::sin(phase);
    }
    assert(std::hypot(iCorrelation, qCorrelation) > 1e7);
    const auto firstEpoch = block.epoch;

    auto fill = [&] {
        for (size_t n = 0; n < ChannelIqTap::pairsPerBlock; ++n) tap.push(1, -1);
    };
    fill();
    assert(tap.read(block) && block.index == ChannelIqTap::pairsPerBlock && block.epoch == firstEpoch);
    assert(tap.begin(1, tuning + 1000, 1, 200));
    for (int i = 0; i < 20; ++i) fill();
    assert(tap.read(block) && block.epoch != firstEpoch && block.index > 0);
    assert(block.frequencyHz == 100011000 && block.samples[0] == 256 && block.samples[1] == -256);
    tap.enable(false);
    assert(!tap.read(block));
    assert(!tap.begin(1, tuning, 1, 300));
}
