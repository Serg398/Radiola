#include "rds_decoder.h"
#include <cassert>
#include <cstdio>
#include <vector>

static uint32_t block(uint16_t data, uint16_t offset) {
    const uint32_t value = static_cast<uint32_t>(data) << 10;
    return value | (RdsDecoder::remainder(value) ^ offset);
}

static void group(std::vector<bool>& bits, uint16_t a, uint16_t b, uint16_t c, uint16_t d, bool corrupt = false) {
    const uint16_t data[] = {a, b, c, d};
    const uint16_t offsets[] = {0xfc, 0x198, static_cast<uint16_t>(b & 0x800 ? 0x350 : 0x168), 0x1b4};
    for (int i = 0; i < 4; ++i) {
        uint32_t value = block(data[i], offsets[i]);
        if (corrupt && i == 1) value ^= 1u << 15;
        for (int bit = 25; bit >= 0; --bit) bits.push_back(value & (1u << bit));
    }
}

static void multiplex(RdsDecoder& decoder, const std::vector<bool>& bits, double phase = .73) {
    bool encoded = false;
    std::vector<bool> differential;
    for (bool bit : bits) { encoded ^= bit; differential.push_back(encoded); }
    const int count = static_cast<int>(bits.size() * 240000.0 / 1187.5);
    for (int sample = 0; sample < count; ++sample) {
        const double time = sample / 240000.0;
        const double symbolTime = time * 1187.5;
        const size_t index = static_cast<size_t>(symbolTime);
        if (index >= differential.size()) break;
        const float symbol = (differential[index] ? 1.f : -1.f) * (symbolTime - index < .5 ? 1.f : -1.f);
        const float value = .07f * symbol * std::cos(2 * 3.141592653589793 * 57000 * time + phase)
            + .35f * std::sin(2 * 3.141592653589793 * 1000 * time)
            + .08f * std::sin(2 * 3.141592653589793 * 19000 * time);
        decoder.process(value);
    }
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    assert(RdsDecoder::remainder(block(0x1234, 0xfc)) == 0xfc);
    std::vector<bool> bits;
    const char ps[] = "RADIOLA ";
    for (int cycle = 0; cycle < 12; ++cycle) {
        for (int segment = 0; segment < 4; ++segment)
            group(bits, 0x1234, 0x400 | (10 << 5) | segment, 0,
                (ps[segment * 2] << 8) | ps[segment * 2 + 1]);
        group(bits, 0x1234, 0x2400 | (10 << 5), ('H' << 8) | 'i', (13 << 8) | ' ');
    }
    RdsDecoder decoder;
    multiplex(decoder, bits);
    auto result = decoder.snapshot();
    std::printf("groups=%llu PI=%04x PS=%.*s RT=%.*s\n", static_cast<unsigned long long>(result.groups), result.pi, 8, result.ps.data(), 2, result.text.data());
    assert(result.groups > 10 && result.pi == 0x1234 && result.pty == 10 && result.tp);
    assert(result.psReady && result.ps[0] == 'R' && result.ps[6] == 'A');
    assert(result.textReady && result.text[0] == 'H' && result.text[1] == 'i');
    decoder.reset();
    assert(decoder.snapshot().groups == 0 && !decoder.snapshot().psReady);
    bits.clear();
    for (int i = 0; i < 15; ++i) group(bits, 0x5678, 0x2800, 0x5678, ('O' << 8) | 13);
    multiplex(decoder, bits, 1.9);
    result = decoder.snapshot();
    assert(result.pi == 0x5678 && result.textReady && result.text[0] == 'O');
    decoder.reset(); bits.clear();
    for (int i = 0; i < 20; ++i) group(bits, 0x1234, 0, 0, 0, true);
    multiplex(decoder, bits);
    assert(decoder.snapshot().groups == 0);
    std::puts("PASS: RDS MPX, PS, RadioText 2A/2B, reset and CRC rejection");
}
