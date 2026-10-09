#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <algorithm>

struct RdsSnapshot {
    uint16_t pi = 0;
    uint8_t pty = 0;
    bool tp = false, ta = false, psReady = false, textReady = false;
    std::array<uint8_t, 8> ps{};
    std::array<uint8_t, 64> text{};
    uint64_t groups = 0;
    int ageMs = 100000;
};

// 240 kHz FM multiplex -> 57 kHz complex baseband -> biphase matched symbols.
// Timing candidates are inexpensive at 24 kHz. Only four CRC-valid blocks in
// A/B/C(or C')/D order are admitted; no speculative error correction.
class RdsDecoder {
public:
    static uint16_t remainder(uint32_t block) {
        for (int bit = 25; bit >= 10; --bit)
            if (block & (1u << bit)) block ^= 0x5b9u << (bit - 10);
        return block & 0x3ff;
    }
    void reset() {
        *this = RdsDecoder();
    }
    void process(float mpx) {
        ++samples_;
        static const auto oscillator = [] {
            std::array<std::array<float, 2>, 80> table{};
            for (int i = 0; i < 80; ++i) {
                const double angle = 2.0 * 3.14159265358979323846 * 19 * i / 80;
                table[i] = {static_cast<float>(std::cos(angle)), static_cast<float>(std::sin(angle))};
            }
            return table;
        }();
        const auto& carrier = oscillator[oscillatorIndex_];
        oscillatorIndex_ = (oscillatorIndex_ + 1) % 80;
        float i = mpx * carrier[0], q = mpx * carrier[1];
        // Three poles at 2.4 kHz reject MPX audio/pilot and prevent aliasing.
        constexpr float alpha = 0.06089863f;
        for (int pole = 0; pole < 3; ++pole) {
            filterI_[pole] += alpha * (i - filterI_[pole]);
            filterQ_[pole] += alpha * (q - filterQ_[pole]);
            i = filterI_[pole]; q = filterQ_[pole];
        }
        if (++decimation_ < 10) return;
        decimation_ = 0;
        for (auto& bank : banks_) {
            const float polarity = bank.phase < .5 ? 1.f : -1.f;
            bank.i += polarity * i; bank.q += polarity * q;
            bank.phase += 1187.5 / 24000.0;
            if (bank.phase < 1.0) continue;
            bank.phase -= 1.0;
            if (bank.havePrevious) {
                const bool bit = bank.i * bank.previousI + bank.q * bank.previousQ < 0.f;
                consumeBit(bank, bit);
            }
            bank.previousI = bank.i; bank.previousQ = bank.q;
            bank.havePrevious = true; bank.i = bank.q = 0.f;
        }
    }
    RdsSnapshot snapshot() const {
        auto result = data_;
        result.ageMs = data_.groups ? static_cast<int>(std::min<uint64_t>(100000, (samples_ - lastGroupSample_) / 240)) : 100000;
        return result;
    }

private:
    struct Bank {
        double phase = 0;
        float i = 0, q = 0, previousI = 0, previousQ = 0;
        bool havePrevious = false;
        uint32_t shift = 0;
        int filled = 0, position = 0, remaining = 0;
        std::array<uint16_t, 4> words{};
    };
    std::array<Bank, 16> banks_ = [] {
        std::array<Bank, 16> result{};
        for (int i = 0; i < 16; ++i) result[i].phase = i / 16.0;
        return result;
    }();
    std::array<float, 3> filterI_{}, filterQ_{};
    int oscillatorIndex_ = 0, decimation_ = 0;
    uint64_t samples_ = 0, lastGroupSample_ = 0;
    std::array<uint16_t, 4> lastWords_{};
    RdsSnapshot data_{};
    uint16_t candidatePi_ = 0;
    int candidatePiCount_ = 0, textAB_ = -1;
    bool textVersionB_ = false;
    std::array<uint16_t, 4> psCandidate_{};
    std::array<uint8_t, 8> psConfirmed_{};
    std::array<bool, 4> psSeen_{};
    unsigned psMask_ = 0;
    std::array<std::array<uint8_t, 4>, 16> textCandidate_{};
    std::array<bool, 16> textSeen_{};
    unsigned textMask_ = 0;

    void consumeBit(Bank& bank, bool bit) {
        bank.shift = ((bank.shift << 1) | bit) & 0x3ffffffu;
        if (bank.filled < 26) { ++bank.filled; if (bank.filled < 26) return; }
        if (bank.position == 0) {
            if (remainder(bank.shift) != 0xfc) return;
            bank.words[0] = bank.shift >> 10;
            bank.position = 1; bank.remaining = 26;
            return;
        }
        if (--bank.remaining > 0) return;
        constexpr uint16_t offsets[] = {0xfc, 0x198, 0x168, 0x1b4};
        const uint16_t expected = bank.position == 2 && (bank.words[1] & 0x800) ? 0x350 : offsets[bank.position];
        if (remainder(bank.shift) != expected) {
            bank.position = 0;
            // This same block can also be the beginning of a new group.
            if (remainder(bank.shift) == 0xfc) {
                bank.words[0] = bank.shift >> 10; bank.position = 1; bank.remaining = 26;
            }
            return;
        }
        bank.words[bank.position++] = bank.shift >> 10;
        bank.remaining = 26;
        if (bank.position == 4) {
            acceptGroup(bank.words);
            bank.position = 0;
        }
    }
    void acceptGroup(const std::array<uint16_t, 4>& words) {
        // Multiple timing candidates can decode the same physical group.
        if (words == lastWords_ && samples_ - lastGroupSample_ < 12000) return;
        if (words[0] != candidatePi_) { candidatePi_ = words[0]; candidatePiCount_ = 1; }
        else ++candidatePiCount_;
        if (candidatePiCount_ < 2) return;
        if (data_.pi != words[0]) {
            data_ = {}; data_.pi = words[0];
            psMask_ = textMask_ = 0; psSeen_.fill(false); textSeen_.fill(false); textAB_ = -1;
        }
        lastWords_ = words; lastGroupSample_ = samples_; ++data_.groups;
        const uint16_t b = words[1];
        data_.pty = (b >> 5) & 31; data_.tp = b & 0x400;
        const int type = b >> 12;
        if (type == 0) {
            data_.ta = b & 0x10;
            const int segment = b & 3;
            if (psSeen_[segment] && psCandidate_[segment] == words[3]) {
                psConfirmed_[segment * 2] = words[3] >> 8;
                psConfirmed_[segment * 2 + 1] = words[3] & 255;
                psMask_ |= 1u << segment;
                if (psMask_ == 15) { data_.ps = psConfirmed_; data_.psReady = true; }
            } else {
                psCandidate_[segment] = words[3]; psSeen_[segment] = true;
                psMask_ &= ~(1u << segment);
            }
        } else if (type == 2) {
            const int ab = (b >> 4) & 1;
            const bool versionB = b & 0x800;
            if (ab != textAB_ || versionB != textVersionB_) {
                textAB_ = ab; textVersionB_ = versionB;
                textMask_ = 0; textSeen_.fill(false); data_.text.fill(0); data_.textReady = false;
            }
            const int segment = b & 15, count = versionB ? 2 : 4;
            const std::array<uint8_t, 4> chars = versionB
                ? std::array<uint8_t, 4>{static_cast<uint8_t>(words[3] >> 8), static_cast<uint8_t>(words[3]), 0, 0}
                : std::array<uint8_t, 4>{static_cast<uint8_t>(words[2] >> 8), static_cast<uint8_t>(words[2]), static_cast<uint8_t>(words[3] >> 8), static_cast<uint8_t>(words[3])};
            if (textSeen_[segment] && textCandidate_[segment] == chars) {
                for (int c = 0; c < count; ++c) data_.text[segment * count + c] = chars[c];
                textMask_ |= 1u << segment;
            } else {
                textCandidate_[segment] = chars; textSeen_[segment] = true;
                textMask_ &= ~(1u << segment);
            }
            // Publish only a complete message through CR or all 16 segments.
            data_.textReady = false;
            for (int s = 0; s < 16 && (textMask_ & (1u << s)); ++s) {
                for (int c = 0; c < count; ++c)
                    if (data_.text[s * count + c] == 13) { data_.textReady = true; return; }
                if (s == 15) data_.textReady = true;
            }
        }
    }
};
