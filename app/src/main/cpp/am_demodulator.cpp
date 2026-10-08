#include "am_demodulator.h"

#include <algorithm>
#include <cmath>
#include "dsp_filters.h"

void AmDemodulator::reset() {
    mixer_.reset();
    setBandwidth(channelBandwidthHz);
    channel_.reset();
    decimator_.reset();
    audioCount_ = 0;
    carrier_ = 0.0f;
    warmupSamples_ = 960;
}

size_t AmDemodulator::process(
    const uint8_t* iq, size_t byteCount, int16_t* output, size_t capacity
) {
    size_t produced = 0;
    for (size_t byte = 0; byte + 1 < byteCount; byte += 2) {
        float mixedI, mixedQ;
        mixer_.mix(static_cast<float>(iq[byte]) - 127.5f,
            static_cast<float>(iq[byte + 1]) - 127.5f, mixedI, mixedQ);
        float channelI, channelQ;
        if (!decimator_.push(mixedI, mixedQ, channelI, channelQ)) continue;
        channel_.push(channelI, channelQ);
        if (++audioCount_ < audioDecimation) continue;
        audioCount_ = 0;

        float filteredI = 0.0f;
        float filteredQ = 0.0f;
        channel_.output(filteredI, filteredQ);
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
