#include "am_demodulator.h"

#include <algorithm>
#include <cmath>
#include "dsp_filters.h"

void AmDemodulator::reset() {
    mixer_.reset();
    historyI_.fill(0.0f);
    historyQ_.fill(0.0f);
    historyPosition_ = 0;
    decimator_.reset();
    audioCount_ = 0;
    carrier_ = 0.0f;
    warmupSamples_ = 960;
}

size_t AmDemodulator::process(
    const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity
) {
    const auto& coefficients = DspFilters::amChannel();
    size_t produced = 0;
    for (size_t byte = 0; byte + 1 < byteCount; byte += 2) {
        float mixedI, mixedQ;
        mixer_.mix(static_cast<float>(iq[byte]) - 127.5f,
            static_cast<float>(iq[byte + 1]) - 127.5f, mixedI, mixedQ);
        float channelI, channelQ;
        if (!decimator_.push(mixedI, mixedQ, channelI, channelQ)) continue;
        historyI_[historyPosition_] = channelI;
        historyQ_[historyPosition_] = channelQ;
        historyPosition_ = (historyPosition_ + 1) % tapsCount;
        if (++audioCount_ < audioDecimation) continue;
        audioCount_ = 0;

        float filteredI = 0.0f;
        float filteredQ = 0.0f;
        int position = historyPosition_;
        for (float coefficient : coefficients) {
            position = position == 0 ? tapsCount - 1 : position - 1;
            filteredI += coefficient * historyI_[position];
            filteredQ += coefficient * historyQ_[position];
        }
        const float envelope = std::sqrt(filteredI * filteredI + filteredQ * filteredQ);
        if (warmupSamples_ > 0) {
            --warmupSamples_;
            carrier_ = envelope;
            if (produced < capacity) output[produced++] = 0;
            continue;
        }
        carrier_ += 0.001f * (envelope - carrier_);
        const float audio = envelope - carrier_;
        if (produced < capacity) {
            output[produced++] = static_cast<int16_t>(
                std::clamp(audio * 256.0f, -30000.0f, 30000.0f)
            );
        }
    }
    return produced;
}
